package delivery

import (
	"context"
	"log/slog"
	"math/rand/v2"
	"sync"
	"time"

	"nekopost/internal/health"
	"nekopost/internal/model"
	"nekopost/internal/store"
)

// DefaultBackoff is the delay before retry n (1-based); the last value
// repeats until the delivery's maximum age is reached.
var DefaultBackoff = []time.Duration{
	5 * time.Second, 15 * time.Second, 30 * time.Second, time.Minute, 2 * time.Minute,
	5 * time.Minute, 10 * time.Minute, 15 * time.Minute, 30 * time.Minute,
}

// Worker processes the outbox. A single worker processes deliveries one at
// a time, which keeps memory flat and naturally respects Telegram's limits.
type Worker struct {
	Store    *store.Store
	Channels map[string]Channel
	Health   *health.Tracker
	Notifier *Notifier
	Log      *slog.Logger
	MaxAge   time.Duration
	Backoff  []time.Duration
	Now      func() time.Time
	// AttemptTimeout bounds one attempt (all parts of one message).
	AttemptTimeout time.Duration

	wake chan struct{}
	once sync.Once
}

func (w *Worker) init() {
	w.once.Do(func() {
		w.wake = make(chan struct{}, 1)
		if w.Now == nil {
			w.Now = time.Now
		}
		if len(w.Backoff) == 0 {
			w.Backoff = DefaultBackoff
		}
		if w.AttemptTimeout == 0 {
			w.AttemptTimeout = 3 * time.Minute
		}
		if w.Notifier == nil {
			w.Notifier = NewNotifier()
		}
		if w.Log == nil {
			w.Log = slog.Default()
		}
	})
}

// Wake asks the worker to look for due deliveries now.
func (w *Worker) Wake() {
	w.init()
	select {
	case w.wake <- struct{}{}:
	default:
	}
}

const batchSize = 20
const idlePoll = 30 * time.Second

// Run processes deliveries until ctx is cancelled.
func (w *Worker) Run(ctx context.Context) {
	w.init()
	for {
		w.Health.WorkerBeat(w.Now())
		n, err := w.RunOnce(ctx)
		if ctx.Err() != nil {
			return
		}
		sleep := idlePoll
		if err != nil {
			w.Log.Error("outbox query failed", "err", err)
			w.Health.Error(w.Now(), "投递队列", "读取队列失败")
			sleep = 5 * time.Second
		} else if n >= batchSize {
			sleep = 0
		} else if next, ok, err := w.Store.NextDueAt(ctx); err == nil && ok {
			if d := next.Sub(w.Now()); d < sleep {
				sleep = max(d, 0)
			}
		}
		if sleep == 0 {
			continue
		}
		t := time.NewTimer(sleep)
		select {
		case <-ctx.Done():
			t.Stop()
			return
		case <-w.wake:
		case <-t.C:
		}
		t.Stop()
	}
}

// RunOnce processes all currently due deliveries (up to one batch).
func (w *Worker) RunOnce(ctx context.Context) (int, error) {
	w.init()
	jobs, err := w.Store.DueJobs(ctx, w.Now(), batchSize)
	if err != nil {
		return 0, err
	}
	for _, j := range jobs {
		if ctx.Err() != nil {
			break
		}
		w.process(ctx, j)
		w.Health.WorkerBeat(w.Now())
	}
	return len(jobs), nil
}

func (w *Worker) backoff(attempt int) time.Duration {
	i := attempt - 1
	if i < 0 {
		i = 0
	}
	if i >= len(w.Backoff) {
		i = len(w.Backoff) - 1
	}
	d := w.Backoff[i]
	// ±10% jitter so retries after an outage do not all fire together.
	j := time.Duration(float64(d) * (rand.Float64()*0.2 - 0.1))
	return d + j
}

func (w *Worker) finish(job store.Job, r store.AttemptResult) {
	// Use a fresh context: outcomes must be recorded even during shutdown.
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	status, err := w.Store.FinishAttempt(ctx, job.Delivery.ID, job.Delivery.MessageID, r, w.Now())
	if err != nil {
		w.Log.Error("record delivery outcome failed", "message", job.Delivery.MessageID, "err", err)
		w.Health.Error(w.Now(), "投递队列", "无法记录投递结果")
		return
	}
	w.Notifier.Publish(job.Delivery.MessageID)
	w.Log.Info("delivery",
		"message", job.Delivery.MessageID, "source", job.Message.SourceID,
		"channel", job.Delivery.Channel, "state", r.State, "status", status,
		"attempt", job.Delivery.Attempts+1, "parts", r.PartsSent, "err", r.LastError)
}

