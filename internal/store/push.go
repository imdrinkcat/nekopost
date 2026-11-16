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

// Reasons recorded on skipped App deliveries.
const (
	SkipOrigin   = "来源设备，不重复推送"
	SkipType     = "此设备已关闭该消息类型"
	SkipPriority = "低于此设备的最低推送优先级"
	SkipCategory = "此设备已关闭该消息类别"
)

var priorityRank = map[model.Priority]int{model.PriorityLow: 1, model.PriorityNormal: 2, model.PriorityHigh: 3}

// skipReason reports why a device's push settings exclude msg ("" = push it).
// Categories are only known after classification, so checkCategory is false
// at insert time.
func (p PushPrefs) skipReason(msg *model.Message, checkCategory bool) string {
	switch {
	case len(p.Types) > 0 && !slices.Contains(p.Types, msg.Kind):
		return SkipType
	case checkCategory && len(p.Categories) > 0 && !slices.Contains(p.Categories, string(msg.Category)):
		return SkipCategory
	case priorityRank[msg.Priority] < priorityRank[p.MinPriority]:
		return SkipPriority
	}
	return ""
}

// pushTargets lists the App devices that receive m. primary means no other
// channel carries the message, so the App push decides its status: the
// rows are then not auxiliary, and the source device is recorded as skipped
// so the status can explain why nothing was pushed. Devices whose own
// settings exclude the message get a skipped row instead of a send attempt.
func pushTargets(ctx context.Context, tx *sql.Tx, m *model.Message, now time.Time, held, primary bool) ([]NewDelivery, error) {
	rows, err := tx.QueryContext(ctx, `SELECT id, push_prefs FROM devices WHERE revoked_at IS NULL AND suspended_at IS NULL
		AND push_state='ok' AND push_token_at>=? AND instr(','||scopes||',',',msg:read,')>0
		ORDER BY id`, now.Add(-45*24*time.Hour).UnixMilli())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var targets []NewDelivery
	for rows.Next() {
		var id, raw string
		if err := rows.Scan(&id, &raw); err != nil {
			return nil, err
		}
		var prefs PushPrefs
		_ = json.Unmarshal([]byte(raw), &prefs) // unreadable settings mean defaults
		t := NewDelivery{Channel: "fcm", Target: id, Held: held, Aux: !primary}
		switch {
		case id == m.OriginDevice && !prefs.IncludeOwn:
			if !primary {
				continue
			}
			t.Skip = SkipOrigin
		default:
			t.Skip = prefs.skipReason(m, false)
		}
		if t.Skip != "" {
			t.Held = false
		}
		targets = append(targets, t)
	}
	return targets, rows.Err()
}

type PushPrefs struct {
	IncludeOwn  bool           `json:"include_own"`
	Types       []string       `json:"types"`
	Categories  []string       `json:"categories"`
	MinPriority model.Priority `json:"min_priority"`
}

// PushRegistration returns the device's FCM registration for msg. skip is
// non-empty when the message must not be pushed to this device: its push
// credential is gone, the device is suspended or revoked, or its own
// settings exclude the message.
func (s *Store) PushRegistration(ctx context.Context, id string, msg model.Message, now time.Time) (registration, skip string, err error) {
	var prefs string
	err = s.db.QueryRowContext(ctx, `SELECT push_token,push_prefs FROM devices WHERE id=? AND revoked_at IS NULL AND suspended_at IS NULL
		AND push_state='ok' AND push_token IS NOT NULL AND push_token_at>=? AND instr(','||scopes||',',',msg:read,')>0`,
		id, now.Add(-45*24*time.Hour).UnixMilli()).Scan(&registration, &prefs)
	if errors.Is(err, sql.ErrNoRows) {
		return "", PushSkipped, nil
	}
	if err != nil {
		return "", "", err
	}
	var p PushPrefs
	if err := json.Unmarshal([]byte(prefs), &p); err != nil {
		return "", "", err
	}
	if reason := p.skipReason(&msg, true); reason != "" {
		return "", reason, nil
	}
	return registration, "", nil
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

// PushSkipped is the reason recorded when a device can no longer receive
// pushes (suspended, revoked or its push credential is gone). Before 0.10.0
// it was stored on failed rows; PushStatus still ignores those.
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
		WHERE channel='fcm' AND state<>'skipped' AND last_attempt_at IS NOT NULL AND last_error NOT IN ('',?)
		ORDER BY last_attempt_at DESC LIMIT 1`, PushSkipped).Scan(&failure, &v.Error)
	if errors.Is(err, sql.ErrNoRows) {
		err = nil
	}
	v.LastFailure = fromMs(failure)
	return v, err
}

// PushDevice is a device that can currently receive App pushes.
type PushDevice struct{ ID, Token string }

// PushDevices lists devices that can receive pushes, except one (the device
// that caused the change being signalled).
func (s *Store) PushDevices(ctx context.Context, except string, now time.Time) ([]PushDevice, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id, push_token FROM devices WHERE id<>? AND revoked_at IS NULL AND suspended_at IS NULL
		AND push_state='ok' AND push_token IS NOT NULL AND push_token_at>=? AND instr(','||scopes||',',',msg:read,')>0
		ORDER BY id`, except, now.Add(-45*24*time.Hour).UnixMilli())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []PushDevice
	for rows.Next() {
		var d PushDevice
		if err := rows.Scan(&d.ID, &d.Token); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// CreateTestDevice inserts a push-enabled device directly. Tests only.
func (s *Store) CreateTestDevice(ctx context.Context, id, scopes, prefs, token string, now time.Time) error {
	if _, err := s.db.ExecContext(ctx, `INSERT INTO devices(id,name,model,app_version,scopes,reauth_jwk,created_at,push_prefs)
		VALUES(?,?,'','',?,'',?,?)`, id, id, scopes, now.UnixMilli(), prefs); err != nil {
		return err
	}
	return s.UpdateDevicePush(ctx, id, token, now)
}
