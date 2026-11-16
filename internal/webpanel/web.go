// Package webpanel serves Nekopost's browser-only console.
package webpanel

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"math/big"
	"mime"
	"net/http"
	"net/netip"
	"regexp"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"nekopost/internal/ratelimit"
	"nekopost/internal/sealbox"
	"nekopost/internal/status"
	"nekopost/internal/store"
)

const (
	flowCookie  = "__Host-np_oauth"
	sidCookie   = "__Host-np_sid"
	trustCookie = "__Host-np_tb"
	flowTTL     = 10 * time.Minute
)

//go:embed panel.html panel.css panel.js
var files embed.FS

var pages = template.Must(template.New("panel.html").Funcs(template.FuncMap{"visible": visible, "auditDate": auditDate}).Funcs(viewFuncs).ParseFS(files, "panel.html"))
var messageID = regexp.MustCompile(`^m_[0-9A-HJKMNP-TV-Z]{26}$`)

type Panel struct {
	Google         *Google
	Allow          map[string]bool
	Keys           sealbox.Keys
	Store          *store.Store
	Status         *status.Service
	Version        string
	Settings       Settings
	ClientIP       func(*http.Request) string
	NotifyApproval func(context.Context, store.WebApproval)
	NotifySecurity func(context.Context, string)
	Now            func() time.Time

	searches    sync.Map
	replayMu    sync.Mutex
	used        map[string]int64
	alertMu     sync.Mutex
	alertWindow time.Time
	alertCount  int
}

type Settings struct {
	Timezone                                             string
	RetentionDays, BodyRetentionDays, CodeRetentionHours int
}

type flow struct {
	Provider, State, Verifier, Nonce, JTI, Reauth string
	Expires                                       int64
}

func (p *Panel) enabled() bool { return p.Google != nil && len(p.Allow) > 0 }
func (p *Panel) now() time.Time {
	if p.Now != nil {
		return p.Now()
	}
	return time.Now()
}

func (p *Panel) Handler() http.Handler {
	mux := http.NewServeMux()
	limiter := ratelimit.NewLimiter(10, 10)
	guard := ratelimit.NewFailureGuard(10, 10*time.Minute, 15*time.Minute)
	var lastCleanup atomic.Int64
	limitOAuth := func(next http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodGet {
				w.Header().Set("Allow", "GET")
				http.Error(w, "请求方法无效", 405)
				return
			}
			key := ratelimit.ScopeKey(p.ip(r), "oauth")
			blocked, wait := guard.Blocked(key)
			if !blocked {
				var allowed bool
				allowed, wait = limiter.Allow(key)
				blocked = !allowed
			}
			if blocked {
				w.Header().Set("Retry-After", fmt.Sprint(max(1, int(wait.Seconds()+1))))
				http.Error(w, "尝试次数过多，请稍后再试", 429)
				return
			}
			now := p.now().Unix()
			if last := lastCleanup.Load(); now-last >= 60 && lastCleanup.CompareAndSwap(last, now) {
				limiter.Cleanup()
				guard.Cleanup()
			}
			next(w, r)
		}
	}
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) { p.page(w, 200, pageData{Mode: "home"}) })
	mux.HandleFunc("/auth/google/start", limitOAuth(p.start))
	mux.HandleFunc("/auth/google/callback", limitOAuth(func(w http.ResponseWriter, r *http.Request) {
		p.callback(w, r, func() { guard.Fail(ratelimit.ScopeKey(p.ip(r), "oauth")) })
	}))

	cookie := http.NewCrossOriginProtection()
	protected := func(next http.HandlerFunc) http.Handler {
		return cookie.Handler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if site := r.Header.Get("Sec-Fetch-Site"); site != "" && site != "same-origin" && site != "none" {
				http.Error(w, "跨站请求已拒绝", 403)
				return
			}
			if origin := r.Header.Get("Origin"); origin != "" && p.enabled() &&
				origin+"/auth/google/callback" != p.Google.RedirectURL {
				http.Error(w, "请求来源无效", 403)
				return
			}
			next(w, r)
		}))
	}

	mux.Handle("GET /v1/session", protected(p.sessionStatus))
	mux.Handle("POST /v1/session/reauth", protected(p.beginReauth))
	mux.Handle("POST /v1/session/reauth/finish", protected(p.finishReauth))
	mux.Handle("GET /v1/approvals/{id}", protected(p.approvalStatus))
	mux.Handle("POST /auth/finish", protected(p.finish))
	mux.Handle("POST /auth/activate", protected(p.activate))
	mux.Handle("POST /auth/logout", protected(p.logout))
	mux.Handle("GET /panel", protected(p.dashboard))
	mux.Handle("GET /panel/confirm", protected(p.confirmPage))
	mux.Handle("GET /panel/message/{id}", protected(p.messagePage))
	mux.Handle("POST /panel/search", protected(p.search))
	for _, asset := range []string{"panel.css", "panel.js"} {
		mux.HandleFunc("GET /assets/"+p.Version+"/"+asset, func(w http.ResponseWriter, r *http.Request) {
			t := "text/css; charset=utf-8"
			if asset == "panel.js" {
				t = "text/javascript; charset=utf-8"
			}
			w.Header().Set("Content-Type", t)
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
			data, _ := files.ReadFile(asset)
			_, _ = w.Write(data)
		})
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		headers(w)
		path := strings.ToLower(r.URL.EscapedPath())
		if strings.Contains(path, "%2f") || strings.Contains(path, "%2e") || strings.Contains(path, "%5c") ||
			strings.Contains(path, "..") || strings.Contains(path, "//") || strings.Contains(path, "\\") {
			http.Error(w, "请求路径无效", 400)
			return
		}
		mux.ServeHTTP(w, r)
	})
}

