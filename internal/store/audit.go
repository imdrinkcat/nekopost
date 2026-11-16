package store

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

type AuditEntry struct {
	ID     int64  `json:"id"`
	At     int64  `json:"at"`
	Actor  string `json:"actor"`
	Action string `json:"action"`
	Target string `json:"target"`
	Result string `json:"result"`
}

func auditMAC(key, prev []byte, e AuditEntry) []byte {
	m := hmac.New(sha256.New, key)
	m.Write([]byte("nekopost/audit/v1\x00"))
	m.Write(prev)
	b, _ := json.Marshal(e)
	m.Write(b)
	return m.Sum(nil)
}

func (s *Store) appendAudit(ctx context.Context, tx *sql.Tx, now time.Time, actor, action, target, result string) error {
	var keyID string
	err := tx.QueryRowContext(ctx, "SELECT v FROM kv WHERE k = 'audit_key_id'").Scan(&keyID)
	if errors.Is(err, sql.ErrNoRows) {
		if _, err := tx.ExecContext(ctx, "INSERT INTO kv(k, v) VALUES('audit_key_id', ?)", s.box.KEKIDHex()); err != nil {
			return err
		}
	} else if err != nil {
		return err
	} else if keyID != s.box.KEKIDHex() {
		return errors.New("审计主密钥不匹配")
	}
	var id int64
	var prev []byte
	err = tx.QueryRowContext(ctx, "SELECT id, mac FROM audit_log ORDER BY id DESC LIMIT 1").Scan(&id, &prev)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if err := checkAuditHead(ctx, tx, id, prev); err != nil {
		return err
	}
	e := AuditEntry{ID: id + 1, At: now.UnixMilli(), Actor: actor, Action: action, Target: target, Result: result}
	mac := auditMAC(s.box.Audit, prev, e)
	_, err = tx.ExecContext(ctx, "INSERT INTO audit_log(id, at, actor, action, target, result, mac) VALUES(?, ?, ?, ?, ?, ?, ?)",
		e.ID, e.At, e.Actor, e.Action, e.Target, e.Result, mac)
	if err != nil {
		return err
	}
	return auditCheckpoint(ctx, tx, "audit_head", e.ID, mac)
}

func (s *Store) Audit(ctx context.Context, now time.Time, actor, action, target, result string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := s.appendAudit(ctx, tx, now, actor, action, target, result); err != nil {
		return err
	}
	return tx.Commit()
}

func auditRows(ctx context.Context, db interface {
	QueryContext(context.Context, string, ...any) (*sql.Rows, error)
}) (*sql.Rows, error) {
	return db.QueryContext(ctx, "SELECT id, at, actor, action, target, result, mac FROM audit_log ORDER BY id")
}

var errAuditChain = errors.New("审计链校验失败")

func auditCheckpoint(ctx context.Context, tx *sql.Tx, key string, id int64, mac []byte) error {
	_, err := tx.ExecContext(ctx, `INSERT INTO kv(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v`,
		key, fmt.Sprintf("%d:%x", id, mac))
	return err
}

func auditPoint(ctx context.Context, tx *sql.Tx, key string) (int64, []byte, error) {
	var value string
	err := tx.QueryRowContext(ctx, "SELECT v FROM kv WHERE k=?", key).Scan(&value)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, nil, nil
	}
	if err != nil {
		return 0, nil, err
	}
	left, right, ok := strings.Cut(value, ":")
	id, numErr := strconv.ParseInt(left, 10, 64)
	mac, macErr := hex.DecodeString(right)
	if !ok || numErr != nil || macErr != nil || id < 1 || len(mac) != sha256.Size {
		return 0, nil, errAuditChain
	}
	return id, mac, nil
}

func checkAuditHead(ctx context.Context, tx *sql.Tx, id int64, mac []byte) error {
	wantID, want, err := auditPoint(ctx, tx, "audit_head")
	if err != nil {
		return err
	}
	if wantID != 0 && (id != wantID || !hmac.Equal(want, mac)) {
		return errAuditChain
	}
	return nil
}

