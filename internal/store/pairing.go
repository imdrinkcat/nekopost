package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"nekopost/internal/auth"
	"nekopost/internal/model"
)

var (
	ErrPairingContested = errors.New("pairing contested")
	ErrPairingPending   = errors.New("pairing pending")
	ErrPairingGone      = errors.New("pairing gone")
	ErrLocked           = errors.New("emergency lockdown")
)

type Pairing struct {
	ID, Scopes, SourceID, Name, Model, AppVersion, State, Code, JKT string
	Choices                                                         []string
	Expires                                                         time.Time
}

type PairClaim struct {
	JWK, JKT, ReauthJWK, Name, Model, AppVersion, Code string
	Choices                                            []string
}

const pairingFields = `id,scopes,source_id,COALESCE(NULLIF(claim_name,''),name_hint),claim_model,claim_version,state,
	short_code,choices,claim_jkt,expires_at`

func scanPairing(scan func(...any) error) (Pairing, error) {
	var p Pairing
	var choices string
	var expiry int64
	err := scan(&p.ID, &p.Scopes, &p.SourceID, &p.Name, &p.Model, &p.AppVersion, &p.State, &p.Code, &choices, &p.JKT, &expiry)
	if errors.Is(err, sql.ErrNoRows) {
		return p, ErrNotFound
	}
	if err != nil {
		return p, err
	}
	if choices != "" {
		if err := json.Unmarshal([]byte(choices), &p.Choices); err != nil {
			return p, err
		}
	}
	p.Expires = time.UnixMilli(expiry)
	return p, nil
}

func (s *Store) NewPairing(ctx context.Context, p Pairing, codeHash, shortHash, actor string, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var locked bool
	if err := tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM kv WHERE k='lockdown')").Scan(&locked); err != nil {
		return err
	}
	if locked && actor != "cli" {
		return ErrLocked
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO pairings(id,code_hash,short_hash,scopes,source_id,name_hint,created_by,
		created_at,expires_at,state) VALUES(?,?,?,?,?,?,?,?,?,'open')`, p.ID, codeHash, shortHash, p.Scopes, p.SourceID, p.Name,
		actor, now.UnixMilli(), p.Expires.UnixMilli()); err != nil {
		return err
	}
	if err := s.appendAudit(ctx, tx, now, actor, "pairing.start", p.ID, "ok"); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) Pairing(ctx context.Context, id string) (Pairing, error) {
	return scanPairing(s.db.QueryRowContext(ctx, "SELECT "+pairingFields+" FROM pairings WHERE id=?", id).Scan)
}

func (s *Store) PendingPairings(ctx context.Context, now time.Time) ([]Pairing, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT "+pairingFields+" FROM pairings WHERE state='claimed' AND expires_at>? ORDER BY created_at,id", now.UnixMilli())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]Pairing, 0)
	for rows.Next() {
		p, err := scanPairing(rows.Scan)
		if err != nil {
			return nil, err
		}
		p.Code = ""
		out = append(out, p)
	}
	return out, rows.Err()
}

func (s *Store) ClaimPairing(ctx context.Context, codeHash string, claim PairClaim, now time.Time) (Pairing, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return Pairing{}, err
	}
	defer tx.Rollback()
	p, err := scanPairing(tx.QueryRowContext(ctx, "SELECT "+pairingFields+" FROM pairings WHERE code_hash=? OR short_hash=?", codeHash, codeHash).Scan)
	if err != nil {
		return p, err
	}
	if !p.Expires.After(now) {
		return p, ErrPairingGone
	}
	switch p.State {
	case "contested":
		return p, ErrPairingContested
	case "claimed", "approved":
		if _, err := tx.ExecContext(ctx, "UPDATE pairings SET state='contested' WHERE id=?", p.ID); err != nil {
			return p, err
		}
		if err := s.appendAudit(ctx, tx, now, "system", "pairing.contested", p.ID, "denied"); err != nil {
			return p, err
		}
		if err := tx.Commit(); err != nil {
			return p, err
		}
		return p, ErrPairingContested
	case "open":
		choices, err := json.Marshal(claim.Choices)
		if err != nil {
			return p, err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE pairings SET state='claimed',claim_jwk=?,claim_jkt=?,claim_reauth_jwk=?,
			claim_name=?,claim_model=?,claim_version=?,short_code=?,choices=? WHERE id=?`,
			claim.JWK, claim.JKT, claim.ReauthJWK, claim.Name, claim.Model, claim.AppVersion, claim.Code, string(choices), p.ID); err != nil {
			return p, err
		}
		if err := s.appendAudit(ctx, tx, now, "system", "pairing.claim", p.ID, "ok"); err != nil {
			return p, err
		}
		p.State, p.JKT, p.Code, p.Choices = "claimed", claim.JKT, claim.Code, claim.Choices
		if claim.Name != "" {
			p.Name = claim.Name
		}
		p.Model, p.AppVersion = claim.Model, claim.AppVersion
		return p, tx.Commit()
	default:
		return p, ErrPairingGone
	}
}

