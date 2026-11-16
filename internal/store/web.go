package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
	"time"
)

const v4SQL = `
CREATE TABLE trusted_browsers (
	id_hash TEXT PRIMARY KEY, identity TEXT NOT NULL, label TEXT NOT NULL,
	created_by TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL
);
CREATE TABLE web_sessions (
	id_hash TEXT PRIMARY KEY, identity TEXT NOT NULL, state TEXT NOT NULL,
	browser_hash TEXT REFERENCES trusted_browsers(id_hash) ON DELETE SET NULL,
	csrf TEXT NOT NULL, created_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL,
	idle_expires_at INTEGER NOT NULL, abs_expires_at INTEGER NOT NULL,
	ip TEXT NOT NULL, ua_label TEXT NOT NULL
);
CREATE INDEX web_sessions_identity ON web_sessions(identity, state, created_at);
CREATE TABLE approvals (
	id TEXT PRIMARY KEY, subject TEXT NOT NULL UNIQUE, identity TEXT NOT NULL,
	requester_ip TEXT NOT NULL, requester_ua_label TEXT NOT NULL,
	short_code TEXT NOT NULL, choices TEXT NOT NULL, state TEXT NOT NULL,
	trust_browser INTEGER NOT NULL DEFAULT 0, decided_by TEXT NOT NULL DEFAULT '', decided_at INTEGER,
	created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL
);
CREATE INDEX approvals_identity ON approvals(identity, created_at);
CREATE INDEX approvals_pending ON approvals(state, expires_at);
CREATE TABLE web_identity_guard (
	identity TEXT PRIMARY KEY, cooldown_until INTEGER NOT NULL, suspended INTEGER NOT NULL
);
CREATE TABLE audit_log (
	id INTEGER PRIMARY KEY, at INTEGER NOT NULL, actor TEXT NOT NULL,
	action TEXT NOT NULL, target TEXT NOT NULL, result TEXT NOT NULL,
	mac BLOB NOT NULL
);
`

const (
	WebPending = "pending"
	WebActive  = "active"
	webIdle    = 12 * time.Hour
	webMax     = 7 * 24 * time.Hour
	webTrust   = 30 * 24 * time.Hour
)

var ErrWebRestricted = errors.New("web login restricted")

type WebSession struct {
	ReauthAt                                 time.Time
	Hash, Identity, CSRF, State, IP, Browser string
	lastSeen, absolute                       int64
}

type WebApproval struct {
	ID, Identity, IP, Browser, Code, State, Kind string
	Choices                                      []string
	Expires                                      time.Time
}

func (s *Store) StartWebSession(ctx context.Context, session WebSession, approval WebApproval, browserHash string, now time.Time) (string, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	if err := s.expireApprovals(ctx, tx, now); err != nil {
		return "", err
	}
	var locked bool
	if err := tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM kv WHERE k='lockdown')").Scan(&locked); err != nil {
		return "", err
	}
	if locked {
		return "", ErrWebRestricted
	}
	ms := now.UnixMilli()
	var restricted bool
	if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM web_identity_guard
		WHERE identity = ? AND (suspended != 0 OR cooldown_until > ?))`, session.Identity, ms).Scan(&restricted); err != nil {
		return "", err
	}
	if restricted {
		if err := tx.Commit(); err != nil {
			return "", err
		}
		return "", ErrWebRestricted
	}
	var trusted bool
	if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM trusted_browsers
		WHERE id_hash = ? AND identity = ? AND expires_at > ?)`, browserHash, session.Identity, ms).Scan(&trusted); err != nil {
		return "", err
	}
	state := WebPending
	var browser any
	if trusted {
		state, browser = WebActive, browserHash
	} else {
		var recent, pending int
		if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM approvals WHERE identity = ? AND created_at > ? AND subject NOT LIKE 'reauth:%'`,
			session.Identity, now.Add(-time.Hour).UnixMilli()).Scan(&recent); err != nil {
			return "", err
		}
		if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM approvals WHERE state IN ('pending','approved') AND subject NOT LIKE 'reauth:%' AND expires_at>?", ms).Scan(&pending); err != nil {
			return "", err
		}
		if recent >= 3 || pending >= 3 {
			if err := tx.Commit(); err != nil {
				return "", err
			}
			return "", ErrWebRestricted
		}
		choices, err := json.Marshal(approval.Choices)
		if err != nil {
			return "", err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO approvals(id, subject, identity, requester_ip, requester_ua_label,
			short_code, choices, state, created_at, expires_at) VALUES(?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?)`,
			approval.ID, session.Hash, session.Identity, session.IP, session.Browser, approval.Code, string(choices), ms, approval.Expires.UnixMilli()); err != nil {
			return "", err
		}
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO web_sessions(id_hash, identity, state, browser_hash, csrf, created_at,
		last_seen_at, idle_expires_at, abs_expires_at, ip, ua_label, reauth_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		session.Hash, session.Identity, state, browser, session.CSRF, ms, ms, now.Add(webIdle).UnixMilli(),
		now.Add(webMax).UnixMilli(), session.IP, session.Browser, map[bool]any{true: ms}[trusted]); err != nil {
		return "", err
	}
	if err := trimWebSessions(ctx, tx, session.Identity, state, session.Hash); err != nil {
		return "", err
	}
	if err := s.appendAudit(ctx, tx, now, session.Identity, "login."+state, "", "ok"); err != nil {
		return "", err
	}
	return state, tx.Commit()
}

