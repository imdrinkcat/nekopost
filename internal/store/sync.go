package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"

	"nekopost/internal/events"
)

type InboxState struct {
	Seq             int64
	Read, HasCode   bool
	BodyState, Late string
	BodyExpires     time.Time
}

func (s *Store) InboxState(ctx context.Context, id string) (InboxState, error) {
	var state InboxState
	var expires sql.NullInt64
	err := s.db.QueryRowContext(ctx, `SELECT m.seq,m.read_at IS NOT NULL,m.has_code,m.body_state,m.late,b.expires_at
		FROM messages m LEFT JOIN bodies b ON b.message_id=m.id WHERE m.id=?`, id).
		Scan(&state.Seq, &state.Read, &state.HasCode, &state.BodyState, &state.Late, &expires)
	if errors.Is(err, sql.ErrNoRows) {
		err = ErrNotFound
	}
	state.BodyExpires = fromMs(expires)
	return state, err
}

// SetMessageRead marks a message read or unread. changed is false when it
// already was in that state.
func (s *Store) SetMessageRead(ctx context.Context, id string, read bool, now time.Time) (changed bool, err error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	var wasRead bool
	err = tx.QueryRowContext(ctx, "SELECT read_at IS NOT NULL FROM messages WHERE id=?", id).Scan(&wasRead)
	if errors.Is(err, sql.ErrNoRows) {
		return false, ErrNotFound
	}
	if err != nil {
		return false, err
	}
	if wasRead == read {
		return false, nil
	}
	seq, err := nextSeq(ctx, tx)
	if err != nil {
		return false, err
	}
	var at any
	if read {
		at = now.UnixMilli()
	}
	if _, err := tx.ExecContext(ctx, "UPDATE messages SET seq=?,read_at=? WHERE id=?", seq, at, id); err != nil {
		return false, err
	}
	return true, tx.Commit()
}

func (s *Store) ChangesSince(ctx context.Context, since int64, limit int) (events.Page, error) {
	tx, err := s.db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return events.Page{}, err
	}
	defer tx.Rollback()
	page, err := events.ChangesSince(ctx, tx, since, limit)
	if err != nil {
		return page, err
	}
	return page, tx.Commit()
}

func (s *Store) AckDevice(ctx context.Context, deviceID string, ids []string, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, id := range ids {
		res, err := tx.ExecContext(ctx, `UPDATE deliveries SET acked_at=? WHERE message_id=? AND target=?
		AND channel='fcm' AND acked_at IS NULL`, now.UnixMilli(), id, deviceID)
		if err != nil {
			return err
		}
		n, err := res.RowsAffected()
		if err != nil {
			return err
		}
		if n == 0 {
			continue
		}
		seq, err := nextSeq(ctx, tx)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, "UPDATE messages SET seq=? WHERE id=?", seq, id); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *Store) CleanTombstones(ctx context.Context, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	cutoff := now.Add(-30 * 24 * time.Hour).UnixMilli()
	var last int64
	if err := tx.QueryRowContext(ctx, "SELECT COALESCE(MAX(seq),0) FROM tombstones WHERE at<=?", cutoff).Scan(&last); err != nil {
		return err
	}
	if last == 0 {
		return nil
	}
	if _, err := tx.ExecContext(ctx, "DELETE FROM tombstones WHERE at<=?", cutoff); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO kv(k,v) VALUES('tombstone_horizon',CAST(? AS TEXT))
		ON CONFLICT(k) DO UPDATE SET v=CAST(MAX(CAST(v AS INTEGER),CAST(excluded.v AS INTEGER)) AS TEXT)`, last); err != nil {
		return err
	}
	return tx.Commit()
}

// MaxReadBatch bounds the IDs one read-state batch may name.
const MaxReadBatch = 500

// ErrStaleBound: the newest message a "mark all read" was bounded by is gone.
var ErrStaleBound = errors.New("store: read bound no longer exists")

// SetMessagesRead marks the named messages read or unread in one
// transaction with one audit entry. Unknown IDs are skipped (they may have
// been deleted meanwhile). changed lists the messages whose state actually
// changed, newest first; each got a new seq so /v1/sync and the event
// stream report it.
func (s *Store) SetMessagesRead(ctx context.Context, ids []string, read bool, actor string, now time.Time) (changed []string, err error) {
	if len(ids) > MaxReadBatch {
		return nil, errors.New("store: too many ids")
	}
	query := `SELECT id FROM messages WHERE read_at IS NOT NULL`
	if read {
		query = `SELECT id FROM messages WHERE read_at IS NULL`
	}
	args := make([]any, 0, len(ids))
	seen := make(map[string]bool, len(ids))
	for _, id := range ids {
		if !seen[id] {
			seen[id] = true
			args = append(args, id)
		}
	}
	action := "message.unread_batch"
	if read {
		action = "message.read_batch"
	}
	if len(args) == 0 {
		return nil, s.Audit(ctx, now, actor, action, "count=0", "ok")
	}
	query += " AND id IN (?" + strings.Repeat(",?", len(args)-1) + ") ORDER BY received_at DESC, id DESC"
	return s.applyRead(ctx, query, args, read, actor, action, now)
}

// MarkAllRead marks every unread message read whose (received_at, id) is
// not newer than the message upTo, the newest one the person saw, so
// messages that arrive meanwhile stay unread. hasCode limits it to messages
// with a verification code.
func (s *Store) MarkAllRead(ctx context.Context, upTo string, hasCode bool, actor string, now time.Time) (changed []string, err error) {
	var at int64
	err = s.db.QueryRowContext(ctx, "SELECT received_at FROM messages WHERE id=?", upTo).Scan(&at)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrStaleBound
	}
	if err != nil {
		return nil, err
	}
	query := `SELECT id FROM messages WHERE read_at IS NULL AND (received_at, id) <= (?, ?)`
	if hasCode {
		query += " AND has_code = 1"
	}
	query += " ORDER BY received_at DESC, id DESC"
	return s.applyRead(ctx, query, []any{at, upTo}, true, actor, "message.read_all", now)
}

func (s *Store) applyRead(ctx context.Context, query string, args []any, read bool, actor, action string, now time.Time) ([]string, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	var changed []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		changed = append(changed, id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	var at any
	if read {
		at = now.UnixMilli()
	}
	// Oldest first, so the seq order matches the list order.
	for i := len(changed) - 1; i >= 0; i-- {
		seq, err := nextSeq(ctx, tx)
		if err != nil {
			return nil, err
		}
		if _, err := tx.ExecContext(ctx, "UPDATE messages SET seq=?,read_at=? WHERE id=?", seq, at, changed[i]); err != nil {
			return nil, err
		}
	}
	if err := s.appendAudit(ctx, tx, now, actor, action, fmt.Sprintf("count=%d", len(changed)), "ok"); err != nil {
		return nil, err
	}
	return changed, tx.Commit()
}
