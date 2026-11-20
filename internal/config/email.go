package config

import (
	"errors"
	"fmt"
	"net"
	"net/mail"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Privacy levels for what an email contains.
const (
	EmailPrivacyFull   = "full"   // the whole message
	EmailPrivacyMasked = "masked" // digits and links hidden, like the inbox preview
	EmailPrivacyPing   = "ping"   // only that a message arrived, and from where
)

// MaxEmailRecipients bounds NEKOPOST_EMAIL_TO.
const MaxEmailRecipients = 5

// EmailConfig is the optional email output. It is enabled when SMTPHost is
// set. Email is an auxiliary channel: Telegram (or the App) still decides a
// message's status, and a failed email never makes a message "failed".
type EmailConfig struct {
	// Warning is set when the settings are invalid; email is then off.
	Warning string

	Host         string
	Port         int
	Security     string // "starttls" (default), "tls", or "none" for a loopback relay
	Username     string
	PasswordFile string
	From         string
	To           []string
	Types        map[string]bool // nil = every type
	Privacy      string
}

// Enabled reports whether email is configured (and valid).
func (e EmailConfig) Enabled() bool { return e.Host != "" && e.Warning == "" }

// TypesAll reports whether every message type is emailed.
func (e EmailConfig) TypesAll() bool { return e.Types == nil }

func isLoopbackHost(h string) bool {
	if strings.EqualFold(h, "localhost") {
		return true
	}
	ip := net.ParseIP(h)
	return ip != nil && ip.IsLoopback()
}

// bareAddress parses one plain address (no display name, no comments).
func bareAddress(key, v string) (string, error) {
	a, err := mail.ParseAddress(v)
	if err != nil || a.Name != "" || a.Address != v || strings.ContainsAny(v, "\r\n<>") {
		return "", fmt.Errorf("%s 包含无效的邮箱地址", key)
	}
	return a.Address, nil
}

func loadEmail(c *Config, getenv func(string) string) error {
	get := func(k string) string { return strings.TrimSpace(getenv(k)) }
	host := strings.ToLower(get("NEKOPOST_SMTP_HOST"))
	if host == "" {
		// Nothing configured. Settings that only make sense with a host are
		// reported rather than silently ignored.
		for _, k := range []string{"NEKOPOST_EMAIL_FROM", "NEKOPOST_EMAIL_TO", "NEKOPOST_SMTP_USERNAME"} {
			if get(k) != "" {
				return errors.New(k + " 已设置，但没有设置 NEKOPOST_SMTP_HOST")
			}
		}
		return nil
	}
	e := EmailConfig{
		Host:         host,
		Security:     strings.ToLower(get("NEKOPOST_SMTP_SECURITY")),
		Username:     get("NEKOPOST_SMTP_USERNAME"),
		PasswordFile: get("NEKOPOST_SMTP_PASSWORD_FILE"),
		Privacy:      strings.ToLower(get("NEKOPOST_EMAIL_PRIVACY")),
	}
	if strings.ContainsAny(host, " \t/:[]@") || strings.ContainsAny(e.Username, "\r\n") {
		return errors.New("NEKOPOST_SMTP_HOST 或 NEKOPOST_SMTP_USERNAME 格式不正确")
	}
	switch e.Security {
	case "":
		e.Security = "starttls"
	case "starttls", "tls":
	case "none":
		if !isLoopbackHost(host) {
			return errors.New("NEKOPOST_SMTP_SECURITY=none 只允许用于本机（localhost、127.0.0.1）的邮件中继")
		}
	default:
		return errors.New("NEKOPOST_SMTP_SECURITY 必须是 starttls、tls 或 none")
	}
	switch e.Security {
	case "tls":
		e.Port = 465
	case "none":
		e.Port = 25
	default:
		e.Port = 587
	}
	if v := get("NEKOPOST_SMTP_PORT"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 65535 {
			return errors.New("NEKOPOST_SMTP_PORT 必须是 1 到 65535 之间的整数")
		}
		e.Port = n
	}
	switch e.Privacy {
	case "":
		e.Privacy = EmailPrivacyFull
	case EmailPrivacyFull, EmailPrivacyMasked, EmailPrivacyPing:
	default:
		return errors.New("NEKOPOST_EMAIL_PRIVACY 必须是 full、masked 或 ping")
	}
	var err error
	if e.From, err = bareAddress("NEKOPOST_EMAIL_FROM", get("NEKOPOST_EMAIL_FROM")); err != nil {
		if get("NEKOPOST_EMAIL_FROM") == "" {
			return errors.New("启用邮件需要设置 NEKOPOST_EMAIL_FROM（发件人地址）")
		}
		return err
	}
	if get("NEKOPOST_EMAIL_TO") == "" {
		return errors.New("启用邮件需要设置 NEKOPOST_EMAIL_TO（收件人地址，逗号分隔）")
	}
	for _, v := range strings.Split(get("NEKOPOST_EMAIL_TO"), ",") {
		if v = strings.TrimSpace(v); v == "" {
			continue
		}
		a, err := bareAddress("NEKOPOST_EMAIL_TO", v)
		if err != nil {
			return err
		}
		e.To = append(e.To, a)
	}
	if len(e.To) == 0 || len(e.To) > MaxEmailRecipients {
		return fmt.Errorf("NEKOPOST_EMAIL_TO 需要 1 到 %d 个收件人", MaxEmailRecipients)
	}
	raw := get("NEKOPOST_EMAIL_TYPES")
	if e.Types, err = parseTypes("NEKOPOST_EMAIL_TYPES", raw); err != nil {
		return err
	}
	c.Email = e
	return nil
}

// Password reads the SMTP password: NEKOPOST_SMTP_PASSWORD_FILE, else the
// systemd credential nekopost-smtp-password, else the Docker secret
// nekopost_smtp_password. It returns "" when no username is configured.
func (e EmailConfig) Password() (string, error) {
	if e.Username == "" {
		return "", nil
	}
	path := e.PasswordFile
	if path == "" {
		if dir := os.Getenv("CREDENTIALS_DIRECTORY"); dir != "" {
			path = filepath.Join(dir, "nekopost-smtp-password")
		} else {
			path = "/run/secrets/nekopost_smtp_password"
		}
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return "", errors.New("无法读取 SMTP 密码（NEKOPOST_SMTP_PASSWORD_FILE）")
	}
	pw := strings.TrimRight(string(b), "\r\n")
	if pw == "" || len(pw) > 1024 || strings.ContainsAny(pw, "\r\n") {
		return "", errors.New("SMTP 密码格式无效")
	}
	return pw, nil
}
