package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"msggw/internal/auth"
	"msggw/internal/delivery"
	"msggw/internal/filter"
	"msggw/internal/health"
	"msggw/internal/model"
	"msggw/internal/ratelimit"
	"msggw/internal/router"
	"msggw/internal/status"
	"msggw/internal/store"
	"msggw/internal/sysinfo"
	"msggw/internal/telegram"
	"msggw/internal/testutil/faketg"
)

type env struct {
	worker   *delivery.Worker
	srv      *httptest.Server
	st       *store.Store
	tg       *faketg.Server
	api      *Server
	tracker  *health.Tracker
	iphone   string // source token
	linux    string
	adminKey string
}

var bg = context.Background()

func setup(t *testing.T, opts ...func(*env)) *env {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	e := &env{st: st, tg: faketg.New(t)}
	now := time.Now()
	e.iphone = auth.NewToken(auth.SourcePrefix)
	e.linux = auth.NewToken(auth.SourcePrefix)
	e.adminKey = auth.NewToken(auth.AdminPrefix)
	st.CreateSource(bg, model.Source{ID: "iphone", Name: "iPhone", Icon: "📱", Enabled: true}, auth.Hash(e.iphone), "p", now)
	st.CreateSource(bg, model.Source{ID: "linux", Name: "Linux", Enabled: true}, auth.Hash(e.linux), "p", now)
	st.CreateAdminKey(bg, "ops", auth.Hash(e.adminKey), "p", now)

	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	e.tracker = health.NewTracker(now)
	e.tracker.SetListening(true, "")
	notifier := delivery.NewNotifier()
	w := &delivery.Worker{Store: st, Health: e.tracker, Notifier: notifier, Log: log, MaxAge: time.Hour,
		Channels: map[string]delivery.Channel{"telegram": &delivery.TelegramChannel{
			Client: telegram.New(e.tg.URL, faketg.Token), Targets: map[string]int64{"default": 42}, Location: time.UTC}}}
	ctx, cancel := context.WithCancel(bg)
	t.Cleanup(cancel)
	go w.Run(ctx)
	e.worker = w

	tokyo, _ := time.LoadLocation("Asia/Tokyo")
	e.api = &Server{
		Store: st, Router: router.Default{Channel: "telegram", Target: "default"}, Worker: w, Notifier: notifier,
		Health: e.tracker, Log: log, DedupeKey: []byte("k"), DedupeWindow: time.Minute, MaxBodyChars: 12000,
		Location: tokyo, Version: "test",
		Status: &status.Service{Store: st, Health: e.tracker, Location: tokyo, StartedAt: now, RetentionDays: 30,
			Sys: sysinfo.Collector{DiskPath: t.TempDir(), CPUSample: time.Millisecond}},
		SourceLimiter: ratelimit.NewLimiter(60, 5),
		FailGuard:     ratelimit.NewFailureGuard(20, 10*time.Minute, 15*time.Minute),
	}
	for _, o := range opts {
		o(e)
	}
	e.srv = httptest.NewServer(e.api.Handler())
	t.Cleanup(e.srv.Close)
	return e
}

type resp struct {
	code int
	hdr  http.Header
	body map[string]any
}

func (e *env) do(t *testing.T, method, path, token, ctype, body string, hdr ...string) resp {
	t.Helper()
	req, _ := http.NewRequest(method, e.srv.URL+path, strings.NewReader(body))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if ctype != "" {
		req.Header.Set("Content-Type", ctype)
	}
	for i := 0; i+1 < len(hdr); i += 2 {
		req.Header.Set(hdr[i], hdr[i+1])
	}
	r, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Body.Close()
	var m map[string]any
	raw, _ := io.ReadAll(r.Body)
	if len(raw) > 0 {
		if err := json.Unmarshal(raw, &m); err != nil {
			t.Fatalf("non-JSON response %q", raw)
		}
	}
	return resp{r.StatusCode, r.Header, m}
}

