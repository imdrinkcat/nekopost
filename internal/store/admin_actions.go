package store

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

type SourceChange struct {
	ID, Name, Icon, Hash, Prefix string
	Enabled                      *bool
}

func (s *Store) ChangeSource(ctx context.Context, actor, action string, v SourceChange, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var res sql.Result
	switch action {
	case "create":
		res, err = tx.ExecContext(ctx, `INSERT INTO sources(id,name,icon,enabled,created_at,updated_at)
			VALUES(?,?,?,1,?,?) ON CONFLICT(id) DO NOTHING`, v.ID, v.Name, v.Icon, now.UnixMilli(), now.UnixMilli())
	case "rotate":
		res, err = tx.ExecContext(ctx, "UPDATE sources SET updated_at=? WHERE id=?", now.UnixMilli(), v.ID)
	case "patch":
		var enabled any
		if v.Enabled != nil {
			enabled = boolInt(*v.Enabled)
		}
		res, err = tx.ExecContext(ctx, `UPDATE sources SET name=COALESCE(NULLIF(?,''),name),icon=COALESCE(NULLIF(?,''),icon),
			enabled=COALESCE(?,enabled),updated_at=? WHERE id=?`, v.Name, v.Icon, enabled, now.UnixMilli(), v.ID)
	case "delete":
		res, err = tx.ExecContext(ctx, "DELETE FROM sources WHERE id=?", v.ID)
	default:
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n != 1 {
		if action == "create" {
			return ErrExists
		}
		return ErrNotFound
	}
	if action == "rotate" {
		if _, err := tx.ExecContext(ctx, "UPDATE source_tokens SET revoked_at=? WHERE source_id=? AND revoked_at IS NULL", now.UnixMilli(), v.ID); err != nil {
			return err
		}
	}
	if action == "create" || action == "rotate" {
		if _, err := tx.ExecContext(ctx, "INSERT INTO source_tokens(hash,source_id,prefix,created_at) VALUES(?,?,?,?)",
			v.Hash, v.ID, v.Prefix, now.UnixMilli()); err != nil {
			return err
		}
	}
	if err := s.appendAudit(ctx, tx, now, actor, "source."+action, v.ID, "ok"); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) ChangeAdminKey(ctx context.Context, actor, action, name, digest, prefix string, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	switch action {
	case "create":
		if _, err := tx.ExecContext(ctx, "INSERT INTO admin_keys(hash,name,prefix,created_at) VALUES(?,?,?,?)",
			digest, name, prefix, now.UnixMilli()); err != nil {
			return err
		}
	case "revoke":
		res, err := tx.ExecContext(ctx, "UPDATE admin_keys SET revoked_at=? WHERE prefix=? AND revoked_at IS NULL", now.UnixMilli(), prefix)
		if err != nil {
			return err
		}
		if n, err := res.RowsAffected(); err != nil {
			return err
		} else if n == 0 {
			return ErrNotFound
		}
	default:
		return ErrNotFound
	}
	if err := s.appendAudit(ctx, tx, now, actor, "admin_key."+action, prefix, "ok"); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) RetryMessage(ctx context.Context, id, actor string, rescue bool, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var status string
	err = tx.QueryRowContext(ctx, `SELECT m.status FROM messages m JOIN bodies b ON b.message_id=m.id
		WHERE m.id=? AND b.expires_at>?`, id, now.UnixMilli()).Scan(&status)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	var count int
	if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM deliveries WHERE message_id=? AND state IN ('sending','pending','retrying','held')`, id).Scan(&count); err != nil {
		return err
	}
	if count != 0 {
		return ErrDeliveryInFlight
	}
	action := "message.redeliver"
	if rescue {
		if status != "filtered" {
			return ErrNotRescuable
		}
		action = "message.rescue"
		if _, err := tx.ExecContext(ctx, `UPDATE deliveries SET state='pending',next_attempt_at=?,created_at=?
			WHERE message_id=? AND state='filtered'`, now.UnixMilli(), now.UnixMilli(), id); err != nil {
			return err
		}
	} else {
		if status == "filtered" {
			return ErrNotRescuable
		}
		// Retain old Telegram message IDs so a later deletion can erase the older copies too.
		res, err := tx.ExecContext(ctx, `UPDATE deliveries SET state='pending',next_attempt_at=?,created_at=?,
			parts_total=0,parts_sent=0,attempts=0,last_error='',delivered_at=NULL,uncertain=0
			WHERE message_id=? AND channel='telegram'`, now.UnixMilli(), now.UnixMilli(), id)
		if err != nil {
			return err
		}
		if n, err := res.RowsAffected(); err != nil {
			return err
		} else if n == 0 {
			return ErrNotFound
		}
	}
	if _, err := s.updateAggregate(ctx, tx, id, now); err != nil {
		return err
	}
	if err := s.appendAudit(ctx, tx, now, actor, action, id, "ok"); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) DestroyBody(ctx context.Context, id, actor string, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var finished bool
	err = tx.QueryRowContext(ctx, "SELECT finished_at IS NOT NULL FROM messages WHERE id=?", id).Scan(&finished)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if !finished {
		return ErrDeliveryInFlight
	}
	seq, err := nextSeq(ctx, tx)
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "DELETE FROM bodies WHERE message_id=?", id); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "UPDATE messages SET body_state='expired',seq=?,content_hash='',idem_key=NULL WHERE id=?", seq, id); err != nil {
		return err
	}
	if err := s.appendAudit(ctx, tx, now, actor, "message.shred", id, "ok"); err != nil {
		return err
	}
	return tx.Commit()
}