func headers(w http.ResponseWriter) {
	h := w.Header()
	h.Set("Cache-Control", "no-store")
	h.Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'; require-trusted-types-for 'script'; trusted-types 'none'; upgrade-insecure-requests")
	h.Set("Cross-Origin-Opener-Policy", "same-origin")
	h.Set("Cross-Origin-Embedder-Policy", "require-corp")
	h.Set("Cross-Origin-Resource-Policy", "same-origin")
	h.Set("Referrer-Policy", "no-referrer")
	h.Set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=(), clipboard-write=(self)")
	h.Set("X-Frame-Options", "DENY")
	h.Set("X-Content-Type-Options", "nosniff")
}

func (p *Panel) ip(r *http.Request) string {
	if p.ClientIP == nil {
		return ""
	}
	ip, err := netip.ParseAddr(p.ClientIP(r))
	if err != nil {
		return ""
	}
	return ip.Unmap().String()
}

func randomToken() string { return rand.Text() + rand.Text() }
func hash(raw string) string {
	digest := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(digest[:])
}

func cookieValue(r *http.Request, name string) string {
	c, err := r.Cookie(name)
	if err != nil || len(c.Value) > 2048 {
		return ""
	}
	return c.Value
}

func setCookie(w http.ResponseWriter, name, value string, age int) {
	mode := http.SameSiteStrictMode
	if name == flowCookie {
		mode = http.SameSiteLaxMode
	}
	http.SetCookie(w, &http.Cookie{Name: name, Value: value, Path: "/", Secure: true, HttpOnly: true, SameSite: mode, MaxAge: age})
}

