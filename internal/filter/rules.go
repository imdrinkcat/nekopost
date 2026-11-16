package filter

import (
	"strings"

	"nekopost/internal/msgrule"
)

// IsCode reports whether the text looks like a verification code message.
func IsCode(text string) bool { return msgrule.IsCode(text) }

// HasCode reports whether local rules treat the text as containing a code.
func HasCode(text string) bool { return msgrule.HasCode(text) }

// Mask removes details the classifier does not need.
func Mask(s string) string { return msgrule.Mask(s) }

// Preview returns a short masked summary. Telegram rendering does not use it.
func Preview(title, body string) string { return msgrule.Preview(title, body) }

// truncate limits s to n runes.
func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return strings.TrimSpace(string(r[:n])) + "…"
}
