package config

import (
	"errors"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

type WebConfig struct {
	Warning                                     string
	PublicURL, GoogleClientID, GoogleSecretFile string
	Allow                                       map[string]bool
}

var webIdentity = regexp.MustCompile(`^(google:[A-Za-z0-9_-]{1,255}|github:[0-9]{1,20})$`)

func loadWeb(c *Config, getenv func(string) string) error {
	get := func(k string) string { return strings.TrimSpace(getenv(k)) }
	c.Web = WebConfig{
		PublicURL:        strings.TrimRight(get("NEKOPOST_PUBLIC_URL"), "/"),
		GoogleClientID:   get("NEKOPOST_GOOGLE_CLIENT_ID"),
		GoogleSecretFile: get("NEKOPOST_GOOGLE_CLIENT_SECRET_FILE"),
		Allow:            make(map[string]bool),
	}
	for _, id := range strings.Split(get("NEKOPOST_WEB_ALLOW"), ",") {
		id = strings.TrimSpace(id)
		if id == "" {
			continue
		}
		if !webIdentity.MatchString(id) {
			return errors.New("NEKOPOST_WEB_ALLOW 包含无效的身份")
		}
		if strings.HasPrefix(id, "google:") {
			c.Web.Allow[id] = true
		}
	}
	if c.Web.PublicURL == "" && (c.Web.GoogleClientID == "" || len(c.Web.Allow) == 0) {
		return nil
	}
	if c.Web.PublicURL == "" && c.Domain != "" && c.TLSMode == "autocert" {
		c.Web.PublicURL = "https://" + c.Domain
	}
	u, err := url.Parse(c.Web.PublicURL)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil ||
		u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || u.Path != "" {
		return errors.New("网页面板需要 HTTPS 站点地址，请设置 NEKOPOST_PUBLIC_URL")
	}
	return nil
}

func (w WebConfig) Enabled() bool { return w.GoogleClientID != "" && len(w.Allow) > 0 }

func (w WebConfig) GoogleSecret() (string, error) {
	path := w.GoogleSecretFile
	if path == "" {
		if dir := os.Getenv("CREDENTIALS_DIRECTORY"); dir != "" {
			path = filepath.Join(dir, "nekopost-google-client-secret")
		} else {
			path = "/run/secrets/nekopost_google_client_secret"
		}
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return "", errors.New("无法读取 Google OAuth 客户端密钥")
	}
	secret := strings.TrimSpace(string(b))
	if secret == "" || len(secret) > 4096 {
		return "", errors.New("Google OAuth 客户端密钥格式无效")
	}
	return secret, nil
}