func (e *env) post(t *testing.T, token, body string, hdr ...string) resp {
	return e.do(t, "POST", "/v1/messages?wait=5", token, "application/json", body, hdr...)
}

func errCode(r resp) string {
	if e, ok := r.body["error"].(map[string]any); ok {
		return e["code"].(string)
	}
	return ""
}

func TestSubmitAndDeliver(t *testing.T) {
	e := setup(t)
	body := `{"title":"招商银行","body":"验证码 123456，5分钟内有效。\n日本語テスト 🎉👍🏽","type":"sms"}`
	r := e.post(t, e.iphone, body)
	if r.code != 202 || r.body["ok"] != true || r.body["status"] != "delivered" || r.body["delivered"] != true ||
		r.body["status_text"] != "已送达 Telegram" || r.body["duplicate"] != false {
		t.Fatalf("%d %v", r.code, r.body)
	}
	if r.body["message"] != "已送达 Telegram" {
		t.Errorf("message %v", r.body["message"])
	}
	if !strings.HasSuffix(r.body["received_at"].(string), "+09:00") {
		t.Error("times should use the configured timezone")
	}
	calls := e.tg.Calls("sendMessage")
	if len(calls) != 1 {
		t.Fatalf("calls %d", len(calls))
	}
	text := calls[0].Params["text"].(string)
	if text != "<b>招商银行</b>\n验证码 123456，5分钟内有效。\n日本語テスト 🎉👍🏽" {
		t.Errorf("telegram text should be only the content:\n%s", text)
	}
	if b := buttons(calls[0].Params); b != "📱 iPhone=i:"+r.body["id"].(string)+" 💬 短信=i:"+r.body["id"].(string) {
		t.Errorf("source and type buttons: %s", b)
	}
	// Status lookup by the owning source.
	id := r.body["id"].(string)
	g := e.do(t, "GET", "/v1/messages/"+id, e.iphone, "", "")
	if g.code != 200 || g.body["status"] != "delivered" || g.body["source"] != "iphone" {
		t.Fatalf("%d %v", g.code, g.body)
	}
	if _, leaked := g.body["body"]; leaked {
		t.Error("status endpoint must not return content")
	}
	// Another source cannot see it.
	if g := e.do(t, "GET", "/v1/messages/"+id, e.linux, "", ""); g.code != 404 {
		t.Errorf("cross-source lookup: %d", g.code)
	}
}

func TestSourcesDistinguished(t *testing.T) {
	e := setup(t)
	e.post(t, e.iphone, `{"body":"a"}`)
	e.post(t, e.linux, `{"body":"b","type":"task"}`)
	calls := e.tg.Calls("sendMessage")
	if len(calls) != 2 || !strings.Contains(buttons(calls[0].Params), "📱 iPhone=") ||
		!strings.Contains(buttons(calls[1].Params), "📨 Linux=") || !strings.Contains(buttons(calls[1].Params), "✅ 任务=") {
		t.Fatalf("%v", calls)
	}
}

func TestTextPlain(t *testing.T) {
	e := setup(t)
	r := e.do(t, "POST", "/v1/messages?title=Build&type=task", e.linux, "text/plain; charset=utf-8", "line1\r\nline2")
	if r.code != 202 {
		t.Fatalf("%d %v", r.code, r.body)
	}
	e.tg.WaitCalls(t, "sendMessage", 1, 5*time.Second)
	if txt := e.tg.Calls("sendMessage")[0].Params["text"].(string); !strings.Contains(txt, "<b>Build</b>\nline1\nline2") {
		t.Errorf("text %q", txt)
	}
}

