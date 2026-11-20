package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func env(m map[string]string) func(string) string { return func(k string) string { return m[k] } }

func TestDefaults(t *testing.T) {
	c, err := Load(env(nil))
	if err != nil {
		t.Fatal(err)
	}
	if c.TLSMode != "autocert" || c.HTTPSAddr != ":443" || c.DataDir != "/var/lib/nekopost" ||
		c.TimezoneName != "Asia/Tokyo" || c.RetentionDays != 30 || c.DeliveryMaxAge != 24*time.Hour ||
		c.DedupeWindow != time.Minute || c.SourceRatePerMin != 60 {
		t.Errorf("unexpected defaults: %+v", c)
	}
	if err := c.ValidateServe(); err == nil {
		t.Error("serve validation should fail without token/admins/domain")
	}
}

func TestLoadValid(t *testing.T) {
	c, err := Load(env(map[string]string{
		"NEKOPOST_DOMAIN":             "Example.COM",
		"NEKOPOST_TELEGRAM_BOT_TOKEN": "123456:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef_-1",
		"NEKOPOST_TELEGRAM_ADMIN_IDS": " 111, 222 ",
		"NEKOPOST_DELIVERY_MAX_AGE":   "6h",
		"NEKOPOST_DEDUPE_WINDOW":      "0s",
	}))
	if err != nil {
		t.Fatal(err)
	}
	if err := c.ValidateServe(); err != nil {
		t.Fatal(err)
	}
	if c.Domain != "example.com" || len(c.AdminIDs) != 2 || c.ChatID != 111 || !c.IsAdmin(222) || c.IsAdmin(333) {
		t.Errorf("unexpected: %+v", c)
	}
	if c.DeliveryMaxAge != 6*time.Hour || c.DedupeWindow != 0 {
		t.Error("durations not applied")
	}
}

func TestLoadInvalid(t *testing.T) {
	_, err := Load(env(map[string]string{
		"NEKOPOST_TLS_MODE":           "maybe",
		"NEKOPOST_TELEGRAM_ADMIN_IDS": "abc",
		"NEKOPOST_TIMEZONE":           "Mars/Base",
		"NEKOPOST_RETENTION_DAYS":     "0",
		"NEKOPOST_DELIVERY_MAX_AGE":   "forever",
		"NEKOPOST_LOG_LEVEL":          "loud",
	}))
	if err == nil {
		t.Fatal("expected errors")
	}
	for _, want := range []string{"NEKOPOST_TLS_MODE", "NEKOPOST_TELEGRAM_ADMIN_IDS", "NEKOPOST_TIMEZONE", "NEKOPOST_RETENTION_DAYS", "NEKOPOST_DELIVERY_MAX_AGE", "NEKOPOST_LOG_LEVEL"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("error should mention %s: %v", want, err)
		}
	}
	c, _ := Load(env(map[string]string{"NEKOPOST_TELEGRAM_BOT_TOKEN": "not-a-token", "NEKOPOST_TELEGRAM_ADMIN_IDS": "1", "NEKOPOST_DOMAIN": "x"}))
	if err := c.ValidateServe(); err == nil || !strings.Contains(err.Error(), "格式") {
		t.Errorf("malformed token should be rejected: %v", err)
	}
}

func TestReadEnvFile(t *testing.T) {
	p := filepath.Join(t.TempDir(), "x.env")
	os.WriteFile(p, []byte("# comment\n\nNEKOPOST_DOMAIN=a.example\nexport NEKOPOST_TIMEZONE=\"UTC\"\nNEKOPOST_ACME_EMAIL='me@x'\n"), 0o600)
	m, err := ReadEnvFile(p)
	if err != nil {
		t.Fatal(err)
	}
	if m["NEKOPOST_DOMAIN"] != "a.example" || m["NEKOPOST_TIMEZONE"] != "UTC" || m["NEKOPOST_ACME_EMAIL"] != "me@x" {
		t.Errorf("parsed %v", m)
	}
	os.WriteFile(p, []byte("garbage line\n"), 0o600)
	if _, err := ReadEnvFile(p); err == nil {
		t.Error("expected parse error")
	}
	t.Setenv("NEKOPOST_DOMAIN", "from-env")
	if EnvWithFile(map[string]string{"NEKOPOST_DOMAIN": "from-file"})("NEKOPOST_DOMAIN") != "from-env" {
		t.Error("process env must take precedence")
	}
}

