package filter

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"msggw/internal/health"
	"msggw/internal/model"
	"msggw/internal/store"
)

var bg = context.Background()

func TestIsCode(t *testing.T) {
	for _, s := range []string{
		"【招商银行】您的验证码为 123456，5 分钟内有效",
		"【菜鸟驿站】取件码 3-2-1056，请尽快取件",
		"【Amazon】認証コード：482913",
		"Your verification code is 1234",
		"G-482913 is your Google verification code.",
		"Your OTP is 9988",
	} {
		if !IsCode(s) {
			t.Errorf("not recognised as code: %q", s)
		}
	}
	for _, s := range []string{
		"【某商城】双11全场5折，回T退订",
		"晚上一起吃饭吗",
		"Use promo code SAVE20 for 20% off",
		"【国网】您的电费余额不足 10 元",
	} {
		if IsCode(s) {
			t.Errorf("wrongly recognised as code: %q", s)
		}
	}
}

func TestMask(t *testing.T) {
	got := Mask("尾号6789的卡于10月25日消费1,234.56元，详情 https://bank.example.com/a?b=1 或 t.cn/AbCd 咨询95588")
	for _, leak := range []string{"6789", "234", "95588", "bank.example", "t.cn", "AbCd"} {
		if strings.Contains(got, leak) {
			t.Errorf("mask leaked %q: %s", leak, got)
		}
	}
	for _, keep := range []string{"10月25日", "[链接]", "尾号#的卡"} {
		if !strings.Contains(got, keep) {
			t.Errorf("mask removed %q: %s", keep, got)
		}
	}
	if Mask("全角１２３４") != "全角#" {
		t.Errorf("full-width digits not masked: %s", Mask("全角１２３４"))
	}
}

func TestParseAnswer(t *testing.T) {
	cases := map[string]model.Category{
		`{"category":"marketing"}`:                  model.CatMarketing,
		"```json\n{\"category\": \"Finance\"}\n```": model.CatFinance,
		`结果：{"category":"spam"}`:                    model.CatSpam,
	}
	for in, want := range cases {
		if got, err := parseAnswer(in); err != nil || got != want {
			t.Errorf("parseAnswer(%q) = %v, %v", in, got, err)
		}
	}
	for _, in := range []string{`{"category":"ads"}`, "marketing", `{"cat":"spam"}`, ""} {
		if _, err := parseAnswer(in); !errors.Is(err, ErrBadAnswer) {
			t.Errorf("parseAnswer(%q) should fail, got %v", in, err)
		}
	}
}

// fakeDeepSeek is a minimal chat-completions endpoint.
type fakeDeepSeek struct {
	*httptest.Server
	mu      sync.Mutex
	reqs    []map[string]any
	auth    []string
	status  int
	content string
	delay   time.Duration
}

func newFakeDeepSeek(t *testing.T) *fakeDeepSeek {
	f := &fakeDeepSeek{status: 200, content: `{"category":"marketing"}`}
	f.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		f.mu.Lock()
		f.reqs = append(f.reqs, body)
		f.auth = append(f.auth, r.Header.Get("Authorization"))
		status, content, delay := f.status, f.content, f.delay
		f.mu.Unlock()
		if r.URL.Path != "/chat/completions" {
			http.NotFound(w, r)
			return
		}
		if delay > 0 {
			select {
			case <-time.After(delay):
			case <-r.Context().Done():
				return
			}
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		if status != 200 {
			w.Write([]byte(`{"error":{"message":"some error"}}`))
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": map[string]any{"content": content}}}})
	}))
	t.Cleanup(f.Close)
	return f
}

func (f *fakeDeepSeek) set(status int, content string) {
	f.mu.Lock()
	f.status, f.content = status, content
	f.mu.Unlock()
}

func (f *fakeDeepSeek) calls() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.reqs)
}

func (f *fakeDeepSeek) lastUser() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	msgs := f.reqs[len(f.reqs)-1]["messages"].([]any)
	return msgs[len(msgs)-1].(map[string]any)["content"].(string)
}