func TestAuthentication(t *testing.T) {
	e := setup(t)
	if r := e.post(t, "", `{"body":"x"}`); r.code != 401 || errCode(r) != "unauthorized" || r.hdr.Get("WWW-Authenticate") == "" ||
		!strings.HasPrefix(r.body["message"].(string), "提交失败") {
		t.Errorf("missing token: %d %v", r.code, r.body)
	}
	if r := e.post(t, "mgs_wrong", `{"body":"x"}`); r.code != 401 {
		t.Errorf("wrong token: %d", r.code)
	}
	if r := e.post(t, e.adminKey, `{"body":"x"}`); r.code != 401 {
		t.Errorf("admin key must not submit messages: %d", r.code)
	}
	// Revoked credential.
	newTok := auth.NewToken(auth.SourcePrefix)
	e.st.RotateSourceToken(bg, "iphone", auth.Hash(newTok), "p", time.Now())
	if r := e.post(t, e.iphone, `{"body":"x"}`); r.code != 401 {
		t.Errorf("revoked token: %d", r.code)
	}
	if src, _ := e.st.GetSource(bg, "iphone"); src.AuthFailCount != 1 {
		t.Error("revoked token use should be attributed to the source")
	}
	if r := e.post(t, newTok, `{"body":"x"}`); r.code != 202 {
		t.Errorf("new token: %d", r.code)
	}
	// Disabled source.
	e.st.SetSourceEnabled(bg, "linux", false, time.Now())
	if r := e.post(t, e.linux, `{"body":"x"}`); r.code != 403 || errCode(r) != "source_disabled" {
		t.Errorf("disabled: %d %v", r.code, r.body)
	}
	if len(e.tg.Calls("sendMessage")) != 1 {
		t.Error("only the valid submission should be forwarded")
	}
	if e.tracker.Snapshot().AuthFailures != 4 {
		t.Errorf("auth failures %d", e.tracker.Snapshot().AuthFailures)
	}
}

func TestBruteForceBlocked(t *testing.T) {
	e := setup(t)
	for i := 0; i < 20; i++ {
		e.post(t, "mgs_guess", `{"body":"x"}`)
	}
	r := e.post(t, e.iphone, `{"body":"x"}`)
	if r.code != 429 || errCode(r) != "too_many_auth_failures" || r.hdr.Get("Retry-After") == "" {
		t.Errorf("client should be blocked after repeated failures: %d %v", r.code, r.body)
	}
}

func TestValidation(t *testing.T) {
	e := setup(t)
	e.api.SourceLimiter = ratelimit.NewLimiter(6000, 1000)
	cases := []struct {
		body, code, field string
		status            int
	}{
		{`not json`, "invalid_json", "", 400},
		{`[1,2]`, "invalid_json", "", 400},
		{`{}`, "validation_failed", "body", 422},
		{`{"body":"   "}`, "validation_failed", "body", 422},
		{`{"body":123}`, "validation_failed", "body", 422},
		{`{"body":"x","type":"Bad Type!"}`, "validation_failed", "type", 422},
		{`{"body":"x","priority":"urgent"}`, "validation_failed", "priority", 422},
		{`{"body":"x","meta":{"bad key":"v"}}`, "validation_failed", "meta.bad key", 422},
		{`{"body":"x","meta":{"k":{"nested":1}}}`, "validation_failed", "meta.k", 422},
		{`{"title":"` + strings.Repeat("t", 300) + `","body":"x"}`, "validation_failed", "title", 422},
		{`{"body":"` + strings.Repeat("长", 12001) + `"}`, "validation_failed", "body", 422},
	}
	for _, c := range cases {
		r := e.post(t, e.iphone, c.body)
		if r.code != c.status || errCode(r) != c.code {
			t.Errorf("%.40s: %d %v", c.body, r.code, r.body)
			continue
		}
		if c.field != "" {
			det := r.body["error"].(map[string]any)["details"].([]any)
			if det[0].(map[string]any)["field"] != c.field {
				t.Errorf("%.40s: details %v", c.body, det)
			}
		}
		if r.body["error"].(map[string]any)["request_id"] == "" {
			t.Error("errors must carry a request id")
		}
	}
	if r := e.do(t, "POST", "/v1/messages", e.iphone, "application/xml", "<x/>"); r.code != 415 {
		t.Errorf("xml: %d", r.code)
	}
	if r := e.post(t, e.iphone, `{"body":"`+strings.Repeat("a", 300<<10)+`"}`); r.code != 413 {
		t.Errorf("huge body: %d", r.code)
	}
	if r := e.do(t, "POST", "/v1/messages?wait=abc", e.iphone, "application/json", `{"body":"x"}`); r.code != 400 {
		t.Errorf("bad wait: %d", r.code)
	}
	if len(e.tg.Calls("sendMessage")) != 0 {
		t.Error("invalid submissions must not be forwarded")
	}
}

