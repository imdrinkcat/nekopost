// Package store persists sources, messages and deliveries in SQLite.
//
// Durability matters more than throughput here: a message is acknowledged to
// the client only after it has been committed with synchronous=FULL, so an
// accepted message survives a crash or power loss and will be retried.
package store

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"time"

	_ "modernc.org/sqlite"
)

// ErrNotFound is returned when a requested row does not exist.
var ErrNotFound = errors.New("not found")

// ErrExists is returned when creating a row whose key is already taken.
var ErrExists = errors.New("already exists")

// Store wraps the database handle.
type Store struct {
	db *sql.DB
}

// Open opens (creating if needed) the database at path and migrates it.
func Open(path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	q := url.Values{}
	for _, p := range []string{
		"busy_timeout(5000)",
		"journal_mode(WAL)",
		"synchronous(FULL)",
		"foreign_keys(1)",
		"secure_delete(1)", // overwrite purged message bodies on disk
	} {
		q.Add("_pragma", p)
	}
	q.Set("_txlock", "immediate")
	db, err := sql.Open("sqlite", "file:"+path+"?"+q.Encode())
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(4)
	db.SetMaxIdleConns(2)
	db.SetConnMaxIdleTime(5 * time.Minute)
	s := &Store{db: db}
	if err := s.migrate(context.Background()); err != nil {
		db.Close()
		return nil, fmt.Errorf("数据库迁移失败: %w", err)
	}
	_ = os.Chmod(path, 0o600)
	return s, nil
}

// Close closes the database.
func (s *Store) Close() error { return s.db.Close() }

// Ping verifies the database is usable.
func (s *Store) Ping(ctx context.Context) error {
	var one int
	return s.db.QueryRowContext(ctx, "SELECT 1").Scan(&one)
}

// Checkpoint truncates the WAL so purged data does not linger in it.
func (s *Store) Checkpoint(ctx context.Context) error {
	_, err := s.db.ExecContext(ctx, "PRAGMA wal_checkpoint(TRUNCATE)")
	return err
}

var migrations = []string{
	`CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT NOT NULL);
	CREATE TABLE sources (
		id TEXT PRIMARY KEY,
		name TEXT NOT NULL,
		icon TEXT NOT NULL DEFAULT '',
		enabled INTEGER NOT NULL DEFAULT 1,
		created_at INTEGER NOT NULL,
		updated_at INTEGER NOT NULL,
		last_seen_at INTEGER,
		auth_fail_count INTEGER NOT NULL DEFAULT 0,
		last_auth_fail_at INTEGER
	);
	CREATE TABLE source_tokens (
		hash TEXT PRIMARY KEY,
		source_id TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
		prefix TEXT NOT NULL,
		created_at INTEGER NOT NULL,
		revoked_at INTEGER
	);
	CREATE INDEX source_tokens_source ON source_tokens(source_id);
	CREATE TABLE admin_keys (
		hash TEXT PRIMARY KEY,
		name TEXT NOT NULL,
		prefix TEXT NOT NULL,
		created_at INTEGER NOT NULL,
		revoked_at INTEGER,
		last_used_at INTEGER
	);
	CREATE TABLE messages (
		id TEXT PRIMARY KEY,
		source_id TEXT NOT NULL,
		idem_key TEXT,
		content_hash TEXT NOT NULL,
		kind TEXT NOT NULL,
		priority TEXT NOT NULL,
		title_len INTEGER NOT NULL DEFAULT 0,
		body_len INTEGER NOT NULL DEFAULT 0,
		occurred_at INTEGER,
		received_at INTEGER NOT NULL,
		status TEXT NOT NULL,
		dup_count INTEGER NOT NULL DEFAULT 0,
		finished_at INTEGER
	);
	CREATE UNIQUE INDEX messages_idem ON messages(source_id, idem_key) WHERE idem_key IS NOT NULL;
	CREATE INDEX messages_received ON messages(received_at);
	CREATE INDEX messages_source_received ON messages(source_id, received_at);
	CREATE INDEX messages_hash ON messages(source_id, content_hash, received_at);
	CREATE TABLE payloads (
		message_id TEXT PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
		title TEXT NOT NULL DEFAULT '',
		body TEXT NOT NULL DEFAULT '',
		meta TEXT NOT NULL DEFAULT ''
	);
	CREATE TABLE deliveries (
		id INTEGER PRIMARY KEY,
		message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
		channel TEXT NOT NULL,
		target TEXT NOT NULL,
		silent INTEGER NOT NULL DEFAULT 0,
		state TEXT NOT NULL,
		attempts INTEGER NOT NULL DEFAULT 0,
		parts_total INTEGER NOT NULL DEFAULT 0,
		parts_sent INTEGER NOT NULL DEFAULT 0,
		next_attempt_at INTEGER NOT NULL,
		last_attempt_at INTEGER,
		delivered_at INTEGER,
		last_error TEXT NOT NULL DEFAULT '',
		uncertain INTEGER NOT NULL DEFAULT 0,
		created_at INTEGER NOT NULL
	);
	CREATE INDEX deliveries_due ON deliveries(state, next_attempt_at);
	CREATE INDEX deliveries_msg ON deliveries(message_id);
	CREATE INDEX deliveries_attempt ON deliveries(last_attempt_at);`,
	// v2: content filter verdict.
	`ALTER TABLE messages ADD COLUMN category TEXT NOT NULL DEFAULT '';
	CREATE INDEX messages_status ON messages(status, received_at);`,
}

