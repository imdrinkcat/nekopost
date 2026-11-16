package enroll

import (
	"net/url"
	"regexp"
	"strings"
	"testing"
	"unicode/utf8"

	"nekopost/internal/auth"
)

func TestLinkRoundTrip(t *testing.T) {
	tok := auth.NewToken(auth.SourcePrefix)
	names := []string{
		"Galaxy Fold",
		"三星 折叠屏",
		"猫猫 📱 phone",
		"a+b&c=d#e?f/g%h",
	}
	for _, name := range names {
		link, err := Link("https://Push.Example.COM:8443", "fold8", tok, name)
		if err != nil {
			t.Fatalf("Link(%q): %v", name, err)
		}
		if strings.Contains(link, "+") || strings.Contains(link, " ") || strings.Contains(link, "#") {
			t.Errorf("link not fully escaped: %q", link)
		}
		u, err := url.Parse(link)
		if err != nil {
			t.Fatalf("parse %q: %v", link, err)
		}
		if u.Scheme != "nekopost" || u.Host != "enroll" || u.Path != "" || u.Fragment != "" {
			t.Errorf("bad scheme/host/path in %q", link)
		}
		q, err := url.ParseQuery(u.RawQuery)
		if err != nil {
			t.Fatalf("query %q: %v", u.RawQuery, err)
		}
		want := map[string]string{
			"v":     "1",
			"kind":  "source",
			"url":   "https://push.example.com:8443",
			"id":    "fold8",
			"token": tok,
			"name":  name,
		}
		if len(q) != len(want) {
			t.Errorf("got %d params, want %d: %v", len(q), len(want), q)
		}
		for k, v := range want {
			if got := q[k]; len(got) != 1 || got[0] != v {
				t.Errorf("param %s = %q, want %q", k, got, v)
			}
		}
		// The parameter order is part of the contract.
		order := regexp.MustCompile(`^v=1&kind=source&url=[^&]+&id=[^&]+&token=[^&]+&name=[^&]+$`)
		if !order.MatchString(u.RawQuery) {
			t.Errorf("unexpected parameter order: %q", u.RawQuery)
		}
	}
}

func TestLinkExactFormat(t *testing.T) {
	tok := "mgs_" + strings.Repeat("A", 43)
	got, err := Link("https://example.com", "my-phone", tok, "我的 手机")
	if err != nil {
		t.Fatal(err)
	}
	want := "nekopost://enroll?v=1&kind=source&url=https%3A%2F%2Fexample.com&id=my-phone&token=" + tok +
		"&name=%E6%88%91%E7%9A%84%20%E6%89%8B%E6%9C%BA"
	if got != want {
		t.Errorf("got  %q\nwant %q", got, want)
	}
}

func TestLinkDefaultsNameToID(t *testing.T) {
	link, err := Link("https://example.com", "phone", auth.NewToken(auth.SourcePrefix), "")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasSuffix(link, "&name=phone") {
		t.Errorf("name did not default to ID: %q", link)
	}
}

func TestLinkRejectsBadInput(t *testing.T) {
	tok := auth.NewToken(auth.SourcePrefix)
	cases := []struct{ base, id, token string }{
		{"https://example.com", "Bad ID", tok},
		{"https://example.com", "", tok},
		{"https://example.com", "phone", "mga_" + strings.Repeat("A", 43)},
		{"https://example.com", "phone", "mgs_short"},
		{"https://example.com", "phone", "mgs_" + strings.Repeat("A", 30) + "="},
		{"http://example.com", "phone", tok},
		{"https://example.com", "phone", "mgs_" + strings.Repeat("A", MaxLinkLength)},
	}
	for _, c := range cases {
		if _, err := Link(c.base, c.id, c.token, "x"); err == nil {
			t.Errorf("Link(%q, %q, %q) accepted", c.base, c.id, c.token)
		}
	}
}