func TestWarningsAndUntrustedFields(t *testing.T) {
	e := setup(t)
	r := e.post(t, e.iphone, `{"body":"x","source":"linux","tittle":"typo","occurred_at":"yesterday-ish","meta":{"n":5,"ok":true}}`)
	if r.code != 202 {
		t.Fatalf("%d %v", r.code, r.body)
	}
	w := strings.Join(toStrings(r.body["warnings"]), "|")
	for _, want := range []string{"source", "tittle", "occurred_at"} {
		if !strings.Contains(w, want) {
			t.Errorf("warnings %q lack %s", w, want)
		}
	}
	call := e.tg.Calls("sendMessage")[0].Params
	txt := call["text"].(string)
	if b := buttons(call); !strings.Contains(b, "iPhone") || strings.Contains(b, "Linux") {
		t.Error("source must come from the credential, not the payload")
	}
	if !strings.Contains(txt, "<code>n</code>: 5") || !strings.Contains(txt, "<code>ok</code>: true") {
		t.Errorf("meta not rendered: %s", txt)
	}
}

func toStrings(v any) []string {
	var out []string
	for _, x := range v.([]any) {
		out = append(out, x.(string))
	}
	return out
}

func TestIdempotency(t *testing.T) {
	e := setup(t)
	r1 := e.post(t, e.iphone, `{"body":"a"}`, "Idempotency-Key", "sms-001")
	r2 := e.post(t, e.iphone, `{"body":"a"}`, "Idempotency-Key", "sms-001")
	r3 := e.post(t, e.iphone, `{"body":"a","idempotency_key":"sms-001"}`)
	if r1.code != 202 || r2.code != 200 || r2.body["duplicate"] != true || r2.body["id"] != r1.body["id"] || r3.body["duplicate"] != true {
		t.Fatalf("%v / %v / %v", r1.body, r2.body, r3.body)
	}
	if r2.body["status"] != "delivered" {
		t.Error("duplicate should report the original's status")
	}
	if r := e.post(t, e.iphone, `{"body":"a","idempotency_key":"x"}`, "Idempotency-Key", "y"); r.code != 422 {
		t.Errorf("conflicting keys: %d", r.code)
	}
	// Content dedupe without a key (within the window).
	e.post(t, e.linux, `{"body":"same"}`)
	if r := e.post(t, e.linux, `{"body":"same"}`); r.body["duplicate"] != true {
		t.Error("identical content within the window should be deduplicated")
	}
	if n := len(e.tg.Calls("sendMessage")); n != 2 {
		t.Errorf("forwarded %d times, want 2", n)
	}
}

func TestStatusNotDeliveredWhenTelegramDown(t *testing.T) {
	e := setup(t)
	e.tg.Script("sendMessage", faketg.Response{Status: 502}, faketg.Response{Status: 502})
	r := e.do(t, "POST", "/v1/messages?wait=1", e.iphone, "application/json", `{"body":"x"}`)
	if r.code != 202 || r.body["delivered"] != false || r.body["status"] != "retrying" {
		t.Fatalf("accepted must not be reported as delivered: %v", r.body)
	}
	// Without wait the response is immediate and only says accepted.
	r = e.do(t, "POST", "/v1/messages", e.iphone, "application/json", `{"body":"y"}`)
	if r.body["delivered"] != false || r.body["status"] != "queued" {
		t.Fatalf("%v", r.body)
	}
}