func trimWebSessions(ctx context.Context, tx *sql.Tx, identity, state, keep string) error {
	limit := 5
	if state == WebPending {
		limit = 2
	}
	_, err := tx.ExecContext(ctx, `UPDATE approvals SET state = 'cancelled' WHERE state IN ('pending','approved') AND subject IN (
		SELECT id_hash FROM web_sessions WHERE identity = ? AND state = ? AND id_hash != ?
		ORDER BY created_at DESC, id_hash DESC LIMIT -1 OFFSET ?)`, identity, state, keep, limit-1)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `DELETE FROM web_sessions WHERE id_hash IN (
		SELECT id_hash FROM web_sessions WHERE identity = ? AND state = ? AND id_hash != ?
		ORDER BY created_at DESC, id_hash DESC LIMIT -1 OFFSET ?)`, identity, state, keep, limit-1)
	return err
}

func (s *Store) WebSession(ctx context.Context, hash string, now time.Time) (WebSession, error) {
	var session WebSession
	var reauth sql.NullInt64
	err := s.db.QueryRowContext(ctx, `SELECT id_hash, identity, csrf, state, ip, ua_label, last_seen_at, abs_expires_at, reauth_at
		FROM web_sessions WHERE id_hash = ? AND idle_expires_at > ? AND abs_expires_at > ?`, hash, now.UnixMilli(), now.UnixMilli()).
		Scan(&session.Hash, &session.Identity, &session.CSRF, &session.State, &session.IP, &session.Browser, &session.lastSeen, &session.absolute, &reauth)
	if errors.Is(err, sql.ErrNoRows) {
		err = ErrNotFound
	}
	session.ReauthAt = fromMs(reauth)
	return session, err
}

func (s *Store) TouchWebSession(ctx context.Context, session WebSession, ip, browser string, now time.Time) error {
	if now.UnixMilli()-session.lastSeen < time.Minute.Milliseconds() {
		return nil
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	res, err := tx.ExecContext(ctx, `UPDATE web_sessions SET last_seen_at = ?, idle_expires_at = ?, ip = ?, ua_label = ?
		WHERE id_hash = ? AND state = 'active' AND last_seen_at = ?`, now.UnixMilli(),
		min(session.absolute, now.Add(webIdle).UnixMilli()), ip, browser, session.Hash, session.lastSeen)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n != 0 && (session.IP != ip || session.Browser != browser) {
		if err := s.appendAudit(ctx, tx, now, session.Identity, "session.context_changed", "", "ok"); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *Store) EndWebSession(ctx context.Context, session WebSession, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, "DELETE FROM web_sessions WHERE id_hash = ?", session.Hash); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "UPDATE approvals SET state = 'cancelled' WHERE subject IN (?,?) AND state IN ('pending','approved')", session.Hash, "reauth:"+session.Hash); err != nil {
		return err
	}
	if err := s.appendAudit(ctx, tx, now, session.Identity, "session.end", "", "ok"); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) WebApproval(ctx context.Context, hash string, now time.Time) (WebApproval, error) {
	var a WebApproval
	var expires int64
	err := s.db.QueryRowContext(ctx, "SELECT id, short_code, state, expires_at FROM approvals WHERE subject = ?", hash).
		Scan(&a.ID, &a.Code, &a.State, &expires)
	if errors.Is(err, sql.ErrNoRows) {
		return a, ErrNotFound
	}
	a.Expires = time.UnixMilli(expires)
	if !a.Expires.After(now) && (a.State == "pending" || a.State == "approved") {
		a.State = "expired"
	}
	return a, err
}

