// Package sealbox encrypts message bodies with a key held outside the database.
//
// A 32-byte KEK derives purpose-separated subkeys. Each UTC day has its own
// DEK, wrapped by the wrap subkey. Destroying that wrapped DEK makes every
// body from that day unreadable.
package sealbox

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
)

const version = 0x01

// Subkey info strings. audit and oauth-flow are derived now so later
// milestones do not change the KDF.
const (
	infoWrap   = "nekopost/v2/wrap"
	infoAudit  = "nekopost/v2/audit"
	infoDedupe = "nekopost/v2/dedupe"
	infoOAuth  = "nekopost/v2/oauth-flow"
	infoKEKID  = "nekopost/v2/kek-id"
)

// Keys are the subkeys of one KEK. The raw KEK is not retained.
type Keys struct {
	Wrap   []byte
	Dedupe []byte
	Audit  []byte
	OAuth  []byte
	KEKID  []byte // 8 bytes; stored and displayed as 16 hex chars
}

// Derive expands a 32-byte KEK into purpose-separated keys.
func Derive(kek []byte) (Keys, error) {
	if len(kek) != 32 {
		return Keys{}, errors.New("主密钥长度必须是 32 字节")
	}
	var k Keys
	var err error
	if k.Wrap, err = sub(kek, infoWrap, 32); err != nil {
		return Keys{}, err
	}
	if k.Dedupe, err = sub(kek, infoDedupe, 32); err != nil {
		return Keys{}, err
	}
	if k.Audit, err = sub(kek, infoAudit, 32); err != nil {
		return Keys{}, err
	}
	if k.OAuth, err = sub(kek, infoOAuth, 32); err != nil {
		return Keys{}, err
	}
	if k.KEKID, err = sub(kek, infoKEKID, 8); err != nil {
		return Keys{}, err
	}
	return k, nil
}

func sub(kek []byte, info string, n int) ([]byte, error) {
	return hkdf.Key(sha256.New, kek, nil, info, n)
}

// KEKIDHex is the identifier stored in data_keys and shown in errors.
func (k Keys) KEKIDHex() string { return hex.EncodeToString(k.KEKID) }

// Prefix is the short form shown to operators (4 hex chars).
func (k Keys) Prefix() string {
	h := k.KEKIDHex()
	if len(h) > 4 {
		return h[:4] + "…"
	}
	return h
}

// WrapDEK seals a DEK for one UTC day.
func WrapDEK(k Keys, day string, dek []byte) ([]byte, error) {
	if len(dek) != 32 {
		return nil, errors.New("DEK 长度必须是 32 字节")
	}
	return seal(k.Wrap, []byte("nekopost/dek/v1|"+day), dek)
}

// UnwrapDEK opens a wrapped DEK. The day is part of the associated data.
func UnwrapDEK(k Keys, day string, wrapped []byte) ([]byte, error) {
	return open(k.Wrap, []byte("nekopost/dek/v1|"+day), wrapped)
}

// SealBody encrypts one message body.
func SealBody(dek []byte, messageID, day string, plain []byte) ([]byte, error) {
	return seal(dek, []byte("nekopost/body/v1|"+messageID+"|"+day), plain)
}

// OpenBody decrypts one message body.
func OpenBody(dek []byte, messageID, day string, ct []byte) ([]byte, error) {
	return open(dek, []byte("nekopost/body/v1|"+messageID+"|"+day), ct)
}

func seal(key, aad, plain []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCMWithRandomNonce(block)
	if err != nil {
		return nil, err
	}
	sealed := aead.Seal(nil, nil, plain, aad)
	out := make([]byte, 1+len(sealed))
	out[0] = version
	copy(out[1:], sealed)
	return out, nil
}

func open(key, aad, blob []byte) ([]byte, error) {
	if len(blob) < 2 || blob[0] != version {
		return nil, errors.New("无法解密")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCMWithRandomNonce(block)
	if err != nil {
		return nil, err
	}
	plain, err := aead.Open(nil, nil, blob[1:], aad)
	if err != nil {
		return nil, errors.New("无法解密")
	}
	return plain, nil
}

// NewDEK returns 32 random bytes.
func NewDEK() ([]byte, error) {
	b := make([]byte, 32)
	_, err := rand.Read(b)
	return b, err
}

// IdemHash is the value stored for an Idempotency-Key.
func IdemHash(dedupe []byte, raw string) string {
	m := hmac.New(sha256.New, dedupe)
	m.Write([]byte(raw))
	return hex.EncodeToString(m.Sum(nil))[:32]
}

// FormatKeys encodes one or two raw keys as base64 lines.
func FormatKeys(current, previous []byte) []byte {
	s := base64.StdEncoding.EncodeToString(current) + "\n"
	if len(previous) == 32 {
		s += base64.StdEncoding.EncodeToString(previous) + "\n"
	}
	return []byte(s)
}

// ParseKeyMaterial accepts a raw 32-byte key, two raw keys, or base64 lines.
func ParseKeyMaterial(b []byte) (current, previous []byte, err error) {
	if len(b) == 32 {
		return append([]byte(nil), b...), nil, nil
	}
	if len(b) == 64 {
		return append([]byte(nil), b[:32]...), append([]byte(nil), b[32:]...), nil
	}
	text := strings.TrimSpace(string(b))
	if text == "" {
		return nil, nil, errors.New("主密钥是空的")
	}
	lines := strings.Split(text, "\n")
	if len(lines) > 2 {
		return nil, nil, errors.New("主密钥文件格式不正确")
	}
	current, err = decodeLine(lines[0])
	if err != nil {
		return nil, nil, err
	}
	if len(lines) == 2 && strings.TrimSpace(lines[1]) != "" {
		previous, err = decodeLine(lines[1])
		if err != nil {
			return nil, nil, err
		}
	}
	return current, previous, nil
}

func decodeLine(s string) ([]byte, error) {
	b, err := base64.StdEncoding.DecodeString(strings.TrimSpace(s))
	if err != nil || len(b) != 32 {
		return nil, fmt.Errorf("主密钥文件格式不正确")
	}
	return b, nil
}

// SealOAuth protects the browser's short-lived OAuth flow.
func SealOAuth(k Keys, plain []byte) ([]byte, error) {
	return seal(k.OAuth, []byte("nekopost/oauth-flow/v1"), plain)
}

func OpenOAuth(k Keys, blob []byte) ([]byte, error) {
	return open(k.OAuth, []byte("nekopost/oauth-flow/v1"), blob)
}
