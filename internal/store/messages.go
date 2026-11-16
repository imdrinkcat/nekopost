package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"

	"nekopost/internal/model"
	"nekopost/internal/sealbox"
)

// NewDelivery describes one routing target for a new message.
type NewDelivery struct {
	Aux     bool
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
	var idemStored any
	if m.IdempotencyKey != "" {
		idemStored = sealbox.IdemHash(s.box.Dedupe, m.IdempotencyKey)
	}
	switch {
	case m.IdempotencyKey != "":
		err = tx.QueryRowContext(ctx, "SELECT id, status, received_at FROM messages WHERE source_id = ? AND idem_key = ?",
			m.SourceID, idemStored).Scan(&dupID, &dupStatus, &dupReceived)
	case dedupeWindow > 0 && m.ContentHash != "":
		err = tx.QueryRowContext(ctx, `SELECT id, status, received_at FROM messages
			WHERE source_id = ? AND content_hash = ? AND content_hash != '' AND idem_key IS NULL AND received_at >= ?
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

	if s.enablePush {
		push, err := pushTargets(ctx, tx, m.OriginDevice, m.ReceivedAt, (m.NeedsClassification || len(targets) > 0 && targets[0].Held))
		if err != nil {
			return InsertResult{}, err
		}
		targets = append(targets, push...)
	}
	states := make([]model.DeliveryState, len(targets))
	var primary []model.DeliveryState
	for i, t := range targets {
		states[i] = model.DeliveryPending
		if t.Held {
			states[i] = model.DeliveryHeld
		}
		if !t.Aux {
			primary = append(primary, states[i])
		}
	}
	m.Status = model.AggregateStatus(primary)
	if len(primary) == 0 && len(states) > 0 && states[0] == model.DeliveryHeld {
		m.Status = model.StatusClassifying
	}
	var finished any
	if m.Status.Terminal() {
		finished = m.ReceivedAt.UnixMilli()
	}
	hasCode, late := s.classifyPlain(m.Title, m.Body, m.ReceivedAt, m.OccurredAt)
	seq, err := nextSeq(ctx, tx)
	if err != nil {
		return InsertResult{}, err
	}
	bodyState := "none"
	if len(targets) > 0 {
		bodyState = "stored"
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO messages(id, source_id, idem_key, content_hash, kind, priority,
		title_len, body_len, occurred_at, received_at, status, finished_at, seq, body_state, has_code, late, origin_device)
		VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULLIF(?,''))`,
		m.ID, m.SourceID, idemStored, m.ContentHash, m.Kind, string(m.Priority),
		len([]rune(m.Title)), len([]rune(m.Body)), ms(m.OccurredAt), m.ReceivedAt.UnixMilli(), string(m.Status), finished,
		seq, bodyState, boolInt(hasCode), late, m.OriginDevice); err != nil {
		return InsertResult{}, err
	}
	if len(targets) > 0 {
		exp := s.expiresAt(m.ReceivedAt, hasCode, string(m.Category))
		if s.bodyRetention == 0 {
			keep := s.filteredKeep
			if keep <= 0 {
				keep = 7 * 24 * time.Hour
			}
			exp = m.ReceivedAt.Add(keep)
		}
		if err := s.putBody(ctx, tx, m.ID, m.ReceivedAt, m.Title, m.Body, m.Meta, exp); err != nil {
			return InsertResult{}, err
		}
	}
	for i, t := range targets {
		if _, err := tx.ExecContext(ctx, `INSERT INTO deliveries(message_id, channel, target, silent, state, next_attempt_at, created_at, aux)
			VALUES(?, ?, ?, ?, ?, ?, ?, ?)`, m.ID, t.Channel, t.Target, boolInt(t.Silent), string(states[i]),
			m.ReceivedAt.UnixMilli(), m.ReceivedAt.UnixMilli(), boolInt(t.Aux)); err != nil {
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
	return getMessage(ctx, s.db, id)
}

func getMessage(ctx context.Context, db interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
	QueryContext(context.Context, string, ...any) (*sql.Rows, error)
}, id string) (MessageInfo, error) {
	var info MessageInfo
	m := &info.Message
	var idem sql.NullString
	var occurred, finished sql.NullInt64
	var received int64
	var prio, status, cat string
	err := db.QueryRowContext(ctx, `SELECT id, source_id, idem_key, kind, priority, occurred_at, received_at,
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
	rows, err := db.QueryContext(ctx, "SELECT "+deliveryCols+" FROM deliveries WHERE message_id = ? ORDER BY id", id)
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
	next_attempt_at, last_attempt_at, delivered_at, last_error, uncertain, created_at, aux, acked_at, tg_message_ids`

func scanDelivery(row interface{ Scan(...any) error }) (model.Delivery, error) {
	var d model.Delivery
	var silent, uncertain, aux int
	var state, tgIDs string
	var next, created int64
	var last, delivered, acked sql.NullInt64
	err := row.Scan(&d.ID, &d.MessageID, &d.Channel, &d.Target, &silent, &state, &d.Attempts, &d.PartsTotal,
		&d.PartsSent, &next, &last, &delivered, &d.LastError, &uncertain, &created, &aux, &acked, &tgIDs)
	d.Silent = silent == 1
	d.Uncertain = uncertain == 1
	d.Aux = aux == 1
	d.State = model.DeliveryState(state)
	d.NextAttemptAt = time.UnixMilli(next)
	d.LastAttemptAt = fromMs(last)
	d.DeliveredAt = fromMs(delivered)
	d.AckedAt = fromMs(acked)
	d.CreatedAt = time.UnixMilli(created)
	if tgIDs != "" {
		_ = json.Unmarshal([]byte(tgIDs), &d.TGMessageIDs)
	}
	return d, err
}

// Job is everything a channel needs to perform one delivery attempt.
type Job struct {
	Seq      int64
	Delivery model.Delivery
	Message  model.Message
	Source   model.Source // Name/Icon may be empty if the source was deleted
	// PayloadMissing is set if the payload is unexpectedly gone; the job
	// cannot be delivered and must be failed.
	PayloadMissing bool
}

// DueJobs returns deliveries whose next attempt is due.
// An empty channel returns every channel.
func (s *Store) DueJobs(ctx context.Context, now time.Time, channel string, limit int) ([]Job, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT d.id, d.message_id, d.channel, d.target, d.silent, d.state, d.attempts,
		d.parts_total, d.parts_sent, d.next_attempt_at, d.last_attempt_at, d.delivered_at, d.last_error, d.uncertain, d.created_at,
		d.aux, d.acked_at, d.tg_message_ids,
		m.source_id, m.kind, m.priority, m.occurred_at, m.received_at, m.category, m.seq,
		COALESCE(s.name, ''), COALESCE(s.icon, '')
		FROM deliveries d
		JOIN messages m ON m.id = d.message_id
		LEFT JOIN sources s ON s.id = m.source_id
		WHERE d.state IN ('pending', 'retrying') AND d.next_attempt_at <= ?
		  AND (? = '' OR d.channel = ?)
		ORDER BY d.next_attempt_at, d.id LIMIT ?`, now.UnixMilli(), channel, channel, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Job
	for rows.Next() {
		var j Job
		d := &j.Delivery
		var silent, uncertain, aux int
		var state, prio, cat, tgIDs string
		var next, created, received int64
		var last, delivered, occurred, acked sql.NullInt64
		if err := rows.Scan(&d.ID, &d.MessageID, &d.Channel, &d.Target, &silent, &state, &d.Attempts,
			&d.PartsTotal, &d.PartsSent, &next, &last, &delivered, &d.LastError, &uncertain, &created,
			&aux, &acked, &tgIDs,
			&j.Message.SourceID, &j.Message.Kind, &prio, &occurred, &received, &cat, &j.Seq,
			&j.Source.Name, &j.Source.Icon); err != nil {
			return nil, err
		}
		d.Silent = silent == 1
		d.Uncertain = uncertain == 1
		d.Aux = aux == 1
		d.State = model.DeliveryState(state)
		d.NextAttemptAt = time.UnixMilli(next)
		d.LastAttemptAt = fromMs(last)
		d.DeliveredAt = fromMs(delivered)
		d.AckedAt = fromMs(acked)
		d.CreatedAt = time.UnixMilli(created)
		if tgIDs != "" {
			_ = json.Unmarshal([]byte(tgIDs), &d.TGMessageIDs)
		}
		j.Message.ID = d.MessageID
		j.Message.Priority = model.Priority(prio)
		j.Message.Category = model.Category(cat)
		j.Message.OccurredAt = fromMs(occurred)
		j.Message.ReceivedAt = time.UnixMilli(received)
		j.Source.ID = j.Message.SourceID
		if !d.Aux {
			title, body, meta, ok, err := s.readBody(ctx, d.MessageID)
			if err != nil {
				return nil, err
			}
			j.Message.Title, j.Message.Body, j.Message.Meta = title, body, meta
			j.PayloadMissing = !ok
		}
		out = append(out, j)
	}
	return out, rows.Err()
}

// NextDueAt returns when the earliest scheduled delivery becomes due.
func (s *Store) NextDueAt(ctx context.Context, channel string) (time.Time, bool, error) {
	var next sql.NullInt64
	err := s.db.QueryRowContext(ctx, `SELECT MIN(next_attempt_at) FROM deliveries
		WHERE state IN ('pending', 'retrying') AND (? = '' OR channel = ?)`, channel, channel).Scan(&next)
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
	res, err := tx.ExecContext(ctx, `UPDATE deliveries SET state = 'sending', attempts = attempts + 1, last_attempt_at = ?
		WHERE id = ? AND message_id = ? AND state IN ('pending', 'retrying') AND next_attempt_at <= ?`,
		now.UnixMilli(), id, msgID, now.UnixMilli())
	if err != nil {
		return err
	}
	if n, err := res.RowsAffected(); err != nil {
		return err
	} else if n == 0 {
		return ErrNotFound
	}
	if _, err := s.updateAggregate(ctx, tx, msgID, now); err != nil {
		return err
	}
	return tx.Commit()
}

// SetPartsProgress records the split-message cursor and Telegram message ID
// together so a restart cannot preserve the cursor while losing the ID.
func (s *Store) SetPartsProgress(ctx context.Context, id int64, msgID string, total, sent int, telegramID int64) error {
	_, err := s.db.ExecContext(ctx, `UPDATE deliveries SET parts_total = ?, parts_sent = ?,
		tg_message_ids = CASE WHEN ? = 0 THEN tg_message_ids
			ELSE json_insert(COALESCE(NULLIF(tg_message_ids, ''), '[]'), '$[#]', ?) END
		WHERE id = ? AND message_id = ? AND state = 'sending'`, total, sent, telegramID, telegramID, id, msgID)
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
	res, err := tx.ExecContext(ctx, `UPDATE deliveries SET state = ?, next_attempt_at = ?, last_error = ?,
		uncertain = MAX(uncertain, ?), parts_total = ?, parts_sent = ?, delivered_at = COALESCE(?, delivered_at)
		WHERE id = ? AND message_id = ?`,
		string(r.State), next.UnixMilli(), r.LastError, boolInt(r.Uncertain), r.PartsTotal, r.PartsSent, delivered, id, msgID)
	if err != nil {
		return "", err
	}
	if n, err := res.RowsAffected(); err != nil {
		return "", err
	} else if n == 0 {
		return "", ErrNotFound
	}
	var aux bool
	if err := tx.QueryRowContext(ctx, "SELECT aux FROM deliveries WHERE id=? AND message_id=?", id, msgID).Scan(&aux); err != nil {
		return "", err
	}
	if aux {
		seq, err := nextSeq(ctx, tx)
		if err != nil {
			return "", err
		}
		var status string
		if err := tx.QueryRowContext(ctx, "UPDATE messages SET seq=? WHERE id=? RETURNING status", seq, msgID).Scan(&status); err != nil {
			return "", err
		}
		return model.Status(status), tx.Commit()
	}
	st, err := s.updateAggregate(ctx, tx, msgID, now)
	if err != nil {
		return "", err
	}
	return st, tx.Commit()
}

func (s *Store) updateAggregate(ctx context.Context, tx *sql.Tx, msgID string, now time.Time) (model.Status, error) {
	rows, err := tx.QueryContext(ctx, "SELECT state FROM deliveries WHERE message_id = ? AND aux = 0", msgID)
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
	if len(states) == 0 {
		var filtered bool
		if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM deliveries
			WHERE message_id=? AND state='filtered')`, msgID).Scan(&filtered); err != nil {
			return "", err
		}
		if filtered {
			status = model.StatusFiltered
		}
	}
	var finished any
	if status.Terminal() {
		finished = now.UnixMilli()
		// V1 mode deletes the body as soon as delivery finishes. Filtered
		// messages stay until ExpireBodies, so they can still be rescued.
		// With a retention period the body stays until it expires.
		if s.bodyRetention == 0 && status != model.StatusFiltered {
			if _, err := tx.ExecContext(ctx, "DELETE FROM bodies WHERE message_id = ?", msgID); err != nil {
				return "", err
			}
			if _, err := tx.ExecContext(ctx, "UPDATE messages SET body_state = 'expired', content_hash = '', idem_key = NULL WHERE id = ?", msgID); err != nil {
				return "", err
			}
		}
	}
	seq, err := nextSeq(ctx, tx)
	if err != nil {
		return "", err
	}
	if _, err := tx.ExecContext(ctx, "UPDATE messages SET status = ?, finished_at = ?, seq = ? WHERE id = ?", string(status), finished, seq, msgID); err != nil {
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
		if _, err := s.updateAggregate(ctx, tx, id, now); err != nil {
			return 0, err
		}
	}
	n, _ := res.RowsAffected()
	return int(n), tx.Commit()
}

// PurgeExpired deletes message metadata received before cutoff.
// Deliveries and bodies cascade. Each deleted id is recorded as a tombstone.
// Unfinished messages are kept.
func (s *Store) PurgeExpired(ctx context.Context, cutoff time.Time) (int64, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx, "SELECT id FROM messages WHERE received_at < ? AND finished_at IS NOT NULL", cutoff.UnixMilli())
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
	if err := rows.Err(); err != nil {
		return 0, err
	}
	now := s.now()
	for _, id := range ids {
		seq, err := nextSeq(ctx, tx)
		if err != nil {
			return 0, err
		}
		if _, err := tx.ExecContext(ctx, "INSERT INTO tombstones(seq, message_id, at) VALUES(?, ?, ?)", seq, id, now.UnixMilli()); err != nil {
			return 0, err
		}
		if _, err := tx.ExecContext(ctx, "DELETE FROM messages WHERE id = ?", id); err != nil {
			return 0, err
		}
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return int64(len(ids)), nil
}

// ExpireBodies removes ciphertext that has reached its retention and whose
// message is already finished. In-flight messages are kept.
func (s *Store) ExpireBodies(ctx context.Context, now time.Time) (int64, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx, `SELECT b.message_id FROM bodies b
		JOIN messages m ON m.id = b.message_id
		WHERE b.expires_at < ? AND m.finished_at IS NOT NULL`, now.UnixMilli())
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
	if err := rows.Err(); err != nil {
		return 0, err
	}
	for _, id := range ids {
		seq, err := nextSeq(ctx, tx)
		if err != nil {
			return 0, err
		}
		if _, err := tx.ExecContext(ctx, "DELETE FROM bodies WHERE message_id = ?", id); err != nil {
			return 0, err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE messages SET body_state = 'expired', content_hash = '', idem_key = NULL, seq = ?
			WHERE id = ?`, seq, id); err != nil {
			return 0, err
		}
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return int64(len(ids)), nil
}

// ClearStaleFingerprints blanks content hashes older than the dedupe horizon
// so a database backup cannot be used to brute-force short codes.
func (s *Store) ClearStaleFingerprints(ctx context.Context, now time.Time, window time.Duration) error {
	if window < time.Hour {
		window = time.Hour
	}
	_, err := s.db.ExecContext(ctx, `UPDATE messages SET content_hash = ''
		WHERE content_hash != '' AND received_at < ?`, now.Add(-window).UnixMilli())
	return err
}

// ShredUnusedDEKs destroys daily keys that nothing references anymore and
// that are older than the body retention.
func (s *Store) ShredUnusedDEKs(ctx context.Context, now time.Time) error {
	horizon := now.Add(-s.bodyRetention - 24*time.Hour)
	if s.bodyRetention == 0 {
		horizon = now.Add(-s.filteredKeep - 24*time.Hour)
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	day := horizon.UTC().Format("2006-01-02")
	const unused = "day < ? AND day NOT IN (SELECT key_day FROM bodies)"
	if _, err := tx.ExecContext(ctx, "UPDATE data_keys SET wrapped = zeroblob(length(wrapped)) WHERE "+unused, day); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "DELETE FROM data_keys WHERE "+unused, day); err != nil {
		return err
	}
	return tx.Commit()
}

// DeleteMessage removes the message and writes a tombstone. It returns the
// delivery records from the same transaction for remote cleanup.
func (s *Store) DeleteMessage(ctx context.Context, id string, now time.Time) (MessageInfo, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return MessageInfo{}, err
	}
	defer tx.Rollback()
	info, err := getMessage(ctx, tx, id)
	if err != nil {
		return MessageInfo{}, err
	}
	for _, d := range info.Deliveries {
		if d.State == model.DeliverySending {
			return MessageInfo{}, ErrDeliveryInFlight
		}
	}
	seq, err := nextSeq(ctx, tx)
	if err != nil {
		return MessageInfo{}, err
	}
	if _, err := tx.ExecContext(ctx, "INSERT INTO tombstones(seq, message_id, at) VALUES(?, ?, ?)", seq, id, now.UnixMilli()); err != nil {
		return MessageInfo{}, err
	}
	if _, err := tx.ExecContext(ctx, "DELETE FROM messages WHERE id = ?", id); err != nil {
		return MessageInfo{}, err
	}
	return info, tx.Commit()
}

func nextSeq(ctx context.Context, tx *sql.Tx) (int64, error) {
	var v int64
	err := tx.QueryRowContext(ctx, `UPDATE kv SET v = CAST(CAST(v AS INTEGER) + 1 AS TEXT) WHERE k = 'seq' RETURNING CAST(v AS INTEGER)`).Scan(&v)
	return v, err
}
