package store

import (
	"context"
	"database/sql"
	"time"

	"nekopost/internal/model"
)

// Counts are message counts by outcome. Each accepted message is counted
// exactly once (duplicate submissions are counted separately in Duplicates).
type Counts struct {
	Received   int // unique accepted messages
	Delivered  int
	Failed     int
	Uncertain  int
	Partial    int
	NotRouted  int
	Filtered   int // dropped by the content filter
	InProgress int // classifying, queued, sending or retrying
	Duplicates int // repeated submissions that were ignored
}

func (c *Counts) add(o Counts) {
	c.Received += o.Received
	c.Delivered += o.Delivered
	c.Failed += o.Failed
	c.Uncertain += o.Uncertain
	c.Partial += o.Partial
	c.NotRouted += o.NotRouted
	c.Filtered += o.Filtered
	c.InProgress += o.InProgress
	c.Duplicates += o.Duplicates
}

// SourceCounts are per-source counts.
type SourceCounts struct {
	SourceID       string
	Counts         Counts
	LastReceivedAt time.Time
}

// Stats aggregates messages received in [From, To).
type Stats struct {
	From, To      time.Time
	Total         Counts
	BySource      []SourceCounts
	LastMessageAt time.Time
}

// Stats computes message statistics for messages received in [from, to).
func (s *Store) Stats(ctx context.Context, from, to time.Time) (Stats, error) {
	st := Stats{From: from, To: to}
	rows, err := s.db.QueryContext(ctx, `SELECT source_id, COUNT(*),
		SUM(status = 'delivered'), SUM(status = 'failed'), SUM(status = 'uncertain'), SUM(status = 'partial'),
		SUM(status = 'not_routed'), SUM(status = 'filtered'), SUM(status IN ('classifying', 'queued', 'sending', 'retrying')),
		SUM(dup_count), MAX(received_at)
		FROM messages WHERE received_at >= ? AND received_at < ?
		GROUP BY source_id ORDER BY COUNT(*) DESC, source_id`, from.UnixMilli(), to.UnixMilli())
	if err != nil {
		return st, err
	}
	defer rows.Close()
	for rows.Next() {
		var sc SourceCounts
		c := &sc.Counts
		var last int64
		if err := rows.Scan(&sc.SourceID, &c.Received, &c.Delivered, &c.Failed, &c.Uncertain, &c.Partial,
			&c.NotRouted, &c.Filtered, &c.InProgress, &c.Duplicates, &last); err != nil {
			return st, err
		}
		sc.LastReceivedAt = time.UnixMilli(last)
		if sc.LastReceivedAt.After(st.LastMessageAt) {
			st.LastMessageAt = sc.LastReceivedAt
		}
		st.Total.add(sc.Counts)
		st.BySource = append(st.BySource, sc)
	}
	return st, rows.Err()
}

// QueueInfo describes messages not yet finished.
type QueueInfo struct {
	InProgress      int
	Retrying        int
	OldestPendingAt time.Time
}

// Queue reports the current backlog.
func (s *Store) Queue(ctx context.Context) (QueueInfo, error) {
	var q QueueInfo
	var retrying sql.NullInt64
	var oldest sql.NullInt64
	err := s.db.QueryRowContext(ctx, `SELECT COUNT(*), SUM(status = 'retrying'), MIN(received_at)
		FROM messages WHERE status IN ('classifying', 'queued', 'sending', 'retrying')`).Scan(&q.InProgress, &retrying, &oldest)
	q.Retrying = int(retrying.Int64)
	q.OldestPendingAt = fromMs(oldest)
	return q, err
}

// LastMessage is the most recent message of a source.
type LastMessage struct {
	At     time.Time
	Status model.Status
}

// LastMessages returns each source's most recent message.
func (s *Store) LastMessages(ctx context.Context) (map[string]LastMessage, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT m.source_id, m.received_at, m.status FROM messages m
		JOIN (SELECT source_id, MAX(received_at) AS mx FROM messages GROUP BY source_id) l
		ON l.source_id = m.source_id AND l.mx = m.received_at`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]LastMessage{}
	for rows.Next() {
		var id, st string
		var at int64
		if err := rows.Scan(&id, &at, &st); err != nil {
			return nil, err
		}
		out[id] = LastMessage{At: time.UnixMilli(at), Status: model.Status(st)}
	}
	return out, rows.Err()
}

// RecentDelivery summarises one recent delivery attempt (no content).
type RecentDelivery struct {
	MessageID string
	SourceID  string
	State     model.DeliveryState
	LastError string
	At        time.Time
	Attempts  int
	Uncertain bool
}

// RecentDeliveries returns the most recently attempted deliveries.
func (s *Store) RecentDeliveries(ctx context.Context, limit int) ([]RecentDelivery, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT d.message_id, m.source_id, d.state, d.last_error, d.last_attempt_at,
		d.attempts, d.uncertain FROM deliveries d JOIN messages m ON m.id = d.message_id
		WHERE d.last_attempt_at IS NOT NULL ORDER BY d.last_attempt_at DESC, d.id DESC LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []RecentDelivery
	for rows.Next() {
		var r RecentDelivery
		var st string
		var at int64
		var unc int
		if err := rows.Scan(&r.MessageID, &r.SourceID, &st, &r.LastError, &at, &r.Attempts, &unc); err != nil {
			return nil, err
		}
		r.State = model.DeliveryState(st)
		r.At = time.UnixMilli(at)
		r.Uncertain = unc == 1
		out = append(out, r)
	}
	return out, rows.Err()
}

// DeliveryHistory is used to seed in-memory health state after a restart.
type DeliveryHistory struct {
	LastDelivered  time.Time
	LastProblem    time.Time // last attempt that ended failed, uncertain or in retry
	LastProblemErr string
}

// LastDeliveryHistory reads the latest success and problem times.
func (s *Store) LastDeliveryHistory(ctx context.Context) (DeliveryHistory, error) {
	var h DeliveryHistory
	var ok sql.NullInt64
	if err := s.db.QueryRowContext(ctx, "SELECT MAX(delivered_at) FROM deliveries").Scan(&ok); err != nil {
		return h, err
	}
	h.LastDelivered = fromMs(ok)
	var at int64
	err := s.db.QueryRowContext(ctx, `SELECT last_attempt_at, last_error FROM deliveries
		WHERE state IN ('failed', 'uncertain', 'retrying') AND last_attempt_at IS NOT NULL
		ORDER BY last_attempt_at DESC LIMIT 1`).Scan(&at, &h.LastProblemErr)
	if err == sql.ErrNoRows {
		return h, nil
	}
	if err != nil {
		return h, err
	}
	h.LastProblem = time.UnixMilli(at)
	return h, nil
}

// OldestRetained returns the receive time of the oldest retained message.
func (s *Store) OldestRetained(ctx context.Context) (time.Time, error) {
	var v sql.NullInt64
	err := s.db.QueryRowContext(ctx, "SELECT MIN(received_at) FROM messages").Scan(&v)
	return fromMs(v), err
}
