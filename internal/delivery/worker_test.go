package delivery

import (
	"context"
	"io"
	"log/slog"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"nekopost/internal/health"
	"nekopost/internal/model"
	"nekopost/internal/store"
	"nekopost/internal/telegram"
	"nekopost/internal/testutil/faketg"
)

type env struct {
	st      *store.Store
	tg      *faketg.Server
	w       *Worker
	tracker *health.Tracker
	now     time.Time
}

var ctx = context.Background()

func setup(t *testing.T, apiBase string) *env {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	e := &env{st: st, tg: faketg.New(t), now: time.Now()}
	if apiBase == "" {
		apiBase = e.tg.URL
	}
	e.tracker = health.NewTracker(e.now)
	e.w = &Worker{
		Store: st,
		Channels: map[string]Channel{"telegram": &TelegramChannel{
			Client: telegram.New(apiBase, faketg.Token), Targets: map[string]int64{"default": 42},
			Location: time.UTC,
		}},
		Health:   e.tracker,
		Notifier: NewNotifier(),
		Log:      slog.New(slog.NewTextHandler(io.Discard, nil)),
		MaxAge:   time.Hour,
		Backoff:  []time.Duration{10 * time.Second},
		Now:      func() time.Time { return e.now },
	}
	return e
}

func (e *env) submit(t *testing.T, body string, prio model.Priority) string {
	t.Helper()
	m := &model.Message{ID: model.NewMessageID(e.now), SourceID: "iphone", Kind: "sms", Priority: prio,
		Title: "Bank", Body: body, ContentHash: body, ReceivedAt: e.now}
	_, err := e.st.InsertMessage(ctx, m, []store.NewDelivery{{Channel: "telegram", Target: "default", Silent: prio == model.PriorityLow}}, 0)
	if err != nil {
		t.Fatal(err)
	}
	return m.ID
}

func (e *env) delivery(t *testing.T, id string) (model.Status, model.Delivery) {
	t.Helper()
	mi, err := e.st.GetMessage(ctx, id)
	if err != nil {
		t.Fatal(err)
	}
	return mi.Message.Status, mi.Deliveries[0]
}

func TestDeliverSuccess(t *testing.T) {
	e := setup(t, "")
	id := e.submit(t, "验证码 123456\n二行目 😀", model.PriorityLow)
	if n, err := e.w.RunOnce(ctx); err != nil || n != 1 {
		t.Fatalf("%d %v", n, err)
	}
	st, d := e.delivery(t, id)
	if st != model.StatusDelivered || d.Attempts != 1 || d.Uncertain || d.PartsSent != 1 {
		t.Fatalf("%s %+v", st, d)
	}
	calls := e.tg.Calls("sendMessage")
	if len(calls) != 1 {
		t.Fatalf("calls %d", len(calls))
	}
	p := calls[0].Params
	if p["chat_id"].(float64) != 42 || p["parse_mode"] != "HTML" || p["disable_notification"] != true ||
		!strings.Contains(p["text"].(string), "验证码 123456\n二行目 😀") {
		t.Errorf("params %v", p)
	}
	if e.tracker.Snapshot().SendOK.IsZero() {
		t.Error("health should record success")
	}
}

func TestRateLimitedThenDelivered(t *testing.T) {
	e := setup(t, "")
	e.tg.Script("sendMessage", faketg.Response{Status: 429, Description: "Too Many Requests", RetryAfter: 30})
	id := e.submit(t, "x", model.PriorityNormal)
	e.w.RunOnce(ctx)
	st, d := e.delivery(t, id)
	if st != model.StatusRetrying || d.Uncertain || d.NextAttemptAt.Sub(e.now) < 30*time.Second {
		t.Fatalf("%s %+v", st, d)
	}
	e.w.RunOnce(ctx)
	if len(e.tg.Calls("sendMessage")) != 1 {
		t.Fatal("must not retry before retry_after")
	}
	e.now = e.now.Add(32 * time.Second)
	e.w.RunOnce(ctx)
	if st, _ := e.delivery(t, id); st != model.StatusDelivered {
		t.Fatalf("status %s", st)
	}
}

func TestServerErrorIsUncertainThenDelivered(t *testing.T) {
	e := setup(t, "")
	e.tg.Script("sendMessage", faketg.Response{Status: 502, Description: "Bad Gateway"})
	id := e.submit(t, "x", model.PriorityNormal)
	e.w.RunOnce(ctx)
	st, d := e.delivery(t, id)
	if st != model.StatusRetrying || !d.Uncertain {
		t.Fatalf("5xx must be uncertain and retried: %s %+v", st, d)
	}
	e.now = e.now.Add(time.Minute)
	e.w.RunOnce(ctx)
	st, d = e.delivery(t, id)
	if st != model.StatusDelivered || !d.Uncertain || d.Attempts != 2 {
		t.Fatalf("%s %+v", st, d)
	}
}

func TestConnectionDroppedAfterSendIsUncertain(t *testing.T) {
	e := setup(t, "")
	e.tg.Script("sendMessage", faketg.Response{Hijack: true})
	id := e.submit(t, "x", model.PriorityNormal)
	e.w.RunOnce(ctx)
	st, d := e.delivery(t, id)
	if st != model.StatusRetrying || !d.Uncertain {
		t.Fatalf("%s %+v", st, d)
	}
	if e.tracker.Snapshot().SendFailKind != health.FailNetwork {
		t.Error("should be a network failure")
	}
}

