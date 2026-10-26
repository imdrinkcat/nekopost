// Package delivery owns the persistent outbox: it picks due deliveries from
// the store, hands them to channels, and records outcomes with retries.
package delivery

import (
	"context"
	"time"

	"nekopost/internal/health"
	"nekopost/internal/store"
)

// Outcome classifies one delivery attempt.
type Outcome int

const (
	// Delivered: the target confirmed receipt of every part.
	Delivered Outcome = iota
	// RetrySafe: nothing (more) was received; retrying cannot duplicate.
	RetrySafe
	// RetryUncertain: the target may have received the message; retrying
	// may duplicate it (accepted under at-least-once semantics).
	RetryUncertain
	// Permanent: the target rejected the message; retrying will not help.
	Permanent
)

// Result is what a channel reports for one attempt.
type Result struct {
	Outcome    Outcome
	PartsTotal int
	PartsSent  int
	RetryAfter time.Duration // hint from the target (e.g. rate limiting)
	FailKind   health.FailKind
	Err        string // short, content-free description
}

// Channel delivers messages to one kind of target (Telegram, ...).
type Channel interface {
	// Name is the channel identifier used by routing ("telegram").
	Name() string
	// Deliver attempts to deliver job, resuming after job.Delivery.PartsSent
	// parts. progress must be called after each confirmed part.
	Deliver(ctx context.Context, job store.Job, progress func(total, sent int)) Result
}
