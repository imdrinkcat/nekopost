package store

import (
	"errors"
	"path/filepath"
	"testing"
	"time"
)

func TestWebApprovalDenialCooldownAndUnlock(t *testing.T) {
	s := openTest(t)
	now := time.Now()
	const identity = "google:123"
	start := func(id string, at time.Time) error {
		_, err := s.StartWebSession(ctx, WebSession{Hash: id, Identity: identity, CSRF: "test"},
			WebApproval{ID: "approval-" + id, Code: "42", Choices: []string{"21", "42", "33"}, Expires: at.Add(5 * time.Minute)}, "", at)
		return err
	}
	if err := start("expired", now); err != nil {
		t.Fatal(err)
	}
	if err := start("wrong", now.Add(6*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if ok, err := s.DecideWebApproval(ctx, "approval-wrong", "21", "cli", true, now.Add(7*time.Minute)); ok || err != nil {
		t.Fatal(ok, err)
	}
	if err := start("cooldown", now.Add(10*time.Minute)); !errors.Is(err, ErrWebRestricted) {
		t.Fatal(err)
	}
	if err := start("suspended", now.Add(2*time.Hour)); !errors.Is(err, ErrWebRestricted) {
		t.Fatal(err)
	}
	if err := s.UnlockWebIdentity(ctx, identity, now.Add(2*time.Hour)); err != nil {
		t.Fatal(err)
	}
	if err := start("unlocked", now.Add(2*time.Hour)); err != nil {
		t.Fatal(err)
	}
	if ok, err := s.DecideWebApproval(ctx, "approval-unlocked", "42", "bot:tg:1001", true, now.Add(2*time.Hour)); !ok || err != nil {
		t.Fatal(ok, err)
	}
	session, err := s.WebSession(ctx, "unlocked", now.Add(2*time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	if trusted, err := s.ActivateWebSession(ctx, session, "new-cookie", "new-csrf", "not-trusted", now.Add(2*time.Hour)); trusted || err != nil {
		t.Fatal(trusted, err)
	}
	if _, err := s.WebSession(ctx, "unlocked", now.Add(2*time.Hour)); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := s.WebSession(ctx, "new-cookie", now.Add(15*time.Hour)); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
}

func TestMigrateV3AndRotateAudit(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nekopost.db")
	options := TestingOptions()
	first, err := Open(path, options)
	if err != nil {
		t.Fatal(err)
	}
	msg := newMsg("iphone", "preserved after migration", "", time.Now())
	if _, err := first.InsertMessage(ctx, msg, tg, 0); err != nil {
		t.Fatal(err)
	}
	if _, err := first.db.Exec("DROP TABLE device_challenges; DROP TABLE pairings; DROP TABLE device_tokens; DROP TABLE devices; ALTER TABLE messages DROP COLUMN origin_device; ALTER TABLE web_sessions DROP COLUMN reauth_at; DROP TABLE web_sessions; DROP TABLE trusted_browsers; DROP TABLE approvals; DROP TABLE web_identity_guard; DROP TABLE audit_log; PRAGMA user_version=3"); err != nil {
		t.Fatal(err)
	}
	if err := first.Close(); err != nil {
		t.Fatal(err)
	}
	// The earlier temporary v4 backup was made during fixture creation.
	options.Now = func() time.Time { return time.Now().Add(time.Minute) }
	v4, err := Open(path, options)
	if err != nil {
		t.Fatal(err)
	}
	var version int
	if err := v4.db.QueryRow("PRAGMA user_version").Scan(&version); err != nil || version != 5 {
		t.Fatal(version, err)
	}
	if _, body, _, ok, err := v4.ReadBody(ctx, msg.ID); err != nil || !ok || body != msg.Body {
		t.Fatal(ok, err)
	}
	if err := v4.Audit(ctx, time.Now(), "cli", "test.login", "", "ok"); err != nil {
		t.Fatal(err)
	}
	if err := v4.Close(); err != nil {
		t.Fatal(err)
	}
	options.PreviousKEK, options.KEK = options.KEK, bytesRepeat(0x9b)
	rotated, err := Open(path, options)
	if err != nil {
		t.Fatal(err)
	}
	defer rotated.Close()
	if audit, ok, err := rotated.ReadAudit(ctx); len(audit) != 1 || !ok || err != nil {
		t.Fatal(len(audit), ok, err)
	}
	if err := rotated.Audit(ctx, time.Now(), "cli", "test.second", "", "ok"); err != nil {
		t.Fatal(err)
	}
	if _, err := rotated.db.Exec("UPDATE audit_log SET actor = 'changed' WHERE id = 1"); err != nil {
		t.Fatal(err)
	}
	if _, ok, err := rotated.ReadAudit(ctx); ok || err != nil {
		t.Fatal(ok, err)
	}
	if _, _, _, ok, err := rotated.ReadBody(ctx, msg.ID); !ok || err != nil {
		t.Fatal(ok, err)
	}
}
