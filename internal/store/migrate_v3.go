package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"time"

	"nekopost/internal/auth"
	"nekopost/internal/msgrule"
	"nekopost/internal/sealbox"
)

const v3SQL = `
CREATE TABLE data_keys (
	day TEXT PRIMARY KEY,
	kek_id TEXT NOT NULL,
	wrapped BLOB NOT NULL,
	created_at INTEGER NOT NULL
);
CREATE TABLE bodies (
	message_id TEXT PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
	key_day TEXT NOT NULL,
	ct BLOB NOT NULL,
	expires_at INTEGER NOT NULL
);
CREATE INDEX bodies_expires ON bodies(expires_at);
CREATE INDEX bodies_day ON bodies(key_day);
CREATE TABLE tombstones (
	seq INTEGER PRIMARY KEY,
	message_id TEXT NOT NULL,
	at INTEGER NOT NULL
);
CREATE INDEX tombstones_at ON tombstones(at);
ALTER TABLE messages ADD COLUMN seq INTEGER NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN read_at INTEGER;
ALTER TABLE messages ADD COLUMN body_state TEXT NOT NULL DEFAULT 'none';
ALTER TABLE messages ADD COLUMN has_code INTEGER NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN late TEXT NOT NULL DEFAULT '';
ALTER TABLE deliveries ADD COLUMN aux INTEGER NOT NULL DEFAULT 0;
ALTER TABLE deliveries ADD COLUMN acked_at INTEGER;
ALTER TABLE deliveries ADD COLUMN tg_message_ids TEXT NOT NULL DEFAULT '';
CREATE INDEX messages_seq ON messages(seq);
CREATE INDEX messages_kind_received ON messages(kind, received_at);
CREATE INDEX messages_category_received ON messages(category, received_at);
CREATE INDEX messages_read_received ON messages(read_at, received_at);
INSERT INTO kv(k, v) VALUES ('seq', '0');
`

func (s *Store) migrateV3(ctx context.Context, tx *sql.Tx) error {
	rows, err := tx.QueryContext(ctx, `SELECT m.id, m.source_id, m.kind, m.status, m.idem_key, m.occurred_at, m.received_at, m.category,
		COALESCE(p.title, ''), COALESCE(p.body, ''), COALESCE(p.meta, ''), p.message_id IS NOT NULL
		FROM messages m LEFT JOIN payloads p ON p.message_id = m.id
		ORDER BY m.received_at, m.id`)
	if err != nil {
		return err
	}
	type row struct {
		id, source, kind, status, idem, title, body, meta, category string
		occurred                                                    sql.NullInt64
		received                                                    int64
		has                                                         bool
	}
	var list []row
	for rows.Next() {
		var r row
		var idem sql.NullString
		var has int
		if err := rows.Scan(&r.id, &r.source, &r.kind, &r.status, &idem, &r.occurred, &r.received, &r.category,
			&r.title, &r.body, &r.meta, &has); err != nil {
			rows.Close()
			return err
		}
		r.idem = idem.String
		r.has = has == 1
		list = append(list, r)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}

	window := s.dedupeWindow
	if window < time.Hour {
		window = time.Hour
	}
	now := s.now()
	for i, r := range list {
		seq := int64(i + 1)
		received := time.UnixMilli(r.received)
		var occurred time.Time
		if r.occurred.Valid {
			occurred = time.UnixMilli(r.occurred.Int64)
		}
		hasCode := false
		late := ""
		state := "none"
		var idem any
		hash := ""
		keepPlain := false
		if r.has {
			var meta map[string]string
			if r.meta != "" {
				_ = json.Unmarshal([]byte(r.meta), &meta)
			}
			hasCode, late = s.classifyPlain(r.title, r.body, received, occurred)
			exp := s.expiresAt(received, hasCode, r.category)
			switch {
			case s.bodyRetention == 0:
				keepPlain = r.status != "delivered" && r.status != "failed" && r.status != "uncertain" &&
					r.status != "partial" && r.status != "not_routed" &&
					(r.status != "filtered" || s.filteredKeep > 0 && now.Sub(received) < s.filteredKeep)
				if r.status == "filtered" && keepPlain {
					exp = now.Add(s.filteredKeep)
				} else if keepPlain {
					exp = now.Add(24 * time.Hour)
				}
			default:
				keepPlain = exp.After(now)
			}
			if keepPlain {
				state = "stored"
				if err := s.putBody(ctx, tx, r.id, received, r.title, r.body, meta, exp); err != nil {
					return err
				}
				if now.Sub(received) < window {
					hash = auth.ContentHash(s.box.Dedupe, r.source, r.kind, r.title, r.body)
				}
			} else {
				state = "expired"
			}
		} else {
			late = msgrule.Late(received, occurred, false)
		}
		if r.idem != "" && state != "expired" {
			if state == "none" && s.bodyRetention > 0 && !s.expiresAt(received, false, r.category).After(now) {
				// Past the new retention: the idempotency key must not outlive the body.
			} else {
				idem = sealbox.IdemHash(s.box.Dedupe, r.idem)
			}
		}
		if _, err := tx.ExecContext(ctx, `UPDATE messages SET seq = ?, body_state = ?, has_code = ?, late = ?,
			content_hash = ?, idem_key = ? WHERE id = ?`,
			seq, state, boolInt(hasCode), late, hash, idem, r.id); err != nil {
			return err
		}
	}
	if _, err := tx.ExecContext(ctx, "UPDATE kv SET v = ? WHERE k = 'seq'", len(list)); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "DELETE FROM kv WHERE k = 'secret:dedupe'"); err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "DROP TABLE payloads")
	return err
}

// DeleteMigrationBackups removes pre-migration copies older than 24 hours.
func (s *Store) DeleteMigrationBackups(dir string, now time.Time) error {
	if s.keepMigBackup {
		return nil
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	cutoff := now.Add(-24 * time.Hour)
	for _, e := range entries {
		name := e.Name()
		if !strings.HasPrefix(name, "nekopost.db.pre-v") || !strings.HasSuffix(name, ".bak") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		if info.ModTime().Before(cutoff) {
			_ = os.Remove(filepath.Join(dir, name))
		}
	}
	return nil
}