func TestFilterConfig(t *testing.T) {
	c, err := Load(env(nil))
	if err != nil || c.FilterEnabled() || c.DeepSeekModel != "deepseek-flash" || c.DeepSeekAPIBase != "https://api.deepseek.com" ||
		strings.Join(c.FilterTypes, ",") != "sms" || len(c.FilterDrop) != 3 || c.FilterKeepDays != 7 {
		t.Fatalf("defaults: %+v %v", c, err)
	}
	c, err = Load(env(map[string]string{"NEKOPOST_DEEPSEEK_API_KEY": "sk-abc", "NEKOPOST_FILTER_TYPES": "SMS, notification",
		"NEKOPOST_FILTER_DROP": "spam"}))
	if err != nil || !c.FilterEnabled() || len(c.FilterTypes) != 2 || c.FilterTypes[0] != "sms" || len(c.FilterDrop) != 1 {
		t.Fatalf("custom: %+v %v", c, err)
	}
	_, err = Load(env(map[string]string{"NEKOPOST_FILTER_DROP": "code,ads", "NEKOPOST_FILTER_KEEP_DAYS": "99"}))
	if err == nil || !strings.Contains(err.Error(), `"code"`) || !strings.Contains(err.Error(), `"ads"`) || !strings.Contains(err.Error(), "NEKOPOST_FILTER_KEEP_DAYS") {
		t.Fatalf("invalid filter config should be rejected: %v", err)
	}
}

func TestTrustedProxies(t *testing.T) {
	c, err := Load(env(map[string]string{"NEKOPOST_TRUSTED_PROXIES": "172.16.0.0/12, 127.0.0.1, ::1, 10.1.2.3/8"}))
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for _, p := range c.TrustedProxies {
		got = append(got, p.String())
	}
	if strings.Join(got, " ") != "172.16.0.0/12 127.0.0.1/32 ::1/128 10.0.0.0/8" {
		t.Errorf("got %v", got)
	}
	if _, err := Load(env(map[string]string{"NEKOPOST_TRUSTED_PROXIES": "proxy.local"})); err == nil ||
		!strings.Contains(err.Error(), "NEKOPOST_TRUSTED_PROXIES") {
		t.Errorf("hostname should be rejected: %v", err)
	}
}

func TestTelegramTypes(t *testing.T) {
	cases := []struct {
		raw     string
		want    []string // nil = all types
		none    bool     // empty, non-nil map
		wantErr bool
	}{
		{raw: ""},
		{raw: "*"},
		{raw: " * "},
		{raw: "none", none: true},
		{raw: "NONE", none: true},
		{raw: "-", none: true},
		{raw: " none , ", none: true},
		{raw: "sms", want: []string{"sms"}},
		{raw: "sms, notification,", want: []string{"sms", "notification"}},
		{raw: "sms,,notification", want: []string{"sms", "notification"}},
		{raw: "none,sms", wantErr: true},
		{raw: "*,sms", wantErr: true},
		{raw: "Bad Type", wantErr: true},
	}
	for _, c := range cases {
		cfg, err := Load(env(map[string]string{"NEKOPOST_TELEGRAM_TYPES": c.raw}))
		if c.wantErr {
			if err == nil {
				t.Errorf("%q: want error", c.raw)
			}
			continue
		}
		if err != nil {
			t.Errorf("%q: %v", c.raw, err)
			continue
		}
		switch {
		case c.none:
			if cfg.TelegramTypes == nil || len(cfg.TelegramTypes) != 0 || cfg.TelegramAll() {
				t.Errorf("%q: want none, got %v", c.raw, cfg.TelegramTypes)
			}
		case c.want == nil:
			if cfg.TelegramTypes != nil || !cfg.TelegramAll() {
				t.Errorf("%q: want all, got %v", c.raw, cfg.TelegramTypes)
			}
		default:
			if len(cfg.TelegramTypes) != len(c.want) {
				t.Errorf("%q: got %v", c.raw, cfg.TelegramTypes)
			}
			for _, k := range c.want {
				if !cfg.TelegramTypes[k] {
					t.Errorf("%q: missing %s", c.raw, k)
				}
			}
		}
	}
}

