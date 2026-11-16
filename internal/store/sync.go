package store

import (
	"context"
	"database/sql"
	"errors"
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

func (s *Store) SetMessageRead(ctx context.Context, id string, read bool, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var wasRead bool
	err = tx.QueryRowContext(ctx, "SELECT read_at IS NOT NULL FROM messages WHERE id=?", id).Scan(&wasRead)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if wasRead == read {
		return nil
	}
	seq, err := nextSeq(ctx, tx)
	if err != nil {
		return err
	}
	var at any
	if read {
		at = now.UnixMilli()
	}
	if _, err := tx.ExecContext(ctx, "UPDATE messages SET seq=?,read_at=? WHERE id=?", seq, at, id); err != nil {
		return err
	}
	return tx.Commit()
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