func (s *Store) migrate(ctx context.Context) error {
	var version int
	if err := s.db.QueryRowContext(ctx, "PRAGMA user_version").Scan(&version); err != nil {
		return err
	}
	for i := version; i < len(migrations); i++ {
		tx, err := s.db.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, migrations[i]); err != nil {
			tx.Rollback()
			return fmt.Errorf("migration %d: %w", i+1, err)
		}
		if _, err := tx.ExecContext(ctx, "PRAGMA user_version = "+strconv.Itoa(i+1)); err != nil {
			tx.Rollback()
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}
	return nil
}

// GetKV reads a key from the kv table.
func (s *Store) GetKV(ctx context.Context, k string) (string, bool, error) {
	var v string
	err := s.db.QueryRowContext(ctx, "SELECT v FROM kv WHERE k = ?", k).Scan(&v)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	}
	return v, err == nil, err
}

// SetKV writes a key to the kv table.
func (s *Store) SetKV(ctx context.Context, k, v string) error {
	_, err := s.db.ExecContext(ctx, "INSERT INTO kv(k, v) VALUES(?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v", k, v)
	return err
}

// Secret returns a persistent random secret, generating it on first use.
func (s *Store) Secret(ctx context.Context, name string) ([]byte, error) {
	key := "secret:" + name
	if v, ok, err := s.GetKV(ctx, key); err != nil {
		return nil, err
	} else if ok {
		return hex.DecodeString(v)
	}
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return nil, err
	}
	if _, err := s.db.ExecContext(ctx, "INSERT OR IGNORE INTO kv(k, v) VALUES(?, ?)", key, hex.EncodeToString(b)); err != nil {
		return nil, err
	}
	v, _, err := s.GetKV(ctx, key)
	if err != nil {
		return nil, err
	}
	return hex.DecodeString(v)
}

// StartInfo describes previous service starts.
type StartInfo struct {
	PreviousStart time.Time
	StartCount    int
}

// RecordStart stores the current start time and returns the previous one.
func (s *Store) RecordStart(ctx context.Context, now time.Time) (StartInfo, error) {
	var info StartInfo
	if v, ok, err := s.GetKV(ctx, "last_start"); err != nil {
		return info, err
	} else if ok {
		if ms, err := strconv.ParseInt(v, 10, 64); err == nil {
			info.PreviousStart = time.UnixMilli(ms)
		}
	}
	if v, ok, err := s.GetKV(ctx, "start_count"); err != nil {
		return info, err
	} else if ok {
		info.StartCount, _ = strconv.Atoi(v)
	}
	info.StartCount++
	if err := s.SetKV(ctx, "last_start", strconv.FormatInt(now.UnixMilli(), 10)); err != nil {
		return info, err
	}
	return info, s.SetKV(ctx, "start_count", strconv.Itoa(info.StartCount))
}

// ms converts a time to a nullable unix-millisecond value.
func ms(t time.Time) any {
	if t.IsZero() {
		return nil
	}
	return t.UnixMilli()
}

// fromMs converts a nullable unix-millisecond value to a time.
func fromMs(v sql.NullInt64) time.Time {
	if !v.Valid {
		return time.Time{}
	}
	return time.UnixMilli(v.Int64)
}
