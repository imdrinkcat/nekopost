package webpanel

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"nekopost/internal/health"
	"nekopost/internal/model"
	"nekopost/internal/sealbox"
	"nekopost/internal/status"
	"nekopost/internal/store"
)

type issuerFixture struct {
	mu        sync.Mutex
	codes     map[string]fixtureCode
	server    *httptest.Server
	key       *rsa.PrivateKey
	exchanges int
}
type fixtureCode struct {
	query   url.Values
	variant string
}

func newIssuer(t *testing.T) *issuerFixture {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	f := &issuerFixture{key: key, codes: make(map[string]fixtureCode)}
	f.server = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/.well-known/openid-configuration":
			u := f.URL()
			reply(w, 200, map[string]any{"issuer": u, "authorization_endpoint": u + "/authorize", "token_endpoint": u + "/token",
				"jwks_uri": u + "/keys", "id_token_signing_alg_values_supported": []string{"RS256"}})
		case "/authorize":
			query := r.URL.Query()
			destination, err := url.Parse(query.Get("redirect_uri"))
			if err != nil || destination.Scheme != "https" || destination.Hostname() != "127.0.0.1" {
				http.Error(w, "invalid test redirect", 400)
				return
			}
			code := randomToken()
			f.mu.Lock()
			f.codes[code] = fixtureCode{query: query}
			f.mu.Unlock()
			destination.RawQuery = url.Values{"state": {query.Get("state")}, "code": {code}}.Encode()
			http.Redirect(w, r, destination.String(), http.StatusSeeOther)
		case "/keys":
			reply(w, 200, map[string]any{"keys": []map[string]any{{"kty": "RSA", "alg": "RS256", "kid": "fixture",
				"n": base64.RawURLEncoding.EncodeToString(key.PublicKey.N.Bytes()), "e": "AQAB"}}})
		case "/token":
			if r.ParseForm() != nil {
				http.Error(w, "invalid form", 400)
				return
			}
			f.mu.Lock()
			f.exchanges++
			code, ok := f.codes[r.Form.Get("code")]
			delete(f.codes, r.Form.Get("code"))
			f.mu.Unlock()
			digest := sha256.Sum256([]byte(r.Form.Get("code_verifier")))
			if !ok || code.query.Get("code_challenge") != base64.RawURLEncoding.EncodeToString(digest[:]) ||
				r.Form.Get("redirect_uri") != code.query.Get("redirect_uri") {
				reply(w, 400, map[string]string{"error": "invalid_grant"})
				return
			}
			now := time.Now()
			claims := map[string]any{"iss": f.URL(), "aud": "fixture-client", "sub": "111", "nonce": code.query.Get("nonce"),
				"email_verified": true, "iat": now.Unix(), "exp": now.Add(time.Minute).Unix()}
			switch code.variant {
			case "wrong nonce":
				claims["nonce"] = "different"
			case "wrong issuer":
				claims["iss"] = "https://example.invalid"
			case "wrong audience":
				claims["aud"] = "other"
			case "expired":
				claims["exp"] = now.Add(-time.Hour).Unix()
			case "unverified email":
				claims["email_verified"] = false
			case "outside allowlist":
				claims["sub"] = "222"
			}
			header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"RS256","kid":"fixture"}`))
			b, _ := json.Marshal(claims)
			unsigned := header + "." + base64.RawURLEncoding.EncodeToString(b)
			digest = sha256.Sum256([]byte(unsigned))
			sig, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, digest[:])
			if err != nil {
				http.Error(w, "sign error", 500)
				return
			}
			if code.variant == "wrong signature" {
				sig[0] ^= 1
			}
			reply(w, 200, map[string]string{"id_token": unsigned + "." + base64.RawURLEncoding.EncodeToString(sig),
				"access_token": "test-only", "token_type": "Bearer"})
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(f.server.Close)
	return f
}

func (f *issuerFixture) URL() string {
	return strings.Replace(f.server.URL, "127.0.0.1", "localhost", 1)
}

type panelFixture struct {
	t       *testing.T
	panel   *Panel
	handler http.Handler
	issuer  *issuerFixture
	alerts  []store.WebApproval
}

func setupPanel(t *testing.T) *panelFixture {
	t.Helper()
	options := store.TestingOptions()
	st, err := store.Open(filepath.Join(t.TempDir(), "test.db"), options)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	keys, err := sealbox.Derive(options.KEK)
	if err != nil {
		t.Fatal(err)
	}
	f := &panelFixture{t: t, issuer: newIssuer(t)}
	now := time.Now()
	f.panel = &Panel{Store: st, Keys: keys, Version: "0.7.0", Allow: map[string]bool{"google:111": true},
		Status:         &status.Service{Store: st, Health: health.NewTracker(now), Location: time.UTC, StartedAt: now},
		Google:         NewGoogle("fixture-client", "fixture-secret", "https://panel.example/auth/google/callback"),
		NotifyApproval: func(_ context.Context, a store.WebApproval) { f.alerts = append(f.alerts, a) }}
	f.panel.Google.issuer = f.issuer.URL()
	f.panel.Google.client = f.issuer.server.Client()
	// The fixture certificate is pinned by httptest and names 127.0.0.1.
	// Use localhost in the browser URL so the OAuth redirect crosses sites.
	f.panel.Google.client.Transport.(*http.Transport).TLSClientConfig.ServerName = "127.0.0.1"
	f.handler = f.panel.Handler()
	return f
}

func (f *panelFixture) request(method, path, csrf, body string, cookies ...*http.Cookie) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, "https://panel.example"+path, strings.NewReader(body))
	for _, c := range cookies {
		if c != nil {
			r.AddCookie(c)
		}
	}
	if method == http.MethodPost {
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Origin", "https://panel.example")
		r.Header.Set("X-Nekopost-CSRF", csrf)
		r.Header.Set("Sec-Fetch-Site", "same-origin")
	}
	w := httptest.NewRecorder()
	f.handler.ServeHTTP(w, r)
	return w
}

func getCookie(w *httptest.ResponseRecorder, name string) *http.Cookie {
	for _, c := range w.Result().Cookies() {
		if c.Name == name && c.MaxAge > 0 {
			return c
		}
	}
	return nil
}

func (f *panelFixture) callback(variant string) (string, *http.Cookie) {
	f.t.Helper()
	w := f.request("GET", "/auth/google/start", "", "")
	cookie := getCookie(w, flowCookie)
	loc, err := url.Parse(w.Header().Get("Location"))
	if w.Code != 303 || cookie == nil || err != nil {
		f.t.Fatalf("OAuth start: %d %v %v", w.Code, cookie, err)
	}
	if !cookie.Secure || !cookie.HttpOnly || cookie.Path != "/" || cookie.SameSite != http.SameSiteLaxMode {
		f.t.Fatalf("invalid flow cookie flags")
	}
	q := loc.Query()
	if q.Get("code_challenge_method") != "S256" || q.Get("scope") != "openid email" ||
		q.Get("nonce") == "" || q.Get("state") == "" || q.Get("redirect_uri") != f.panel.Google.RedirectURL {
		f.t.Fatalf("invalid authorization parameters")
	}
	code := randomToken()
	f.issuer.mu.Lock()
	f.issuer.codes[code] = fixtureCode{q, variant}
	f.issuer.mu.Unlock()
	return "/auth/google/callback?" + url.Values{"state": {q.Get("state")}, "code": {code}}.Encode(), cookie
}

func (f *panelFixture) signIn(trusted *http.Cookie) *http.Cookie {
	f.t.Helper()
	path, cookie := f.callback("")
	w := f.request("GET", path, "", "", cookie)
	finish := getCookie(w, "__Host-np_finish")
	if w.Code != 200 || finish == nil {
		f.t.Fatalf("callback: %d %s", w.Code, w.Body.String())
	}
	w = f.request("POST", "/auth/finish", "", "{}", finish, trusted)
	sid := getCookie(w, sidCookie)
	if w.Code != 200 || sid == nil || sid.SameSite != http.SameSiteStrictMode || !sid.Secure || !sid.HttpOnly {
		f.t.Fatalf("finish: %d %s", w.Code, w.Body.String())
	}
	w = f.request("POST", "/auth/finish", "", "{}", finish, trusted)
	if w.Code != 403 {
		f.t.Fatalf("finish cookie replay: %d", w.Code)
	}
	return sid
}

func TestGoogleLoginApprovalAndReadOnlyPanel(t *testing.T) {
	f := setupPanel(t)
	st := f.panel.Store
	ctx := context.Background()
	now := time.Now()
	msg := &model.Message{ID: model.NewMessageID(now), SourceID: "test", Kind: "sms", Priority: model.PriorityNormal,
		ReceivedAt: now, Title: "消息标题", Body: "<script>bad()</script>\u202e",
	}
	if _, err := st.InsertMessage(ctx, msg, []store.NewDelivery{{Channel: "telegram", Target: "default"}}, 0); err != nil {
		t.Fatal(err)
	}
	if w := f.request("GET", "/", "", ""); w.Code != 200 || !strings.Contains(w.Body.String(), "使用 Google 登录") {
		t.Fatal(w.Code)
	}

	pending := f.signIn(nil)
	if len(f.alerts) != 1 {
		t.Fatal("expected a Telegram approval")
	}
	a := f.alerts[0]
	w := f.request("GET", "/panel/message/"+msg.ID, "", "", pending)
	if w.Code != 403 || strings.Contains(w.Body.String(), "bad()") {
		t.Fatal("pending session can read a message")
	}
	if w := f.request("GET", "/v1/approvals/a_other", "", "", pending); w.Code != 404 {
		t.Fatal(w.Code)
	}
	if w := f.request("GET", "/panel/confirm", "", "", pending); w.Code != 200 || !strings.Contains(w.Body.String(), ">"+a.Code+"<") {
		t.Fatal(w.Code, w.Body.String())
	}
	s, err := st.WebSession(ctx, hash(pending.Value), now)
	if err != nil {
		t.Fatal(err)
	}
	if w := f.request("POST", "/auth/activate", s.CSRF, "{}", pending); w.Code != 409 {
		t.Fatal(w.Code)
	}
	if ok, err := st.DecideWebApproval(ctx, a.ID, a.Code, "cli", true, time.Now()); !ok || err != nil {
		t.Fatal(ok, err)
	}
	if w := f.request("POST", "/auth/activate", "bad", "{}", pending); w.Code != 403 {
		t.Fatal(w.Code)
	}
	w = f.request("POST", "/auth/activate", s.CSRF, "{}", pending)
	active, trusted := getCookie(w, sidCookie), getCookie(w, trustCookie)
	if w.Code != 200 || active == nil || trusted == nil || active.Value == pending.Value {
		t.Fatal(w.Code, w.Body.String())
	}
	if w := f.request("GET", "/v1/session", "", "", pending); w.Code != 401 {
		t.Fatal(w.Code)
	}
	w = f.request("GET", "/panel/message/"+msg.ID, "", "", active)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "&lt;script&gt;bad()&lt;/script&gt;⟨U&#43;202E⟩") ||
		strings.Contains(w.Body.String(), "<script>bad()") || w.Header().Get("Cache-Control") != "no-store" ||
		!strings.Contains(w.Header().Get("Content-Security-Policy"), "require-trusted-types-for 'script'") {
		t.Fatal(w.Code, w.Body.String())
	}
	for _, path := range []string{"/panel", "/panel?tab=filtered", "/panel?tab=status", "/panel?tab=stats", "/panel?tab=sources", "/panel?tab=security", "/panel?tab=settings", "/assets/0.7.0/panel.js", "/assets/0.7.0/panel.css"} {
		w := f.request("GET", path, "", "", active)
		if w.Code != 200 {
			t.Fatalf("%s: %d %s", path, w.Code, w.Body.String())
		}
	}
	for _, path := range []string{"/panel/message/%2e%2e", "/panel/message/a%2fb", "/panel//message"} {
		if w := f.request("GET", path, "", "", active); w.Code != 400 {
			t.Fatalf("%s: %d", path, w.Code)
		}
	}
	r := httptest.NewRequest("GET", "https://panel.example/panel", nil)
	r.AddCookie(active)
	r.Header.Set("Sec-Fetch-Site", "same-site")
	w = httptest.NewRecorder()
	f.handler.ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatal(w.Code)
	}
	r = httptest.NewRequest("GET", "https://panel.example/v1/session", nil)
	r.AddCookie(active)
	r.Header.Set("Authorization", "Bearer fixture")
	w = httptest.NewRecorder()
	f.handler.ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatal(w.Code)
	}
	s, err = st.WebSession(ctx, hash(active.Value), time.Now())
	if err != nil {
		t.Fatal(err)
	}
	w = f.request("POST", "/panel/search", s.CSRF, `{"query":"消息标题"}`, active)
	if w.Code != 200 || !strings.Contains(w.Body.String(), msg.ID) {
		t.Fatal(w.Code, w.Body.String())
	}
	second := f.signIn(trusted)
	s2, err := st.WebSession(ctx, hash(second.Value), time.Now())
	if err != nil || s2.State != store.WebActive || len(f.alerts) != 1 {
		t.Fatal(s2.State, err)
	}
	if w := f.request("POST", "/auth/logout", s.CSRF, "{}", active); w.Code != 204 {
		t.Fatal(w.Code)
	}
	if w := f.request("GET", "/v1/session", "", "", active); w.Code != 401 {
		t.Fatal(w.Code)
	}
	if audit, ok, err := st.ReadAudit(ctx); err != nil || !ok || len(audit) < 5 {
		t.Fatal(len(audit), ok, err)
	}
}

func TestGoogleRejectsInvalidCallback(t *testing.T) {
	for _, variant := range []string{"wrong state", "tampered cookie", "wrong nonce", "wrong issuer", "wrong audience", "expired", "wrong signature", "unverified email", "outside allowlist", "replay"} {
		t.Run(variant, func(t *testing.T) {
			f := setupPanel(t)
			path, cookie := f.callback(variant)
			want := 403
			switch variant {
			case "wrong state":
				parsed, _ := url.Parse(path)
				q := parsed.Query()
				q.Set("state", "wrong")
				parsed.RawQuery = q.Encode()
				path = parsed.String()
				want = 400
			case "tampered cookie":
				cookie.Value = "AAAA" + cookie.Value[4:]
				want = 400
			case "replay":
				if w := f.request("GET", path, "", "", cookie); w.Code != 200 {
					t.Fatal(w.Code)
				}
				want = 400
			}
			w := f.request("GET", path, "", "", cookie)
			if w.Code != want || getCookie(w, sidCookie) != nil || getCookie(w, "__Host-np_finish") != nil || len(f.alerts) != 0 {
				b, _ := io.ReadAll(w.Result().Body)
				t.Fatalf("expected rejected callback: got %d want %d %s", w.Code, want, b)
			}
			if variant == "outside allowlist" && !strings.Contains(w.Body.String(), "google:222") {
				t.Fatal("bootstrap identity missing")
			}
		})
	}
}

func TestChromiumPanel(t *testing.T) {
	if os.Getenv("NEKOPOST_RUN_BROWSER_TEST") != "1" {
		t.Skip("Chromium runs in web-checks CI")
	}
	f := setupPanel(t)
	st := f.panel.Store
	msg := &model.Message{ID: model.NewMessageID(time.Now()), SourceID: "test", Kind: "sms", Priority: model.PriorityNormal,
		ReceivedAt: time.Now(), Title: "浏览器演练", Body: "<script>bad()</script>\u202e"}
	if _, err := st.InsertMessage(t.Context(), msg, []store.NewDelivery{{Channel: "telegram", Target: "default"}}, 0); err != nil {
		t.Fatal(err)
	}

	var mu sync.Mutex
	var approval store.WebApproval
	f.panel.NotifyApproval = func(_ context.Context, a store.WebApproval) {
		mu.Lock()
		defer mu.Unlock()
		approval = a
	}
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/__test/approve" {
			f.handler.ServeHTTP(w, r)
			return
		}
		mu.Lock()
		a := approval
		mu.Unlock()
		ok, err := st.DecideWebApproval(r.Context(), a.ID, a.Code, "bot:tg:1", false, time.Now())
		if !ok || err != nil {
			http.Error(w, "approval failed", 500)
			return
		}
		w.WriteHeader(204)
	}))
	defer server.Close()
	f.panel.Google.RedirectURL = server.URL + "/auth/google/callback"
	ctx, cancel := context.WithTimeout(t.Context(), time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, "node", filepath.Join("..", "..", "web-tests", "panel.cjs"))
	cmd.Env = append(os.Environ(), "NEKOPOST_TEST_PANEL="+server.URL)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("%v\n%s", err, out)
	}
}
