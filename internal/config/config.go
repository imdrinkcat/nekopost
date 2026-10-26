// Package config loads gateway settings from environment variables
// (typically supplied by systemd's EnvironmentFile).
package config

import (
	"bufio"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
	_ "time/tzdata" // timezone data even on minimal hosts

	"msggw/internal/model"
)

// DefaultEnvFile is where the installed service keeps its configuration.
const DefaultEnvFile = "/etc/msggw/msggw.env"

// Config holds all runtime settings.
type Config struct {
	Domain    string
	TLSMode   string // "autocert" or "off"
	HTTPSAddr string
	HTTPAddr  string
	ACMEEmail string
	DataDir   string

	BotToken        string
	AdminIDs        []int64
	ChatID          int64
	TelegramAPIBase string

	TimezoneName string
	Location     *time.Location

	RetentionDays    int
	DeliveryMaxAge   time.Duration
	DedupeWindow     time.Duration
	MaxBodyChars     int
	SourceRatePerMin int
	LogLevel         slog.Level

	// Content filter (DeepSeek). Enabled when DeepSeekAPIKey is set.
	DeepSeekAPIKey  string
	DeepSeekModel   string
	DeepSeekAPIBase string
	FilterTypes     []string         // message types that are classified
	FilterDrop      []model.Category // categories that are not forwarded
	FilterKeepDays  int              // how long filtered messages stay rescuable
}

// FilterEnabled reports whether the content filter is configured.
func (c *Config) FilterEnabled() bool { return c.DeepSeekAPIKey != "" }

// DBPath is the SQLite database location.
func (c *Config) DBPath() string { return filepath.Join(c.DataDir, "msggw.db") }

// ACMECacheDir stores Let's Encrypt account keys and certificates.
func (c *Config) ACMECacheDir() string { return filepath.Join(c.DataDir, "acme") }

// IsAdmin reports whether the Telegram user id is an administrator.
func (c *Config) IsAdmin(id int64) bool {
	for _, a := range c.AdminIDs {
		if a == id {
			return true
		}
	}
	return false
}

var botTokenRe = regexp.MustCompile(`^[0-9]{5,}:[A-Za-z0-9_-]{30,}$`)

// Load reads configuration through getenv. It applies defaults and validates
// formats but does not require Telegram settings; use ValidateServe for that.
func Load(getenv func(string) string) (*Config, error) {
	get := func(k, def string) string {
		if v := strings.TrimSpace(getenv(k)); v != "" {
			return v
		}
		return def
	}
	var errs []error
	c := &Config{
		Domain:          strings.ToLower(get("MSGGW_DOMAIN", "")),
		TLSMode:         get("MSGGW_TLS_MODE", "autocert"),
		HTTPSAddr:       get("MSGGW_HTTPS_ADDR", ":443"),
		HTTPAddr:        get("MSGGW_HTTP_ADDR", ":80"),
		ACMEEmail:       get("MSGGW_ACME_EMAIL", ""),
		DataDir:         get("MSGGW_DATA_DIR", "/var/lib/msggw"),
		BotToken:        get("MSGGW_TELEGRAM_BOT_TOKEN", ""),
		TelegramAPIBase: strings.TrimRight(get("MSGGW_TELEGRAM_API_BASE", "https://api.telegram.org"), "/"),
		TimezoneName:    get("MSGGW_TIMEZONE", "Asia/Tokyo"),
	}
	if c.TLSMode != "autocert" && c.TLSMode != "off" {
		errs = append(errs, fmt.Errorf("MSGGW_TLS_MODE 必须是 autocert 或 off"))
	}
	if ids := get("MSGGW_TELEGRAM_ADMIN_IDS", ""); ids != "" {
		for _, p := range strings.Split(ids, ",") {
			p = strings.TrimSpace(p)
			if p == "" {
				continue
			}
			id, err := strconv.ParseInt(p, 10, 64)
			if err != nil || id <= 0 {
				errs = append(errs, fmt.Errorf("MSGGW_TELEGRAM_ADMIN_IDS 包含无效的用户 ID %q", p))
				continue
			}
			c.AdminIDs = append(c.AdminIDs, id)
		}
	}
	if v := get("MSGGW_TELEGRAM_CHAT_ID", ""); v != "" {
		id, err := strconv.ParseInt(v, 10, 64)
		if err != nil || id == 0 {
			errs = append(errs, fmt.Errorf("MSGGW_TELEGRAM_CHAT_ID 无效"))
		}
		c.ChatID = id
	} else if len(c.AdminIDs) > 0 {
		c.ChatID = c.AdminIDs[0]
	}
	loc, err := time.LoadLocation(c.TimezoneName)
	if err != nil {
		errs = append(errs, fmt.Errorf("MSGGW_TIMEZONE 无效: %v", err))
		loc = time.UTC
	}
	c.Location = loc

	intVar := func(key string, def, min, max int) int {
		v := get(key, "")
		if v == "" {
			return def
		}
		n, err := strconv.Atoi(v)
		if err != nil || n < min || n > max {
			errs = append(errs, fmt.Errorf("%s 必须是 %d 到 %d 之间的整数", key, min, max))
			return def
		}
		return n
	}
	durVar := func(key string, def, min, max time.Duration) time.Duration {
		v := get(key, "")
		if v == "" {
			return def
		}
		d, err := time.ParseDuration(v)
		if err != nil || d < min || d > max {
			errs = append(errs, fmt.Errorf("%s 必须是 %s 到 %s 之间的时长（例如 24h、90s）", key, min, max))
			return def
		}
		return d
	}
	c.RetentionDays = intVar("MSGGW_RETENTION_DAYS", 30, 1, 365)
	c.DeliveryMaxAge = durVar("MSGGW_DELIVERY_MAX_AGE", 24*time.Hour, time.Minute, 7*24*time.Hour)
	c.DedupeWindow = durVar("MSGGW_DEDUPE_WINDOW", 60*time.Second, 0, 24*time.Hour)
	c.MaxBodyChars = intVar("MSGGW_MAX_BODY_CHARS", 12000, 100, 50000)
	c.SourceRatePerMin = intVar("MSGGW_SOURCE_RATE_PER_MIN", 60, 1, 6000)

	c.DeepSeekAPIKey = get("MSGGW_DEEPSEEK_API_KEY", "")
	c.DeepSeekModel = get("MSGGW_DEEPSEEK_MODEL", "deepseek-flash")
	c.DeepSeekAPIBase = strings.TrimRight(get("MSGGW_DEEPSEEK_API_BASE", "https://api.deepseek.com"), "/")
	if strings.ContainsAny(c.DeepSeekAPIKey, " \t") {
		errs = append(errs, errors.New("MSGGW_DEEPSEEK_API_KEY 格式不正确（不能包含空格）"))
	}
	for _, t := range splitList(get("MSGGW_FILTER_TYPES", "sms")) {
		if !model.ValidKind(t) {
			errs = append(errs, fmt.Errorf("MSGGW_FILTER_TYPES 包含无效的类型 %q", t))
			continue
		}
		c.FilterTypes = append(c.FilterTypes, t)
	}
	for _, v := range splitList(get("MSGGW_FILTER_DROP", "roaming,marketing,spam")) {
		cat, ok := model.ParseCategory(v)
		if !ok || cat == model.CatCode {
			errs = append(errs, fmt.Errorf("MSGGW_FILTER_DROP 包含无效的类别 %q（可选 finance/logistics/personal/service/roaming/marketing/spam）", v))
			continue
		}
		c.FilterDrop = append(c.FilterDrop, cat)
	}
	c.FilterKeepDays = intVar("MSGGW_FILTER_KEEP_DAYS", 7, 1, 30)

	switch strings.ToLower(get("MSGGW_LOG_LEVEL", "info")) {
	case "debug":
		c.LogLevel = slog.LevelDebug
	case "info":
		c.LogLevel = slog.LevelInfo
	case "warn":
		c.LogLevel = slog.LevelWarn
	case "error":
		c.LogLevel = slog.LevelError
	default:
		errs = append(errs, fmt.Errorf("MSGGW_LOG_LEVEL 必须是 debug/info/warn/error"))
	}
	return c, errors.Join(errs...)
}