func TestSourceRateLimit(t *testing.T) {
	e := setup(t)
	var limited bool
	for i := 0; i < 10; i++ {
		r := e.do(t, "POST", "/v1/messages", e.iphone, "application/json", `{"body":"n`+string(rune('a'+i))+`"}`)
		if r.code == 429 {
			limited = errCode(r) == "rate_limited" && r.hdr.Get("Retry-After") != ""
			break
		}
	}
	if !limited {
		t.Fatal("expected rate limiting after the burst")
	}
	if r := e.do(t, "POST", "/v1/messages", e.linux, "application/json", `{"body":"ok"}`); r.code != 202 {
		t.Error("one source's limit must not affect another source")
	}
}

func TestAdminAPI(t *testing.T) {
	e := setup(t)
	e.post(t, e.iphone, `{"body":"x"}`)
	if r := e.do(t, "GET", "/v1/admin/status", e.iphone, "", ""); r.code != 401 {
		t.Errorf("source token on admin API: %d", r.code)
	}
	if r := e.do(t, "GET", "/v1/admin/status", "", "", ""); r.code != 401 {
		t.Errorf("no token: %d", r.code)
	}
	r := e.do(t, "GET", "/v1/admin/status", e.adminKey, "", "")
	if r.code != 200 || r.body["report"] == nil || r.body["server"] == nil {
		t.Fatalf("%d %v", r.code, r.body)
	}
	comps := r.body["report"].(map[string]any)["components"].([]any)
	if len(comps) != 6 {
		t.Errorf("components %d", len(comps))
	}
	r = e.do(t, "GET", "/v1/admin/sources", e.adminKey, "", "")
	srcs := r.body["sources"].([]any)
	if len(srcs) != 2 || srcs[0].(map[string]any)["today"].(map[string]any)["received"].(float64) != 1 {
		t.Errorf("sources %v", srcs)
	}
	r = e.do(t, "GET", "/v1/admin/stats?range=7d", e.adminKey, "", "")
	if r.code != 200 || r.body["totals"].(map[string]any)["delivered"].(float64) != 1 {
		t.Errorf("stats %v", r.body)
	}
	if r := e.do(t, "GET", "/v1/admin/stats?range=year", e.adminKey, "", ""); r.code != 400 {
		t.Errorf("bad range %d", r.code)
	}
	if r := e.do(t, "POST", "/v1/admin/status", e.adminKey, "", ""); r.code != 405 {
		t.Errorf("admin API is read-only: %d", r.code)
	}
}

func TestMisc(t *testing.T) {
	e := setup(t)
	r := e.do(t, "GET", "/healthz", "", "", "")
	if r.code != 200 || r.body["status"] != "ok" || len(r.body) != 1 {
		t.Errorf("healthz %d %v", r.code, r.body)
	}
	if r := e.do(t, "GET", "/nope", "", "", ""); r.code != 404 || errCode(r) != "not_found" {
		t.Errorf("404 %v", r.body)
	}
	if r := e.do(t, "GET", "/v1/messages", e.iphone, "", ""); r.code != 405 || r.hdr.Get("Allow") != "POST" {
		t.Errorf("405 %d %v", r.code, r.hdr)
	}
	if r.hdr.Get("Cache-Control") != "no-store" || r.hdr.Get("X-Request-Id") == "" {
		t.Error("missing security headers")
	}
}