func (s *Store) DecidePairing(ctx context.Context, id, choice, actor string, now time.Time) (bool, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	var locked bool
	if err := tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM kv WHERE k='lockdown')").Scan(&locked); err != nil {
		return false, err
	}
	if locked && actor != "cli" {
		return false, ErrLocked
	}
	var code, creator string
	err = tx.QueryRowContext(ctx, "SELECT short_code, created_by FROM pairings WHERE id=? AND state='claimed' AND expires_at>?", id, now.UnixMilli()).Scan(&code, &creator)
	if errors.Is(err, sql.ErrNoRows) {
		return false, ErrNotFound
	}
	if err != nil {
		return false, err
	}
	if actor != "cli" && !strings.HasPrefix(actor, "bot:tg:") && actor != creator {
		return false, ErrNotFound
	}
	ok := code == choice
	state, result := "rejected", "denied"
	if ok {
		state, result = "approved", "ok"
	}
	if _, err := tx.ExecContext(ctx, "UPDATE pairings SET state=? WHERE id=?", state, id); err != nil {
		return false, err
	}
	if err := s.appendAudit(ctx, tx, now, actor, "pairing.confirm", id, result); err != nil {
		return false, err
	}
	return ok, tx.Commit()
}

func (s *Store) ConsumePairing(ctx context.Context, id, jkt, deviceID, tokenHash string, now time.Time) (Device, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return Device{}, err
	}
	defer tx.Rollback()
	p, err := scanPairing(tx.QueryRowContext(ctx, "SELECT "+pairingFields+" FROM pairings WHERE id=? AND claim_jkt=?", id, jkt).Scan)
	if err != nil {
		return Device{}, err
	}
	if !p.Expires.After(now) {
		return Device{}, ErrPairingGone
	}
	switch p.State {
	case "claimed":
		return Device{}, ErrPairingPending
	case "contested":
		return Device{}, ErrPairingContested
	case "approved":
	default:
		return Device{}, ErrPairingGone
	}
	d := Device{ID: deviceID, Name: p.Name, Model: p.Model, AppVersion: p.AppVersion, Scopes: p.Scopes, SourceID: p.SourceID, JKT: jkt}
	if d.Name == "" {
		d.Name = "Nekopost"
	}
	ms := now.UnixMilli()
	var source any
	if d.Has(ScopeSend) {
		if !model.ValidSourceID(d.SourceID) {
			return Device{}, ErrNotFound
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO sources(id,name,enabled,created_at,updated_at) VALUES(?,?,1,?,?)
			ON CONFLICT(id) DO NOTHING`, d.SourceID, d.Name, ms, ms); err != nil {
			return Device{}, err
		}
		source = d.SourceID
	}
	if err := tx.QueryRowContext(ctx, "SELECT claim_reauth_jwk FROM pairings WHERE id=?", id).Scan(&d.ReauthJWK); err != nil {
		return Device{}, err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO devices(id,name,model,app_version,scopes,source_id,reauth_jwk,created_at)
		VALUES(?,?,?,?,?,?,?,?)`, d.ID, d.Name, d.Model, d.AppVersion, d.Scopes, source, d.ReauthJWK, ms); err != nil {
		return Device{}, err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO device_tokens(hash,device_id,jkt,jwk,created_at)
		SELECT ?,?,claim_jkt,claim_jwk,? FROM pairings WHERE id=?`, tokenHash, d.ID, ms, id); err != nil {
		return Device{}, err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE pairings SET state='done',device_id=?,short_code='',choices='' WHERE id=?`, d.ID, id); err != nil {
		return Device{}, err
	}
	if err := s.appendAudit(ctx, tx, now, "system", "device.pair", d.ID, "ok"); err != nil {
		return Device{}, err
	}
	return d, tx.Commit()
}

func PairingCodeHash(code string) string { return auth.Hash("nekopost/pairing/v1|" + code) }

func (s *Store) PairingOwned(ctx context.Context, id, actor string) (Pairing, error) {
	return scanPairing(s.db.QueryRowContext(ctx, "SELECT "+pairingFields+" FROM pairings WHERE id=? AND created_by=?", id, actor).Scan)
}
