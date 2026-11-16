package store

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"nekopost/internal/model"
)

// HeldMessage is a message waiting for the content filter.
type HeldMessage struct {
	ID       string
	SourceID string
	Kind     string
	Title    string
	Body     string
}

// HeldMessages returns messages whose deliveries are held for the content
// filter, oldest first.
func (s *Store) HeldMessages(ctx context.Context, limit int) ([]HeldMessage, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id, source_id, kind FROM messages
		WHERE status = 'classifying' ORDER BY received_at, id LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []HeldMessage
	for rows.Next() {
		var h HeldMessage
		if err := rows.Scan(&h.ID, &h.SourceID, &h.Kind); err != nil {
			return nil, err
		}
		title, body, _, ok, err := s.readBody(ctx, h.ID)
		if err != nil {
			return nil, err
		}
		if ok {
			h.Title, h.Body = title, body
		}
		out = append(out, h)
	}
	return out, rows.Err()
}

// ResolveHeld records the filter's verdict: drop=false releases the held
// deliveries to the outbox, drop=true marks the message filtered.
func (s *Store) ResolveHeld(ctx context.Context, msgID string, cat model.Category, drop bool, now time.Time) (model.Status, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, "UPDATE messages SET category = ? WHERE id = ?", string(cat), msgID); err != nil {
		return "", err
	}
	state := model.DeliveryPending
	if drop {
		state = model.DeliveryFiltered
	}
	if _, err := tx.ExecContext(ctx, "UPDATE deliveries SET state = ?, next_attempt_at = ? WHERE message_id = ? AND state = 'held'",
		string(state), now.UnixMilli(), msgID); err != nil {
		return "", err
	}
	if cat == model.CatCode {
		var received int64
		if err := tx.QueryRowContext(ctx, "SELECT received_at FROM messages WHERE id = ?", msgID).Scan(&received); err != nil {
			return "", err
		}
		exp := s.expiresAt(time.UnixMilli(received), true, string(cat))
		if _, err := tx.ExecContext(ctx, `UPDATE bodies SET expires_at = ?
			WHERE message_id = ? AND expires_at > ?`, exp.UnixMilli(), msgID, exp.UnixMilli()); err != nil {
			return "", err
		}
	}
	st, err := s.updateAggregate(ctx, tx, msgID, now)
	if err != nil {
		return "", err
	}
	return st, tx.Commit()
}

// ReleaseAllHeld releases every held delivery unclassified, e.g. when the
// filter has been switched off while messages were waiting for it.
func (s *Store) ReleaseAllHeld(ctx context.Context, now time.Time) (int, error) {
	held, err := s.HeldMessages(ctx, 1_000_000)
	if err != nil {
		return 0, err
	}
	for _, h := range held {
		if _, err := s.ResolveHeld(ctx, h.ID, "", false, now); err != nil {
			return 0, err
		}
	}
	return len(held), nil
}

// FilteredMessage is a filtered message that can still be rescued.
type FilteredMessage struct {
	ID         string
	SourceID   string
	Category   model.Category
	ReceivedAt time.Time
	Title      string
	Body       string
}

// FilteredMessages lists rescuable filtered messages, newest first, and the
// total number of them.
func (s *Store) FilteredMessages(ctx context.Context, offset, limit int) ([]FilteredMessage, int, error) {
	var total int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM messages m JOIN bodies b ON b.message_id = m.id
		WHERE m.status = 'filtered'`).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.db.QueryContext(ctx, `SELECT m.id, m.source_id, m.category, m.received_at
		FROM messages m JOIN bodies b ON b.message_id = m.id
		WHERE m.status = 'filtered' ORDER BY m.received_at DESC, m.id DESC LIMIT ? OFFSET ?`, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []FilteredMessage
	for rows.Next() {
		var f FilteredMessage
		var cat string
		var at int64
		if err := rows.Scan(&f.ID, &f.SourceID, &cat, &at); err != nil {
			return nil, 0, err
		}
		f.Category = model.Category(cat)
		f.ReceivedAt = time.UnixMilli(at)
		title, body, _, _, err := s.readBody(ctx, f.ID)
		if err != nil {
			return nil, 0, err
		}
		f.Title, f.Body = title, body
		out = append(out, f)
	}
	return out, total, rows.Err()
}

// ErrNotRescuable is returned when a message is not (or no longer) a
// filtered message with its content still available.
var ErrNotRescuable = errors.New("message is not rescuable")

// RescueFiltered queues a filtered message for normal delivery. The
// deliveries' age restarts now, so the full retry period applies.
func (s *Store) RescueFiltered(ctx context.Context, msgID string, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var status string
	var hasBody int
	err = tx.QueryRowContext(ctx, `SELECT m.status, b.message_id IS NOT NULL FROM messages m
		LEFT JOIN bodies b ON b.message_id = m.id WHERE m.id = ?`, msgID).Scan(&status, &hasBody)
	if errors.Is(err, sql.ErrNoRows) || err == nil && (status != string(model.StatusFiltered) || hasBody == 0) {
		return ErrNotRescuable
	}
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE deliveries SET state = 'pending', next_attempt_at = ?, created_at = ?
		WHERE message_id = ? AND state = 'filtered'`, now.UnixMilli(), now.UnixMilli(), msgID); err != nil {
		return err
	}
	if _, err := s.updateAggregate(ctx, tx, msgID, now); err != nil {
		return err
	}
	return tx.Commit()
}
