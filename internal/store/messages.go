package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"

	"nekopost/internal/model"
)

// NewDelivery describes one routing target for a new message.
type NewDelivery struct {
	Channel string
	Target  string
	Silent  bool
	Held    bool // wait for the content filter before delivering
}

// InsertResult reports what happened to a submitted message.
type InsertResult struct {
	ID         string
	Status     model.Status
	Duplicate  bool
	ReceivedAt time.Time
}

// InsertMessage persists a message, its payload and its deliveries in one
// transaction. If the message duplicates an earlier one (same idempotency key,
// or identical content from the same source within dedupeWindow when no key
// is given) nothing is inserted and the earlier message is returned instead.
func (s *Store) InsertMessage(ctx context.Context, m *model.Message, targets []NewDelivery, dedupeWindow time.Duration) (InsertResult, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return InsertResult{}, err
	}
	defer tx.Rollback()

	var dupID string
	var dupStatus string
	var dupReceived int64
	switch {
	case m.IdempotencyKey != "":
		err = tx.QueryRowContext(ctx, "SELECT id, status, received_at FROM messages WHERE source_id = ? AND idem_key = ?",
			m.SourceID, m.IdempotencyKey).Scan(&dupID, &dupStatus, &dupReceived)
	case dedupeWindow > 0:
		err = tx.QueryRowContext(ctx, `SELECT id, status, received_at FROM messages
			WHERE source_id = ? AND content_hash = ? AND idem_key IS NULL AND received_at >= ?
			ORDER BY received_at DESC LIMIT 1`,
			m.SourceID, m.ContentHash, m.ReceivedAt.Add(-dedupeWindow).UnixMilli()).Scan(&dupID, &dupStatus, &dupReceived)
	default:
		err = sql.ErrNoRows
	}
	if err == nil {
		if _, err := tx.ExecContext(ctx, "UPDATE messages SET dup_count = dup_count + 1 WHERE id = ?", dupID); err != nil {
			return InsertResult{}, err
		}
		if err := tx.Commit(); err != nil {
			return InsertResult{}, err
		}
		return InsertResult{ID: dupID, Status: model.Status(dupStatus), Duplicate: true, ReceivedAt: time.UnixMilli(dupReceived)}, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return InsertResult{}, err
	}

	states := make([]model.DeliveryState, len(targets))
	for i, t := range targets {
		states[i] = model.DeliveryPending
		if t.Held {
			states[i] = model.DeliveryHeld
		}
	}
	m.Status = model.AggregateStatus(states)
	var idem any
	if m.IdempotencyKey != "" {
		idem = m.IdempotencyKey
	}
	var finished any
	if m.Status.Terminal() {
		finished = m.ReceivedAt.UnixMilli()
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO messages(id, source_id, idem_key, content_hash, kind, priority,
		title_len, body_len, occurred_at, received_at, status, finished_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		m.ID, m.SourceID, idem, m.ContentHash, m.Kind, string(m.Priority),
		len([]rune(m.Title)), len([]rune(m.Body)), ms(m.OccurredAt), m.ReceivedAt.UnixMilli(), string(m.Status), finished); err != nil {
		return InsertResult{}, err
	}
	if len(targets) > 0 {
		meta := ""
		if len(m.Meta) > 0 {
			b, _ := json.Marshal(m.Meta)
			meta = string(b)
		}
		if _, err := tx.ExecContext(ctx, "INSERT INTO payloads(message_id, title, body, meta) VALUES(?, ?, ?, ?)",
			m.ID, m.Title, m.Body, meta); err != nil {
			return InsertResult{}, err
		}
	}
	for i, t := range targets {
		if _, err := tx.ExecContext(ctx, `INSERT INTO deliveries(message_id, channel, target, silent, state, next_attempt_at, created_at)
			VALUES(?, ?, ?, ?, ?, ?, ?)`, m.ID, t.Channel, t.Target, boolInt(t.Silent), string(states[i]),
			m.ReceivedAt.UnixMilli(), m.ReceivedAt.UnixMilli()); err != nil {
			return InsertResult{}, err
		}
	}
	if err := tx.Commit(); err != nil {
		return InsertResult{}, err
	}
	return InsertResult{ID: m.ID, Status: m.Status, ReceivedAt: m.ReceivedAt}, nil
}

