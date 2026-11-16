// Package enroll builds the enrollment link that the Android app scans to
// register itself as a message source, and renders it as a terminal QR code.
//
// The link format is a contract shared with the app and must not change
// without bumping v:
//
//	nekopost://enroll?v=1&kind=source&url=<base>&id=<id>&token=<mgs_…>&name=<name>
//
// Every value is percent-encoded (spaces as %20, never "+") and the
// parameters always appear in this order. The rules here are kept no looser
// than the app's parser (android/core …/enroll/EnrollmentLink.kt), so every
// link built here is accepted there unchanged; the shared fixture
// android/core/src/test/resources/enroll-fixtures.json is checked by the tests
// on both sides.
package enroll

import (
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"rsc.io/qr"

	"nekopost/internal/model"
)

// Version is the enrollment link format version.
const Version = "1"

// Limits enforced by the app's parser.
const (
	// MaxLinkLength is the longest link the app accepts.
	MaxLinkLength = 2048
	// MaxNameRunes is the longest display name (in code points) the app accepts.
	MaxNameRunes = 128
)

var (
	// tokenRe matches source tokens accepted by the app (see auth.SourcePrefix).
	tokenRe = regexp.MustCompile(`^mgs_[A-Za-z0-9_-]{20,}$`)
	// dnsLabelRe matches one lower-case host name label (RFC 1123).
	dnsLabelRe = regexp.MustCompile(`^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$`)
)

// NormalizeBaseURL validates a server base URL for the enrollment link: it
// must be an absolute https URL with a host and nothing else (no user info,
// path, query, fragment or trailing slash). The scheme and host are
// lower-cased.
func NormalizeBaseURL(raw string) (string, error) {
	if raw == "" {
		return "", errors.New("服务器地址为空")
	}
	if strings.HasSuffix(raw, "/") {
		return "", fmt.Errorf("服务器地址 %q 不能以 / 结尾", raw)
	}
	if strings.ContainsAny(raw, " \t\r\n\\") {
		return "", fmt.Errorf("服务器地址 %q 包含非法字符", raw)
	}
	u, err := url.Parse(raw)
	if err != nil {
		return "", fmt.Errorf("服务器地址 %q 无法解析: %v", raw, err)
	}
	switch {
	case !strings.EqualFold(u.Scheme, "https"):
		return "", fmt.Errorf("服务器地址 %q 必须以 https:// 开头", raw)
	case u.Opaque != "" || u.Host == "" || u.Hostname() == "":
		return "", fmt.Errorf("服务器地址 %q 缺少主机名", raw)
	case u.User != nil:
		return "", fmt.Errorf("服务器地址 %q 不能包含用户名或密码", raw)
	case u.Path != "" || u.RawPath != "":
		return "", fmt.Errorf("服务器地址 %q 不能包含路径", raw)
	case u.RawQuery != "" || u.ForceQuery:
		return "", fmt.Errorf("服务器地址 %q 不能包含查询参数", raw)
	case u.Fragment != "" || strings.Contains(raw, "#"):
		return "", fmt.Errorf("服务器地址 %q 不能包含 # 片段", raw)
	}
	host := strings.ToLower(u.Host)
	if !validHost(strings.ToLower(u.Hostname()), strings.HasPrefix(host, "[")) {
		return "", fmt.Errorf("服务器地址 %q 的主机名无效（只接受 ASCII 域名或 IP 地址）", raw)
	}
	if port := u.Port(); port != "" || strings.HasSuffix(host, ":") {
		if n, err := strconv.Atoi(port); err != nil || port[0] == '0' || n < 1 || n > 65535 {
			return "", fmt.Errorf("服务器地址 %q 的端口无效", raw)
		}
	}
	return "https://" + host, nil
}

// validHost reports whether host (lower-case, without brackets or port) is
// a name the app's URI parser accepts as a server host: a bracketed IPv6
// literal, a dotted IPv4 address, or an ASCII DNS name whose last label
// starts with a letter. Internationalized names must be given in punycode.
func validHost(host string, bracketed bool) bool {
	if bracketed {
		ip := net.ParseIP(host)
		return ip != nil && ip.To4() == nil
	}
	if ip := net.ParseIP(host); ip != nil {
		return ip.To4() != nil && !strings.Contains(host, ":")
	}
	labels := strings.Split(host, ".")
	for _, l := range labels {
		if !dnsLabelRe.MatchString(l) {
			return false
		}
	}
	last := labels[len(labels)-1]
	return last[0] >= 'a' && last[0] <= 'z'
}