func (s *Store) ReadAudit(ctx context.Context) ([]AuditEntry, bool, error) {
	tx, err := s.db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return nil, false, err
	}
	defer tx.Rollback()
	entries, err := s.verifyAudit(ctx, tx)
	if errors.Is(err, errAuditChain) {
		return entries, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	return entries, true, tx.Commit()
}

func (s *Store) verifyAudit(ctx context.Context, tx *sql.Tx) ([]AuditEntry, error) {
	id, prev, err := auditPoint(ctx, tx, "audit_anchor")
	if err != nil {
		return nil, err
	}
	rows, err := auditRows(ctx, tx)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var recent []AuditEntry
	for rows.Next() {
		var e AuditEntry
		var mac []byte
		if err := rows.Scan(&e.ID, &e.At, &e.Actor, &e.Action, &e.Target, &e.Result, &mac); err != nil {
			return nil, err
		}
		if e.ID != id+1 || !hmac.Equal(mac, auditMAC(s.box.Audit, prev, e)) {
			return recent, errAuditChain
		}
		id, prev = e.ID, mac
		recent = append(recent, e)
		if len(recent) == 200 {
			recent = append(recent[:0], recent[100:]...)
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}
	if err := checkAuditHead(ctx, tx, id, prev); err != nil {
		return recent, err
	}
	if len(recent) > 100 {
		recent = recent[len(recent)-100:]
	}
	return recent, nil
}

// CleanAudit drops the contiguous expired prefix after verifying the chain.
func (s *Store) CleanAudit(ctx context.Context, cutoff time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var id int64
	var mac []byte
	err = tx.QueryRowContext(ctx, `SELECT id,mac FROM audit_log WHERE id <
		(SELECT MIN(id) FROM audit_log WHERE at>=? OR id=(SELECT MAX(id) FROM audit_log))
		ORDER BY id DESC LIMIT 1`, cutoff.UnixMilli()).Scan(&id, &mac)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	if _, err := s.verifyAudit(ctx, tx); err != nil {
		return err
	}
	if err := auditCheckpoint(ctx, tx, "audit_anchor", id, mac); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "DELETE FROM audit_log WHERE id<=?", id); err != nil {
		return err
	}
	return tx.Commit()
}

// rekeyAudit verifies the complete chain before changing its signing key.
func (s *Store) rekeyAudit(ctx context.Context, tx *sql.Tx, old []byte) error {
	id, prev, err := auditPoint(ctx, tx, "audit_anchor")
	if err != nil {
		return err
	}
	prevNew := prev
	rows, err := auditRows(ctx, tx)
	if err != nil {
		return err
	}
	defer rows.Close()
	type update struct {
		id  int64
		mac []byte
	}
	var updates []update
	validOld, validNew := true, true
	for rows.Next() {
		var e AuditEntry
		var mac []byte
		if err := rows.Scan(&e.ID, &e.At, &e.Actor, &e.Action, &e.Target, &e.Result, &mac); err != nil {
			return err
		}
		if e.ID != id+1 {
			return errAuditChain
		}
		id = e.ID
		validOld = validOld && hmac.Equal(mac, auditMAC(old, prev, e))
		validNew = validNew && hmac.Equal(mac, auditMAC(s.box.Audit, prev, e))
		prev = mac
		prevNew = auditMAC(s.box.Audit, prevNew, e)
		updates = append(updates, update{e.ID, prevNew})
	}
	if err := rows.Err(); err != nil {
		return err
	}
	if err := rows.Close(); err != nil {
		return err
	}
	if err := checkAuditHead(ctx, tx, id, prev); err != nil {
		return err
	}
	if len(updates) == 0 {
		return nil
	}
	if !validNew {
		if !validOld {
			return errors.New("审计链无法验证，主密钥轮换未完成")
		}
		for _, u := range updates {
			if _, err := tx.ExecContext(ctx, "UPDATE audit_log SET mac = ? WHERE id = ?", u.mac, u.id); err != nil {
				return err
			}
		}
	}
	if err := auditCheckpoint(ctx, tx, "audit_head", id, prevNew); err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO kv(k, v) VALUES('audit_key_id', ?)
		ON CONFLICT(k) DO UPDATE SET v = excluded.v`, s.box.KEKIDHex())
	return err
}