// MessageInfo is a message's metadata (no payload) plus its deliveries.
type MessageInfo struct {
	Message    model.Message
	Deliveries []model.Delivery
}

// GetMessage returns message metadata and deliveries; the payload is never
// returned because it may already be purged and is not needed for status.
func (s *Store) GetMessage(ctx context.Context, id string) (MessageInfo, error) {
	var info MessageInfo
	m := &info.Message
	var idem sql.NullString
	var occurred, finished sql.NullInt64
	var received int64
	var prio, status, cat string
	err := s.db.QueryRowContext(ctx, `SELECT id, source_id, idem_key, kind, priority, occurred_at, received_at,
		status, dup_count, finished_at, category FROM messages WHERE id = ?`, id).
		Scan(&m.ID, &m.SourceID, &idem, &m.Kind, &prio, &occurred, &received, &status, &m.DupCount, &finished, &cat)
	if errors.Is(err, sql.ErrNoRows) {
		return info, ErrNotFound
	}
	if err != nil {
		return info, err
	}
	m.IdempotencyKey = idem.String
	m.Category = model.Category(cat)
	m.Priority = model.Priority(prio)
	m.Status = model.Status(status)
	m.OccurredAt = fromMs(occurred)
	m.ReceivedAt = time.UnixMilli(received)
	m.FinishedAt = fromMs(finished)
	rows, err := s.db.QueryContext(ctx, "SELECT "+deliveryCols+" FROM deliveries WHERE message_id = ? ORDER BY id", id)
	if err != nil {
		return info, err
	}
	defer rows.Close()
	for rows.Next() {
		d, err := scanDelivery(rows)
		if err != nil {
			return info, err
		}
		info.Deliveries = append(info.Deliveries, d)
	}
	return info, rows.Err()
}

// MessageStatus returns only the aggregate status of a message.
func (s *Store) MessageStatus(ctx context.Context, id string) (model.Status, error) {
	var st string
	err := s.db.QueryRowContext(ctx, "SELECT status FROM messages WHERE id = ?", id).Scan(&st)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotFound
	}
	return model.Status(st), err
}

const deliveryCols = `id, message_id, channel, target, silent, state, attempts, parts_total, parts_sent,
	next_attempt_at, last_attempt_at, delivered_at, last_error, uncertain, created_at`

func scanDelivery(row interface{ Scan(...any) error }) (model.Delivery, error) {
	var d model.Delivery
	var silent, uncertain int
	var state string
	var next, created int64
	var last, delivered sql.NullInt64
	err := row.Scan(&d.ID, &d.MessageID, &d.Channel, &d.Target, &silent, &state, &d.Attempts, &d.PartsTotal,
		&d.PartsSent, &next, &last, &delivered, &d.LastError, &uncertain, &created)
	d.Silent = silent == 1
	d.Uncertain = uncertain == 1
	d.State = model.DeliveryState(state)
	d.NextAttemptAt = time.UnixMilli(next)
	d.LastAttemptAt = fromMs(last)
	d.DeliveredAt = fromMs(delivered)
	d.CreatedAt = time.UnixMilli(created)
	return d, err
}

// Job is everything a channel needs to perform one delivery attempt.
type Job struct {
	Delivery model.Delivery
	Message  model.Message
	Source   model.Source // Name/Icon may be empty if the source was deleted
	// PayloadMissing is set if the payload is unexpectedly gone; the job
	// cannot be delivered and must be failed.
	PayloadMissing bool
}