func TestNormalizeName(t *testing.T) {
	good := map[string]string{
		"":                       "phone",
		"   ":                    "phone",
		"  Galaxy Fold\u3000":    "Galaxy Fold",
		strings.Repeat("猫", 128): strings.Repeat("猫", 128),
		strings.Repeat("🐱", 128): strings.Repeat("🐱", 128),
		"zero\u200bwidth":        "zero\u200bwidth",
		"a+b&c=d#e?f/g%h ~._-":   "a+b&c=d#e?f/g%h ~._-",
	}
	for in, want := range good {
		got, err := NormalizeName(in, "phone")
		if err != nil || got != want {
			t.Errorf("NormalizeName(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
	for _, in := range []string{"a\nb", "tab\there", "del\x7f", "c1\u0090", strings.Repeat("猫", 129), "bad\xffutf8"} {
		if got, err := NormalizeName(in, "phone"); err == nil {
			t.Errorf("NormalizeName(%q) = %q, want error", in, got)
		}
	}
}

func TestNormalizeBaseURL(t *testing.T) {
	good := map[string]string{
		"https://example.com":           "https://example.com",
		"HTTPS://Example.COM":           "https://example.com",
		"https://push.example.com:8443": "https://push.example.com:8443",
		"https://[2001:db8::1]":         "https://[2001:db8::1]",
		"https://xn--fsq.example":       "https://xn--fsq.example",
		"https://10.0.0.5:443":          "https://10.0.0.5:443",
		"https://localhost:65535":       "https://localhost:65535",
		"https://a-b.c-d.example":       "https://a-b.c-d.example",
	}
	for in, want := range good {
		got, err := NormalizeBaseURL(in)
		if err != nil || got != want {
			t.Errorf("NormalizeBaseURL(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
	bad := []string{
		"",
		"example.com",
		"http://example.com",
		"ftp://example.com",
		"https://",
		"https:example.com",
		"https://example.com/",
		"https://example.com/nekopost",
		"https://example.com?x=1",
		"https://example.com?",
		"https://example.com#frag",
		"https://example.com#",
		"https://user:pass@example.com",
		"https://user@example.com",
		"https://exa mple.com",
		"https://example.com\n",
		"https://:443",
		"//example.com",
		"https://my_host.example",
		"https://例え.jp",
		"https://host.123",
		"https://example.com.",
		"https://-bad.example",
		"https://bad-.example",
		"https://a..b",
		"https://example.com:",
		"https://example.com:0",
		"https://example.com:0443",
		"https://example.com:65536",
		"https://[fe80::1%25eth0]",
		"https://[1.2.3.4]",
	}
	for _, in := range bad {
		if got, err := NormalizeBaseURL(in); err == nil {
			t.Errorf("NormalizeBaseURL(%q) = %q, want error", in, got)
		}
	}
}

func TestEncodeMatrix(t *testing.T) {
	link, err := Link("https://example.com", "phone", "mgs_"+strings.Repeat("x", 43), "我的手机 📱")
	if err != nil {
		t.Fatal(err)
	}
	c1, err := Encode(link)
	if err != nil {
		t.Fatal(err)
	}
	// A QR symbol of version v has 17+4v modules per side.
	if c1.Size < 21 || c1.Size > 177 || (c1.Size-17)%4 != 0 {
		t.Fatalf("invalid symbol size %d", c1.Size)
	}
	// Byte mode at level M: ~150 bytes needs at least version 7 (45x45).
	if len(link) > 120 && c1.Size < 45 {
		t.Errorf("symbol size %d too small for %d bytes", c1.Size, len(link))
	}
	// Finder patterns: the top-left 7x7 has a dark border and a light ring.
	for i := 0; i < 7; i++ {
		for _, p := range [][2]int{{i, 0}, {i, 6}, {0, i}, {6, i}} {
			if !c1.Black(p[0], p[1]) {
				t.Fatalf("finder pattern border missing at %v", p)
			}
		}
	}
	if c1.Black(1, 1) || !c1.Black(3, 3) {
		t.Error("finder pattern interior wrong")
	}

	// Deterministic: same input, same matrix.
	c2, err := Encode(link)
	if err != nil {
		t.Fatal(err)
	}
	if c1.Size != c2.Size || string(c1.Bitmap) != string(c2.Bitmap) {
		t.Error("encoding is not deterministic")
	}
	// Different input, different matrix.
	other, _ := Link("https://example.com", "phone", "mgs_"+strings.Repeat("y", 43), "我的手机 📱")
	c3, err := Encode(other)
	if err != nil {
		t.Fatal(err)
	}
	if string(c1.Bitmap) == string(c3.Bitmap) {
		t.Error("different links produced the same matrix")
	}
}

var ansiRe = regexp.MustCompile("\x1b\\[[0-9]+m")

func TestRender(t *testing.T) {
	c, err := Encode("nekopost://enroll?v=1")
	if err != nil {
		t.Fatal(err)
	}
	out := Render(c)
	if out != Render(c) {
		t.Fatal("render is not deterministic")
	}
	n := c.Size + 2*QuietZone
	lines := strings.Split(strings.TrimSuffix(out, "\n"), "\n")
	if want := (n + 1) / 2; len(lines) != want {
		t.Fatalf("got %d lines, want %d", len(lines), want)
	}
	for i, line := range lines {
		if !strings.HasSuffix(line, reset) {
			t.Errorf("line %d does not reset colours", i)
		}
		// Every line must set both colours explicitly before drawing.
		if !strings.HasPrefix(line, fgWhite+bgWhite) {
			t.Errorf("line %d does not start with the light quiet zone: %q", i, line[:min(len(line), 20)])
		}
		plain := ansiRe.ReplaceAllString(line, "")
		if utf8.RuneCountInString(plain) != n || strings.Trim(plain, "▀") != "" {
			t.Errorf("line %d: %d cells, want %d half blocks", i, utf8.RuneCountInString(plain), n)
		}
	}

	// Re-derive the module matrix from the colour codes and compare.
	for row, line := range lines {
		fg, bg := "", ""
		x := 0
		for len(line) > 0 {
			if m := ansiRe.FindString(line); m != "" && strings.HasPrefix(line, m) {
				switch m {
				case fgWhite, fgBlack:
					fg = m
				case bgWhite, bgBlack:
					bg = m
				}
				line = line[len(m):]
				continue
			}
			_, size := utf8.DecodeRuneInString(line)
			line = line[size:]
			y := 2 * row
			if got, want := fg == fgBlack, c.Black(x-QuietZone, y-QuietZone); got != want {
				t.Fatalf("module (%d,%d): dark=%v, want %v", x, y, got, want)
			}
			if got, want := bg == bgBlack, c.Black(x-QuietZone, y+1-QuietZone); got != want {
				t.Fatalf("module (%d,%d): dark=%v, want %v", x, y+1, got, want)
			}
			x++
		}
	}
}

func TestRenderTerminalTooLong(t *testing.T) {
	var b strings.Builder
	if err := RenderTerminal(&b, strings.Repeat("x", 4000)); err == nil {
		t.Error("expected error for oversized payload")
	}
}
