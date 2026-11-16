package auth

import (
	"crypto/ecdsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"

	jose "github.com/go-jose/go-jose/v4"
)

func VerifyDeviceReauth(jwk, deviceID, challenge, signature string) bool {
	var k jose.JSONWebKey
	if json.Unmarshal([]byte(jwk), &k) != nil {
		return false
	}
	if _, _, err := deviceJWK(&k); err != nil {
		return false
	}
	sig, err := base64.RawURLEncoding.Strict().DecodeString(signature)
	if err != nil || len(sig) > 80 {
		return false
	}
	digest := sha256.Sum256([]byte("nekopost/reauth/v1|" + deviceID + "|" + challenge))
	return ecdsa.VerifyASN1(k.Key.(*ecdsa.PublicKey), digest[:], sig)
}
