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

func TestListAndRevokeWebAccess(t *testing.T) {
	s := openTest(t)
	now := time.Now()
	const identity = "google:123"
	login := func(pending, active, browser string, trust bool) {
		t.Helper()
		// One identity per login keeps clear of the three-per-hour approval limit.
		if _, err := s.StartWebSession(ctx, WebSession{Hash: pending, Identity: identity + pending, CSRF: "test", Browser: "Chrome"},
			WebApproval{ID: "approval-" + pending, Code: "42", Choices: []string{"21", "42", "33"}, Expires: now.Add(5 * time.Minute)}, "", now); err != nil {
			t.Fatal(err)
		}
		if ok, err := s.DecideWebApproval(ctx, "approval-"+pending, "42", "cli", trust, now); !ok || err != nil {
			t.Fatal(ok, err)
		}
		session, err := s.WebSession(ctx, pending, now)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := s.ActivateWebSession(ctx, session, active, "csrf", browser, now); err != nil {
			t.Fatal(err)
		}
	}
	login("p1", "a1", "b1", true)
	login("p2", "a2", "", false)
	login("p3", "a3", "", false)

	sessions, browsers, err := s.ListWebSessions(ctx, now)
	if err != nil || len(sessions) != 3 || len(browsers) != 1 || browsers[0].Hash != "b1" {
		t.Fatal(sessions, browsers, err)
	}
	if err := s.RevokeWebAccess(ctx, "web:"+identity, "session", "missing", "", now); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if err := s.RevokeWebAccess(ctx, "web:"+identity, "session", "a2", "", now); err != nil {
		t.Fatal(err)
	}
	if _, err := s.WebSession(ctx, "a2", now); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	// Revoking a trusted browser also ends the sessions it carries.
	if err := s.RevokeWebAccess(ctx, "web:"+identity, "browser", "b1", "", now); err != nil {
		t.Fatal(err)
	}
	if _, err := s.WebSession(ctx, "a1", now); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if err := s.RevokeWebAccess(ctx, "web:"+identity, "browser", "b1", "", now); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}

	// From the web, "others" keeps the caller's session and trusted browsers.
	login("p4", "a4", "b4", true)
	if err := s.RevokeWebAccess(ctx, "web:"+identity, "others", "", "a4", now); err != nil {
		t.Fatal(err)
	}
	sessions, browsers, err = s.ListWebSessions(ctx, now)
	if err != nil || len(sessions) != 1 || sessions[0].Hash != "a4" || len(browsers) != 1 {
		t.Fatal(sessions, browsers, err)
	}
	// From the CLI nothing is kept.
	if err := s.RevokeWebAccess(ctx, "cli", "others", "", "", now); err != nil {
		t.Fatal(err)
	}
	sessions, browsers, err = s.ListWebSessions(ctx, now)
	if err != nil || len(sessions) != 0 || len(browsers) != 0 {
		t.Fatal(sessions, browsers, err)
	}
	if _, valid, err := s.ReadAudit(ctx); !valid || err != nil {
		t.Fatal(valid, err)
	}
}
