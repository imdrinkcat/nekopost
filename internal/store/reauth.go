package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"
)

func (s *Store) BeginWebReauth(ctx context.Context, session WebSession, a WebApproval, now time.Time) (WebApproval, bool, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return a, false, err
	}
	defer tx.Rollback()
	var active bool
	if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM web_sessions WHERE id_hash=? AND state='active'
		AND idle_expires_at>? AND abs_expires_at>?)`, session.Hash, now.UnixMilli(), now.UnixMilli()).Scan(&active); err != nil {
		return a, false, err
	}
	if !active {
		return a, false, ErrNotFound
	}
	subject := "reauth:" + session.Hash
	var old WebApproval
	var expires int64
	err = tx.QueryRowContext(ctx, `SELECT id,short_code,state,expires_at FROM approvals
		WHERE subject=? AND state IN ('pending','approved') AND expires_at>?`, subject, now.UnixMilli()).
		Scan(&old.ID, &old.Code, &old.State, &expires)
	if err == nil {
		old.Expires = time.UnixMilli(expires)
		return old, false, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return a, false, err
	}
	var activeCount int
	if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM approvals WHERE subject LIKE 'reauth:%'
		AND state IN ('pending','approved') AND expires_at>?`, now.UnixMilli()).Scan(&activeCount); err != nil {
		return a, false, err
	}
	if activeCount >= 3 {
		return a, false, ErrWebRestricted
	}
	if _, err := tx.ExecContext(ctx, "DELETE FROM approvals WHERE subject=?", subject); err != nil {
		return a, false, err
	}
	choices, err := json.Marshal(a.Choices)
	if err != nil {
		return a, false, err
	}
	a.Kind = "reauth"
	if _, err := tx.ExecContext(ctx, `INSERT INTO approvals(id,subject,identity,requester_ip,requester_ua_label,
		short_code,choices,state,created_at,expires_at) VALUES(?,?,?,?,?,?,?,'pending',?,?)`,
		a.ID, subject, session.Identity, a.IP, a.Browser, a.Code, string(choices), now.UnixMilli(), a.Expires.UnixMilli()); err != nil {
		return a, false, err
	}
	if err := s.appendAudit(ctx, tx, now, session.Identity, "session.reauth_start", "", "ok"); err != nil {
		return a, false, err
	}
	return a, true, tx.Commit()
}

func (s *Store) ConsumeWebReauth(ctx context.Context, session WebSession, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var id string
	err = tx.QueryRowContext(ctx, `UPDATE approvals SET state='consumed' WHERE subject=? AND state='approved'
		AND expires_at>? RETURNING id`, "reauth:"+session.Hash, now.UnixMilli()).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	res, err := tx.ExecContext(ctx, `UPDATE web_sessions SET reauth_at=? WHERE id_hash=? AND state='active'
		AND idle_expires_at>? AND abs_expires_at>?`, now.UnixMilli(), session.Hash, now.UnixMilli(), now.UnixMilli())
	if err != nil {
		return err
	}
	if n, err := res.RowsAffected(); err != nil {
		return err
	} else if n != 1 {
		return ErrNotFound
	}
	if err := s.appendAudit(ctx, tx, now, session.Identity, "session.reauth", "", "ok"); err != nil {
		return err
	}
	return tx.Commit()
}
