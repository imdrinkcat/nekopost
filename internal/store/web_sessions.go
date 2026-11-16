package store

import (
	"context"
	"database/sql"
	"time"
)

type WebSessionInfo struct {
	Hash       string    `json:"id"`
	Identity   string    `json:"identity"`
	State      string    `json:"state"`
	Browser    string    `json:"browser"`
	CreatedAt  time.Time `json:"created_at"`
	LastSeenAt time.Time `json:"last_seen_at"`
}
type TrustedBrowserInfo struct {
	Hash      string    `json:"id"`
	Identity  string    `json:"identity"`
	Label     string    `json:"label"`
	ExpiresAt time.Time `json:"expires_at"`
}

func (s *Store) ListWebSessions(ctx context.Context, now time.Time) ([]WebSessionInfo, []TrustedBrowserInfo, error) {
	sessions := make([]WebSessionInfo, 0)
	browsers := make([]TrustedBrowserInfo, 0)
	rows, err := s.db.QueryContext(ctx, `SELECT id_hash,identity,state,ua_label,created_at,last_seen_at FROM web_sessions
		WHERE idle_expires_at>? AND abs_expires_at>? ORDER BY created_at DESC`, now.UnixMilli(), now.UnixMilli())
	if err != nil {
		return sessions, browsers, err
	}
	defer rows.Close()
	for rows.Next() {
		var v WebSessionInfo
		var created, last int64
		if err := rows.Scan(&v.Hash, &v.Identity, &v.State, &v.Browser, &created, &last); err != nil {
			return sessions, browsers, err
		}
		v.CreatedAt, v.LastSeenAt = time.UnixMilli(created), time.UnixMilli(last)
		sessions = append(sessions, v)
	}
	if err := rows.Err(); err != nil {
		return sessions, browsers, err
	}
	if err := rows.Close(); err != nil {
		return sessions, browsers, err
	}
	rows, err = s.db.QueryContext(ctx, `SELECT id_hash,identity,label,expires_at FROM trusted_browsers WHERE expires_at>? ORDER BY created_at DESC`, now.UnixMilli())
	if err != nil {
		return sessions, browsers, err
	}
	defer rows.Close()
	for rows.Next() {
		var b TrustedBrowserInfo
		var exp int64
		if err := rows.Scan(&b.Hash, &b.Identity, &b.Label, &exp); err != nil {
			return sessions, browsers, err
		}
		b.ExpiresAt = time.UnixMilli(exp)
		browsers = append(browsers, b)
	}
	return sessions, browsers, rows.Err()
}

func (s *Store) RevokeWebAccess(ctx context.Context, actor, kind, id, keep string, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var result sql.Result
	switch kind {
	case "session":
		result, err = tx.ExecContext(ctx, "DELETE FROM web_sessions WHERE id_hash=?", id)
	case "browser":
		return s.revokeBrowser(ctx, tx, actor, id, now)
	case "others":
		result, err = tx.ExecContext(ctx, "DELETE FROM web_sessions WHERE id_hash<>?", keep)
		if err == nil && keep == "" {
			_, err = tx.ExecContext(ctx, "DELETE FROM trusted_browsers")
		}
	default:
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if n, err := result.RowsAffected(); err != nil {
		return err
	} else if kind != "others" && n == 0 {
		return ErrNotFound
	}
	if err := s.cleanOrphanApprovals(ctx, tx); err != nil {
		return err
	}
	if err := s.appendAudit(ctx, tx, now, actor, "web.revoke_"+kind, id, "ok"); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) revokeBrowser(ctx context.Context, tx *sql.Tx, actor, id string, now time.Time) error {
	if _, err := tx.ExecContext(ctx, "DELETE FROM web_sessions WHERE browser_hash=?", id); err != nil {
		return err
	}
	result, err := tx.ExecContext(ctx, "DELETE FROM trusted_browsers WHERE id_hash=?", id)
	if err != nil {
		return err
	}
	if n, err := result.RowsAffected(); err != nil {
		return err
	} else if n == 0 {
		return ErrNotFound
	}
	if err := s.cleanOrphanApprovals(ctx, tx); err != nil {
		return err
	}
	if err := s.appendAudit(ctx, tx, now, actor, "web.revoke_browser", id, "ok"); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) cleanOrphanApprovals(ctx context.Context, tx *sql.Tx) error {
	_, err := tx.ExecContext(ctx, `UPDATE approvals SET state='cancelled' WHERE state IN ('pending','approved')
		AND NOT EXISTS(SELECT 1 FROM web_sessions WHERE subject=id_hash OR subject='reauth:'||id_hash)`)
	return err
}
