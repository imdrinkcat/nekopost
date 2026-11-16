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
	demo := seedDemo(t, st)

	var mu sync.Mutex
	var approval store.WebApproval
	f.panel.NotifyApproval = func(_ context.Context, a store.WebApproval) {
		mu.Lock()
		defer mu.Unlock()
		approval = a
	}
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/v1/inbox/") {
			// Management routes live in httpapi; the real browser check
			// shows the panel's re-authentication prompt.
			if _, ok := f.panel.AuthorizeAdmin(w, r, true); ok {
				w.WriteHeader(204)
			}
			return
		}
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
	ctx, cancel := context.WithTimeout(t.Context(), 8*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, "node", filepath.Join("..", "..", "web-tests", "panel.cjs"))
	cmd.Env = append(os.Environ(), "NEKOPOST_TEST_PANEL="+server.URL, "NEKOPOST_TEST_MESSAGES="+strings.Join(demo[:3], ","))
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("%v\n%s", err, out)
	}
}

// seedDemo fills the store with a representative mix of data so every page
// renders its populated layout (used for the CI review screenshots too).
func seedDemo(t *testing.T, st *store.Store) []string {
	t.Helper()
	ctx := context.Background()
	now := time.Now()
	for _, src := range []store.SourceChange{{ID: "pixel-sms", Name: "Pixel 短信"}, {ID: "iphone", Name: "旧 iPhone"}, {ID: "bank-hook", Name: "银行通知 Webhook"}} {
		src.Hash, src.Prefix = "hash-"+src.ID, "src_"+src.ID[:4]
		if err := st.ChangeSource(ctx, "web:google:111", "create", src, now); err != nil {
			t.Fatal(err)
		}
	}
	if err := st.SetSourceEnabled(ctx, "iphone", false, now); err != nil {
		t.Fatal(err)
	}
	var ids []string
	outcomes := []struct {
		source, kind, title, body string
		state                     model.DeliveryState
		err                       string
	}{
		{"pixel-sms", "sms", "【某银行】验证码", "您的验证码为 482913，5 分钟内有效。请勿告诉他人。", model.DeliveryDelivered, ""},
		{"bank-hook", "notification", "信用卡账单", "本期账单 ¥3,280.00，还款日 11 月 25 日。", model.DeliveryRetrying, "telegram: 502 Bad Gateway"},
		{"pixel-sms", "sms", "快递到了", "您的包裹已放入 3 号快递柜，取件码 7731。", model.DeliveryUncertain, "context deadline exceeded"},
		{"iphone", "sms", "", "晚上一起吃饭吗？", model.DeliveryFailed, "telegram: chat not found"},
		{"pixel-sms", "sms", "", "长时间未投递的消息。", "", ""},
	}
	for i, o := range outcomes {
		m := &model.Message{ID: model.NewMessageID(now.Add(time.Duration(-i) * time.Minute)), SourceID: o.source, Kind: o.kind,
			Priority: model.PriorityNormal, ReceivedAt: now.Add(time.Duration(-i) * 17 * time.Minute), Title: o.title, Body: o.body,
			Meta: map[string]string{"sender": "+81 90-0000-000" + string(rune('0'+i))}}
		if _, err := st.InsertMessage(ctx, m, []store.NewDelivery{{Channel: "telegram", Target: "default"}}, 0); err != nil {
			t.Fatal(err)
		}
		ids = append(ids, m.ID)
		if o.state == "" {
			continue
		}
		info, err := st.GetMessage(ctx, m.ID)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := st.FinishAttempt(ctx, info.Deliveries[0].ID, m.ID, store.AttemptResult{State: o.state, LastError: o.err,
			Uncertain: o.state == model.DeliveryUncertain, PartsTotal: 1, PartsSent: map[bool]int{true: 1}[o.state == model.DeliveryDelivered],
			NextAttemptAt: now.Add(time.Minute)}, now); err != nil {
			t.Fatal(err)
		}
	}
	for i, c := range []model.Category{model.CatMarketing, model.CatSpam, model.CatCode} {
		m := &model.Message{ID: model.NewMessageID(now), SourceID: "pixel-sms", Kind: "sms", Priority: model.PriorityNormal,
			ReceivedAt: now.Add(time.Duration(-i) * time.Hour), Title: "过滤示例", Body: "双十一大促，全场五折起！"}
		if _, err := st.InsertMessage(ctx, m, []store.NewDelivery{{Channel: "telegram", Target: "default", Held: true}}, 0); err != nil {
			t.Fatal(err)
		}
		if _, err := st.ResolveHeld(ctx, m.ID, c, true, now); err != nil {
			t.Fatal(err)
		}
	}
	for i, name := range []string{"Galaxy Z Fold8", "旧平板"} {
		p := store.Pairing{ID: "p_" + rand.Text(), Scopes: store.ScopeRead, Name: name, Expires: now.Add(5 * time.Minute)}
		code := "code-" + p.ID
		if err := st.NewPairing(ctx, p, code, "short-"+p.ID, "cli", now); err != nil {
			t.Fatal(err)
		}
		if _, err := st.ClaimPairing(ctx, code, store.PairClaim{JWK: "{}", JKT: "jkt-" + p.ID, Name: name, Model: "SM-F9760", AppVersion: "0.2.1", Code: "42", Choices: []string{"17", "42", "88"}}, now); err != nil {
			t.Fatal(err)
		}
		if ok, err := st.DecidePairing(ctx, p.ID, "42", "cli", now); !ok || err != nil {
			t.Fatal(ok, err)
		}
		d, err := st.ConsumePairing(ctx, p.ID, "jkt-"+p.ID, "d_"+rand.Text(), "token-"+p.ID, now)
		if err != nil {
			t.Fatal(err)
		}
		if i == 1 {
			if err := st.RevokeDevice(ctx, d.ID, "cli", now); err != nil {
				t.Fatal(err)
			}
		}
	}
	for i, name := range []string{"备份脚本", "旧 CLI"} {
		prefix := "mga_" + rand.Text()[:6]
		if err := st.CreateAdminKey(ctx, name, "hash-"+prefix, prefix, now); err != nil {
			t.Fatal(err)
		}
		if i == 1 {
			if err := st.RevokeAdminKey(ctx, prefix, now); err != nil {
				t.Fatal(err)
			}
		}
	}
	return ids
}

