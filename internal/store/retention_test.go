package store

import (
	"testing"
	"time"

	"nekopost/internal/model"
)

func bodyExpiry(t *testing.T, s *Store, id string) time.Time {
	t.Helper()
	var ms int64
	if err := s.db.QueryRow("SELECT expires_at FROM bodies WHERE message_id = ?", id).Scan(&ms); err != nil {
		t.Fatal(err)
	}
	return time.UnixMilli(ms)
}

func TestKeepForeverNeverExpiresBodies(t *testing.T) {
	s := pushStore(t, func(o *Options) { o.KeepForever = true })
	now := time.Now()
	plain := newMsg("fixture", "an ordinary message", "", now)
	code := newMsg("fixture", "您的验证码是 123456", "", now)
	for _, m := range []*model.Message{plain, code} {
		if _, err := s.InsertMessage(ctx, m, tg, 0); err != nil {
			t.Fatal(err)
		}
		if got := bodyExpiry(t, s, m.ID); !IsNever(got) {
			t.Fatalf("%s: expiry %v, want never", m.ID, got)
		}
		d := deliveriesOf(t, s, m.ID).Deliveries[0]
		if _, err := s.FinishAttempt(ctx, d.ID, m.ID, AttemptResult{State: model.DeliveryDelivered}, now); err != nil {
			t.Fatal(err)
		}
	}
	// Ten years on, nothing has expired and both bodies still decrypt.
	if n, err := s.ExpireBodies(ctx, now.AddDate(10, 0, 0)); err != nil || n != 0 {
		t.Fatal("bodies expired", n, err)
	}
	if _, body, _, ok, err := s.ReadBody(ctx, plain.ID); err != nil || !ok || body != "an ordinary message" {
		t.Fatal(body, ok, err)
	}
	if err := s.ShredUnusedDEKs(ctx, now.AddDate(10, 0, 0)); err != nil {
		t.Fatal(err)
	}
	if _, body, _, ok, err := s.ReadBody(ctx, code.ID); err != nil || !ok || body == "" {
		t.Fatal("the daily key of a kept body was shredded", ok, err)
	}
}

func TestKeepForeverIsNotV1Mode(t *testing.T) {
	// Retention 0 normally means "delete when delivery finishes"; with
	// automatic deletion off the body must stay.
	s := pushStore(t, func(o *Options) { o.KeepForever, o.BodyRetention, o.CodeRetention = true, 0, 0 })
	now := time.Now()
	m := newMsg("fixture", "stays", "", now)
	if _, err := s.InsertMessage(ctx, m, tg, 0); err != nil {
		t.Fatal(err)
	}
	d := deliveriesOf(t, s, m.ID).Deliveries[0]
	if _, err := s.FinishAttempt(ctx, d.ID, m.ID, AttemptResult{State: model.DeliveryDelivered}, now); err != nil {
		t.Fatal(err)
	}
	if _, body, _, ok, err := s.ReadBody(ctx, m.ID); err != nil || !ok || body != "stays" {
		t.Fatal(body, ok, err)
	}
}

func TestApplyRetentionModeSwitches(t *testing.T) {
	path := t.TempDir() + "/t.db"
	open := func(keep bool) *Store {
		opts := TestingOptions()
		opts.KeepForever = keep
		s, err := Open(path, opts)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { s.Close() })
		return s
	}
	now := time.Now()

	// Written with automatic deletion on: ordinary 7 days, code 24 hours.
	s := open(false)
	plain := newMsg("fixture", "an ordinary message", "", now)
	code := newMsg("fixture", "您的验证码是 123456", "", now)
	for _, m := range []*model.Message{plain, code} {
		if _, err := s.InsertMessage(ctx, m, tg, 0); err != nil {
			t.Fatal(err)
		}
	}
	if got := bodyExpiry(t, s, plain.ID); IsNever(got) || !got.After(now.Add(6*24*time.Hour)) {
		t.Fatal("ordinary expiry", got)
	}
	s.Close()

	// Switching automatic deletion off keeps everything.
	s = open(true)
	if n, err := s.ApplyRetentionMode(ctx, now); err != nil || n != 2 {
		t.Fatal(n, err)
	}
	for _, id := range []string{plain.ID, code.ID} {
		if !IsNever(bodyExpiry(t, s, id)) {
			t.Fatal(id, "still expires")
		}
	}
	if n, err := s.ApplyRetentionMode(ctx, now); err != nil || n != 0 {
		t.Fatal("second run should change nothing", n, err)
	}
	s.Close()

	// Switching it back on starts a fresh period from now, never from the
	// day the message arrived: nothing is deleted on the spot.
	later := now.AddDate(0, 6, 0)
	s = open(false)
	if n, err := s.ApplyRetentionMode(ctx, later); err != nil || n != 2 {
		t.Fatal(n, err)
	}
	if got := bodyExpiry(t, s, plain.ID); !got.Equal(later.Add(7 * 24 * time.Hour).Truncate(time.Millisecond)) {
		t.Fatal("ordinary body: expiry", got, "want now+7d")
	}
	if got := bodyExpiry(t, s, code.ID); !got.Equal(later.Add(24 * time.Hour).Truncate(time.Millisecond)) {
		t.Fatal("code body: expiry", got, "want now+24h")
	}
	if n, err := s.ExpireBodies(ctx, later); err != nil || n != 0 {
		t.Fatal("deleted on the spot", n, err)
	}
}
