package store

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"nekopost/internal/model"
)

const sourceCols = `id, name, icon, enabled, created_at, last_seen_at, auth_fail_count, last_auth_fail_at`

func scanSource(row interface{ Scan(...any) error }) (model.Source, error) {
	var src model.Source
	var enabled int
	var created int64
	var lastSeen, lastFail sql.NullInt64
	if err := row.Scan(&src.ID, &src.Name, &src.Icon, &enabled, &created, &lastSeen, &src.AuthFailCount, &lastFail); err != nil {
		return src, err
	}
	src.Enabled = enabled == 1
	src.CreatedAt = time.UnixMilli(created)
	src.LastSeenAt = fromMs(lastSeen)
	src.LastAuthFailAt = fromMs(lastFail)
	return src, nil
}

// CreateSource adds a source together with its first credential.
func (s *Store) CreateSource(ctx context.Context, src model.Source, tokenHash, prefix string, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var exists int
	if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM sources WHERE id = ?", src.ID).Scan(&exists); err != nil {
		return err
	}
	if exists > 0 {
		return ErrExists
	}
	if _, err := tx.ExecContext(ctx,
		"INSERT INTO sources(id, name, icon, enabled, created_at, updated_at) VALUES(?, ?, ?, ?, ?, ?)",
		src.ID, src.Name, src.Icon, boolInt(src.Enabled), now.UnixMilli(), now.UnixMilli()); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx,
		"INSERT INTO source_tokens(hash, source_id, prefix, created_at) VALUES(?, ?, ?, ?)",
		tokenHash, src.ID, prefix, now.UnixMilli()); err != nil {
		return err
	}
	return tx.Commit()
}

// ListSources returns all sources ordered by id.
func (s *Store) ListSources(ctx context.Context) ([]model.Source, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+sourceCols+" FROM sources ORDER BY created_at, id")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []model.Source
	for rows.Next() {
		src, err := scanSource(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, src)
	}
	return out, rows.Err()
}

// GetSource returns one source.
func (s *Store) GetSource(ctx context.Context, id string) (model.Source, error) {
	src, err := scanSource(s.db.QueryRowContext(ctx, "SELECT "+sourceCols+" FROM sources WHERE id = ?", id))
	if errors.Is(err, sql.ErrNoRows) {
		return src, ErrNotFound
	}
	return src, err
}

// SetSourceEnabled enables or disables a source.
func (s *Store) SetSourceEnabled(ctx context.Context, id string, enabled bool, now time.Time) error {
	return s.execOne(ctx, "UPDATE sources SET enabled = ?, updated_at = ? WHERE id = ?", boolInt(enabled), now.UnixMilli(), id)
}

// UpdateSourceInfo changes the display name and icon of a source.
func (s *Store) UpdateSourceInfo(ctx context.Context, id, name, icon string, now time.Time) error {
	return s.execOne(ctx, "UPDATE sources SET name = ?, icon = ?, updated_at = ? WHERE id = ?", name, icon, now.UnixMilli(), id)
}

// DeleteSource removes a source and its credentials. Message metadata is kept
// for statistics until it ages out.
func (s *Store) DeleteSource(ctx context.Context, id string) error {
	return s.execOne(ctx, "DELETE FROM sources WHERE id = ?", id)
}

// RotateSourceToken revokes all active credentials of a source and adds a new one.
func (s *Store) RotateSourceToken(ctx context.Context, id, tokenHash, prefix string, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	res, err := tx.ExecContext(ctx, "UPDATE sources SET updated_at = ? WHERE id = ?", now.UnixMilli(), id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	if _, err := tx.ExecContext(ctx, "UPDATE source_tokens SET revoked_at = ? WHERE source_id = ? AND revoked_at IS NULL", now.UnixMilli(), id); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "INSERT INTO source_tokens(hash, source_id, prefix, created_at) VALUES(?, ?, ?, ?)", tokenHash, id, prefix, now.UnixMilli()); err != nil {
		return err
	}
	return tx.Commit()
}

// TokenInfo describes one stored credential (never the secret itself).
type TokenInfo struct {
	Prefix    string
	CreatedAt time.Time
	RevokedAt time.Time
}

// SourceTokens lists the credentials of a source.
func (s *Store) SourceTokens(ctx context.Context, id string) ([]TokenInfo, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT prefix, created_at, revoked_at FROM source_tokens WHERE source_id = ? ORDER BY created_at", id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []TokenInfo
	for rows.Next() {
		var t TokenInfo
		var created int64
		var revoked sql.NullInt64
		if err := rows.Scan(&t.Prefix, &created, &revoked); err != nil {
			return nil, err
		}
		t.CreatedAt = time.UnixMilli(created)
		t.RevokedAt = fromMs(revoked)
		out = append(out, t)
	}
	return out, rows.Err()
}

