package store

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"nekopost/internal/model"
)

func openTest(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

var ctx = context.Background()

func newMsg(src, body, idem string, at time.Time) *model.Message {
	return &model.Message{ID: model.NewMessageID(at), SourceID: src, Kind: "text", Priority: model.PriorityNormal,
		Body: body, IdempotencyKey: idem, ContentHash: "h:" + body, ReceivedAt: at}
}

var tg = []NewDelivery{{Channel: "telegram", Target: "default"}}

func TestReopenKeepsData(t *testing.T) {
	p := filepath.Join(t.TempDir(), "t.db")
	s, _ := Open(p)
	if err := s.CreateSource(ctx, model.Source{ID: "a", Name: "A", Enabled: true}, "hash", "pfx", time.Now()); err != nil {
		t.Fatal(err)
	}
	s.Close()
	s, err := Open(p)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if _, err := s.GetSource(ctx, "a"); err != nil {
		t.Fatal("data lost after reopen:", err)
	}
}

func TestSourcesAndTokens(t *testing.T) {
	s := openTest(t)
	now := time.Now()
	if err := s.CreateSource(ctx, model.Source{ID: "iphone", Name: "iPhone", Icon: "📱", Enabled: true}, "h1", "p1", now); err != nil {
		t.Fatal(err)
	}
	if err := s.CreateSource(ctx, model.Source{ID: "iphone"}, "h2", "p2", now); !errors.Is(err, ErrExists) {
		t.Fatalf("want ErrExists, got %v", err)
	}
	lk, err := s.LookupSourceToken(ctx, "h1")
	if err != nil || lk.Source.ID != "iphone" || lk.Revoked || !lk.Source.Enabled {
		t.Fatalf("lookup: %+v %v", lk, err)
	}
	if _, err := s.LookupSourceToken(ctx, "nope"); !errors.Is(err, ErrNotFound) {
		t.Error("unknown token should be not found")
	}
	if err := s.RotateSourceToken(ctx, "iphone", "h3", "p3", now); err != nil {
		t.Fatal(err)
	}
	if lk, _ := s.LookupSourceToken(ctx, "h1"); !lk.Revoked {
		t.Error("old token should be revoked after rotate")
	}
	if lk, _ := s.LookupSourceToken(ctx, "h3"); lk.Revoked {
		t.Error("new token should be active")
	}
	if err := s.RotateSourceToken(ctx, "ghost", "h4", "p4", now); !errors.Is(err, ErrNotFound) {
		t.Error("rotate of unknown source should fail")
	}
	if err := s.SetSourceEnabled(ctx, "iphone", false, now); err != nil {
		t.Fatal(err)
	}
	src, _ := s.GetSource(ctx, "iphone")
	if src.Enabled {
		t.Error("should be disabled")
	}
	_ = s.RecordSourceAuthFailure(ctx, "iphone", now)
	_ = s.TouchSource(ctx, "iphone", now)
	src, _ = s.GetSource(ctx, "iphone")
	if src.AuthFailCount != 1 || src.LastSeenAt.IsZero() {
		t.Errorf("counters: %+v", src)
	}
	if err := s.DeleteSource(ctx, "iphone"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.LookupSourceToken(ctx, "h3"); !errors.Is(err, ErrNotFound) {
		t.Error("tokens should be deleted with the source")
	}
}

func TestDedupe(t *testing.T) {
	s := openTest(t)
	now := time.Now()
	r1, err := s.InsertMessage(ctx, newMsg("a", "x", "k1", now), tg, time.Minute)
	if err != nil || r1.Duplicate || r1.Status != model.StatusQueued {
		t.Fatalf("%+v %v", r1, err)
	}
	r2, _ := s.InsertMessage(ctx, newMsg("a", "different", "k1", now.Add(time.Hour)), tg, time.Minute)
	if !r2.Duplicate || r2.ID != r1.ID {
		t.Error("same idempotency key must be a duplicate regardless of content/time")
	}
	r3, _ := s.InsertMessage(ctx, newMsg("b", "x", "k1", now), tg, time.Minute)
	if r3.Duplicate {
		t.Error("idempotency keys are scoped per source")
	}
	// Content-based dedupe within the window, only without keys.
	c1, _ := s.InsertMessage(ctx, newMsg("a", "same", "", now), tg, time.Minute)
	c2, _ := s.InsertMessage(ctx, newMsg("a", "same", "", now.Add(30*time.Second)), tg, time.Minute)
	c3, _ := s.InsertMessage(ctx, newMsg("a", "same", "", now.Add(3*time.Minute)), tg, time.Minute)
	if c1.Duplicate || !c2.Duplicate || c2.ID != c1.ID || c3.Duplicate {
		t.Errorf("content dedupe: %+v %+v %+v", c1, c2, c3)
	}
	c4, _ := s.InsertMessage(ctx, newMsg("a", "same", "", now.Add(3*time.Minute+time.Second)), tg, 0)
	if c4.Duplicate {
		t.Error("window 0 disables content dedupe")
	}
	mi, _ := s.GetMessage(ctx, r1.ID)
	if mi.Message.DupCount != 1 {
		t.Errorf("dup count %d", mi.Message.DupCount)
	}
	st, _ := s.Stats(ctx, now.Add(-time.Hour), now.Add(time.Hour))
	if st.Total.Received != 5 || st.Total.Duplicates != 2 {
		t.Errorf("stats must count unique messages once: %+v", st.Total)
	}
}

func TestDeliveryLifecycleAndPurge(t *testing.T) {
	s := openTest(t)
	now := time.Now()
	m := newMsg("a", "secret 123456", "", now)
	m.Title = "t"
	res, _ := s.InsertMessage(ctx, m, tg, 0)
	jobs, err := s.DueJobs(ctx, now, 10)
	if err != nil || len(jobs) != 1 || jobs[0].Message.Body != "secret 123456" || jobs[0].PayloadMissing {
		t.Fatalf("jobs %+v %v", jobs, err)
	}
	j := jobs[0]
	if err := s.ClaimDelivery(ctx, j.Delivery.ID, res.ID, now); err != nil {
		t.Fatal(err)
	}
	if st, _ := s.MessageStatus(ctx, res.ID); st != model.StatusSending {
		t.Errorf("status %s", st)
	}
	if jobs, _ := s.DueJobs(ctx, now, 10); len(jobs) != 0 {
		t.Error("in-flight delivery must not be due")
	}
	st, err := s.FinishAttempt(ctx, j.Delivery.ID, res.ID, AttemptResult{State: model.DeliveryRetrying, NextAttemptAt: now.Add(time.Minute), Uncertain: true, LastError: "x"}, now)
	if err != nil || st != model.StatusRetrying {
		t.Fatalf("%s %v", st, err)
	}
	if next, ok, _ := s.NextDueAt(ctx); !ok || !next.Equal(time.UnixMilli(now.Add(time.Minute).UnixMilli())) {
		t.Errorf("next due %v", next)
	}
	st, _ = s.FinishAttempt(ctx, j.Delivery.ID, res.ID, AttemptResult{State: model.DeliveryDelivered, PartsTotal: 1, PartsSent: 1}, now)
	if st != model.StatusDelivered {
		t.Errorf("status %s", st)
	}
	var n int
	s.db.QueryRow("SELECT COUNT(*) FROM payloads").Scan(&n)
	if n != 0 {
		t.Error("payload must be purged once the message is finished")
	}
	mi, _ := s.GetMessage(ctx, res.ID)
	if !mi.Deliveries[0].Uncertain || mi.Deliveries[0].DeliveredAt.IsZero() || mi.Message.FinishedAt.IsZero() {
		t.Errorf("delivery record %+v", mi.Deliveries[0])
	}
	// Retention.
	if n, _ := s.PurgeExpired(ctx, now.Add(time.Hour)); n != 1 {
		t.Errorf("purged %d", n)
	}
}

func TestRecoverInFlight(t *testing.T) {
	s := openTest(t)
	now := time.Now()
	res, _ := s.InsertMessage(ctx, newMsg("a", "x", "", now), tg, 0)
	jobs, _ := s.DueJobs(ctx, now, 10)
	_ = s.ClaimDelivery(ctx, jobs[0].Delivery.ID, res.ID, now)
	n, err := s.RecoverInFlight(ctx, now)
	if err != nil || n != 1 {
		t.Fatalf("%d %v", n, err)
	}
	mi, _ := s.GetMessage(ctx, res.ID)
	if mi.Deliveries[0].State != model.DeliveryRetrying || !mi.Deliveries[0].Uncertain || mi.Message.Status != model.StatusRetrying {
		t.Errorf("after recovery: %+v %s", mi.Deliveries[0], mi.Message.Status)
	}
	if jobs, _ := s.DueJobs(ctx, now, 10); len(jobs) != 1 {
		t.Error("recovered delivery should be due again")
	}
}

func TestNotRoutedHasNoPayload(t *testing.T) {
	s := openTest(t)
	res, _ := s.InsertMessage(ctx, newMsg("a", "x", "", time.Now()), nil, 0)
	if res.Status != model.StatusNotRouted {
		t.Errorf("status %s", res.Status)
	}
	var n int
	s.db.QueryRow("SELECT COUNT(*) FROM payloads").Scan(&n)
	if n != 0 {
		t.Error("unrouted messages must not keep a payload")
	}
}

func TestStatsAndActivity(t *testing.T) {
	s := openTest(t)
	now := time.Now()
	for i, src := range []string{"a", "a", "b"} {
		res, _ := s.InsertMessage(ctx, newMsg(src, string(rune('x'+i)), "", now.Add(time.Duration(i)*time.Second)), tg, 0)
		if i == 0 {
			jobs, _ := s.DueJobs(ctx, now.Add(time.Hour), 10)
			s.ClaimDelivery(ctx, jobs[0].Delivery.ID, res.ID, now)
			s.FinishAttempt(ctx, jobs[0].Delivery.ID, res.ID, AttemptResult{State: model.DeliveryFailed}, now)
		}
	}
	st, err := s.Stats(ctx, now.Add(-time.Minute), now.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if st.Total.Received != 3 || st.Total.Failed != 1 || st.Total.InProgress != 2 || len(st.BySource) != 2 || st.BySource[0].SourceID != "a" {
		t.Errorf("stats %+v", st)
	}
	last, _ := s.LastMessages(ctx)
	if last["a"].Status != model.StatusQueued || last["b"].At.IsZero() {
		t.Errorf("last %+v", last)
	}
	q, _ := s.Queue(ctx)
	if q.InProgress != 2 {
		t.Errorf("queue %+v", q)
	}
	h, _ := s.LastDeliveryHistory(ctx)
	if h.LastProblem.IsZero() {
		t.Error("history should include the failure")
	}
}

func TestFilteredRescueAndPayloadRetention(t *testing.T) {
	s := openTest(t)
	now := time.Now()
	held := []NewDelivery{{Channel: "telegram", Target: "default", Held: true}}
	ad := newMsg("a", "ad", "", now.Add(-8*24*time.Hour))
	ad2 := newMsg("a", "ad2", "", now)
	for _, m := range []*model.Message{ad, ad2} {
		if res, err := s.InsertMessage(ctx, m, held, 0); err != nil || res.Status != model.StatusClassifying {
			t.Fatalf("insert held: %v %v", res.Status, err)
		}
	}
	if q, _ := s.Queue(ctx); q.InProgress != 2 {
		t.Fatalf("held messages must count as in progress: %d", q.InProgress)
	}
	if st, err := s.ResolveHeld(ctx, ad.ID, model.CatMarketing, true, now.Add(-8*24*time.Hour)); err != nil || st != model.StatusFiltered {
		t.Fatalf("resolve: %v %v", st, err)
	}
	if st, _ := s.ResolveHeld(ctx, ad2.ID, model.CatSpam, true, now); st != model.StatusFiltered {
		t.Fatal(st)
	}
	stats, _ := s.Stats(ctx, now.Add(-30*24*time.Hour), now.Add(time.Second))
	if stats.Total.Filtered != 2 || stats.Total.InProgress != 0 {
		t.Fatalf("stats: %+v", stats.Total)
	}
	// Payloads survive the normal purge until the keep period has passed.
	if _, total, _ := s.FilteredMessages(ctx, 0, 10); total != 2 {
		t.Fatalf("filtered before purge: %d", total)
	}
	if _, err := s.PurgeFinishedPayloads(ctx, now.Add(-7*24*time.Hour)); err != nil {
		t.Fatal(err)
	}
	list, total, _ := s.FilteredMessages(ctx, 0, 10)
	if total != 1 || list[0].ID != ad2.ID || list[0].Category != model.CatSpam || list[0].Body != "ad2" {
		t.Fatalf("after purge: %+v %d", list, total)
	}
	if err := s.RescueFiltered(ctx, ad.ID, now); !errors.Is(err, ErrNotRescuable) {
		t.Fatalf("expired message must not be rescuable: %v", err)
	}

	// Rescue queues the message with a fresh retry period.
	if err := s.RescueFiltered(ctx, ad2.ID, now.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := s.RescueFiltered(ctx, ad2.ID, now); !errors.Is(err, ErrNotRescuable) {
		t.Fatalf("second rescue: %v", err)
	}
	jobs, _ := s.DueJobs(ctx, now.Add(2*time.Minute), 10)
	if len(jobs) != 1 || jobs[0].Message.Category != model.CatSpam || jobs[0].Message.Body != "ad2" ||
		!jobs[0].Delivery.CreatedAt.Equal(now.Add(time.Minute).Truncate(time.Millisecond)) {
		t.Fatalf("rescued job: %+v", jobs)
	}
	if mi, _ := s.GetMessage(ctx, ad2.ID); mi.Message.Status != model.StatusQueued || !mi.Message.FinishedAt.IsZero() {
		t.Fatalf("rescued status: %v", mi.Message.Status)
	}
}

func TestReleaseAllHeld(t *testing.T) {
	s := openTest(t)
	now := time.Now()
	m := newMsg("a", "x", "", now)
	s.InsertMessage(ctx, m, []NewDelivery{{Channel: "telegram", Target: "default", Held: true}}, 0)
	if n, err := s.ReleaseAllHeld(ctx, now); err != nil || n != 1 {
		t.Fatalf("release: %d %v", n, err)
	}
	if jobs, _ := s.DueJobs(ctx, now, 10); len(jobs) != 1 || jobs[0].Message.Category != "" {
		t.Fatalf("released job: %+v", jobs)
	}
}