func TestAutoDeleteIsOffByDefault(t *testing.T) {
	c, err := Load(env(nil))
	if err != nil {
		t.Fatal(err)
	}
	if c.AutoDelete || c.MessageRetentionDays() != 0 || c.AutoDeleteWarn != "" {
		t.Errorf("default: %+v", c)
	}
	if !c.StoreOptions(make([]byte, 32), nil).KeepForever {
		t.Error("store should keep bodies forever by default")
	}
}

func TestAutoDeleteOn(t *testing.T) {
	c, err := Load(env(map[string]string{"NEKOPOST_AUTO_DELETE": "true", "NEKOPOST_RETENTION_DAYS": "60"}))
	if err != nil {
		t.Fatal(err)
	}
	if !c.AutoDelete || c.MessageRetentionDays() != 60 || c.AutoDeleteWarn != "" {
		t.Errorf("on: %+v", c)
	}
	opts := c.StoreOptions(make([]byte, 32), nil)
	if opts.KeepForever || opts.BodyRetention != 7*24*time.Hour || opts.CodeRetention != 24*time.Hour {
		t.Errorf("store options: %+v", opts)
	}
	// The old consistency check still applies while it is on.
	if _, err := Load(env(map[string]string{"NEKOPOST_AUTO_DELETE": "1", "NEKOPOST_RETENTION_DAYS": "3"})); err == nil {
		t.Error("record retention shorter than body retention should be rejected")
	}
}

func TestAutoDeleteOffIgnoresRetentionSettingsAndSaysSo(t *testing.T) {
	c, err := Load(env(map[string]string{"NEKOPOST_RETENTION_DAYS": "3", "NEKOPOST_BODY_RETENTION_DAYS": "7"}))
	if err != nil {
		t.Fatal("retention values must not be validated while off:", err)
	}
	if c.AutoDelete || c.MessageRetentionDays() != 0 {
		t.Errorf("%+v", c)
	}
	for _, k := range []string{"NEKOPOST_RETENTION_DAYS", "NEKOPOST_BODY_RETENTION_DAYS", "NEKOPOST_AUTO_DELETE=true"} {
		if !strings.Contains(c.AutoDeleteWarn, strings.TrimSuffix(k, "=true")) {
			t.Errorf("warning %q lacks %s", c.AutoDeleteWarn, k)
		}
	}
	if _, err := Load(env(map[string]string{"NEKOPOST_AUTO_DELETE": "maybe"})); err == nil {
		t.Error("invalid boolean accepted")
	}
}

func emailEnv(extra map[string]string) func(string) string {
	m := map[string]string{"NEKOPOST_SMTP_HOST": "smtp.example.com", "NEKOPOST_EMAIL_FROM": "nekopost@example.com", "NEKOPOST_EMAIL_TO": "me@example.org"}
	for k, v := range extra {
		m[k] = v
	}
	return env(m)
}

func TestEmailOffByDefault(t *testing.T) {
	c, err := Load(env(nil))
	if err != nil || c.Email.Enabled() || c.Email.Warning != "" {
		t.Fatalf("%+v %v", c.Email, err)
	}
}