func TestPanelPagesRender(t *testing.T) {
	f := setupPanel(t)
	ids := seedDemo(t, f.panel.Store)
	pending := f.signIn(nil)
	a := f.alerts[0]
	if w := f.request("GET", "/panel/confirm", "", "", pending); w.Code != 200 || !strings.Contains(w.Body.String(), ">"+a.Code+"<") {
		t.Fatal(w.Code)
	}
	if ok, err := f.panel.Store.DecideWebApproval(context.Background(), a.ID, a.Code, "cli", false, time.Now()); !ok || err != nil {
		t.Fatal(ok, err)
	}
	s, err := f.panel.Store.WebSession(context.Background(), hash(pending.Value), time.Now())
	if err != nil {
		t.Fatal(err)
	}
	active := getCookie(f.request("POST", "/auth/activate", s.CSRF, "{}", pending), sidCookie)
	paths := []string{"/", "/panel", "/panel?tab=filtered", "/panel?tab=status", "/panel?tab=stats", "/panel?tab=stats&range=today",
		"/panel?tab=sources", "/panel?tab=devices", "/panel?tab=keys", "/panel?tab=security", "/panel?tab=settings"}
	for _, id := range ids {
		paths = append(paths, "/panel/message/"+id)
	}
	for _, path := range paths {
		w := f.request("GET", path, "", "", active)
		body := w.Body.String()
		if w.Code != 200 || !strings.HasPrefix(body, "<!doctype html>") || strings.Contains(body, " style=") || strings.Contains(body, "<style") {
			t.Fatalf("%s: %d %.300s", path, w.Code, body)
		}
		if n := strings.Count(body, "<script"); n != 1 {
			t.Fatalf("%s: %d script tags", path, n)
		}
	}
	if w := f.request("GET", "/panel?tab=sources", "", "", active); !strings.Contains(w.Body.String(), `data-row="iphone"`) || !strings.Contains(w.Body.String(), `data-action="delete-source"`) {
		t.Fatal("source row contract missing")
	}
}
