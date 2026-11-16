// Package auth generates and verifies bearer credentials.
//
// Tokens carry 256 bits of randomness, so a plain SHA-256 digest is a safe
// at-rest representation: only the digest is stored, never the token itself.
package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"regexp"
	"strings"
)

// Token prefixes make leaked credentials easy to recognise and keep the two
// credential classes visibly separate.
const (
	SourcePrefix = "mgs_"
	AdminPrefix  = "mga_"
)

// NewToken returns a fresh random token with the given prefix.
func NewToken(prefix string) string {
	var b [32]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	return prefix + base64.RawURLEncoding.EncodeToString(b[:])
}

var (
	sourceTokenRe = regexp.MustCompile(`^mgs_[A-Za-z0-9_-]{20,}$`)
	adminTokenRe  = regexp.MustCompile(`^mga_[A-Za-z0-9_-]{20,}$`)
)

// SourceToken reports whether token has the shape of a source credential.
func SourceToken(token string) bool { return sourceTokenRe.MatchString(token) }

// AdminToken reports whether token has the shape of an admin credential.
func AdminToken(token string) bool { return adminTokenRe.MatchString(token) }
func Hash(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// DisplayPrefix returns a short, non-secret identifier for a token.
func DisplayPrefix(token string) string {
	if len(token) <= 10 {
		return token
	}
	return token[:10]
}

// BearerToken extracts the token from an Authorization header value.
func BearerToken(header string) (string, bool) {
	scheme, tok, ok := strings.Cut(strings.TrimSpace(header), " ")
	if !ok || !strings.EqualFold(scheme, "Bearer") {
		return "", false
	}
	tok = strings.TrimSpace(tok)
	if tok == "" || len(tok) > 256 {
		return "", false
	}
	return tok, true
}

// ContentHash returns a keyed digest of message content used for duplicate
// detection. Keying prevents brute-forcing short secrets (such as one-time
// codes) back out of stored hashes.
func ContentHash(key []byte, parts ...string) string {
	m := hmac.New(sha256.New, key)
	for _, p := range parts {
		m.Write([]byte(p))
		m.Write([]byte{0})
	}
	return hex.EncodeToString(m.Sum(nil))
}