func TestDeepSeekRequestAndErrors(t *testing.T) {
	f := newFakeDeepSeek(t)
	ds := &DeepSeek{BaseURL: f.URL + "/", APIKey: "sk-test", Model: "deepseek-flash"}
	cat, err := ds.Classify(bg, "106", "全场5折")
	if err != nil || cat != model.CatMarketing {
		t.Fatalf("classify: %v %v", cat, err)
	}
	req := f.reqs[0]
	if f.auth[0] != "Bearer sk-test" || req["model"] != "deepseek-flash" || req["temperature"].(float64) != 0 ||
		req["response_format"].(map[string]any)["type"] != "json_object" ||
		req["thinking"].(map[string]any)["type"] != "disabled" {
		t.Fatalf("unexpected request: %v %v", f.auth[0], req)
	}
	if u := f.lastUser(); !strings.Contains(u, "发送方：106") || !strings.Contains(u, "全场5折") {
		t.Fatalf("user prompt: %q", u)
	}

	for status, want := range map[int]string{401: "API Key 无效", 402: "余额不足", 429: "频繁", 503: "服务端错误"} {
		f.set(status, "")
		if _, err := ds.Classify(bg, "", "x"); err == nil || !strings.Contains(err.Error(), want) {
			t.Errorf("status %d: got %v", status, err)
		}
	}
	f.set(200, `{"category":"weather"}`)
	if _, err := ds.Classify(bg, "", "x"); !errors.Is(err, ErrBadAnswer) {
		t.Errorf("unknown category: %v", err)
	}

	f.mu.Lock()
	f.status, f.delay = 200, time.Second
	f.mu.Unlock()
	ctx, cancel := context.WithTimeout(bg, 50*time.Millisecond)
	defer cancel()
	if _, err := ds.Classify(ctx, "", "x"); err == nil || !strings.Contains(err.Error(), "超时") {
		t.Errorf("timeout: %v", err)
	}
	down := &DeepSeek{BaseURL: "http://127.0.0.1:1", APIKey: "sk-secret"}
	if _, err := down.Classify(bg, "", "x"); err == nil || strings.Contains(err.Error(), "sk-secret") {
		t.Errorf("unreachable: %v", err)
	}
}

type fixture struct {
	st      *store.Store
	f       *Filter
	ds      *fakeDeepSeek
	tracker *health.Tracker
	woken   *counter
	now     time.Time
}

type counter struct {
	mu sync.Mutex
	n  int
}

func (c *counter) Wake() { c.mu.Lock(); c.n++; c.mu.Unlock() }
func (c *counter) get() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.n
}

func setup(t *testing.T) *fixture {
	st, err := store.Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	fx := &fixture{st: st, ds: newFakeDeepSeek(t), tracker: health.NewTracker(time.Now()), woken: &counter{}, now: time.Now()}
	fx.f = &Filter{
		Store:    st,
		AI:       &DeepSeek{BaseURL: fx.ds.URL, APIKey: "sk-test", Model: "deepseek-flash"},
		Drop:     map[model.Category]bool{model.CatMarketing: true, model.CatSpam: true},
		Health:   fx.tracker,
		Delivery: fx.woken,
		Log:      slog.New(slog.NewTextHandler(io.Discard, nil)),
		Now:      func() time.Time { return fx.now },
		Timeout:  2 * time.Second,
	}
	return fx
}

func (fx *fixture) hold(t *testing.T, title, body string) string {
	t.Helper()
	m := &model.Message{ID: model.NewMessageID(fx.now), SourceID: "iphone", Kind: "sms", Priority: model.PriorityNormal,
		Title: title, Body: body, ContentHash: "h:" + body, ReceivedAt: fx.now}
	res, err := fx.st.InsertMessage(bg, m, []store.NewDelivery{{Channel: "telegram", Target: "default", Held: true}}, 0)
	if err != nil || res.Status != model.StatusClassifying {
		t.Fatalf("insert: %v %v", res.Status, err)
	}
	return m.ID
}

func (fx *fixture) info(t *testing.T, id string) store.MessageInfo {
	t.Helper()
	mi, err := fx.st.GetMessage(bg, id)
	if err != nil {
		t.Fatal(err)
	}
	return mi
}

func TestHeldMessagesAreNotDeliveredBeforeClassification(t *testing.T) {
	fx := setup(t)
	fx.hold(t, "10086", "hello")
	jobs, err := fx.st.DueJobs(bg, fx.now.Add(time.Hour), 10)
	if err != nil || len(jobs) != 0 {
		t.Fatalf("held message must not be due: %d %v", len(jobs), err)
	}
}

