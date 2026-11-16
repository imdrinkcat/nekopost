package store

import (
	"testing"
	"time"
)

func TestAuditRetentionAndTailDeletion(t *testing.T) {
	s := openTest(t)
	now := time.Now()
	for _, at := range []time.Time{now.Add(-181 * 24 * time.Hour), now.Add(-180 * 24 * time.Hour), now} {
		if err := s.Audit(ctx, at, "cli", "fixture", "", "ok"); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.CleanAudit(ctx, now.Add(-180*24*time.Hour)); err != nil {
		t.Fatal(err)
	}
	if entries, valid, err := s.ReadAudit(ctx); err != nil || !valid || len(entries) != 2 || entries[0].ID != 2 {
		t.Fatal(entries, valid, err)
	}
	if _, err := s.db.Exec("DELETE FROM audit_log WHERE id=3"); err != nil {
		t.Fatal(err)
	}
	if _, valid, err := s.ReadAudit(ctx); err != nil || valid {
		t.Fatal(valid, err)
	}
	if err := s.Audit(ctx, now, "cli", "fixture", "", "ok"); err == nil {
		t.Fatal("cannot append over a missing audit record")
	}
}
