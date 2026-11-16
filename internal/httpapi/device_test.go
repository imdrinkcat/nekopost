package httpapi

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	jose "github.com/go-jose/go-jose/v4"

	"nekopost/internal/auth"
	"nekopost/internal/enroll"
	"nekopost/internal/store"
)

func testKey(t *testing.T) *ecdsa.PrivateKey {
	t.Helper()
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func testProof(t *testing.T, key *ecdsa.PrivateKey, method, target, token, nonce string, now time.Time) string {
	t.Helper()
	claims := map[string]any{"htm": method, "htu": target, "jti": rand.Text(), "iat": now.Unix()}
	if token != "" {
		sum := sha256.Sum256([]byte(token))
		claims["ath"] = base64.RawURLEncoding.EncodeToString(sum[:])
	}
	if nonce != "" {
		claims["nonce"] = nonce
	}
	opts := (&jose.SignerOptions{}).WithType("dpop+jwt").WithHeader("jwk", jose.JSONWebKey{Key: &key.PublicKey})
	signer, err := jose.NewSigner(jose.SigningKey{Algorithm: jose.ES256, Key: key}, opts)
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := json.Marshal(claims)
	p, err := signer.Sign(raw)
	if err != nil {
		t.Fatal(err)
	}
	wire, err := p.CompactSerialize()
	if err != nil {
		t.Fatal(err)
	}
	return wire
}

func deviceSetup(t *testing.T) *env {
	return setup(t, func(e *env) { e.api.DPoP = auth.NewDPoP(); e.api.PublicURL = "https://fixture.invalid" })
}

func TestDevicePairSubmitReadAndRevoke(t *testing.T) {
	e := deviceSetup(t)
	key, reauth := testKey(t), testKey(t)
	code, short := enroll.NewPairingCodes()
	p := store.Pairing{ID: "p_" + rand.Text(), Name: "Fold8", SourceID: "fold8", Scopes: "msg:send,msg:read,admin:read,admin:write", Expires: time.Now().Add(5 * time.Minute)}
	if err := e.st.NewPairing(bg, p, store.PairingCodeHash(code), store.PairingCodeHash(short), "cli", time.Now()); err != nil {
		t.Fatal(err)
	}
	claim, _ := json.Marshal(map[string]any{"code": code, "jwk": jose.JSONWebKey{Key: &key.PublicKey},
		"reauth_jwk": jose.JSONWebKey{Key: &reauth.PublicKey}, "device": map[string]string{"name": "Fold8"}})
	first := e.do(t, "POST", "/v1/pairing/claim", "", "application/json", string(claim))
	if first.code != 200 || first.body["pairing_id"] != p.ID {
		t.Fatal(first.code, first.body)
	}
	tokenPath := "/v1/pairing/" + p.ID + "/token"
	poll := func() resp {
		return e.do(t, "POST", tokenPath, "", "application/json", "{}", "DPoP",
			testProof(t, key, "POST", e.api.PublicURL+tokenPath, "", "", time.Now()))
	}
	if r := poll(); r.code != 202 {
		t.Fatal(r.code, r.body)
	}
	if ok, err := e.st.DecidePairing(bg, p.ID, first.body["short_code"].(string), "cli", time.Now()); !ok || err != nil {
		t.Fatal(ok, err)
	}
	issued := poll()
	if issued.code != 200 {
		t.Fatal(issued.code, issued.body)
	}
	token, id := issued.body["token"].(string), issued.body["device_id"].(string)
	if r := poll(); r.code != 410 {
		t.Fatal(r.code, r.body)
	}
	call := func(method, path, payload string) resp {
		base, _, _ := strings.Cut(path, "?")
		proof := testProof(t, key, method, e.api.PublicURL+base, token, "", time.Now())
		return e.do(t, method, path, "", "application/json", payload, "Authorization", "DPoP "+token, "DPoP", proof)
	}
	msg := call("POST", "/v1/messages?wait=4", `{"body":"验证码 123456","type":"sms"}`)
	if msg.code != 202 || msg.body["status"] != "delivered" {
		t.Fatal(msg.code, msg.body)
	}
	path := "/v1/inbox/" + msg.body["id"].(string)
	if r := call("GET", path, ""); r.code != 200 || r.body["body"] != "验证码 123456" {
		t.Fatal(r.code, r.body)
	}
	if r := call("PATCH", path, `{"read":true}`); r.code != 204 {
		t.Fatal(r.code, r.body)
	}
	if r := call("GET", "/v1/sync?since=0", ""); r.code != 200 || len(r.body["changes"].([]any)) != 1 {
		t.Fatal(r.code, r.body)
	}
	if r := call("DELETE", path+"/body", ""); r.code != 403 || errCode(r) != "reauth_required" {
		t.Fatal(r.code, r.body)
	}
	ch := call("POST", "/v1/devices/self/reauth/challenge", "{}")
	if ch.code != 200 {
		t.Fatal(ch.code, ch.body)
	}
	challenge := ch.body["challenge"].(string)
	digest := sha256.Sum256([]byte("nekopost/reauth/v1|" + id + "|" + challenge))
	der, err := ecdsa.SignASN1(rand.Reader, reauth, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	b, _ := json.Marshal(map[string]string{"challenge": challenge, "sig": base64.RawURLEncoding.EncodeToString(der)})
	if r := call("POST", "/v1/devices/self/reauth", string(b)); r.code != 204 {
		t.Fatal(r.code, r.body)
	}
	if r := call("POST", "/v1/devices/self/reauth", string(b)); r.code != 401 {
		t.Fatal(r.code, r.body)
	}
	if r := call("DELETE", path+"/body", ""); r.code != 204 {
		t.Fatal(r.code, r.body)
	}
	if err := e.st.RevokeDevice(bg, id, "cli", time.Now()); err != nil {
		t.Fatal(err)
	}
	if r := call("GET", "/v1/devices/self", ""); r.code != 401 || errCode(r) != "device_revoked" {
		t.Fatal(r.code, r.body)
	}
	if r := e.do(t, http.MethodGet, "/v1/admin/audit", e.adminKey, "", ""); r.code != 403 {
		t.Fatal(r.code, r.body)
	}
	if r := e.do(t, http.MethodGet, "/v1/admin/settings", e.adminKey, "", ""); r.code != 200 {
		t.Fatal(r.code, r.body)
	}
}
