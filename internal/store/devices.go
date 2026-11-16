package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"

	"nekopost/internal/model"
)

const v5SQL = `
CREATE TABLE devices (
	id TEXT PRIMARY KEY, name TEXT NOT NULL, model TEXT NOT NULL, app_version TEXT NOT NULL,
	scopes TEXT NOT NULL, source_id TEXT REFERENCES sources(id) ON DELETE SET NULL,
	reauth_jwk TEXT NOT NULL, reauth_at INTEGER, created_at INTEGER NOT NULL, last_seen_at INTEGER,
	push_token TEXT, push_token_at INTEGER, push_state TEXT NOT NULL DEFAULT 'none',
	push_prefs TEXT NOT NULL DEFAULT '{}', suspended_at INTEGER, revoked_at INTEGER
);
CREATE TABLE device_tokens (
	hash TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
	jkt TEXT NOT NULL, jwk TEXT NOT NULL, created_at INTEGER NOT NULL
);
CREATE TABLE pairings (
	id TEXT PRIMARY KEY, code_hash TEXT NOT NULL UNIQUE, short_hash TEXT NOT NULL UNIQUE,
	scopes TEXT NOT NULL, source_id TEXT NOT NULL, name_hint TEXT NOT NULL,
	created_by TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
	state TEXT NOT NULL, claim_jwk TEXT NOT NULL DEFAULT '', claim_jkt TEXT NOT NULL DEFAULT '',
	claim_reauth_jwk TEXT NOT NULL DEFAULT '', claim_name TEXT NOT NULL DEFAULT '',
	claim_model TEXT NOT NULL DEFAULT '', claim_version TEXT NOT NULL DEFAULT '',
	short_code TEXT NOT NULL DEFAULT '', choices TEXT NOT NULL DEFAULT '',
	device_id TEXT REFERENCES devices(id) ON DELETE SET NULL
);
CREATE INDEX pairings_expiry ON pairings(expires_at);
CREATE TABLE device_challenges (
	device_id TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
	challenge_hash TEXT NOT NULL, expires_at INTEGER NOT NULL
);
ALTER TABLE messages ADD COLUMN origin_device TEXT;
ALTER TABLE web_sessions ADD COLUMN reauth_at INTEGER;
`

const (
	ScopeSend       = "msg:send"
	ScopeRead       = "msg:read"
	ScopeAdminRead  = "admin:read"
	ScopeAdminWrite = "admin:write"
)

type Device struct {
	ID         string    `json:"id"`
	Name       string    `json:"name"`
	Model      string    `json:"model"`
	AppVersion string    `json:"app_version"`
	SourceID   string    `json:"source_id,omitempty"`
	Scopes     string    `json:"scopes"`
	Suspended  bool      `json:"suspended"`
	Revoked    bool      `json:"revoked"`
	PushState  string    `json:"push_state"`
	JKT        string    `json:"-"`
	ReauthJWK  string    `json:"-"`
	ReauthAt   time.Time `json:"-"`
}

func (d Device) Has(scope string) bool {
	for _, s := range strings.Split(d.Scopes, ",") {
		if s == scope {
			return true
		}
	}
	return false
}

const deviceFields = `d.id,d.name,d.model,d.app_version,COALESCE(d.source_id,''),d.scopes,
	d.suspended_at IS NOT NULL,d.revoked_at IS NOT NULL,d.push_state,d.reauth_jwk,d.reauth_at`

func scanDevice(scan func(...any) error, jkt *string) (Device, error) {
	var d Device
	var at sql.NullInt64
	args := []any{&d.ID, &d.Name, &d.Model, &d.AppVersion, &d.SourceID, &d.Scopes, &d.Suspended, &d.Revoked, &d.PushState, &d.ReauthJWK, &at}
	if jkt != nil {
		args = append(args, &d.JKT)
	}
	err := scan(args...)
	if errors.Is(err, sql.ErrNoRows) {
		err = ErrNotFound
	}
	d.ReauthAt = fromMs(at)
	return d, err
}

func (s *Store) DeviceByToken(ctx context.Context, hash string) (Device, error) {
	row := s.db.QueryRowContext(ctx, `SELECT `+deviceFields+`,t.jkt FROM devices d JOIN device_tokens t ON t.device_id=d.id WHERE t.hash=?`, hash)
	var jkt string
	return scanDevice(row.Scan, &jkt)
}