// DueJobs returns deliveries whose next attempt is due.
func (s *Store) DueJobs(ctx context.Context, now time.Time, limit int) ([]Job, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT d.id, d.message_id, d.channel, d.target, d.silent, d.state, d.attempts,
		d.parts_total, d.parts_sent, d.next_attempt_at, d.last_attempt_at, d.delivered_at, d.last_error, d.uncertain, d.created_at,
		m.source_id, m.kind, m.priority, m.occurred_at, m.received_at, m.category,
		COALESCE(p.title, ''), COALESCE(p.body, ''), COALESCE(p.meta, ''), p.message_id IS NOT NULL,
		COALESCE(s.name, ''), COALESCE(s.icon, '')
		FROM deliveries d
		JOIN messages m ON m.id = d.message_id
		LEFT JOIN payloads p ON p.message_id = d.message_id
		LEFT JOIN sources s ON s.id = m.source_id
		WHERE d.state IN ('pending', 'retrying') AND d.next_attempt_at <= ?
		ORDER BY d.next_attempt_at, d.id LIMIT ?`, now.UnixMilli(), limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Job
	for rows.Next() {
		var j Job
		d := &j.Delivery
		var silent, uncertain, hasPayload int
		var state, prio, meta, cat string
		var next, created, received int64
		var last, delivered, occurred sql.NullInt64
		if err := rows.Scan(&d.ID, &d.MessageID, &d.Channel, &d.Target, &silent, &state, &d.Attempts,
			&d.PartsTotal, &d.PartsSent, &next, &last, &delivered, &d.LastError, &uncertain, &created,
			&j.Message.SourceID, &j.Message.Kind, &prio, &occurred, &received, &cat,
			&j.Message.Title, &j.Message.Body, &meta, &hasPayload,
			&j.Source.Name, &j.Source.Icon); err != nil {
			return nil, err
		}
		d.Silent = silent == 1
		d.Uncertain = uncertain == 1
		d.State = model.DeliveryState(state)
		d.NextAttemptAt = time.UnixMilli(next)
		d.LastAttemptAt = fromMs(last)
		d.DeliveredAt = fromMs(delivered)
		d.CreatedAt = time.UnixMilli(created)
		j.Message.ID = d.MessageID
		j.Message.Priority = model.Priority(prio)
		j.Message.Category = model.Category(cat)
		j.Message.OccurredAt = fromMs(occurred)
		j.Message.ReceivedAt = time.UnixMilli(received)
		j.Source.ID = j.Message.SourceID
		if meta != "" {
			_ = json.Unmarshal([]byte(meta), &j.Message.Meta)
		}
		j.PayloadMissing = hasPayload == 0
		out = append(out, j)
	}
	return out, rows.Err()
}

// NextDueAt returns when the earliest scheduled delivery becomes due.
func (s *Store) NextDueAt(ctx context.Context) (time.Time, bool, error) {
	var next sql.NullInt64
	err := s.db.QueryRowContext(ctx, "SELECT MIN(next_attempt_at) FROM deliveries WHERE state IN ('pending', 'retrying')").Scan(&next)
	if err != nil || !next.Valid {
		return time.Time{}, false, err
	}
	return time.UnixMilli(next.Int64), true, nil
}

// ClaimDelivery marks a delivery as in flight. Crash recovery relies on this
// state: anything still "sending" at startup may or may not have been sent.
func (s *Store) ClaimDelivery(ctx context.Context, id int64, msgID string, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, "UPDATE deliveries SET state = 'sending', attempts = attempts + 1, last_attempt_at = ? WHERE id = ?",
		now.UnixMilli(), id); err != nil {
		return err
	}
	if _, err := updateAggregate(ctx, tx, msgID, now); err != nil {
		return err
	}
	return tx.Commit()
}

// SetPartsProgress records how many parts of a split message were sent, so a
// retry resumes after the last confirmed part instead of resending everything.
func (s *Store) SetPartsProgress(ctx context.Context, id int64, total, sent int) error {
	_, err := s.db.ExecContext(ctx, "UPDATE deliveries SET parts_total = ?, parts_sent = ? WHERE id = ?", total, sent, id)
	return err
}

// AttemptResult is the outcome of one delivery attempt as persisted.
type AttemptResult struct {
	State         model.DeliveryState
	NextAttemptAt time.Time
	LastError     string
	Uncertain     bool
	PartsTotal    int
	PartsSent     int
}

// FinishAttempt stores the outcome of an attempt, updates the message's
// aggregate status, and purges the payload once the message is finished.
func (s *Store) FinishAttempt(ctx context.Context, id int64, msgID string, r AttemptResult, now time.Time) (model.Status, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	var delivered any
	if r.State == model.DeliveryDelivered {
		delivered = now.UnixMilli()
	}
	next := r.NextAttemptAt
	if next.IsZero() {
		next = now
	}
	if _, err := tx.ExecContext(ctx, `UPDATE deliveries SET state = ?, next_attempt_at = ?, last_error = ?,
		uncertain = MAX(uncertain, ?), parts_total = ?, parts_sent = ?, delivered_at = COALESCE(?, delivered_at) WHERE id = ?`,
		string(r.State), next.UnixMilli(), r.LastError, boolInt(r.Uncertain), r.PartsTotal, r.PartsSent, delivered, id); err != nil {
		return "", err
	}
	st, err := updateAggregate(ctx, tx, msgID, now)
	if err != nil {
		return "", err
	}
	return st, tx.Commit()
}

func updateAggregate(ctx context.Context, tx *sql.Tx, msgID string, now time.Time) (model.Status, error) {
	rows, err := tx.QueryContext(ctx, "SELECT state FROM deliveries WHERE message_id = ?", msgID)
	if err != nil {
		return "", err
	}
	var states []model.DeliveryState
	for rows.Next() {
		var st string
		if err := rows.Scan(&st); err != nil {
			rows.Close()
			return "", err
		}
		states = append(states, model.DeliveryState(st))
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return "", err
	}
	status := model.AggregateStatus(states)
	var finished any
	if status.Terminal() {
		finished = now.UnixMilli()
		// Filtered messages keep their payload for a few days so they can be
		// rescued; PurgeFinishedPayloads removes it afterwards.
		if status != model.StatusFiltered {
			if _, err := tx.ExecContext(ctx, "DELETE FROM payloads WHERE message_id = ?", msgID); err != nil {
				return "", err
			}
		}
	}
	if _, err := tx.ExecContext(ctx, "UPDATE messages SET status = ?, finished_at = ? WHERE id = ?", string(status), finished, msgID); err != nil {
		return "", err
	}
	return status, nil
}

// RecoverInFlight handles deliveries left "sending" by a crash or kill: the
// attempt may have reached Telegram, so it is flagged uncertain and retried
// immediately (at-least-once).
func (s *Store) RecoverInFlight(ctx context.Context, now time.Time) (int, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx, "SELECT DISTINCT message_id FROM deliveries WHERE state = 'sending'")
	if err != nil {
		return 0, err
	}
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return 0, err
		}
		ids = append(ids, id)
	}
	rows.Close()
	res, err := tx.ExecContext(ctx, `UPDATE deliveries SET state = 'retrying', uncertain = 1, next_attempt_at = ?,
		last_error = 'interrupted: 服务在投递过程中重启，结果未知' WHERE state = 'sending'`, now.UnixMilli())
	if err != nil {
		return 0, err
	}
	for _, id := range ids {
		if _, err := updateAggregate(ctx, tx, id, now); err != nil {
			return 0, err
		}
	}
	n, _ := res.RowsAffected()
	return int(n), tx.Commit()
}

// PurgeExpired deletes message metadata received before cutoff (deliveries and
// payloads cascade). Unfinished messages are kept.
func (s *Store) PurgeExpired(ctx context.Context, cutoff time.Time) (int64, error) {
	res, err := s.db.ExecContext(ctx, "DELETE FROM messages WHERE received_at < ? AND finished_at IS NOT NULL", cutoff.UnixMilli())
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// PurgeFinishedPayloads removes any payload whose message is finished, except
// filtered messages that finished after filteredCutoff (still rescuable). For
// delivered messages the worker already does this on completion; this is a
// safety net.
func (s *Store) PurgeFinishedPayloads(ctx context.Context, filteredCutoff time.Time) (int64, error) {
	res, err := s.db.ExecContext(ctx, `DELETE FROM payloads WHERE message_id IN (SELECT id FROM messages
		WHERE finished_at IS NOT NULL AND (status != 'filtered' OR finished_at < ?))`, filteredCutoff.UnixMilli())
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}
