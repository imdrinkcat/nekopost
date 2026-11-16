package delivery

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/oauth2"

	"nekopost/internal/health"
	"nekopost/internal/model"
	"nekopost/internal/store"
)

// fakeFCM is a channel named "fcm" that returns scripted results.
type fakeFCM struct {
	mu      sync.Mutex
	results []Result
	calls   int
	jobs    []store.Job
}

func (f *fakeFCM) Name() string { return "fcm" }

func (f *fakeFCM) Deliver(_ context.Context, job store.Job, progress func(int, int, int64)) Result {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	f.jobs = append(f.jobs, job)
	r := Result{Outcome: Delivered, PartsTotal: 1, PartsSent: 1}
	if len(f.results) > 0 {
		r = f.results[0]
		f.results = f.results[1:]
	}
	if r.Outcome == Delivered {
		progress(1, 1, 0)
	}
	return r
}

func (e *env) submitApp(t *testing.T) string {
	t.Helper()
	m := &model.Message{ID: model.NewMessageID(e.now), SourceID: "iphone", Kind: "sms", Priority: model.PriorityNormal,
		Title: "Bank", Body: "app only", ContentHash: "app", ReceivedAt: e.now}
	if _, err := e.st.InsertMessage(ctx, m, []store.NewDelivery{{Channel: "fcm", Target: "d_fixture"}}, 0); err != nil {
		t.Fatal(err)
	}
	return m.ID
}

func TestPrimaryFCMDoesNotTouchTelegramHealth(t *testing.T) {
	e := setup(t, "")
	f := &fakeFCM{results: []Result{{Outcome: RetrySafe, FailKind: health.FailNetwork, Err: "FCM 网络请求失败"}}}
	e.w.Channels["fcm"] = f
	id := e.submitApp(t)
	if _, err := e.w.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	if st := e.tracker.Snapshot(); !st.SendFail.IsZero() || !st.SendOK.IsZero() {
		t.Fatal("App push failures must not count as Telegram failures", st.SendFail, st.SendFailMsg)
	}
	if st, _ := e.delivery(t, id); st != model.StatusRetrying {
		t.Fatal(st)
	}
	// The job carries no payload: the device fetches it.
	if j := f.jobs[0]; j.Message.Body != "" || j.PayloadMissing {
		t.Fatal(j.Message.Body, j.PayloadMissing)
	}
	e.now = e.now.Add(time.Minute)
	if _, err := e.w.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	if st, d := e.delivery(t, id); st != model.StatusDelivered || d.State != model.DeliveryDelivered {
		t.Fatal(st, d.State)
	}
	if st := e.tracker.Snapshot(); !st.SendOK.IsZero() {
		t.Fatal("App push successes must not count as Telegram successes")
	}
}

func TestPrimaryFCMMaxAge(t *testing.T) {
	e := setup(t, "")
	e.w.MaxAge = 48 * time.Hour
	f := &fakeFCM{}
	for range 10 {
		f.results = append(f.results, Result{Outcome: RetrySafe, Err: "FCM 暂时不可用或限流"})
	}
	e.w.Channels["fcm"] = f
	id := e.submitApp(t)
	if _, err := e.w.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	// Past the 1 h auxiliary limit it keeps retrying...
	e.now = e.now.Add(2 * time.Hour)
	if _, err := e.w.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	if st, d := e.delivery(t, id); st != model.StatusRetrying || d.Attempts != 2 {
		t.Fatal(st, d.Attempts)
	}
	// ...and gives up at FCM's own 24 h limit, not the 48 h MaxAge.
	e.now = e.now.Add(23 * time.Hour)
	if _, err := e.w.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	if st, d := e.delivery(t, id); st != model.StatusFailed || !strings.Contains(d.LastError, "超过最长重试时间") {
		t.Fatal(st, d.LastError)
	}
}

func TestSkippedNoRetry(t *testing.T) {
	e := setup(t, "")
	f := &fakeFCM{results: []Result{{Outcome: Skipped, Err: store.SkipCategory}}}
	e.w.Channels["fcm"] = f
	id := e.submitApp(t)
	if _, err := e.w.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	st, d := e.delivery(t, id)
	if st != model.StatusNotRouted || d.State != model.DeliverySkipped || d.LastError != store.SkipCategory {
		t.Fatal(st, d.State, d.LastError)
	}
	e.now = e.now.Add(time.Hour)
	if n, _ := e.w.RunOnce(ctx); n != 0 || f.calls != 1 {
		t.Fatal("skipped deliveries are final", n, f.calls)
	}
	if st := e.tracker.Snapshot(); !st.SendFail.IsZero() {
		t.Fatal("skip is not a failure")
	}
}

// fcmFixture is an FCM channel against a local server and a store with
// push enabled.
func fcmFixture(t *testing.T, handler http.HandlerFunc) (*FCM, *store.Store) {
	t.Helper()
	opts := store.TestingOptions()
	opts.EnablePush = true
	st, err := store.Open(filepath.Join(t.TempDir(), "t.db"), opts)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	return &FCM{Store: st, endpoint: srv.URL, http: srv.Client(),
		tokens: oauth2.StaticTokenSource(&oauth2.Token{AccessToken: "fixture"}), Now: time.Now}, st
}

func TestFCMSkippedOutcome(t *testing.T) {
	var requests int
	f, st := fcmFixture(t, func(w http.ResponseWriter, r *http.Request) { requests++ })
	now := time.Now()
	if err := st.CreateTestDevice(ctx, "d_sms_only", "msg:read", `{"types":["sms"]}`, "fixture_registration_token", now); err != nil {
		t.Fatal(err)
	}
	job := store.Job{Delivery: model.Delivery{Target: "d_sms_only"}, Message: model.Message{ID: "m_fixture", Kind: "notification", Priority: model.PriorityNormal}}
	res := f.Deliver(ctx, job, func(int, int, int64) {})
	if res.Outcome != Skipped || res.Err != store.SkipType || requests != 0 {
		t.Fatal(res, requests)
	}
	job.Delivery.Target = "d_unknown"
	if res := f.Deliver(ctx, job, func(int, int, int64) {}); res.Outcome != Skipped || res.Err != store.PushSkipped {
		t.Fatal(res)
	}
}

func TestFCMSignal(t *testing.T) {
	var mu sync.Mutex
	var got []map[string]any
	f, st := fcmFixture(t, func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Message map[string]any `json:"message"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		mu.Lock()
		got = append(got, body.Message)
		mu.Unlock()
	})
	now := time.Now()
	for _, id := range []string{"d_actor", "d_other"} {
		if err := st.CreateTestDevice(ctx, id, "msg:read", "{}", "fixture_registration_"+id, now); err != nil {
			t.Fatal(err)
		}
	}
	f.Signal(ctx, SignalRead, []string{"m_A", "m_B"}, "d_actor")
	if len(got) != 1 {
		t.Fatal(got)
	}
	data, _ := got[0]["data"].(map[string]any)
	android, _ := got[0]["android"].(map[string]any)
	if got[0]["token"] != "fixture_registration_d_other" || data["t"] != "read" || data["ids"] != "m_A,m_B" ||
		data["v"] != "1" || android["priority"] != "NORMAL" || got[0]["notification"] != nil {
		t.Fatal(got[0])
	}
}