func TestFilterVerdicts(t *testing.T) {
	fx := setup(t)
	code := fx.hold(t, "招商银行", "您的验证码为 482913，5 分钟内有效")
	ad := fx.hold(t, "某商城", "双11全场5折起，满300减50，回T退订")
	if _, err := fx.f.RunOnce(bg); err != nil {
		t.Fatal(err)
	}
	// The code was decided locally: DeepSeek saw only the advertisement.
	if fx.ds.calls() != 1 {
		t.Fatalf("DeepSeek calls = %d, want 1", fx.ds.calls())
	}
	if u := fx.ds.lastUser(); strings.Contains(u, "482913") || strings.Contains(u, "300") || !strings.Contains(u, "满#减") {
		t.Fatalf("prompt not masked or wrong message sent: %q", u)
	}
	mi := fx.info(t, code)
	if mi.Message.Status != model.StatusQueued || mi.Message.Category != model.CatCode {
		t.Fatalf("code message: %v %v", mi.Message.Status, mi.Message.Category)
	}
	mi = fx.info(t, ad)
	if mi.Message.Status != model.StatusFiltered || mi.Message.Category != model.CatMarketing || mi.Deliveries[0].State != model.DeliveryFiltered {
		t.Fatalf("ad: %v %v %v", mi.Message.Status, mi.Message.Category, mi.Deliveries[0].State)
	}
	if fx.woken.get() != 1 {
		t.Fatalf("delivery woken %d times, want 1 (only for the forwarded message)", fx.woken.get())
	}
	// The filtered message stays rescuable with its content.
	list, total, err := fx.st.FilteredMessages(bg, 0, 10)
	if err != nil || total != 1 || list[0].ID != ad || !strings.Contains(list[0].Body, "满300减50") {
		t.Fatalf("filtered list: %v %d %v", list, total, err)
	}
	if st := fx.tracker.Snapshot(); st.FilterOK.IsZero() {
		t.Fatal("filter success not tracked")
	}

	fx.ds.set(200, `{"category":"finance"}`)
	bill := fx.hold(t, "95588", "您尾号1234的卡消费100元")
	fx.f.RunOnce(bg)
	if mi := fx.info(t, bill); mi.Message.Status != model.StatusQueued || mi.Message.Category != model.CatFinance {
		t.Fatalf("bill: %v %v", mi.Message.Status, mi.Message.Category)
	}
}

func TestFilterFailsOpenAndPauses(t *testing.T) {
	fx := setup(t)
	fx.ds.set(500, "")
	var ids []string
	for i := 0; i < 4; i++ {
		ids = append(ids, fx.hold(t, "x", "广告 "+string(rune('a'+i))))
	}
	if _, err := fx.f.RunOnce(bg); err != nil {
		t.Fatal(err)
	}
	for _, id := range ids {
		mi := fx.info(t, id)
		if mi.Message.Status != model.StatusQueued || mi.Message.Category != "" {
			t.Fatalf("failure must forward unclassified: %v %v", mi.Message.Status, mi.Message.Category)
		}
	}
	// After 3 consecutive failures the 4th message is not sent to DeepSeek.
	if fx.ds.calls() != 3 {
		t.Fatalf("DeepSeek calls = %d, want 3", fx.ds.calls())
	}
	st := fx.tracker.Snapshot()
	if st.FilterFail.IsZero() || !strings.Contains(st.FilterFailMsg, "服务端错误") {
		t.Fatalf("failure not tracked: %+v", st)
	}
	// Once the pause is over DeepSeek is used again.
	fx.ds.set(200, `{"category":"spam"}`)
	fx.now = fx.now.Add(6 * time.Minute)
	id := fx.hold(t, "x", "中奖了")
	fx.f.RunOnce(bg)
	if mi := fx.info(t, id); mi.Message.Status != model.StatusFiltered {
		t.Fatalf("after pause: %v", mi.Message.Status)
	}
}

func TestFilterTimeoutForwards(t *testing.T) {
	fx := setup(t)
	fx.f.Timeout = 50 * time.Millisecond
	fx.ds.mu.Lock()
	fx.ds.delay = time.Second
	fx.ds.mu.Unlock()
	id := fx.hold(t, "x", "hello")
	fx.f.RunOnce(bg)
	if mi := fx.info(t, id); mi.Message.Status != model.StatusQueued {
		t.Fatalf("timeout must forward: %v", mi.Message.Status)
	}
}

func TestFilterShutdownLeavesMessageHeld(t *testing.T) {
	fx := setup(t)
	fx.ds.mu.Lock()
	fx.ds.delay = time.Second
	fx.ds.mu.Unlock()
	id := fx.hold(t, "x", "hello")
	ctx, cancel := context.WithCancel(bg)
	go func() { time.Sleep(50 * time.Millisecond); cancel() }()
	fx.f.RunOnce(ctx)
	if mi := fx.info(t, id); mi.Message.Status != model.StatusClassifying {
		t.Fatalf("interrupted classification must stay held for the next start: %v", mi.Message.Status)
	}
}