func splitList(v string) []string {
	var out []string
	for _, p := range strings.Split(v, ",") {
		if p = strings.ToLower(strings.TrimSpace(p)); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// ValidateServe checks the settings required to run the service.
func (c *Config) ValidateServe() error {
	var errs []error
	if c.BotToken == "" {
		errs = append(errs, errors.New("未设置 MSGGW_TELEGRAM_BOT_TOKEN"))
	} else if !botTokenRe.MatchString(c.BotToken) {
		errs = append(errs, errors.New("MSGGW_TELEGRAM_BOT_TOKEN 格式不正确（应形如 123456:ABC...）"))
	}
	if len(c.AdminIDs) == 0 {
		errs = append(errs, errors.New("未设置 MSGGW_TELEGRAM_ADMIN_IDS（至少一个管理员的数字 ID）"))
	}
	if c.ChatID == 0 {
		errs = append(errs, errors.New("无法确定投递目标：请设置 MSGGW_TELEGRAM_CHAT_ID 或管理员 ID"))
	}
	if c.TLSMode == "autocert" && c.Domain == "" {
		errs = append(errs, errors.New("TLS 模式为 autocert 时必须设置 MSGGW_DOMAIN"))
	}
	return errors.Join(errs...)
}

// ReadEnvFile parses a KEY=VALUE file (comments and blank lines allowed,
// optional surrounding quotes) in the same spirit as systemd EnvironmentFile.
func ReadEnvFile(path string) (map[string]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	out := map[string]string{}
	sc := bufio.NewScanner(f)
	line := 0
	for sc.Scan() {
		line++
		s := strings.TrimSpace(sc.Text())
		if s == "" || strings.HasPrefix(s, "#") || strings.HasPrefix(s, ";") {
			continue
		}
		s = strings.TrimPrefix(s, "export ")
		k, v, ok := strings.Cut(s, "=")
		if !ok {
			return nil, fmt.Errorf("%s:%d: 缺少 '='", path, line)
		}
		k, v = strings.TrimSpace(k), strings.TrimSpace(v)
		if len(v) >= 2 && (v[0] == '"' && v[len(v)-1] == '"' || v[0] == '\'' && v[len(v)-1] == '\'') {
			v = v[1 : len(v)-1]
		}
		out[k] = v
	}
	return out, sc.Err()
}

// EnvWithFile returns a getenv function where process environment variables
// take precedence over values from the env file.
func EnvWithFile(file map[string]string) func(string) string {
	return func(k string) string {
		if v, ok := os.LookupEnv(k); ok {
			return v
		}
		return file[k]
	}
}
