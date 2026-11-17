// Package msgrule recognises verification-code wording and masks it.
// The store and the filter both use it; it does not touch the database.
package msgrule

import (
	"regexp"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

// codeRe matches wording used by verification codes. A false positive only
// makes a message expire sooner and a preview show more masks.
var codeRe = regexp.MustCompile(`(?i)` +
	`验证码|驗證碼|校验码|动态码|动态密码|确认码|认证码|安全码|激活码|登录码|登陆码|取件码|提货码|取餐码|` +
	`暗証番号|临时密码|初始密码|动态口令|인증번호|` +
	`認証コード|確認コード|認証番号|確認番号|ワンタイム|パスコード|セキュリティコード|` +
	`verification code|security code|login code|confirmation code|account code|sign-in code|` +
	`one[- ]time (?:pass|code)|\botp\b|\bPIN\b|passcode|\bcode is\b|your code`)

var (
	urlRe          = regexp.MustCompile(`(?i)(?:https?://|www\.)[^\s，。！？、]+|\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,6}/[^\s，。！？、]*`)
	digitRunRe     = regexp.MustCompile(`[0-9０-９]{3,}`)
	previewDigitRe = regexp.MustCompile(`[0-9０-９]{4,}`)
	wordRe         = regexp.MustCompile(`[A-Za-z0-9０-９]{4,16}`)
	addressRe      = regexp.MustCompile(`^\+?[0-9][0-9 -]{2,19}$`)
)

// IsCode reports whether the text looks like a verification-code message.
func IsCode(text string) bool { return codeRe.MatchString(text) }

// HasCode is true when local rules see a code keyword, or a keyword
// immediately followed by a one-time token.
func HasCode(text string) bool {
	return codeRe.MatchString(text) || keywordToken(text) != ""
}

// Mask hides links, long digit runs and one-time tokens before text is sent
// to the classifier.
func Mask(s string) string {
	s = urlRe.ReplaceAllString(s, "[链接]")
	s = digitRunRe.ReplaceAllString(s, "#")
	return maskTokens(s, "#")
}

// Preview returns a short masked summary for a person. Telegram rendering
// does not use this.
func Preview(title, body string) string {
	s := strings.TrimSpace(title)
	if body != "" {
		if s != "" {
			s += " "
		}
		s += strings.TrimSpace(body)
	}
	if keyword := keywordToken(s); keyword != "" {
		s = strings.ReplaceAll(s, keyword, "******")
	}
	s = previewDigitRe.ReplaceAllString(s, "******")
	s = maskTokens(s, "******")
	r := []rune(s)
	if len(r) > 120 {
		s = string(r[:120])
	}
	return s
}

// Sender returns a message title for a conversation list: the SMS address
// or "<app> · <title>" as submitted, clipped to 64 runes. Unlike Preview it
// keeps digit runs and names, so sender numbers such as 95555 stay
// readable. A token right after a code keyword is always masked; when the
// message has a code (hasCode, the stored local-rule flag) every code-shaped
// token is masked too, wherever it stands ("G-482913 是您的验证码"), unless sms
// is set and the whole title is a bare phone number or short code (the SMS
// address).
func Sender(title string, hasCode, sms bool) string {
	s := strings.Join(strings.Fields(title), " ")
	if keyword := keywordToken(s); keyword != "" {
		s = strings.ReplaceAll(s, keyword, "******")
	}
	if hasCode && !(sms && addressRe.MatchString(s)) {
		s = wordRe.ReplaceAllStringFunc(s, func(tok string) string {
			if otpToken(tok) {
				return "******"
			}
			return tok
		})
	}
	if r := []rune(s); len(r) > 64 {
		s = string(r[:64])
	}
	return s
}

// Late classifies a delay between the client timestamp and receipt.
// otp_hold is the Android 17 verification-code hold window.
func Late(received, occurred time.Time, hasCode bool) string {
	if occurred.IsZero() || received.Before(occurred) {
		return ""
	}
	d := received.Sub(occurred)
	if hasCode && d >= 2*time.Hour+50*time.Minute && d <= 3*time.Hour+15*time.Minute {
		return "otp_hold"
	}
	if d > 10*time.Minute {
		return "late"
	}
	return ""
}

func maskTokens(s, repl string) string {
	return wordRe.ReplaceAllStringFunc(s, func(tok string) string {
		if otpToken(tok) && !digitsOnly(tok) {
			return repl
		}
		return tok
	})
}

// VerificationCode returns the code adjacent to a recognised keyword when unambiguous.
func VerificationCode(text string) string { return keywordToken(text) }

// keywordToken returns the token immediately after a code keyword.
func keywordToken(text string) string {
	loc := codeRe.FindStringIndex(text)
	if loc == nil {
		return ""
	}
	rest := strings.TrimLeftFunc(text[loc[1]:], func(r rune) bool {
		return unicode.IsSpace(r) || strings.ContainsRune("：:，,。．.、|/-", r)
	})
	if rest == "" {
		return ""
	}
	i := 0
	for i < len(rest) {
		r, size := utf8.DecodeRuneInString(rest[i:])
		if !unicode.IsLetter(r) && !unicode.IsDigit(r) {
			break
		}
		i += size
	}
	tok := rest[:i]
	if otpToken(tok) {
		return tok
	}
	return ""
}

func otpToken(s string) bool {
	n := utf8.RuneCountInString(s)
	if n < 4 || n > 16 {
		return false
	}
	if digitsOnly(s) {
		return n <= 8
	}
	var let, dig bool
	for _, r := range s {
		switch {
		case unicode.IsDigit(r):
			dig = true
		case unicode.IsLetter(r):
			let = true
		default:
			return false
		}
	}
	return let && dig
}

func digitsOnly(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if !unicode.IsDigit(r) {
			return false
		}
	}
	return true
}
