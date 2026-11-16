package store

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/json"
	"fmt"
	"time"

	"nekopost/internal/msgrule"
	"nekopost/internal/sealbox"
)

// writeDEK runs in the same immediate transaction as the body write.
func (s *Store) writeDEK(ctx context.Context, tx *sql.Tx, day string) ([]byte, error) {
	var wrapped []byte
	var kekID string
	err := tx.QueryRowContext(ctx, "SELECT wrapped, kek_id FROM data_keys WHERE day = ?", day).Scan(&wrapped, &kekID)
	if err == nil {
		return s.unwrapDEK(day, kekID, wrapped)
	}
	if err != sql.ErrNoRows {
		return nil, err
	}
	dek := make([]byte, 32)
	if _, err := rand.Read(dek); err != nil {
		return nil, err
	}
	wrapped, err = sealbox.WrapDEK(s.box, day, dek)
	if err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, "INSERT INTO data_keys(day, kek_id, wrapped, created_at) VALUES(?, ?, ?, ?)",
		day, s.box.KEKIDHex(), wrapped, s.now().UnixMilli()); err != nil {
		return nil, err
	}
	return dek, nil
}

func (s *Store) unwrapDEK(day, kekID string, wrapped []byte) ([]byte, error) {
	if kekID != s.box.KEKIDHex() {
		return nil, fmt.Errorf("主密钥与数据库不匹配（数据库为 %s，当前为 %s）", shortID(kekID), s.box.Prefix())
	}
	dek, err := sealbox.UnwrapDEK(s.box, day, wrapped)
	if err != nil {
		return nil, fmt.Errorf("无法解开 %s 的数据密钥", day)
	}
	return dek, nil
}

type plainBody struct {
	V     int               `json:"v"`
	Title string            `json:"t"`
	Body  string            `json:"b"`
	Meta  map[string]string `json:"m,omitempty"`
}

func encodeBody(title, body string, meta map[string]string) ([]byte, error) {
	return json.Marshal(plainBody{V: 1, Title: title, Body: body, Meta: meta})
}

func decodeBody(b []byte) (plainBody, error) {
	var p plainBody
	err := json.Unmarshal(b, &p)
	return p, err
}