func (p *Panel) start(w http.ResponseWriter, r *http.Request) {
	if !p.enabled() {
		p.page(w, 404, pageData{Mode: "error", Error: "网页面板未启用"})
		return
	}
	f := flow{Provider: "google", State: randomToken(), Verifier: randomToken(), Nonce: randomToken(), JTI: randomToken(),
		Expires: p.now().Add(flowTTL).UnixMilli()}
	if r.URL.Query().Get("reauth") == "1" {
		if !p.sameOrigin(r) {
			http.Error(w, "跨站请求已拒绝", 403)
			return
		}
		session, ok := p.require(w, r, true)
		if !ok {
			return
		}
		f.Reauth = session.Hash
	}
	b, _ := json.Marshal(f)
	encrypted, err := sealbox.SealOAuth(p.Keys, b)
	if err != nil {
		p.failed(w)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()
	url, err := p.Google.authorize(ctx, f)
	if err != nil {
		p.page(w, 502, pageData{Mode: "error", Error: "暂时无法连接 Google，请稍后重试"})
		return
	}
	setCookie(w, flowCookie, base64.RawURLEncoding.EncodeToString(encrypted), int(flowTTL.Seconds()))
	http.Redirect(w, r, url, http.StatusSeeOther)
}

func (p *Panel) consume(f flow) bool {
	p.replayMu.Lock()
	defer p.replayMu.Unlock()
	now := p.now().UnixMilli()
	for id, expires := range p.used {
		if expires <= now {
			delete(p.used, id)
		}
	}
	if p.used[f.JTI] != 0 || f.Expires <= now || len(p.used) >= 1024 {
		return false
	}
	if p.used == nil {
		p.used = make(map[string]int64)
	}
	p.used[f.JTI] = f.Expires
	return true
}

func (p *Panel) callback(w http.ResponseWriter, r *http.Request, badState func()) {
	if !p.enabled() {
		http.NotFound(w, r)
		return
	}
	setCookie(w, flowCookie, "", -1)
	q := r.URL.Query()
	raw, err := base64.RawURLEncoding.DecodeString(cookieValue(r, flowCookie))
	var f flow
	plain, decryptErr := sealbox.OpenOAuth(p.Keys, raw)
	if err != nil || decryptErr != nil || json.Unmarshal(plain, &f) != nil || f.Provider != "google" ||
		f.Expires <= p.now().UnixMilli() || len(q["state"]) != 1 || subtle.ConstantTimeCompare([]byte(q.Get("state")), []byte(f.State)) != 1 {
		badState()
		p.page(w, 400, pageData{Mode: "error", Error: "登录已过期或无效，请重新开始"})
		return
	}
	if len(q["code"]) != 1 || q.Get("code") == "" || q.Get("error") != "" {
		p.page(w, 400, pageData{Mode: "error", Error: "Google 登录未完成"})
		return
	}
	p.replayMu.Lock()
	used := p.used[f.JTI] != 0
	p.replayMu.Unlock()
	if used {
		p.page(w, 400, pageData{Mode: "error", Error: "此登录请求已使用"})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	identity, err := p.Google.exchange(ctx, f, q.Get("code"))
	if err != nil || !p.consume(f) {
		p.page(w, 403, pageData{Mode: "error", Error: "Google 身份验证失败，请重新登录"})
		return
	}
	if !p.Allow[identity] {
		if err := p.Store.Audit(ctx, p.now(), identity, "login.allowlist", "", "denied"); err != nil {
			p.failed(w)
			return
		}
		p.alert(ctx, "有 Google 账号尝试登录 Nekopost，但未获授权。")
		p.page(w, 403, pageData{Mode: "error", Error: "此账号未获授权", Identity: identity})
		return
	}

	// Strict cookies are not sent back on a cross-site redirect from Google.
	// Complete the login from a first-party request so trusted browsers work.
	rawFinish, err := json.Marshal(finishFlow{Identity: identity, Reauth: f.Reauth, Expires: p.now().Add(time.Minute).UnixMilli()})
	if err != nil {
		p.failed(w)
		return
	}
	encrypted, err := sealbox.SealOAuth(p.Keys, rawFinish)
	if err != nil {
		p.failed(w)
		return
	}
	setCookie(w, "__Host-np_finish", base64.RawURLEncoding.EncodeToString(encrypted), 60)
	p.page(w, 200, pageData{Mode: "finish"})
}

type finishFlow struct {
	Identity, Reauth string
	Expires          int64
}

func (p *Panel) finish(w http.ResponseWriter, r *http.Request) {
	if !p.enabled() || !jsonRequest(r) || len(r.Header.Values("Authorization")) != 0 {
		http.Error(w, "请求无效", 400)
		return
	}
	const name = "__Host-np_finish"
	raw, err := base64.RawURLEncoding.DecodeString(cookieValue(r, name))
	plain, decryptErr := sealbox.OpenOAuth(p.Keys, raw)
	var f finishFlow
	if err != nil || decryptErr != nil || json.Unmarshal(plain, &f) != nil ||
		!p.Allow[f.Identity] || f.Expires <= p.now().UnixMilli() ||
		!p.consume(flow{JTI: "finish:" + hash(string(raw)), Expires: f.Expires}) {
		http.Error(w, "登录已过期，请重新开始", 403)
		return
	}
	setCookie(w, name, "", -1)
	if f.Reauth != "" {
		session, ok := p.require(w, r, true)
		if !ok {
			return
		}
		if session.Hash != f.Reauth || session.Identity != f.Identity {
			http.Error(w, "重新认证身份不匹配", 403)
			return
		}
		if err := p.Store.RefreshWebReauth(r.Context(), session, p.now()); err != nil {
			p.failed(w)
			return
		}
		reply(w, 200, map[string]string{"state": store.WebActive})
		return
	}
	now := p.now()
	sid := randomToken()
	session := store.WebSession{Hash: hash(sid), Identity: f.Identity, CSRF: randomToken(),
		IP: p.ip(r), Browser: browserLabel(r.UserAgent())}
	a, err := newApproval(session, now)
	if err != nil {
		p.failed(w)
		return
	}
	state, err := p.Store.StartWebSession(r.Context(), session, a, hash(cookieValue(r, trustCookie)), now)
	if errors.Is(err, store.ErrWebRestricted) {
		http.Error(w, "登录正在冷却或暂停，请稍后重试或在服务器解除", 429)
		return
	}
	if err != nil {
		p.failed(w)
		return
	}
	setCookie(w, sidCookie, sid, int((7 * 24 * time.Hour).Seconds()))
	if state == store.WebPending && p.NotifyApproval != nil {
		p.NotifyApproval(r.Context(), a)
	}
	if state == store.WebActive {
		p.alert(r.Context(), "可信浏览器刚刚登录 Nekopost · "+session.Browser+" · "+session.IP)
	}
	reply(w, 200, map[string]string{"state": state})
}

func newApproval(session store.WebSession, now time.Time) (store.WebApproval, error) {
	a := store.WebApproval{ID: "a_" + rand.Text(), Identity: session.Identity, IP: session.IP, Browser: session.Browser, Expires: now.Add(5 * time.Minute)}
	for len(a.Choices) < 3 {
		n, err := rand.Int(rand.Reader, big.NewInt(100))
		if err != nil {
			return a, err
		}
		value := fmt.Sprintf("%02d", n.Int64())
		if len(a.Choices) == 0 {
			a.Code = value
		}
		if !strings.Contains(strings.Join(a.Choices, " "), value) {
			a.Choices = append(a.Choices, value)
		}
	}
	sort.Strings(a.Choices)
	return a, nil
}

func browserLabel(ua string) string {
	for _, item := range []struct{ match, label string }{
		{"SamsungBrowser/", "Samsung Internet"}, {"Edg/", "Edge"}, {"Firefox/", "Firefox"},
		{"Chrome/", "Chrome"}, {"Safari/", "Safari"},
	} {
		if strings.Contains(ua, item.match) {
			return item.label
		}
	}
	return "未知浏览器"
}

func (p *Panel) alert(ctx context.Context, msg string) {
	if p.NotifySecurity == nil {
		return
	}
	p.alertMu.Lock()
	now := p.now()
	if now.Sub(p.alertWindow) >= time.Hour {
		p.alertWindow, p.alertCount = now, 0
	}
	if p.alertCount >= 3 {
		p.alertMu.Unlock()
		return
	}
	p.alertCount++
	p.alertMu.Unlock()
	p.NotifySecurity(ctx, msg)
}

func (p *Panel) session(r *http.Request) (store.WebSession, error) {
	if !p.enabled() || len(r.Header.Values("Authorization")) != 0 {
		return store.WebSession{}, store.ErrNotFound
	}
	raw := cookieValue(r, sidCookie)
	if raw == "" {
		return store.WebSession{}, store.ErrNotFound
	}
	session, err := p.Store.WebSession(r.Context(), hash(raw), p.now())
	if err != nil {
		return session, err
	}
	if !p.Allow[session.Identity] {
		if err := p.Store.EndWebSession(r.Context(), session, p.now()); err != nil {
			return store.WebSession{}, err
		}
		return store.WebSession{}, store.ErrNotFound
	}
	return session, nil
}

func (p *Panel) require(w http.ResponseWriter, r *http.Request, active bool) (store.WebSession, bool) {
	session, err := p.session(r)
	if errors.Is(err, store.ErrNotFound) {
		if strings.HasPrefix(r.URL.Path, "/panel") {
			http.Redirect(w, r, "/", 303)
		} else {
			http.Error(w, "请先登录", 401)
		}
		return session, false
	}
	if err != nil {
		p.failed(w)
		return session, false
	}
	if active && session.State != store.WebActive {
		http.Error(w, "请先确认此浏览器", 403)
		return session, false
	}
	if session.State == store.WebActive {
		if err := p.Store.TouchWebSession(r.Context(), session, p.ip(r), browserLabel(r.UserAgent()), p.now()); err != nil {
			p.failed(w)
			return session, false
		}
	}
	return session, true
}

func jsonRequest(r *http.Request) bool {
	typ, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	return err == nil && typ == "application/json"
}

func csrfOK(r *http.Request, session store.WebSession) bool {
	return jsonRequest(r) && subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Nekopost-CSRF")), []byte(session.CSRF)) == 1
}

func (p *Panel) sessionStatus(w http.ResponseWriter, r *http.Request) {
	session, ok := p.require(w, r, false)
	if !ok {
		return
	}
	reply(w, 200, map[string]string{"state": session.State, "identity": session.Identity, "csrf": session.CSRF})
}

func (p *Panel) approvalStatus(w http.ResponseWriter, r *http.Request) {
	session, ok := p.require(w, r, false)
	if !ok {
		return
	}
	subject := session.Hash
	if session.State == store.WebActive {
		subject = "reauth:" + subject
	}
	a, err := p.Store.WebApproval(r.Context(), subject, p.now())
	if errors.Is(err, store.ErrNotFound) || err == nil && a.ID != r.PathValue("id") {
		http.NotFound(w, r)
		return
	}
	if err != nil {
		p.failed(w)
		return
	}
	reply(w, 200, map[string]string{"state": a.State})
}

func (p *Panel) activate(w http.ResponseWriter, r *http.Request) {
	session, ok := p.require(w, r, false)
	if !ok {
		return
	}
	if !csrfOK(r, session) || session.State != store.WebPending {
		http.Error(w, "无法确认会话", 403)
		return
	}
	sid, browser := randomToken(), randomToken()
	trusted, err := p.Store.ActivateWebSession(r.Context(), session, hash(sid), randomToken(), hash(browser), p.now())
	if errors.Is(err, store.ErrNotFound) {
		http.Error(w, "登录尚未确认或已过期", 409)
		return
	}
	if err != nil {
		p.failed(w)
		return
	}
	setCookie(w, sidCookie, sid, int((7 * 24 * time.Hour).Seconds()))
	if trusted {
		setCookie(w, trustCookie, browser, int((30 * 24 * time.Hour).Seconds()))
	}
	reply(w, 200, map[string]string{"state": store.WebActive})
}

func (p *Panel) logout(w http.ResponseWriter, r *http.Request) {
	session, ok := p.require(w, r, false)
	if !ok {
		return
	}
	if !csrfOK(r, session) {
		http.Error(w, "请求校验失败", 403)
		return
	}
	if err := p.Store.EndWebSession(r.Context(), session, p.now()); err != nil {
		p.failed(w)
		return
	}
	setCookie(w, sidCookie, "", -1)
	w.Header().Set("Clear-Site-Data", `"cache","cookies","storage"`)
	w.WriteHeader(204)
}

func reply(w http.ResponseWriter, code int, data any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(data)
}

func (p *Panel) failed(w http.ResponseWriter) {
	p.page(w, 500, pageData{Mode: "error", Error: "暂时无法处理请求，请稍后重试"})
}

func (p *Panel) sameOrigin(r *http.Request) bool {
	site := r.Header.Get("Sec-Fetch-Site")
	if site != "" && site != "same-origin" && site != "none" {
		return false
	}
	origin := r.Header.Get("Origin")
	return origin == "" || p.enabled() && origin+"/auth/google/callback" == p.Google.RedirectURL
}

// AuthorizeAdmin protects JSON management routes that accept browser cookies.
func (p *Panel) AuthorizeAdmin(w http.ResponseWriter, r *http.Request, high bool) (string, bool) {
	headers(w)
	if http.NewCrossOriginProtection().Check(r) != nil || !p.sameOrigin(r) {
		reply(w, 403, map[string]string{"code": "cross_origin", "message": "跨站请求已拒绝"})
		return "", false
	}
	session, ok := p.require(w, r, true)
	if !ok {
		return "", false
	}
	if r.Method != "GET" && !csrfOK(r, session) {
		reply(w, 403, map[string]string{"code": "invalid_csrf", "message": "请求校验失败"})
		return "", false
	}
	if high && (session.ReauthAt.IsZero() || p.now().Sub(session.ReauthAt) > 5*time.Minute) {
		reply(w, 403, map[string]string{"code": "reauth_required", "message": "请先重新认证"})
		return "", false
	}
	return "web:" + session.Identity, true
}

func (p *Panel) SessionHash(r *http.Request) string {
	session, err := p.session(r)
	if err != nil || session.State != store.WebActive {
		return ""
	}
	return session.Hash
}
