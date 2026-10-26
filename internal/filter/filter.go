package filter

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"msggw/internal/health"
	"msggw/internal/model"
	"msggw/internal/store"
)

// Waker is implemented by the delivery worker.
type Waker interface{ Wake() }

// Publisher is implemented by delivery.Notifier.
type Publisher interface{ Publish(id string) }

// Filter resolves held messages one at a time. It never loses a message:
// when the classifier cannot give an answer the message is forwarded.
type Filter struct {
	Store    *store.Store
	AI       Classifier
	Drop     map[model.Category]bool // categories that are not forwarded
	Health   *health.Tracker
	Notifier Publisher
	Delivery Waker
	Log      *slog.Logger
	Now      func() time.Time
	// Timeout bounds one classification call.
	Timeout time.Duration
	// After PauseAfter consecutive failures the AI is skipped (messages are
	// forwarded directly) for PauseFor, so an outage does not delay every
	// message by Timeout.
	PauseAfter int
	PauseFor   time.Duration

	wake        chan struct{}
	once        sync.Once
	fails       int
	pausedUntil time.Time
}

func (f *Filter) init() {
	f.once.Do(func() {
		f.wake = make(chan struct{}, 1)
		if f.Now == nil {
			f.Now = time.Now
		}
		if f.Timeout == 0 {
			f.Timeout = 15 * time.Second
		}
		if f.PauseAfter == 0 {
			f.PauseAfter = 3
		}
		if f.PauseFor == 0 {
			f.PauseFor = 5 * time.Minute
		}
		if f.Log == nil {
			f.Log = slog.Default()
		}
	})
}

// Wake asks the filter to look for held messages now.
func (f *Filter) Wake() {
	f.init()
	select {
	case f.wake <- struct{}{}:
	default:
	}
}

// Run processes held messages until ctx is cancelled.
func (f *Filter) Run(ctx context.Context) {
	f.init()
	for {
		n, err := f.RunOnce(ctx)
		if ctx.Err() != nil {
			return
		}
		sleep := 30 * time.Second
		if err != nil {
			f.Log.Error("filter queue failed", "err", err)
			sleep = 5 * time.Second
		} else if n > 0 {
			continue // there may be more
		}
		t := time.NewTimer(sleep)
		select {
		case <-ctx.Done():
			t.Stop()
			return
		case <-f.wake:
		case <-t.C:
		}
		t.Stop()
	}
}

// RunOnce resolves the currently held messages (up to one batch).
func (f *Filter) RunOnce(ctx context.Context) (int, error) {
	f.init()
	held, err := f.Store.HeldMessages(ctx, 20)
	if err != nil {
		return 0, err
	}
	for _, h := range held {
		if ctx.Err() != nil {
			break
		}
		if err := f.resolve(ctx, h); err != nil {
			return 0, err // back off instead of spinning on a failing store
		}
	}
	return len(held), nil
}

// Verdict is the outcome of classifying one message.
type Verdict struct {
	Category model.Category // "" when unknown
	By       string         // "rule", "ai", "skipped" or "error"
	Err      string
}

// Decide classifies one message without touching the store.
func (f *Filter) Decide(ctx context.Context, title, body string) Verdict {
	f.init()
	if IsCode(title + "\n" + body) {
		return Verdict{Category: model.CatCode, By: "rule"}
	}
	now := f.Now()
	if now.Before(f.pausedUntil) {
		return Verdict{By: "skipped", Err: "DeepSeek 连续失败，暂停调用中"}
	}
	cctx, cancel := context.WithTimeout(ctx, f.Timeout)
	cat, err := f.AI.Classify(cctx, Mask(title), Mask(body))
	cancel()
	if err != nil {
		f.fails++
		if f.fails >= f.PauseAfter {
			f.pausedUntil = f.Now().Add(f.PauseFor)
			f.fails = 0
		}
		return Verdict{By: "error", Err: err.Error()}
	}
	f.fails = 0
	return Verdict{Category: cat, By: "ai"}
}

func (f *Filter) resolve(ctx context.Context, h store.HeldMessage) error {
	start := f.Now()
	v := f.Decide(ctx, h.Title, h.Body)
	if ctx.Err() != nil {
		return nil // shutting down: leave it held, it is picked up after restart
	}
	switch v.By {
	case "ai":
		f.Health.FilterOK(f.Now())
	case "error":
		f.Health.FilterFailed(f.Now(), v.Err+"，已直接转发")
	}
	drop := v.Category != "" && f.Drop[v.Category]
	// Record the verdict with a fresh context so it is stored even during shutdown.
	sctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	status, err := f.Store.ResolveHeld(sctx, h.ID, v.Category, drop, f.Now())
	cancel()
	if err != nil {
		f.Log.Error("record filter verdict failed", "message", h.ID, "err", err)
		f.Health.Error(f.Now(), "短信过滤", "无法记录分类结果")
		return err
	}
	f.Log.Info("filter", "message", h.ID, "source", h.SourceID, "category", v.Category, "by", v.By,
		"dropped", drop, "status", status, "ms", f.Now().Sub(start).Milliseconds(), "err", v.Err)
	if f.Notifier != nil {
		f.Notifier.Publish(h.ID)
	}
	if !drop && f.Delivery != nil {
		f.Delivery.Wake()
	}
	return nil
}