func (s *Store) putBody(ctx context.Context, tx *sql.Tx, id string, received time.Time, title, body string, meta map[string]string, expires time.Time) error {
	day := received.UTC().Format("2006-01-02")
	dek, err := s.writeDEK(ctx, tx, day)
	if err != nil {
		return err
	}
	plain, err := encodeBody(title, body, meta)
	if err != nil {
		return err
	}
	ct, err := sealbox.SealBody(dek, id, day, plain)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO bodies(message_id, key_day, ct, expires_at) VALUES(?, ?, ?, ?)
		ON CONFLICT(message_id) DO UPDATE SET key_day = excluded.key_day, ct = excluded.ct, expires_at = excluded.expires_at`,
		id, day, ct, expires.UnixMilli())
	return err
}

func (s *Store) readBody(ctx context.Context, id string) (title, body string, meta map[string]string, ok bool, err error) {
	var day string
	var kekID sql.NullString
	var ct, wrapped []byte
	err = s.db.QueryRowContext(ctx, `SELECT b.key_day, b.ct, k.kek_id, k.wrapped
		FROM bodies b LEFT JOIN data_keys k ON k.day = b.key_day WHERE b.message_id = ?`, id).
		Scan(&day, &ct, &kekID, &wrapped)
	if err == sql.ErrNoRows {
		return "", "", nil, false, nil
	}
	if err != nil {
		return "", "", nil, false, err
	}
	if !kekID.Valid {
		return "", "", nil, false, fmt.Errorf("找不到 %s 的数据密钥", day)
	}
	dek, err := s.unwrapDEK(day, kekID.String, wrapped)
	if err != nil {
		return "", "", nil, false, err
	}
	plain, err := sealbox.OpenBody(dek, id, day, ct)
	if err != nil {
		return "", "", nil, false, err
	}
	p, err := decodeBody(plain)
	if err != nil {
		return "", "", nil, false, err
	}
	return p.Title, p.Body, p.Meta, true, nil
}

func (s *Store) expiresAt(received time.Time, hasCode bool, category string) time.Time {
	d := s.bodyRetention
	code := s.codeRetention
	if code == 0 {
		code = d
	}
	if (hasCode || category == "code") && code > 0 && (d == 0 || code < d) {
		d = code
	}
	if d == 0 {
		return received
	}
	return received.Add(d)
}

func (s *Store) verifyKEK(ctx context.Context) error {
	var kekID, day string
	var wrapped []byte
	err := s.db.QueryRowContext(ctx, "SELECT kek_id, day, wrapped FROM data_keys ORDER BY day DESC LIMIT 1").Scan(&kekID, &day, &wrapped)
	if err == sql.ErrNoRows {
		return nil
	}
	if err != nil {
		return err
	}
	if kekID != s.box.KEKIDHex() {
		return fmt.Errorf("主密钥与数据库不匹配（数据库为 %s，当前为 %s）", shortID(kekID), s.box.Prefix())
	}
	if _, err := sealbox.UnwrapDEK(s.box, day, wrapped); err != nil {
		return fmt.Errorf("主密钥无法解开最新的数据密钥")
	}
	return nil
}

func (s *Store) rewrapIfNeeded(ctx context.Context, previous []byte) error {
	old, err := sealbox.Derive(previous)
	if err != nil {
		return err
	}
	if old.KEKIDHex() == s.box.KEKIDHex() {
		return nil
	}
	rows, err := s.db.QueryContext(ctx, "SELECT day, kek_id, wrapped FROM data_keys")
	if err != nil {
		return err
	}
	type row struct {
		day, id string
		wrapped []byte
	}
	var list []row
	for rows.Next() {
		var r row
		if err := rows.Scan(&r.day, &r.id, &r.wrapped); err != nil {
			rows.Close()
			return err
		}
		list = append(list, r)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, r := range list {
		if r.id == s.box.KEKIDHex() {
			continue
		}
		if r.id != old.KEKIDHex() {
			return fmt.Errorf("主密钥与数据库不匹配（数据库为 %s，当前为 %s）", shortID(r.id), s.box.Prefix())
		}
		dek, err := sealbox.UnwrapDEK(old, r.day, r.wrapped)
		if err != nil {
			return fmt.Errorf("无法用旧主密钥解开 %s 的数据密钥", r.day)
		}
		wrapped, err := sealbox.WrapDEK(s.box, r.day, dek)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, "UPDATE data_keys SET kek_id = ?, wrapped = ? WHERE day = ?", s.box.KEKIDHex(), wrapped, r.day); err != nil {
			return err
		}
	}
	if err := s.rekeyAudit(ctx, tx, old.Audit); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) noteKEKSince(ctx context.Context) error {
	key := "kek_since:" + s.box.KEKIDHex()
	var n int
	if err := s.db.QueryRowContext(ctx, "SELECT COUNT(*) FROM kv WHERE k = ?", key).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return nil
	}
	return s.SetKV(ctx, key, fmt.Sprintf("%d", s.now().UnixMilli()))
}

func shortID(hexID string) string {
	if len(hexID) > 4 {
		return hexID[:4] + "…"
	}
	if hexID == "" {
		return "无"
	}
	return hexID
}

// CryptoStatus is what the health report shows. It contains no key material.
type CryptoStatus struct {
	Prefix  string
	DEKs    int
	Oldest  string
	Since   time.Time
	AgeDays int
	Ready   bool
	Detail  string
}

// CryptoStatus reports the state of the loaded KEK and the DEKs.
func (s *Store) CryptoStatus(ctx context.Context) (CryptoStatus, error) {
	st := CryptoStatus{Prefix: s.box.Prefix(), Ready: true, Detail: "主密钥可用"}
	if err := s.verifyKEK(ctx); err != nil {
		st.Ready = false
		st.Detail = "主密钥无法解密"
		return st, nil
	}
	if err := s.db.QueryRowContext(ctx, "SELECT COUNT(*), COALESCE(MIN(day), '') FROM data_keys").Scan(&st.DEKs, &st.Oldest); err != nil {
		return st, err
	}
	var ms int64
	err := s.db.QueryRowContext(ctx, "SELECT v FROM kv WHERE k = ?", "kek_since:"+s.box.KEKIDHex()).Scan(&ms)
	if err == nil {
		st.Since = time.UnixMilli(ms)
		st.AgeDays = int(s.now().Sub(st.Since).Hours() / 24)
	}
	if st.DEKs == 0 {
		st.Detail = "主密钥可用，尚无数据密钥"
	}
	return st, nil
}

// ShredAll destroys every DEK and ciphertext. Metadata is kept.
func (s *Store) ShredAll(ctx context.Context) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, "UPDATE data_keys SET wrapped = zeroblob(length(wrapped))"); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "DELETE FROM data_keys"); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "DELETE FROM bodies"); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "UPDATE messages SET body_state = 'expired', content_hash = '', idem_key = NULL WHERE body_state = 'stored'"); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	return s.Checkpoint(ctx)
}

// ResetBodies drops ciphertext and keys. The caller replaces the KEK.
func (s *Store) ResetBodies(ctx context.Context) error {
	return s.ShredAll(ctx)
}

func (s *Store) classifyPlain(title, body string, received, occurred time.Time) (hasCode bool, late string) {
	text := title + "\n" + body
	hasCode = msgrule.HasCode(text)
	late = msgrule.Late(received, occurred, hasCode)
	return hasCode, late
}
