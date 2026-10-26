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
	if c.TLSMode != "autocert" || c.HTTPSAddr != ":443" || c.DataDir != "/var/lib/msggw" ||
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
		"MSGGW_DOMAIN":             "Example.COM",
		"MSGGW_TELEGRAM_BOT_TOKEN": "123456:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef_-1",
		"MSGGW_TELEGRAM_ADMIN_IDS": " 111, 222 ",
		"MSGGW_DELIVERY_MAX_AGE":   "6h",
		"MSGGW_DEDUPE_WINDOW":      "0s",
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
		"MSGGW_TLS_MODE":           "maybe",
		"MSGGW_TELEGRAM_ADMIN_IDS": "abc",
		"MSGGW_TIMEZONE":           "Mars/Base",
		"MSGGW_RETENTION_DAYS":     "0",
		"MSGGW_DELIVERY_MAX_AGE":   "forever",
		"MSGGW_LOG_LEVEL":          "loud",
	}))
	if err == nil {
		t.Fatal("expected errors")
	}
	for _, want := range []string{"MSGGW_TLS_MODE", "MSGGW_TELEGRAM_ADMIN_IDS", "MSGGW_TIMEZONE", "MSGGW_RETENTION_DAYS", "MSGGW_DELIVERY_MAX_AGE", "MSGGW_LOG_LEVEL"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("error should mention %s: %v", want, err)
		}
	}
	c, _ := Load(env(map[string]string{"MSGGW_TELEGRAM_BOT_TOKEN": "not-a-token", "MSGGW_TELEGRAM_ADMIN_IDS": "1", "MSGGW_DOMAIN": "x"}))
	if err := c.ValidateServe(); err == nil || !strings.Contains(err.Error(), "格式") {
		t.Errorf("malformed token should be rejected: %v", err)
	}
}

func TestReadEnvFile(t *testing.T) {
	p := filepath.Join(t.TempDir(), "x.env")
	os.WriteFile(p, []byte("# comment\n\nMSGGW_DOMAIN=a.example\nexport MSGGW_TIMEZONE=\"UTC\"\nMSGGW_ACME_EMAIL='me@x'\n"), 0o600)
	m, err := ReadEnvFile(p)
	if err != nil {
		t.Fatal(err)
	}
	if m["MSGGW_DOMAIN"] != "a.example" || m["MSGGW_TIMEZONE"] != "UTC" || m["MSGGW_ACME_EMAIL"] != "me@x" {
		t.Errorf("parsed %v", m)
	}
	os.WriteFile(p, []byte("garbage line\n"), 0o600)
	if _, err := ReadEnvFile(p); err == nil {
		t.Error("expected parse error")
	}
	t.Setenv("MSGGW_DOMAIN", "from-env")
	if EnvWithFile(map[string]string{"MSGGW_DOMAIN": "from-file"})("MSGGW_DOMAIN") != "from-env" {
		t.Error("process env must take precedence")
	}
}

func TestFilterConfig(t *testing.T) {
	c, err := Load(env(nil))
	if err != nil || c.FilterEnabled() || c.DeepSeekModel != "deepseek-flash" || c.DeepSeekAPIBase != "https://api.deepseek.com" ||
		strings.Join(c.FilterTypes, ",") != "sms" || len(c.FilterDrop) != 3 || c.FilterKeepDays != 7 {
		t.Fatalf("defaults: %+v %v", c, err)
	}
	c, err = Load(env(map[string]string{"MSGGW_DEEPSEEK_API_KEY": "sk-abc", "MSGGW_FILTER_TYPES": "SMS, notification",
		"MSGGW_FILTER_DROP": "spam"}))
	if err != nil || !c.FilterEnabled() || len(c.FilterTypes) != 2 || c.FilterTypes[0] != "sms" || len(c.FilterDrop) != 1 {
		t.Fatalf("custom: %+v %v", c, err)
	}
	_, err = Load(env(map[string]string{"MSGGW_FILTER_DROP": "code,ads", "MSGGW_FILTER_KEEP_DAYS": "99"}))
	if err == nil || !strings.Contains(err.Error(), `"code"`) || !strings.Contains(err.Error(), `"ads"`) || !strings.Contains(err.Error(), "MSGGW_FILTER_KEEP_DAYS") {
		t.Fatalf("invalid filter config should be rejected: %v", err)
	}
}