func TestLogsContainNoSecrets(t *testing.T) {
	e := setup(t)
	var buf bytes.Buffer
	e.api.Log = slog.New(slog.NewTextHandler(&buf, nil))
	e.post(t, e.iphone, `{"body":"SECRET-OTP-998877"}`)
	e.post(t, "mgs_wrongtoken_zzz", `{"body":"x"}`)
	logs := buf.String()
	if !strings.Contains(logs, "path=/v1/messages") {
		t.Fatalf("expected access logs, got %q", logs)
	}
	for _, secret := range []string{"SECRET-OTP-998877", e.iphone, "mgs_wrongtoken_zzz", "wait=5"} {
		if strings.Contains(logs, secret) {
			t.Errorf("logs leak %q", secret)
		}
	}
}

func TestSMSFilter(t *testing.T) {
	var aiCalls atomic.Int32
	ai := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		aiCalls.Add(1)
		io.Copy(io.Discard, r.Body)
		w.Write([]byte(`{"choices":[{"message":{"content":"{\"category\":\"marketing\"}"}}]}`))
	}))
	t.Cleanup(ai.Close)
	e := setup(t, func(e *env) {
		fl := &filter.Filter{
			Store: e.st, AI: &filter.DeepSeek{BaseURL: ai.URL, APIKey: "sk-test", Model: "deepseek-flash"},
			Drop: map[model.Category]bool{model.CatMarketing: true}, Health: e.tracker, Notifier: e.api.Notifier,
			Delivery: e.worker, Log: slog.New(slog.NewTextHandler(io.Discard, nil)),
		}
		ctx, cancel := context.WithCancel(bg)
		t.Cleanup(cancel)
		go fl.Run(ctx)
		e.api.Filter = fl
		e.api.FilterTypes = map[string]bool{"sms": true}
	})

	// An advertisement is classified and not forwarded.
	r := e.do(t, "POST", "/v1/messages?wait=8", e.iphone, "application/json", `{"title":"某商城","body":"全场5折，回T退订","type":"sms"}`)
	if r.code != 202 || r.body["status"] != "filtered" || r.body["delivered"] != false || r.body["message"] != "已过滤（不需要推送的短信），未转发" {
		t.Fatalf("%d %v", r.code, r.body)
	}
	g := e.do(t, "GET", "/v1/messages/"+r.body["id"].(string), e.iphone, "", "")
	if g.body["category"] != "marketing" || g.body["category_text"] != "广告营销" {
		t.Fatalf("category: %v", g.body)
	}
	if n := len(e.tg.Calls("sendMessage")); n != 0 {
		t.Fatalf("filtered message was sent to Telegram (%d calls)", n)
	}

	// A verification code is decided locally and forwarded with its category.
	r = e.do(t, "POST", "/v1/messages?wait=8", e.iphone, "application/json", `{"title":"银行","body":"验证码 123456","type":"sms"}`)
	if r.body["status"] != "delivered" || aiCalls.Load() != 1 {
		t.Fatalf("code sms: %v, ai calls %d", r.body, aiCalls.Load())
	}
	if b := buttons(e.tg.Calls("sendMessage")[0].Params); !strings.Contains(b, "💬 短信 · 验证码=") {
		t.Errorf("category missing from notification buttons: %s", b)
	}

	// Other types are not classified at all.
	r = e.do(t, "POST", "/v1/messages?wait=8", e.iphone, "application/json", `{"body":"全场5折","type":"notification"}`)
	if r.body["status"] != "delivered" || aiCalls.Load() != 1 {
		t.Fatalf("notification: %v, ai calls %d", r.body, aiCalls.Load())
	}
}

// buttons flattens a sendMessage call's inline keyboard to "text=data" pairs.
func buttons(p map[string]any) string {
	kb, _ := p["reply_markup"].(map[string]any)
	if kb == nil {
		return ""
	}
	var out []string
	for _, row := range kb["inline_keyboard"].([]any) {
		for _, b := range row.([]any) {
			m := b.(map[string]any)
			out = append(out, m["text"].(string)+"="+m["callback_data"].(string))
		}
	}
	return strings.Join(out, " ")
}