// NormalizeName trims surrounding white space, falls back to the source ID
// when nothing is left, and checks the result against the app's limits:
// valid UTF-8, at most MaxNameRunes code points, no control characters.
func NormalizeName(name, sourceID string) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		name = sourceID
	}
	switch {
	case !utf8.ValidString(name):
		return "", errors.New("名称不是有效的 UTF-8 文本")
	case utf8.RuneCountInString(name) > MaxNameRunes:
		return "", fmt.Errorf("名称超过 %d 个字符", MaxNameRunes)
	case strings.IndexFunc(name, unicode.IsControl) >= 0:
		return "", errors.New("名称包含控制字符")
	}
	return name, nil
}

// Link returns the enrollment link for a source. baseURL is validated with
// NormalizeBaseURL and name with NormalizeName (an empty name falls back to
// the source ID).
func Link(baseURL, sourceID, token, name string) (string, error) {
	base, err := NormalizeBaseURL(baseURL)
	if err != nil {
		return "", err
	}
	if !model.ValidSourceID(sourceID) {
		return "", fmt.Errorf("来源 ID %q 无效", sourceID)
	}
	if !tokenRe.MatchString(token) {
		return "", errors.New("凭证格式无效")
	}
	if name, err = NormalizeName(name, sourceID); err != nil {
		return "", err
	}
	link := "nekopost://enroll?v=" + Version +
		"&kind=source" +
		"&url=" + escape(base) +
		"&id=" + escape(sourceID) +
		"&token=" + escape(token) +
		"&name=" + escape(name)
	if len(link) > MaxLinkLength {
		return "", fmt.Errorf("注册链接长 %d 个字符，超过 App 接受的 %d 个字符（请缩短名称）", len(link), MaxLinkLength)
	}
	return link, nil
}

// escape percent-encodes s for a query value. Spaces become %20 rather than
// "+", so decoders that do not treat "+" as a space still get the right value.
func escape(s string) string {
	return strings.ReplaceAll(url.QueryEscape(s), "+", "%20")
}

// QuietZone is the number of light modules printed around the QR symbol.
const QuietZone = 4

// Encode returns the QR symbol for text at error correction level M.
func Encode(text string) (*qr.Code, error) {
	return qr.Encode(text, qr.M)
}

// ANSI colours: bright white and black, set explicitly for both foreground
// and background so the code has the same polarity (dark modules on a light
// background) on dark and light terminal themes.
const (
	fgWhite = "\x1b[97m"
	fgBlack = "\x1b[30m"
	bgWhite = "\x1b[107m"
	bgBlack = "\x1b[40m"
	reset   = "\x1b[0m"
)

// RenderTerminal writes text as a QR code to w (see Render).
func RenderTerminal(w io.Writer, text string) error {
	c, err := Encode(text)
	if err != nil {
		return fmt.Errorf("无法生成二维码: %w", err)
	}
	_, err = io.WriteString(w, Render(c))
	return err
}

// Render returns c drawn with Unicode upper half blocks ("▀"), two modules
// per character cell: the foreground colour paints the upper module and the
// background colour the lower one. The symbol is surrounded by a
// QuietZone-wide light border, and every line resets its colours before the
// newline so nothing bleeds into the rest of the terminal.
func Render(c *qr.Code) string {
	n := c.Size + 2*QuietZone
	dark := func(x, y int) bool {
		// Coordinates outside the symbol (the quiet zone, including the
		// extra half row when n is odd) are light.
		return c.Black(x-QuietZone, y-QuietZone)
	}
	var b strings.Builder
	for y := 0; y < n; y += 2 {
		fg, bg := "", ""
		for x := 0; x < n; x++ {
			wantFg, wantBg := fgWhite, bgWhite
			if dark(x, y) {
				wantFg = fgBlack
			}
			if dark(x, y+1) {
				wantBg = bgBlack
			}
			if wantFg != fg {
				fg = wantFg
				b.WriteString(fg)
			}
			if wantBg != bg {
				bg = wantBg
				b.WriteString(bg)
			}
			b.WriteString("▀")
		}
		b.WriteString(reset + "\n")
	}
	return b.String()
}
