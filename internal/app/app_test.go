package app

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"msggw/internal/auth"
	"msggw/internal/config"
	"msggw/internal/model"
	"msggw/internal/store"
	"msggw/internal/testutil/faketg"
)

func freeAddr(t *testing.T) string {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().String()
}

func waitHealthy(t *testing.T, base string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if r, err := http.Get(base + "/healthz"); err == nil {
			r.Body.Close()
			if r.StatusCode == 200 {
				return
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("service did not become healthy")
}

// TestServiceLifecycle runs the whole service (TLS off), submits a message,
// restarts it and checks that state persisted.
func TestServiceLifecycle(t *testing.T) {
	tg := faketg.New(t)
	dir := t.TempDir()
	addr := freeAddr(t)
	cfg, err := config.Load(func(k string) string {
		return map[string]string{
			"MSGGW_TLS_MODE":           "off",
			"MSGGW_HTTP_ADDR":          addr,
			"MSGGW_DATA_DIR":           dir,
			"MSGGW_TELEGRAM_BOT_TOKEN": faketg.Token,
			"MSGGW_TELEGRAM_ADMIN_IDS": "1001",
			"MSGGW_TELEGRAM_API_BASE":  tg.URL,
		}[k]
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := cfg.ValidateServe(); err != nil {
		t.Fatal(err)
	}
	tok := auth.NewToken(auth.SourcePrefix)
	st, err := store.Open(cfg.DBPath())
	if err != nil {
		t.Fatal(err)
	}
	st.CreateSource(context.Background(), model.Source{ID: "iphone", Name: "iPhone", Enabled: true}, auth.Hash(tok), "p", time.Now())
	st.Close()

	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	start := func() (context.CancelFunc, chan error) {
		ctx, cancel := context.WithCancel(context.Background())
		done := make(chan error, 1)
		go func() { done <- Run(ctx, cfg, "test", log) }()
		waitHealthy(t, "http://"+addr)
		return cancel, done
	}

	cancel, done := start()
	req, _ := http.NewRequest("POST", "http://"+addr+"/v1/messages?wait=5", strings.NewReader(`{"body":"hello 世界"}`))
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	var body map[string]any
	json.NewDecoder(resp.Body).Decode(&body)
	resp.Body.Close()
	if resp.StatusCode != 202 || body["status"] != "delivered" {
		t.Fatalf("%d %v", resp.StatusCode, body)
	}
	tg.WaitCalls(t, "getUpdates", 1, 5*time.Second)
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(20 * time.Second):
		t.Fatal("shutdown hung")
	}

	if fi, err := os.Stat(cfg.DBPath()); err != nil || fi.Mode().Perm() != 0o600 {
		t.Errorf("database should be private: %v %v", fi.Mode(), err)
	}

	// Restart: previous state survives.
	cancel, done = start()
	defer func() { cancel(); <-done }()
	st2, _ := store.Open(filepath.Join(dir, "msggw.db"))
	defer st2.Close()
	stats, _ := st2.Stats(context.Background(), time.Now().Add(-time.Hour), time.Now().Add(time.Hour))
	if stats.Total.Delivered != 1 {
		t.Errorf("stats after restart %+v", stats.Total)
	}
	v, _, _ := st2.GetKV(context.Background(), "start_count")
	if v != "2" {
		t.Errorf("start count %q", v)
	}
}

// TestRecoveryAfterCrash simulates a crash in the middle of a delivery.
func TestRecoveryAfterCrash(t *testing.T) {
	tg := faketg.New(t)
	dir := t.TempDir()
	addr := freeAddr(t)
	cfg, _ := config.Load(func(k string) string {
		return map[string]string{"MSGGW_TLS_MODE": "off", "MSGGW_HTTP_ADDR": addr, "MSGGW_DATA_DIR": dir,
			"MSGGW_TELEGRAM_BOT_TOKEN": faketg.Token, "MSGGW_TELEGRAM_ADMIN_IDS": "1001", "MSGGW_TELEGRAM_API_BASE": tg.URL}[k]
	})
	st, _ := store.Open(cfg.DBPath())
	now := time.Now()
	m := &model.Message{ID: model.NewMessageID(now), SourceID: "iphone", Kind: "text", Priority: model.PriorityNormal,
		Body: "in flight", ContentHash: "h", ReceivedAt: now}
	st.InsertMessage(context.Background(), m, []store.NewDelivery{{Channel: "telegram", Target: "default"}}, 0)
	jobs, _ := st.DueJobs(context.Background(), now, 10)
	st.ClaimDelivery(context.Background(), jobs[0].Delivery.ID, m.ID, now) // "crash" here
	st.Close()

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- Run(ctx, cfg, "test", slog.New(slog.NewTextHandler(io.Discard, nil))) }()
	defer func() { cancel(); <-done }()
	calls := tg.WaitCalls(t, "sendMessage", 1, 5*time.Second)
	if !strings.Contains(calls[0].Params["text"].(string), "in flight") {
		t.Error("interrupted message should be re-sent after restart")
	}
	st2, _ := store.Open(cfg.DBPath())
	defer st2.Close()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		mi, _ := st2.GetMessage(context.Background(), m.ID)
		if mi.Message.Status == model.StatusDelivered {
			if !mi.Deliveries[0].Uncertain {
				t.Error("possible duplicate must be flagged")
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("not delivered after recovery")
}
