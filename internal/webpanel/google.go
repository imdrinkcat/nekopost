package webpanel

import (
	"context"
	"crypto/subtle"
	"errors"
	"net/http"
	"regexp"
	"sync"
	"time"

	"github.com/coreos/go-oidc/v3/oidc"
	"golang.org/x/oauth2"
)

const GoogleIssuer = "https://accounts.google.com"

var googleSubject = regexp.MustCompile(`^[A-Za-z0-9_-]{1,255}$`)

// Google lazily discovers the issuer so an outage does not block message ingestion.
type Google struct {
	ClientID, ClientSecret, RedirectURL string
	issuer                              string
	client                              *http.Client
	mu                                  sync.Mutex
	provider                            *oidc.Provider
}

func NewGoogle(id, secret, redirect string) *Google {
	return &Google{ClientID: id, ClientSecret: secret, RedirectURL: redirect, issuer: GoogleIssuer,
		client: &http.Client{Timeout: 8 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		}}}
}

func (g *Google) config(ctx context.Context) (*oauth2.Config, *oidc.Provider, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.provider == nil {
		p, err := oidc.NewProvider(ctx, g.issuer)
		if err != nil {
			return nil, nil, err
		}
		g.provider = p
	}
	return &oauth2.Config{ClientID: g.ClientID, ClientSecret: g.ClientSecret, RedirectURL: g.RedirectURL,
		Scopes: []string{oidc.ScopeOpenID, "email"}, Endpoint: g.provider.Endpoint()}, g.provider, nil
}

func (g *Google) authorize(ctx context.Context, f flow) (string, error) {
	cfg, _, err := g.config(oidc.ClientContext(ctx, g.client))
	if err != nil {
		return "", err
	}
	opts := []oauth2.AuthCodeOption{oauth2.S256ChallengeOption(f.Verifier),
		oidc.Nonce(f.Nonce), oauth2.SetAuthURLParam("prompt", "select_account")}
	if f.Reauth != "" {
		opts = append(opts, oauth2.SetAuthURLParam("max_age", "0"), oauth2.SetAuthURLParam("claims", `{"id_token":{"auth_time":{"essential":true}}}`))
	}
	return cfg.AuthCodeURL(f.State, opts...), nil
}

func (g *Google) exchange(ctx context.Context, f flow, code string) (string, error) {
	ctx = oidc.ClientContext(ctx, g.client)
	cfg, provider, err := g.config(ctx)
	if err != nil {
		return "", err
	}
	token, err := cfg.Exchange(ctx, code, oauth2.VerifierOption(f.Verifier))
	if err != nil {
		return "", err
	}
	raw, _ := token.Extra("id_token").(string)
	if raw == "" {
		return "", errors.New("missing ID token")
	}
	verified, err := provider.Verifier(&oidc.Config{ClientID: g.ClientID, SupportedSigningAlgs: []string{oidc.RS256}}).Verify(ctx, raw)
	if err != nil {
		return "", err
	}
	var claims struct {
		EmailVerified   bool   `json:"email_verified"`
		AuthTime        int64  `json:"auth_time"`
		AuthorizedParty string `json:"azp"`
	}
	if err := verified.Claims(&claims); err != nil {
		return "", err
	}
	if subtle.ConstantTimeCompare([]byte(verified.Nonce), []byte(f.Nonce)) != 1 || !claims.EmailVerified ||
		!googleSubject.MatchString(verified.Subject) ||
		((len(verified.Audience) != 1 || claims.AuthorizedParty != "") && claims.AuthorizedParty != g.ClientID) {
		return "", errors.New("invalid Google identity")
	}
	if f.Reauth != "" {
		now := time.Now().Unix()
		if claims.AuthTime < now-300 || claims.AuthTime > now+60 {
			return "", errors.New("Google authentication is stale")
		}
	}
	return "google:" + verified.Subject, nil
}