func TestEmailDefaults(t *testing.T) {
	c, err := Load(emailEnv(nil))
	if err != nil {
		t.Fatal(err)
	}
	e := c.Email
	if !e.Enabled() || e.Port != 587 || e.Security != "starttls" || e.Privacy != EmailPrivacyFull || !e.TypesAll() ||
		e.From != "nekopost@example.com" || len(e.To) != 1 || e.To[0] != "me@example.org" {
		t.Fatalf("%+v", e)
	}
	c, _ = Load(emailEnv(map[string]string{"NEKOPOST_SMTP_SECURITY": "TLS", "NEKOPOST_EMAIL_TYPES": "sms, notification",
		"NEKOPOST_EMAIL_PRIVACY": "Masked", "NEKOPOST_EMAIL_TO": "a@example.org, b@example.org"}))
	if c.Email.Port != 465 || c.Email.Security != "tls" || c.Email.Privacy != EmailPrivacyMasked ||
		len(c.Email.To) != 2 || c.Email.TypesAll() || !c.Email.Types["sms"] || !c.Email.Types["notification"] {
		t.Fatalf("%+v", c.Email)
	}
}

func TestEmailInvalidSettingsDisableOnlyEmail(t *testing.T) {
	cases := map[string]map[string]string{
		"no recipient":        {"NEKOPOST_EMAIL_TO": ""},
		"no sender":           {"NEKOPOST_EMAIL_FROM": ""},
		"display name":        {"NEKOPOST_EMAIL_FROM": "Neko <nekopost@example.com>"},
		"header injection":    {"NEKOPOST_EMAIL_TO": "me@example.org\r\nBcc: x@example.net"},
		"too many recipients": {"NEKOPOST_EMAIL_TO": "1@e.org,2@e.org,3@e.org,4@e.org,5@e.org,6@e.org"},
		"bad port":            {"NEKOPOST_SMTP_PORT": "70000"},
		"bad security":        {"NEKOPOST_SMTP_SECURITY": "ssl"},
		"plaintext to remote": {"NEKOPOST_SMTP_SECURITY": "none"},
		"bad privacy":         {"NEKOPOST_EMAIL_PRIVACY": "everything"},
		"bad types":           {"NEKOPOST_EMAIL_TYPES": "sms,*"},
		"host with a path":    {"NEKOPOST_SMTP_HOST": "smtp.example.com/x"},
	}
	for name, extra := range cases {
		c, err := Load(emailEnv(extra))
		if err != nil {
			t.Errorf("%s: a bad email setting must not stop the service: %v", name, err)
		}
		if c.Email.Warning == "" || c.Email.Enabled() {
			t.Errorf("%s: want email off with a warning, got %+v", name, c.Email)
		}
	}
	// Plaintext is allowed only to a relay on the same host.
	if c, _ := Load(emailEnv(map[string]string{"NEKOPOST_SMTP_HOST": "127.0.0.1", "NEKOPOST_SMTP_SECURITY": "none"})); !c.Email.Enabled() || c.Email.Port != 25 {
		t.Errorf("loopback relay: %+v", c.Email)
	}
	// Email settings without a host are reported, not silently ignored.
	if c, _ := Load(env(map[string]string{"NEKOPOST_EMAIL_TO": "me@example.org"})); c.Email.Warning == "" {
		t.Error("EMAIL_TO without SMTP_HOST should warn")
	}
}

func TestEmailPasswordFile(t *testing.T) {
	dir := t.TempDir()
	path := dir + "/pw"
	if err := os.WriteFile(path, []byte("hunter2\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	c, _ := Load(emailEnv(map[string]string{"NEKOPOST_SMTP_USERNAME": "neko", "NEKOPOST_SMTP_PASSWORD_FILE": path}))
	pw, err := c.Email.Password()
	if err != nil || pw != "hunter2" {
		t.Fatalf("%q %v", pw, err)
	}
	c, _ = Load(emailEnv(map[string]string{"NEKOPOST_SMTP_USERNAME": "neko", "NEKOPOST_SMTP_PASSWORD_FILE": dir + "/missing"}))
	if _, err := c.Email.Password(); err == nil || strings.Contains(err.Error(), dir) {
		t.Errorf("a missing file should fail without echoing a path: %v", err)
	}
	// No username: no password is read at all.
	c, _ = Load(emailEnv(nil))
	if pw, err := c.Email.Password(); pw != "" || err != nil {
		t.Errorf("%q %v", pw, err)
	}
}
