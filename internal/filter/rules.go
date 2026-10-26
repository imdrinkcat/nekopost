// Package filter classifies held messages (by default SMS) and decides
// whether they are forwarded. Verification codes are recognised locally and
// never leave the server; everything else is masked and sent to an AI
// classifier. Any failure forwards the message unchanged (fail-open).
package filter

import (
	"regexp"
	"strings"
)

// codeRe matches wording used by verification / one-time / pickup codes in
// Chinese, Japanese and English. A false positive only means a message is
// forwarded without asking the AI, which is the safe direction.
var codeRe = regexp.MustCompile(`(?i)` +
	`验证码|驗證碼|校验码|动态码|动态密码|确认码|认证码|安全码|激活码|登录码|登陆码|取件码|提货码|取餐码|` +
	`認証コード|確認コード|認証番号|確認番号|ワンタイム|パスコード|セキュリティコード|` +
	`verification code|security code|login code|confirmation code|one[- ]time (?:pass|code)|\botp\b|passcode|\bcode is\b|your code`)

// IsCode reports whether the text looks like a verification code message.
func IsCode(text string) bool { return codeRe.MatchString(text) }

var (
	urlRe   = regexp.MustCompile(`(?i)(?:https?://|www\.)[^\s，。！？、]+|\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,6}/[^\s，。！？、]*`)
	digitRe = regexp.MustCompile(`[0-9０-９]{3,}`)
)

// Mask removes details the classifier does not need: links become "[链接]"
// and runs of three or more digits (card numbers, amounts, phone numbers,
// order numbers) become "#".
func Mask(s string) string {
	s = urlRe.ReplaceAllString(s, "[链接]")
	return digitRe.ReplaceAllString(s, "#")
}

// truncate limits s to n runes.
func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return strings.TrimSpace(string(r[:n])) + "…"
}
