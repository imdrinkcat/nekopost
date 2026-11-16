package enroll

import (
	"bytes"
	"encoding/json"
	"flag"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// fixturePath is the enrollment-link fixture shared with the Android app's
// EnrollmentLinkFixtureTest (:core unit tests), so the Go builder and the
// Kotlin parser cannot drift apart.
var fixturePath = filepath.Join("..", "..", "android", "core", "src", "test", "resources", "enroll-fixtures.json")

var updateFixture = flag.Bool("update-fixture", false, "rewrite the shared enrollment fixture from the cases below")

// Fake tokens: they only have to match the token format.
const (
	fixtureToken     = "mgs_AbCdEfGhIjKlMnOpQrStUv_-12"
	fixtureLongToken = "mgs_0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_-0123"
)

type fixtureInput struct {
	Base  string `json:"base"`
	ID    string `json:"id"`
	Token string `json:"token"`
	Name  string `json:"name"`
}

// fixtureValid is a link the server builds and the app must accept as is.
type fixtureValid struct {
	fixtureInput
	Link        string `json:"link"`
	ServerURL   string `json:"serverUrl"`
	ServerHost  string `json:"serverHost"`
	DisplayName string `json:"displayName"`
}

// fixtureRejected is input the server refuses to put into a link.
// AppRejects marks inputs that EnrollmentLink.create also refuses; the
// others are stricter on the server side only (the app accepts them, but
// the server never produces them).
type fixtureRejected struct {
	fixtureInput
	AppRejects bool   `json:"appRejects"`
	Why        string `json:"why"`
}

type fixtureFile struct {
	Comment  string            `json:"comment"`
	Valid    []fixtureValid    `json:"valid"`
	Rejected []fixtureRejected `json:"rejected"`
}

func fixtureCases() ([]fixtureInput, []fixtureRejected) {
	in := func(base, id, token, name string) fixtureInput { return fixtureInput{base, id, token, name} }
	long := strings.Repeat("A", 2100)
	cat128, emoji128, cat129 := strings.Repeat("猫", 128), strings.Repeat("🐱", 128), strings.Repeat("猫", 129)

	valid := []fixtureInput{
		in("https://osaka.drinkcat.com", "fold8", fixtureToken, "我的 Fold8 & 手机+猫"),
		in("HTTPS://Push.Example.COM:8443", "phone-2", fixtureToken, "Galaxy Fold"),
		in("https://[2001:DB8::1]", "v6", fixtureToken, "IPv6 主机"),
		in("https://10.0.0.5:443", "lan", fixtureToken, "局域网"),
		in("https://xn--fsq.example", "puny", fixtureToken, "テスト端末"),
		in("https://example.com", "noname", fixtureToken, ""),
		in("https://example.com", "spaced", fixtureToken, "  前后有空格　"),
		in("https://example.com", "special", fixtureToken, "a+b&c=d#e?f/g%h ~._-"),
		in("https://example.com", "emoji", fixtureToken, "猫猫 📱 phone"),
		in("https://example.com", "zw", fixtureToken, "zero​width"),
		in("https://example.com", "max-cjk", fixtureToken, cat128),
		in("https://example.com", "max-emoji", fixtureToken, emoji128),
		in("https://example.com", "a", fixtureLongToken, "x"),
		in("https://example.com", "abcdefghijklmnopqrstuvwxyz012345", fixtureToken, "最长 ID"),
		in("https://a-b.c-d.example:65535", "0_under-score", fixtureToken, "Pixel"),
	}
	rej := func(i fixtureInput, appRejects bool, why string) fixtureRejected {
		return fixtureRejected{i, appRejects, why}
	}
	rejected := []fixtureRejected{
		rej(in("http://example.com", "p", fixtureToken, "x"), true, "plain http"),
		rej(in("http://10.0.2.2", "p", fixtureToken, "x"), true, "plain http (the app allows it only in debug builds)"),
		rej(in("https://example.com/", "p", fixtureToken, "x"), true, "trailing slash"),
		rej(in("https://example.com/nekopost", "p", fixtureToken, "x"), true, "path"),
		rej(in("https://example.com?x=1", "p", fixtureToken, "x"), true, "query"),
		rej(in("https://user@example.com", "p", fixtureToken, "x"), true, "user info"),
		rej(in("https://exa mple.com", "p", fixtureToken, "x"), true, "space"),
		rej(in("https://my_host.example", "p", fixtureToken, "x"), true, "underscore in host"),
		rej(in("https://例え.jp", "p", fixtureToken, "x"), true, "non-ASCII host (use punycode)"),
		rej(in("https://host.123", "p", fixtureToken, "x"), true, "last label starts with a digit"),
		rej(in("ftp://example.com", "p", fixtureToken, "x"), true, "scheme"),
		rej(in("https://example.com:", "p", fixtureToken, "x"), false, "empty port"),
		rej(in("https://example.com:0443", "p", fixtureToken, "x"), false, "port with leading zero"),
		rej(in("https://example.com.", "p", fixtureToken, "x"), false, "trailing dot"),
		rej(in("https://example.com", "Bad ID", fixtureToken, "x"), true, "source ID"),
		rej(in("https://example.com", "-x", fixtureToken, "x"), true, "source ID starts with -"),
		rej(in("https://example.com", "abcdefghijklmnopqrstuvwxyz0123456", fixtureToken, "x"), true, "source ID longer than 32"),
		rej(in("https://example.com", "p", "mgs_short", "x"), true, "token too short"),
		rej(in("https://example.com", "p", "mga_AbCdEfGhIjKlMnOpQrStUv", "x"), true, "token prefix"),
		rej(in("https://example.com", "p", "mgs_AbCdEfGhIjKlMnOpQrSt.v", "x"), true, "token character"),
		rej(in("https://example.com", "p", "mgs_"+long, "x"), false, "link longer than 2048 (the app's parser rejects the link, create() does not check length)"),
		rej(in("https://example.com", "p", fixtureToken, "a\nb"), true, "control character in name"),
		rej(in("https://example.com", "p", fixtureToken, "del\u007f"), true, "DEL in name"),
		rej(in("https://example.com", "p", fixtureToken, cat129), true, "name longer than 128 code points"),
	}
	return valid, rejected
}

func buildFixture(t *testing.T) fixtureFile {
	t.Helper()
	valid, rejected := fixtureCases()
	f := fixtureFile{
		Comment: "Shared enrollment-link fixture. Generated by `go test ./internal/enroll -run TestSharedFixture -update-fixture`; " +
			"checked by internal/enroll (Go) and EnrollmentLinkFixtureTest (Android :core). Tokens are fake.",
		Rejected: rejected,
	}
	for _, c := range valid {
		link, err := Link(c.Base, c.ID, c.Token, c.Name)
		if err != nil {
			t.Fatalf("valid case %+v: %v", c, err)
		}
		base, _ := NormalizeBaseURL(c.Base)
		name, _ := NormalizeName(c.Name, c.ID)
		f.Valid = append(f.Valid, fixtureValid{
			fixtureInput: c,
			Link:         link,
			ServerURL:    base,
			ServerHost:   base[len("https://"):],
			DisplayName:  name,
		})
	}
	return f
}

func encodeFixture(t *testing.T, f fixtureFile) []byte {
	t.Helper()
	var b bytes.Buffer
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "  ")
	if err := enc.Encode(f); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

// TestSharedFixture checks that the committed fixture is exactly what this
// package produces, and that every rejected input is still rejected.
func TestSharedFixture(t *testing.T) {
	want := encodeFixture(t, buildFixture(t))
	if *updateFixture {
		if err := os.WriteFile(fixturePath, want, 0o644); err != nil {
			t.Fatal(err)
		}
		return
	}
	got, err := os.ReadFile(fixturePath)
	if err != nil {
		t.Fatalf("read shared fixture: %v (regenerate with -update-fixture)", err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("%s is out of date; regenerate it with `go test ./internal/enroll -run TestSharedFixture -update-fixture` "+
			"and make sure the Android EnrollmentLinkFixtureTest still passes", fixturePath)
	}

	var f fixtureFile
	if err := json.Unmarshal(got, &f); err != nil {
		t.Fatal(err)
	}
	for _, c := range f.Valid {
		link, err := Link(c.Base, c.ID, c.Token, c.Name)
		if err != nil || link != c.Link {
			t.Errorf("Link(%q, %q, …, %q) = %q, %v; fixture has %q", c.Base, c.ID, c.Name, link, err, c.Link)
		}
	}
	for _, c := range f.Rejected {
		if link, err := Link(c.Base, c.ID, c.Token, c.Name); err == nil {
			t.Errorf("rejected case %q accepted: %q", c.Why, link)
		}
	}
}
