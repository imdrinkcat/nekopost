package auth

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"time"

	jose "github.com/go-jose/go-jose/v4"
)

const DevicePrefix = "mgd_"

var (
	ErrDPoP     = errors.New("invalid DPoP proof")
	ErrDPoPTime = errors.New("DPoP proof clock outside permitted window")
)

func DeviceAuthorization(header string) (string, bool) {
	scheme, token, ok := strings.Cut(strings.TrimSpace(header), " ")
	if !ok || !strings.EqualFold(scheme, "DPoP") || !strings.HasPrefix(token, DevicePrefix) {
		return "", false
	}
	decoded, err := base64.RawURLEncoding.Strict().DecodeString(strings.TrimPrefix(token, DevicePrefix))
	return token, err == nil && len(decoded) == 32
}

// DeviceJWK accepts a bare public P-256 key and returns its canonical JSON and RFC 7638 thumbprint.
func DeviceJWK(raw []byte) (canonical, thumbprint string, err error) {
	var key jose.JSONWebKey
	if len(raw) > 512 || json.Unmarshal(raw, &key) != nil {
		return "", "", ErrDPoP
	}
	return deviceJWK(&key)
}

func deviceJWK(key *jose.JSONWebKey) (string, string, error) {
	if key == nil {
		return "", "", ErrDPoP
	}
	pub, ok := key.Key.(*ecdsa.PublicKey)
	if !ok || pub.Curve != elliptic.P256() || !key.Valid() {
		return "", "", ErrDPoP
	}
	bare := jose.JSONWebKey{Key: pub}
	digest, err := bare.Thumbprint(crypto.SHA256)
	if err != nil {
		return "", "", ErrDPoP
	}
	raw, err := json.Marshal(bare)
	if err != nil {
		return "", "", ErrDPoP
	}
	return string(raw), base64.RawURLEncoding.EncodeToString(digest), nil
}

// DPoP validates proof binding and consumes each proof at most once per server process.
type DPoP struct {
	mu   sync.Mutex
	seen map[string]int64
	key  [32]byte
}

func NewDPoP() *DPoP {
	d := &DPoP{seen: make(map[string]int64)}
	_, _ = rand.Read(d.key[:])
	return d
}

func (d *DPoP) Nonce(thumb string, now time.Time) string {
	var issued [8]byte
	binary.BigEndian.PutUint64(issued[:], uint64(now.Unix()))
	mac := hmac.New(sha256.New, d.key[:])
	mac.Write(issued[:])
	mac.Write([]byte(thumb))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(issued[:]))
}

func (d *DPoP) validNonce(raw, thumb string, now time.Time) bool {
	b, err := base64.RawURLEncoding.Strict().DecodeString(raw)
	if err != nil || len(b) != 40 {
		return false
	}
	t := int64(binary.BigEndian.Uint64(b[:8]))
	return t <= now.Unix() && t >= now.Unix()-60 &&
		subtle.ConstantTimeCompare([]byte(raw), []byte(d.Nonce(thumb, time.Unix(t, 0)))) == 1
}

// Verify checks an RFC 9449 proof. A token is absent only at the pairing token endpoint.
func (d *DPoP) Verify(proof, thumb, method, target, token string, now time.Time) error {
	if len(proof) > 4096 || thumb == "" {
		return ErrDPoP
	}
	signed, err := jose.ParseSignedCompact(proof, []jose.SignatureAlgorithm{jose.ES256})
	if err != nil || len(signed.Signatures) != 1 {
		return ErrDPoP
	}
	head := signed.Signatures[0].Protected
	typ, _ := head.ExtraHeaders[jose.HeaderType].(string)
	if !strings.EqualFold(typ, "dpop+jwt") {
		return ErrDPoP
	}
	_, got, err := deviceJWK(head.JSONWebKey)
	if err != nil || subtle.ConstantTimeCompare([]byte(got), []byte(thumb)) != 1 {
		return ErrDPoP
	}
	raw, err := signed.Verify(head.JSONWebKey.Key)
	if err != nil {
		return ErrDPoP
	}
	var c struct {
		ID     string `json:"jti"`
		Method string `json:"htm"`
		Target string `json:"htu"`
		Issued int64  `json:"iat"`
		Hash   string `json:"ath"`
		Nonce  string `json:"nonce"`
	}
	if json.Unmarshal(raw, &c) != nil || len(c.ID) < 16 || len(c.ID) > 128 || c.Method != method || c.Target != target || c.Issued <= 0 {
		return ErrDPoP
	}
	ath := ""
	if token != "" {
		sum := sha256.Sum256([]byte(token))
		ath = base64.RawURLEncoding.EncodeToString(sum[:])
	}
	if subtle.ConstantTimeCompare([]byte(ath), []byte(c.Hash)) != 1 {
		return ErrDPoP
	}
	if c.Issued < now.Unix()-60 || c.Issued > now.Unix()+60 {
		return ErrDPoPTime
	}
	if c.Nonce != "" && !d.validNonce(c.Nonce, thumb, now) {
		return ErrDPoPTime
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	for k, expiry := range d.seen {
		if expiry <= now.Unix() {
			delete(d.seen, k)
		}
	}
	id := thumb + ":" + c.ID
	if d.seen[id] != 0 || len(d.seen) >= 4096 {
		return ErrDPoP
	}
	d.seen[id] = now.Add(5 * time.Minute).Unix()
	return nil
}
