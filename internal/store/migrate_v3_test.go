package store

import (
	"bytes"
	"database/sql"
	"os"
	"path/filepath"
	"testing"
	"time"

	_ "modernc.org/sqlite"
)

func TestMigrateV2EncryptsPlaintext(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "nekopost.db")
	db, err := sql.Open("sqlite", "file:"+path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(migrations[0].sql); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(migrations[1].sql); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec("PRAGMA user_version = 2"); err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	const id = "m_migrate"
	const sentinel = "SENTINEL-otp-884422"
	if _, err := db.Exec(`INSERT INTO messages(id, source_id, idem_key, content_hash, kind, priority, received_at, status)
		VALUES(?, 'iphone', 'raw-key', 'old-hash', 'sms', 'normal', ?, 'queued')`, id, now.UnixMilli()); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO payloads(message_id, title, body, meta) VALUES(?, '银行', ?, '')`, id, sentinel); err != nil {
		t.Fatal(err)
	}
	const expiredCodeID = "m_expired_code"
	if _, err := db.Exec(`INSERT INTO messages(id, source_id, idem_key, content_hash, kind, priority, received_at, status, category)
		VALUES(?, 'iphone', 'expired-key', 'old-hash', 'sms', 'normal', ?, 'filtered', 'code')`,
		expiredCodeID, now.Add(-25*time.Hour).UnixMilli()); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO payloads(message_id, title, body) VALUES(?, '通知', '旧正文')`, expiredCodeID); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO kv(k, v) VALUES('secret:dedupe', '001122')`); err != nil {
		t.Fatal(err)
	}
	db.Close()

	s, err := Open(path, TestingOptions())
	if err != nil {
		t.Fatal(err)
	}
	title, body, _, ok, err := s.ReadBody(ctx, id)
	if err != nil || !ok || title != "银行" || body != sentinel {
		t.Fatalf("body after migration: %q %q ok=%v %v", title, body, ok, err)
	}
	if _, _, _, ok, err := s.ReadBody(ctx, expiredCodeID); err != nil || ok {
		t.Fatalf("expired code body: ok=%v err=%v", ok, err)
	}
	var state string
	var expiredIdem sql.NullString
	if err := s.db.QueryRow("SELECT body_state, idem_key FROM messages WHERE id = ?", expiredCodeID).
		Scan(&state, &expiredIdem); err != nil || state != "expired" || expiredIdem.Valid {
		t.Fatalf("expired code metadata: state=%q idem=%v err=%v", state, expiredIdem, err)
	}
	var secret int
	if err := s.db.QueryRow("SELECT COUNT(*) FROM kv WHERE k = 'secret:dedupe'").Scan(&secret); err != nil || secret != 0 {
		t.Fatalf("old dedupe secret still present: %d %v", secret, err)
	}
	var idem string
	if err := s.db.QueryRow("SELECT idem_key FROM messages WHERE id = ?", id).Scan(&idem); err != nil {
		t.Fatal(err)
	}
	if idem == "raw-key" || idem == "" {
		t.Fatalf("idempotency key was not hashed: %q", idem)
	}
	dup, err := s.InsertMessage(ctx, newMsg("iphone", "other", "raw-key", now.Add(time.Minute)), tg, time.Minute)
	if err != nil || !dup.Duplicate || dup.ID != id {
		t.Fatalf("retry after migration: %+v %v", dup, err)
	}
	s.Close()

	for _, name := range []string{path, path + "-wal"} {
		b, err := os.ReadFile(name)
		if err != nil {
			continue
		}
		if bytes.Contains(b, []byte(sentinel)) {
			t.Fatalf("plaintext sentinel remains in %s", name)
		}
	}
}