func (s *Store) PendingWebApprovals(ctx context.Context, now time.Time) ([]WebApproval, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id, identity, requester_ip, requester_ua_label, choices, expires_at, subject
		FROM approvals WHERE state = 'pending' AND expires_at > ? ORDER BY created_at, id`, now.UnixMilli())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []WebApproval
	for rows.Next() {
		var a WebApproval
		var choices, subject string
		var expires int64
		if err := rows.Scan(&a.ID, &a.Identity, &a.IP, &a.Browser, &choices, &expires, &subject); err != nil {
			return nil, err
		}
		if err := json.Unmarshal([]byte(choices), &a.Choices); err != nil {
			return nil, err
		}
		a.Expires = time.UnixMilli(expires)
		if strings.HasPrefix(subject, "reauth:") {
			a.Kind = "reauth"
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

func (s *Store) DecideWebApproval(ctx context.Context, id, choice, actor string, trust bool, now time.Time) (bool, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	if err := s.expireApprovals(ctx, tx, now); err != nil {
		return false, err
	}
	var correct, identity, subject string
	err = tx.QueryRowContext(ctx, "SELECT short_code, identity, subject FROM approvals WHERE id = ? AND state = 'pending'", id).Scan(&correct, &identity, &subject)
	if errors.Is(err, sql.ErrNoRows) {
		if err := tx.Commit(); err != nil {
			return false, err
		}
		return false, ErrNotFound
	}
	if err != nil {
		return false, err
	}
	var restricted bool
	if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM web_identity_guard
		WHERE identity = ? AND (suspended != 0 OR cooldown_until > ?))`, identity, now.UnixMilli()).Scan(&restricted); err != nil {
		return false, err
	}
	if restricted {
		if err := tx.Commit(); err != nil {
			return false, err
		}
		return false, ErrWebRestricted
	}
	reauth := strings.HasPrefix(subject, "reauth:")
	ok := choice == correct
	state, result := "approved", "ok"
	if !ok {
		state, result = "denied", "denied"
		if !reauth {
			if err := s.recordDenial(ctx, tx, identity, now, true); err != nil {
				return false, err
			}
		}
	}
	if _, err := tx.ExecContext(ctx, "UPDATE approvals SET state = ?, trust_browser = ?, decided_by = ?, decided_at = ? WHERE id = ?",
		state, boolInt(ok && trust && actor == "cli" && !reauth), actor, now.UnixMilli(), id); err != nil {
		return false, err
	}
	if err := s.appendAudit(ctx, tx, now, actor, map[bool]string{true: "session.reauth_confirm", false: "login.confirm"}[reauth], identity, result); err != nil {
		return false, err
	}
	return ok, tx.Commit()
}

func (s *Store) ActivateWebSession(ctx context.Context, session WebSession, hash, csrf, browserHash string, now time.Time) (bool, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	var trust bool
	var actor string
	err = tx.QueryRowContext(ctx, `UPDATE approvals SET state = 'consumed'
		WHERE subject = ? AND state = 'approved' AND expires_at > ?
		AND NOT EXISTS(SELECT 1 FROM web_identity_guard g WHERE g.identity = approvals.identity
			AND (g.suspended != 0 OR g.cooldown_until > ?)) RETURNING trust_browser, decided_by`,
		session.Hash, now.UnixMilli(), now.UnixMilli()).Scan(&trust, &actor)
	if errors.Is(err, sql.ErrNoRows) {
		return false, ErrNotFound
	}
	if err != nil {
		return false, err
	}
	var browser any
	if trust {
		if _, err := tx.ExecContext(ctx, `INSERT INTO trusted_browsers(id_hash, identity, label, created_by, created_at, expires_at)
			VALUES (?, ?, ?, ?, ?, ?)`, browserHash, session.Identity, session.Browser, actor,
			now.UnixMilli(), now.Add(webTrust).UnixMilli()); err != nil {
			return false, err
		}
		browser = browserHash
	}
	res, err := tx.ExecContext(ctx, `UPDATE web_sessions SET id_hash = ?, csrf = ?, state = 'active',
		browser_hash = ?, created_at = ?, last_seen_at = ?, idle_expires_at = ?, abs_expires_at = ?, reauth_at = ?
		WHERE id_hash = ? AND identity = ? AND state = 'pending' AND abs_expires_at > ?`, hash, csrf, browser,
		now.UnixMilli(), now.UnixMilli(), now.Add(webIdle).UnixMilli(), now.Add(webMax).UnixMilli(), now.UnixMilli(),
		session.Hash, session.Identity, now.UnixMilli())
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return false, err
	}
	if n == 0 {
		return false, ErrNotFound
	}
	if err := trimWebSessions(ctx, tx, session.Identity, WebActive, hash); err != nil {
		return false, err
	}
	if err := s.appendAudit(ctx, tx, now, session.Identity, "login.active", "", "ok"); err != nil {
		return false, err
	}
	return trust, tx.Commit()
}