func (w *Worker) process(ctx context.Context, job store.Job) {
	now := w.Now()
	d := job.Delivery
	deadline := d.CreatedAt.Add(w.MaxAge)
	final := func(msg string) store.AttemptResult {
		st := model.DeliveryFailed
		if d.Uncertain {
			st = model.DeliveryUncertain
		}
		return store.AttemptResult{State: st, LastError: msg, PartsTotal: d.PartsTotal, PartsSent: d.PartsSent}
	}
	if job.PayloadMissing {
		w.finish(job, final("消息内容已丢失，无法投递"))
		return
	}
	if now.After(deadline) {
		w.finish(job, final("超过最长重试时间（"+health.Dur(w.MaxAge)+"），已放弃。最后错误："+d.LastError))
		return
	}
	ch, ok := w.Channels[d.Channel]
	if !ok {
		w.finish(job, final("未注册的投递通道 "+d.Channel))
		return
	}
	if err := w.Store.ClaimDelivery(ctx, d.ID, d.MessageID, now); err != nil {
		w.Log.Error("claim delivery failed", "message", d.MessageID, "err", err)
		return
	}
	w.Notifier.Publish(d.MessageID)

	actx, cancel := context.WithTimeout(ctx, w.AttemptTimeout)
	res := ch.Deliver(actx, job, func(total, sent int) {
		pctx, pcancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer pcancel()
		if err := w.Store.SetPartsProgress(pctx, d.ID, total, sent); err != nil {
			w.Log.Error("record part progress failed", "message", d.MessageID, "err", err)
		}
	})
	cancel()

	after := w.Now()
	r := store.AttemptResult{PartsTotal: res.PartsTotal, PartsSent: res.PartsSent, LastError: res.Err,
		Uncertain: res.Outcome == RetryUncertain}
	switch res.Outcome {
	case Delivered:
		r.State = model.DeliveryDelivered
		w.Health.DeliveryOK(after)
	case Permanent:
		r.State = model.DeliveryFailed
		if d.Uncertain || res.PartsSent > 0 {
			r.State = model.DeliveryUncertain
		}
		w.Health.DeliveryFailed(after, res.FailKind, res.Err)
	default:
		w.Health.DeliveryFailed(after, res.FailKind, res.Err)
		delay := w.backoff(d.Attempts + 1)
		if res.RetryAfter > delay {
			delay = res.RetryAfter + time.Second
		}
		next := after.Add(delay)
		if next.After(deadline) {
			r.State = model.DeliveryFailed
			if d.Uncertain || r.Uncertain {
				r.State = model.DeliveryUncertain
			}
			r.LastError = "超过最长重试时间，已放弃。最后错误：" + res.Err
		} else {
			r.State = model.DeliveryRetrying
			r.NextAttemptAt = next
		}
	}
	w.finish(job, r)
}

// Notifier lets request handlers wait for a message's status to change.
type Notifier struct {
	mu   sync.Mutex
	subs map[string]map[chan struct{}]struct{}
}

// NewNotifier returns an empty notifier.
func NewNotifier() *Notifier {
	return &Notifier{subs: map[string]map[chan struct{}]struct{}{}}
}

// Subscribe returns a channel signalled whenever id changes, and a cancel func.
func (n *Notifier) Subscribe(id string) (<-chan struct{}, func()) {
	ch := make(chan struct{}, 1)
	n.mu.Lock()
	if n.subs[id] == nil {
		n.subs[id] = map[chan struct{}]struct{}{}
	}
	n.subs[id][ch] = struct{}{}
	n.mu.Unlock()
	return ch, func() {
		n.mu.Lock()
		delete(n.subs[id], ch)
		if len(n.subs[id]) == 0 {
			delete(n.subs, id)
		}
		n.mu.Unlock()
	}
}

// Publish signals subscribers of id.
func (n *Notifier) Publish(id string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	for ch := range n.subs[id] {
		select {
		case ch <- struct{}{}:
		default:
		}
	}
}
