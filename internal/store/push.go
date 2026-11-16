package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"slices"
	"time"

	"nekopost/internal/model"
)

func pushTargets(ctx context.Context, tx *sql.Tx, origin string, now time.Time, held bool) ([]NewDelivery, error) {
	rows, err := tx.QueryContext(ctx, `SELECT id FROM devices WHERE (id<>? OR json_extract(push_prefs,'$.include_own')=1) AND revoked_at IS NULL AND suspended_at IS NULL
		AND push_state='ok' AND push_token_at>=? AND instr(','||scopes||',',',msg:read,')>0
		ORDER BY id`, origin, now.Add(-45*24*time.Hour).UnixMilli())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var targets []NewDelivery
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		targets = append(targets, NewDelivery{Channel: "fcm", Target: id, Held: held, Aux: true})
	}
	return targets, rows.Err()
}

type PushPrefs struct {
	IncludeOwn  bool           `json:"include_own"`
	Types       []string       `json:"types"`
	Categories  []string       `json:"categories"`
	MinPriority model.Priority `json:"min_priority"`
}

func (s *Store) PushRegistration(ctx context.Context, id string, msg model.Message, now time.Time) (string, bool, error) {
	var registration, prefs string
	err := s.db.QueryRowContext(ctx, `SELECT push_token,push_prefs FROM devices WHERE id=? AND revoked_at IS NULL AND suspended_at IS NULL
		AND push_state='ok' AND push_token IS NOT NULL AND push_token_at>=? AND instr(','||scopes||',',',msg:read,')>0`,
		id, now.Add(-45*24*time.Hour).UnixMilli()).Scan(&registration, &prefs)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	var p PushPrefs
	if err := json.Unmarshal([]byte(prefs), &p); err != nil {
		return "", false, err
	}
	if (len(p.Types) > 0 && !slices.Contains(p.Types, msg.Kind)) || (len(p.Categories) > 0 && !slices.Contains(p.Categories, string(msg.Category))) {
		return "", false, nil
	}
	priority := map[model.Priority]int{model.PriorityLow: 1, model.PriorityNormal: 2, model.PriorityHigh: 3}
	return registration, priority[msg.Priority] >= priority[p.MinPriority], nil
}

func (s *Store) RejectPushRegistration(ctx context.Context, id, registration string) error {
	_, err := s.db.ExecContext(ctx, `UPDATE devices SET push_token=NULL,push_state='invalid' WHERE id=? AND push_token=?`, id, registration)
	return err
}

func (s *Store) CleanDevices(ctx context.Context, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `UPDATE devices SET push_token=NULL,push_state='stale' WHERE push_state='ok' AND push_token_at<?`,
		now.Add(-45*24*time.Hour).UnixMilli()); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM pairings WHERE expires_at<?`, now.Add(-24*time.Hour).UnixMilli()); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM device_challenges WHERE expires_at<?`, now.UnixMilli()); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM devices WHERE revoked_at<?`, now.Add(-30*24*time.Hour).UnixMilli()); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) UpdatePushPrefs(ctx context.Context, id string, prefs PushPrefs) error {
	wire, err := json.Marshal(prefs)
	if err != nil {
		return err
	}
	return s.execOne(ctx, "UPDATE devices SET push_prefs=? WHERE id=? AND revoked_at IS NULL AND suspended_at IS NULL", string(wire), id)
}

func (s *Store) PushTokenForTest(ctx context.Context, id string, now time.Time) (string, error) {
	var token string
	err := s.db.QueryRowContext(ctx, `SELECT push_token FROM devices WHERE id=? AND push_token IS NOT NULL AND push_state='ok'
		AND revoked_at IS NULL AND suspended_at IS NULL AND push_token_at>=?`, id, now.Add(-45*24*time.Hour).UnixMilli()).Scan(&token)
	if errors.Is(err, sql.ErrNoRows) {
		err = ErrNotFound
	}
	return token, err
}

// PushSkipped is the delivery error for an FCM delivery the device's own
// settings ruled out. It is expected, so PushStatus does not count it.
const PushSkipped = "设备已暂停、推送凭证不可用或消息类型已关闭"

type PushStatus struct {
	Registered, Stale, Invalid int
	LastDelivered, LastFailure time.Time
	Error                      string
}

func (s *Store) PushStatus(ctx context.Context) (PushStatus, error) {
	var v PushStatus
	err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FILTER(WHERE push_state='ok'),
		COUNT(*) FILTER(WHERE push_state='stale'), COUNT(*) FILTER(WHERE push_state='invalid')
		FROM devices WHERE revoked_at IS NULL`).Scan(&v.Registered, &v.Stale, &v.Invalid)
	if err != nil {
		return v, err
	}
	var delivered sql.NullInt64
	if err := s.db.QueryRowContext(ctx, "SELECT MAX(delivered_at) FROM deliveries WHERE channel='fcm'").Scan(&delivered); err != nil {
		return v, err
	}
	v.LastDelivered = fromMs(delivered)
	var failure sql.NullInt64
	err = s.db.QueryRowContext(ctx, `SELECT last_attempt_at,last_error FROM deliveries
		WHERE channel='fcm' AND last_error NOT IN ('',?) ORDER BY last_attempt_at DESC LIMIT 1`, PushSkipped).Scan(&failure, &v.Error)
	if errors.Is(err, sql.ErrNoRows) {
		err = nil
	}
	v.LastFailure = fromMs(failure)
	return v, err
}