func TestUnreachableIsSafeRetry(t *testing.T) {
	e := setup(t, "http://127.0.0.1:1")
	id := e.submit(t, "x", model.PriorityNormal)
	e.w.RunOnce(ctx)
	st, d := e.delivery(t, id)
	if st != model.StatusRetrying || d.Uncertain || !strings.Contains(d.LastError, "无法连接") {
		t.Fatalf("%s %+v", st, d)
	}
	if strings.Contains(d.LastError, faketg.Token) {
		t.Fatal("error message leaks the bot token")
	}
	// Two consecutive network failures: delivery reported unreachable.
	e.now = e.now.Add(time.Minute)
	e.w.RunOnce(ctx)
	rep := health.Evaluate(health.Inputs{Now: e.now, State: e.tracker.Snapshot()})
	if c := rep.Component("delivery"); c.Level != health.Down || !strings.Contains(c.Summary, "不可达") {
		t.Errorf("component %+v", c)
	}
}

func TestPermanentRejection(t *testing.T) {
	e := setup(t, "")
	e.tg.Script("sendMessage", faketg.Response{Status: 400, Description: "Bad Request: message text is empty"})
	id := e.submit(t, "x", model.PriorityNormal)
	e.w.RunOnce(ctx)
	if st, d := e.delivery(t, id); st != model.StatusFailed || !strings.Contains(d.LastError, "拒绝") {
		t.Fatalf("%s %+v", st, d)
	}
}

func TestParseErrorFallsBackToPlain(t *testing.T) {
	e := setup(t, "")
	e.tg.Script("sendMessage", faketg.Response{Status: 400, Description: "Bad Request: can't parse entities: x"})
	id := e.submit(t, "x", model.PriorityNormal)
	e.w.RunOnce(ctx)
	if st, _ := e.delivery(t, id); st != model.StatusDelivered {
		t.Fatalf("status %s", st)
	}
	calls := e.tg.Calls("sendMessage")
	if len(calls) != 2 || calls[1].Params["parse_mode"] != nil {
		t.Errorf("fallback should resend without parse_mode: %v", calls)
	}
}

func TestConfigErrorKeepsRetrying(t *testing.T) {
	e := setup(t, "")
	e.tg.Script("sendMessage", faketg.Response{Status: 403, Description: "Forbidden: bot was blocked by the user"})
	id := e.submit(t, "x", model.PriorityNormal)
	e.w.RunOnce(ctx)
	if st, _ := e.delivery(t, id); st != model.StatusRetrying {
		t.Fatalf("config errors are fixable, must retry: %s", st)
	}
	rep := health.Evaluate(health.Inputs{Now: e.now, State: e.tracker.Snapshot()})
	if rep.Component("delivery").Level != health.ConfigError {
		t.Errorf("want config error, got %+v", rep.Component("delivery"))
	}
}

func TestMultipartResumesAfterFailure(t *testing.T) {
	e := setup(t, "")
	e.w.Channels["telegram"].(*TelegramChannel).MinInterval = 0
	body := strings.Repeat("长文本测试。", 1500) // ~9000 chars -> 3 parts
	e.tg.Script("sendMessage", faketg.Response{}, faketg.Response{Status: 429, Description: "Too Many Requests", RetryAfter: 1})
	id := e.submit(t, body, model.PriorityNormal)
	e.w.RunOnce(ctx)
	st, d := e.delivery(t, id)
	if st != model.StatusRetrying || d.PartsSent != 1 || d.PartsTotal < 3 {
		t.Fatalf("%s %+v", st, d)
	}
	e.now = e.now.Add(time.Minute)
	e.w.RunOnce(ctx)
	st, d = e.delivery(t, id)
	if st != model.StatusDelivered || d.PartsSent != d.PartsTotal {
		t.Fatalf("%s %+v", st, d)
	}
	calls := e.tg.Calls("sendMessage")
	if len(calls) != d.PartsTotal+1 {
		t.Errorf("part 1 must not be resent: %d calls for %d parts", len(calls), d.PartsTotal)
	}
	if !strings.Contains(lastButton(calls[0].Params), "(1/") || !strings.Contains(lastButton(calls[2].Params), "(2/") {
		t.Error("parts out of order")
	}
}

func TestGivesUpAfterMaxAge(t *testing.T) {
	e := setup(t, "")
	e.tg.Script("sendMessage", faketg.Response{Status: 502}, faketg.Response{Status: 502})
	id := e.submit(t, "x", model.PriorityNormal)
	e.w.RunOnce(ctx)
	e.now = e.now.Add(2 * time.Hour)
	e.w.RunOnce(ctx)
	st, d := e.delivery(t, id)
	if st != model.StatusUncertain || !strings.Contains(d.LastError, "最长重试时间") {
		t.Fatalf("an earlier uncertain attempt must end as uncertain, not failed: %s %+v", st, d)
	}
	if len(e.tg.Calls("sendMessage")) != 1 {
		t.Error("expired delivery must not be attempted again")
	}
}

func TestWakeAndNotifier(t *testing.T) {
	e := setup(t, "")
	e.w.Now = time.Now
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	go e.w.Run(runCtx)
	id := e.submit(t, "x", model.PriorityNormal)
	ch, unsub := e.w.Notifier.Subscribe(id)
	defer unsub()
	e.w.Wake()
	deadline := time.After(5 * time.Second)
	for {
		select {
		case <-ch:
			if st, _ := e.st.MessageStatus(ctx, id); st == model.StatusDelivered {
				return
			}
		case <-deadline:
			t.Fatal("not delivered after wake")
		}
	}
}

// lastButton returns the text of the last inline button of a sendMessage call.
func lastButton(p map[string]any) string {
	kb, _ := p["reply_markup"].(map[string]any)
	if kb == nil {
		return ""
	}
	row := kb["inline_keyboard"].([]any)[0].([]any)
	return row[len(row)-1].(map[string]any)["text"].(string)
}