func (s *Store) ListDevices(ctx context.Context) ([]Device, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT `+deviceFields+` FROM devices d ORDER BY d.created_at DESC,d.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]Device, 0)
	for rows.Next() {
		d, err := scanDevice(rows.Scan, nil)
		if err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

func (s *Store) RevokeDevice(ctx context.Context, id, actor string, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	res, err := tx.ExecContext(ctx, `UPDATE devices SET revoked_at=?,push_token=NULL,push_state='none' WHERE id=? AND revoked_at IS NULL`, now.UnixMilli(), id)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrNotFound
	}
	if err := s.appendAudit(ctx, tx, now, actor, "device.revoke", id, "ok"); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) UpdateDeviceInfo(ctx context.Context, id, model, version string, now time.Time) error {
	_, err := s.db.ExecContext(ctx, `UPDATE devices SET model=?,app_version=?,last_seen_at=? WHERE id=? AND revoked_at IS NULL`, model, version, now.UnixMilli(), id)
	return err
}

func (s *Store) UpdateDevicePush(ctx context.Context, id, token string, now time.Time) error {
	_, err := s.db.ExecContext(ctx, `UPDATE devices SET push_token=?,push_token_at=?,push_state='ok'
		WHERE id=? AND revoked_at IS NULL AND suspended_at IS NULL`, token, now.UnixMilli(), id)
	return err
}

func (s *Store) DeviceChallenge(ctx context.Context, id, hash string, expires time.Time) error {
	_, err := s.db.ExecContext(ctx, `INSERT INTO device_challenges(device_id,challenge_hash,expires_at) VALUES(?,?,?)
		ON CONFLICT(device_id) DO UPDATE SET challenge_hash=excluded.challenge_hash,expires_at=excluded.expires_at`, id, hash, expires.UnixMilli())
	return err
}

func (s *Store) CompleteDeviceReauth(ctx context.Context, id, hash string, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var key string
	err = tx.QueryRowContext(ctx, `DELETE FROM device_challenges WHERE device_id=? AND challenge_hash=? AND expires_at>?
		RETURNING device_id`, id, hash, now.UnixMilli()).Scan(&key)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	res, err := tx.ExecContext(ctx, `UPDATE devices SET reauth_at=? WHERE id=? AND revoked_at IS NULL AND suspended_at IS NULL`, now.UnixMilli(), id)
	if err != nil {
		return err
	}
	if n, err := res.RowsAffected(); err != nil {
		return err
	} else if n != 1 {
		return ErrNotFound
	}
	return tx.Commit()
}

// Lockdown immediately removes browser access and disables device reading.
func (s *Store) Lockdown(ctx context.Context, actor string, now time.Time, lift, revoke bool, keep string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	action := "security.lockdown"
	if lift {
		action = "security.lockdown_lift"
		if _, err := tx.ExecContext(ctx, `DELETE FROM kv WHERE k='lockdown'`); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE devices SET suspended_at=NULL WHERE revoked_at IS NULL`); err != nil {
			return err
		}
	} else {
		if _, err := tx.ExecContext(ctx, `INSERT INTO kv(k,v) VALUES('lockdown','1') ON CONFLICT(k) DO NOTHING`); err != nil {
			return err
		}
		for _, sql := range []string{
			"DELETE FROM web_sessions", "DELETE FROM trusted_browsers",
			"UPDATE approvals SET state='cancelled' WHERE state IN ('pending','approved')",
			"UPDATE pairings SET state='rejected' WHERE state IN ('open','claimed','approved')",
		} {
			if _, err := tx.ExecContext(ctx, sql); err != nil {
				return err
			}
		}
		if _, err := tx.ExecContext(ctx, `UPDATE devices SET revoked_at=?,push_token=NULL,push_state='none'
			WHERE revoked_at IS NULL AND id<>? AND (? OR created_at>?)`, now.UnixMilli(), keep, revoke, now.Add(-24*time.Hour).UnixMilli()); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE devices SET suspended_at=?,reauth_at=NULL WHERE revoked_at IS NULL AND id<>?`, now.UnixMilli(), keep); err != nil {
			return err
		}
	}
	if err := s.appendAudit(ctx, tx, now, actor, action, "", "ok"); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) LockdownActive(ctx context.Context) (bool, error) {
	_, ok, err := s.GetKV(ctx, "lockdown")
	return ok, err
}

func (s *Store) DeviceSource(ctx context.Context, d Device) (model.Source, error) {
	if !d.Has(ScopeSend) || d.SourceID == "" {
		return model.Source{}, ErrNotFound
	}
	return s.GetSource(ctx, d.SourceID)
}