func (s *Store) expireApprovals(ctx context.Context, tx *sql.Tx, now time.Time) error {
	rows, err := tx.QueryContext(ctx, `UPDATE approvals SET state = 'expired'
		WHERE state = 'pending' AND expires_at <= ? AND subject NOT LIKE 'reauth:%' RETURNING identity`, now.UnixMilli())
	if err != nil {
		return err
	}
	expired := map[string]bool{}
	for rows.Next() {
		var identity string
		if err := rows.Scan(&identity); err != nil {
			rows.Close()
			return err
		}
		expired[identity] = true
	}
	if err := rows.Close(); err != nil {
		return err
	}
	if err := rows.Err(); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "UPDATE approvals SET state = 'unclaimed' WHERE state = 'approved' AND expires_at <= ?", now.UnixMilli()); err != nil {
		return err
	}
	for identity := range expired {
		if err := s.recordDenial(ctx, tx, identity, now, false); err != nil {
			return err
		}
		if err := s.appendAudit(ctx, tx, now, "system", "login.expired", identity, "denied"); err != nil {
			return err
		}
	}
	return nil
}

func (s *Store) recordDenial(ctx context.Context, tx *sql.Tx, identity string, now time.Time, current bool) error {
	var n int
	if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM approvals WHERE identity = ? AND subject NOT LIKE 'reauth:%' AND
		((state = 'denied' AND decided_at > ?) OR (state = 'expired' AND expires_at > ?))`,
		identity, now.Add(-time.Hour).UnixMilli(), now.Add(-time.Hour).UnixMilli()).Scan(&n); err != nil {
		return err
	}
	if current {
		n++
	}
	cooldown := int64(0)
	if current {
		cooldown = now.Add(time.Hour).UnixMilli()
	}
	_, err := tx.ExecContext(ctx, `INSERT INTO web_identity_guard(identity, cooldown_until, suspended) VALUES(?, ?, ?)
		ON CONFLICT(identity) DO UPDATE SET cooldown_until = MAX(cooldown_until, excluded.cooldown_until),
		suspended = MAX(suspended, excluded.suspended)`, identity, cooldown, boolInt(n >= 2))
	return err
}

func (s *Store) UnlockWebIdentity(ctx context.Context, identity string, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, "DELETE FROM web_identity_guard WHERE identity = ?", identity); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "UPDATE approvals SET state = 'cleared' WHERE identity = ? AND state IN ('denied','expired','pending','approved')", identity); err != nil {
		return err
	}
	if err := s.appendAudit(ctx, tx, now, "cli", "login.unlock", identity, "ok"); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) CleanWeb(ctx context.Context, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := s.expireApprovals(ctx, tx, now); err != nil {
		return err
	}
	ms := now.UnixMilli()
	queries := []struct {
		sql  string
		args []any
	}{
		{"DELETE FROM web_sessions WHERE idle_expires_at <= ? OR abs_expires_at <= ?", []any{ms, ms}},
		{"DELETE FROM trusted_browsers WHERE expires_at <= ?", []any{ms}},
		{"DELETE FROM approvals WHERE expires_at <= ?", []any{now.Add(-24 * time.Hour).UnixMilli()}},
	}
	for _, query := range queries {
		if _, err := tx.ExecContext(ctx, query.sql, query.args...); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *Store) RefreshWebReauth(ctx context.Context, session WebSession, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	res, err := tx.ExecContext(ctx, `UPDATE web_sessions SET reauth_at=? WHERE id_hash=? AND state='active'
		AND abs_expires_at>? AND idle_expires_at>?`, now.UnixMilli(), session.Hash, now.UnixMilli(), now.UnixMilli())
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