// TokenLookup is the result of resolving a source credential.
type TokenLookup struct {
	Source  model.Source
	Revoked bool
}

// LookupSourceToken resolves a credential digest to its source.
func (s *Store) LookupSourceToken(ctx context.Context, hash string) (TokenLookup, error) {
	var res TokenLookup
	var revoked sql.NullInt64
	row := s.db.QueryRowContext(ctx, `SELECT s.id, s.name, s.icon, s.enabled, s.created_at, s.last_seen_at,
		s.auth_fail_count, s.last_auth_fail_at, t.revoked_at
		FROM source_tokens t JOIN sources s ON s.id = t.source_id WHERE t.hash = ?`, hash)
	var enabled int
	var created int64
	var lastSeen, lastFail sql.NullInt64
	err := row.Scan(&res.Source.ID, &res.Source.Name, &res.Source.Icon, &enabled, &created, &lastSeen,
		&res.Source.AuthFailCount, &lastFail, &revoked)
	if errors.Is(err, sql.ErrNoRows) {
		return res, ErrNotFound
	}
	if err != nil {
		return res, err
	}
	res.Source.Enabled = enabled == 1
	res.Source.CreatedAt = time.UnixMilli(created)
	res.Source.LastSeenAt = fromMs(lastSeen)
	res.Source.LastAuthFailAt = fromMs(lastFail)
	res.Revoked = revoked.Valid
	return res, nil
}

// TouchSource records an authenticated request from a source.
func (s *Store) TouchSource(ctx context.Context, id string, now time.Time) error {
	_, err := s.db.ExecContext(ctx, "UPDATE sources SET last_seen_at = ? WHERE id = ?", now.UnixMilli(), id)
	return err
}

// RecordSourceAuthFailure records the use of a revoked credential.
func (s *Store) RecordSourceAuthFailure(ctx context.Context, id string, now time.Time) error {
	_, err := s.db.ExecContext(ctx, "UPDATE sources SET auth_fail_count = auth_fail_count + 1, last_auth_fail_at = ? WHERE id = ?", now.UnixMilli(), id)
	return err
}

// AdminKey describes an admin API credential.
type AdminKey struct {
	Name       string
	Prefix     string
	CreatedAt  time.Time
	RevokedAt  time.Time
	LastUsedAt time.Time
}

// CreateAdminKey stores a new admin API credential.
func (s *Store) CreateAdminKey(ctx context.Context, name, hash, prefix string, now time.Time) error {
	_, err := s.db.ExecContext(ctx, "INSERT INTO admin_keys(hash, name, prefix, created_at) VALUES(?, ?, ?, ?)", hash, name, prefix, now.UnixMilli())
	return err
}

// LookupAdminKey verifies an admin credential digest and records its use.
func (s *Store) LookupAdminKey(ctx context.Context, hash string, now time.Time) (string, error) {
	var name string
	err := s.db.QueryRowContext(ctx, "SELECT name FROM admin_keys WHERE hash = ? AND revoked_at IS NULL", hash).Scan(&name)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotFound
	}
	if err != nil {
		return "", err
	}
	_, _ = s.db.ExecContext(ctx, "UPDATE admin_keys SET last_used_at = ? WHERE hash = ?", now.UnixMilli(), hash)
	return name, nil
}

// ListAdminKeys returns all admin credentials.
func (s *Store) ListAdminKeys(ctx context.Context) ([]AdminKey, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT name, prefix, created_at, revoked_at, last_used_at FROM admin_keys ORDER BY created_at")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []AdminKey
	for rows.Next() {
		var k AdminKey
		var created int64
		var revoked, used sql.NullInt64
		if err := rows.Scan(&k.Name, &k.Prefix, &created, &revoked, &used); err != nil {
			return nil, err
		}
		k.CreatedAt = time.UnixMilli(created)
		k.RevokedAt = fromMs(revoked)
		k.LastUsedAt = fromMs(used)
		out = append(out, k)
	}
	return out, rows.Err()
}

// RevokeAdminKey revokes the admin credential(s) with the given display prefix.
func (s *Store) RevokeAdminKey(ctx context.Context, prefix string, now time.Time) error {
	return s.execOne(ctx, "UPDATE admin_keys SET revoked_at = ? WHERE prefix = ? AND revoked_at IS NULL", now.UnixMilli(), prefix)
}

func (s *Store) execOne(ctx context.Context, q string, args ...any) error {
	res, err := s.db.ExecContext(ctx, q, args...)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}
