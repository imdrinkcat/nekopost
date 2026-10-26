// Package health tracks what the gateway has actually observed (successful
// and failed Telegram calls, incoming requests, worker liveness) and turns
// those observations into per-component states. A component with no evidence
// is reported as unknown, never assumed healthy.
package health

import (
	"sync"
	"time"
)

// FailKind classifies a failed Telegram interaction.
type FailKind int

const (
	FailOther   FailKind = iota // rejected request, rate limit, server error
	FailNetwork                 // could not reach Telegram
	FailConfig                  // bad token, unknown chat, bot blocked, conflict
)

// ErrorEvent is one entry of the recent-error log (no message content).
type ErrorEvent struct {
	At        time.Time
	Component string
	Msg       string
}

// BotIdentity is the result of getMe.
type BotIdentity struct {
	ID       int64
	Username string
	Name     string
}

// State is a copy of everything the tracker knows.
type State struct {
	StartedAt time.Time

	SendOK, SendFail     time.Time
	SendFailMsg          string
	SendFailKind         FailKind
	SendConsecutiveFails int

	PollOK, PollFail     time.Time
	PollFailMsg          string
	PollFailKind         FailKind
	PollConsecutiveFails int
	Bot                  *BotIdentity

	Listening       bool
	ListenErr       string
	LastIngest      time.Time
	AuthFailures    int
	LastAuthFailure time.Time

	WorkerBeat time.Time

	// Content filter (AI classification); zero values when unused.
	FilterOK, FilterFail   time.Time
	FilterFailMsg          string
	FilterConsecutiveFails int

	BotUnauthorized     int
	LastBotUnauthorized time.Time
}

// Tracker is safe for concurrent use.
type Tracker struct {
	mu     sync.Mutex
	st     State
	errors []ErrorEvent
}

const maxErrors = 20

// NewTracker returns a tracker for a process started at startedAt.
func NewTracker(startedAt time.Time) *Tracker {
	return &Tracker{st: State{StartedAt: startedAt}}
}

func (t *Tracker) logError(at time.Time, component, msg string) {
	t.errors = append(t.errors, ErrorEvent{At: at, Component: component, Msg: msg})
	if len(t.errors) > maxErrors {
		t.errors = t.errors[len(t.errors)-maxErrors:]
	}
}

// Seed initialises delivery history from persistent storage after a restart.
func (t *Tracker) Seed(lastOK, lastFail time.Time, failMsg string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.st.SendOK = lastOK
	if lastFail.After(lastOK) {
		t.st.SendFail = lastFail
		t.st.SendFailMsg = failMsg
	}
}

// DeliveryOK records a confirmed Telegram delivery.
func (t *Tracker) DeliveryOK(at time.Time) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.st.SendOK = at
	t.st.SendConsecutiveFails = 0
}

// DeliveryFailed records a failed or uncertain delivery attempt.
func (t *Tracker) DeliveryFailed(at time.Time, kind FailKind, msg string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.st.SendFail = at
	t.st.SendFailKind = kind
	t.st.SendFailMsg = msg
	t.st.SendConsecutiveFails++
	t.logError(at, "投递", msg)
}

// PollOK records a successful getUpdates call.
func (t *Tracker) PollOK(at time.Time) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.st.PollOK = at
	t.st.PollConsecutiveFails = 0
}

// PollFailed records a failed Bot API call made by the management bot.
func (t *Tracker) PollFailed(at time.Time, kind FailKind, msg string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.st.PollFail = at
	t.st.PollFailKind = kind
	t.st.PollFailMsg = msg
	t.st.PollConsecutiveFails++
	// Avoid flooding the log while Telegram is down for a long time.
	if t.st.PollConsecutiveFails <= 3 || t.st.PollConsecutiveFails%30 == 0 {
		t.logError(at, "管理 Bot", msg)
	}
}

// SetBot stores the bot identity from getMe.
func (t *Tracker) SetBot(b BotIdentity) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.st.Bot = &b
}

// SetListening records whether the HTTP listeners are up.
func (t *Tracker) SetListening(ok bool, errMsg string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.st.Listening = ok
	t.st.ListenErr = errMsg
	if errMsg != "" {
		t.logError(time.Now(), "消息接入", errMsg)
	}
}

// IngestOK records an accepted message submission.
func (t *Tracker) IngestOK(at time.Time) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.st.LastIngest = at
}

// AuthFailure records a request with an invalid credential.
func (t *Tracker) AuthFailure(at time.Time) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.st.AuthFailures++
	t.st.LastAuthFailure = at
}

// WorkerBeat records that the delivery worker loop is alive.
func (t *Tracker) WorkerBeat(at time.Time) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.st.WorkerBeat = at
}

// FilterOK records a successful AI classification.
func (t *Tracker) FilterOK(at time.Time) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.st.FilterOK = at
	t.st.FilterConsecutiveFails = 0
}

// FilterFailed records a failed AI classification (the message was
// forwarded without filtering).
func (t *Tracker) FilterFailed(at time.Time, msg string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.st.FilterFail = at
	t.st.FilterFailMsg = msg
	t.st.FilterConsecutiveFails++
	if t.st.FilterConsecutiveFails <= 3 || t.st.FilterConsecutiveFails%30 == 0 {
		t.logError(at, "短信过滤", msg)
	}
}

// BotUnauthorized records a bot interaction from a non-admin user.
func (t *Tracker) BotUnauthorized(at time.Time) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.st.BotUnauthorized++
	t.st.LastBotUnauthorized = at
}

// Error adds an entry to the recent-error log.
func (t *Tracker) Error(at time.Time, component, msg string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.logError(at, component, msg)
}

// Snapshot returns a copy of the current state.
func (t *Tracker) Snapshot() State {
	t.mu.Lock()
	defer t.mu.Unlock()
	st := t.st
	if st.Bot != nil {
		b := *st.Bot
		st.Bot = &b
	}
	return st
}

// RecentErrors returns up to n most recent errors, newest first.
func (t *Tracker) RecentErrors(n int) []ErrorEvent {
	t.mu.Lock()
	defer t.mu.Unlock()
	var out []ErrorEvent
	for i := len(t.errors) - 1; i >= 0 && len(out) < n; i-- {
		out = append(out, t.errors[i])
	}
	return out
}
