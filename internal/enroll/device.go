package enroll

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"strings"
)

func DeviceLink(baseURL, code string) (string, error) {
	base, err := NormalizeBaseURL(baseURL)
	if err != nil {
		return "", err
	}
	b, err := base64.RawURLEncoding.Strict().DecodeString(code)
	if err != nil || len(b) != 16 {
		return "", errors.New("配对码格式无效")
	}
	return "nekopost://enroll?v=1&kind=device&url=" + escape(base) + "&code=" + escape(code), nil
}

func NewPairingCodes() (code, short string) {
	var data [26]byte
	_, _ = rand.Read(data[:])
	const alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
	out := make([]byte, 10)
	for i := range out {
		out[i] = alphabet[data[16+i]&31]
	}
	return base64.RawURLEncoding.EncodeToString(data[:16]), string(out)
}

func NormalizePairingCode(code string) string {
	if b, err := base64.RawURLEncoding.Strict().DecodeString(code); err == nil && len(b) == 16 {
		return code
	}
	short := strings.ToUpper(strings.ReplaceAll(strings.TrimSpace(code), "-", ""))
	if len(short) != 10 || strings.Trim(short, "0123456789ABCDEFGHJKMNPQRSTVWXYZ") != "" {
		return ""
	}
	return short
}
