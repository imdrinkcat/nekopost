package store

import (
	"errors"
	"slices"
	"strings"
	"testing"
	"time"
)

func TestReadBatch(t *testing.T) {
	s := openTest(t)
	now := time.Now()
	old := newMsg("fixture", "旧消息", "", now)
	code := newMsg("fixture", "验证码 123456", "", now.Add(time.Second))
	newer := newMsg("fixture", "按下按钮后才到", "", now.Add(2*time.Second))
	if _, err := s.InsertMessage(ctx, old, tg, 0); err != nil {
		t.Fatal(err)
	}
	if _, err := s.InsertMessage(ctx, code, tg, 0); err != nil {
		t.Fatal(err)
	}
	if _, err := s.InsertMessage(ctx, newer, tg, 0); err != nil {
		t.Fatal(err)
	}
	before, err := s.ChangesSince(ctx, 0, 50)
	if err != nil {
		t.Fatal(err)
	}

	// Codes only, bounded by the code message.
	changed, err := s.MarkAllRead(ctx, code.ID, true, "device:d_a", now)
	if err != nil || !slices.Equal(changed, []string{code.ID}) {
		t.Fatal(changed, err)
	}
	// Everything up to the code message: the newer one stays unread.
	changed, err = s.MarkAllRead(ctx, code.ID, false, "device:d_a", now)
	if err != nil || !slices.Equal(changed, []string{old.ID}) {
		t.Fatal(changed, err)
	}
	if st, _ := s.InboxState(ctx, newer.ID); st.Read {
		t.Fatal("a message newer than the bound was marked read")
	}
	if n, _ := s.UnreadCount(ctx); n != 1 {
		t.Fatal(n)
	}
	// Idempotent: nothing left to change.
	if changed, err := s.MarkAllRead(ctx, code.ID, false, "device:d_a", now); err != nil || len(changed) != 0 {
		t.Fatal(changed, err)
	}
	// Each change got a new seq, in list order, for /v1/sync and events.
	after, err := s.ChangesSince(ctx, before.Next, 50)
	if err != nil || len(after.Changes) != 2 || after.Changes[0].MessageID != code.ID || after.Changes[1].MessageID != old.ID {
		t.Fatal(after, err)
	}

	// Mark unread by ID: unknown and repeated IDs are skipped.
	changed, err = s.SetMessagesRead(ctx, []string{old.ID, old.ID, "m_00000000000000000000000000", code.ID, newer.ID}, false, "web:google:1", now)
	if err != nil || !slices.Equal(changed, []string{code.ID, old.ID}) {
		t.Fatal(changed, err)
	}
	changed, err = s.SetMessagesRead(ctx, []string{newer.ID, old.ID}, true, "web:google:1", now)
	if err != nil || !slices.Equal(changed, []string{newer.ID, old.ID}) {
		t.Fatal(changed, err)
	}
	if changed, err := s.SetMessagesRead(ctx, []string{newer.ID}, true, "web:google:1", now); err != nil || len(changed) != 0 {
		t.Fatal(changed, err)
	}
	if _, err := s.SetMessagesRead(ctx, make([]string, MaxReadBatch+1), true, "x", now); err == nil {
		t.Fatal("over the batch limit")
	}
	if _, err := s.MarkAllRead(ctx, "m_00000000000000000000000000", false, "x", now); !errors.Is(err, ErrStaleBound) {
		t.Fatal(err)
	}

	// One audit entry per batch, never one per message.
	entries, ok, err := s.ReadAudit(ctx)
	if err != nil || !ok {
		t.Fatal(ok, err)
	}
	var got []string
	for _, e := range entries {
		if strings.HasPrefix(e.Action, "message.") {
			got = append(got, e.Actor+" "+e.Action+" "+e.Target)
		}
	}
	want := []string{
		"device:d_a message.read_all count=1", "device:d_a message.read_all count=1", "device:d_a message.read_all count=0",
		"web:google:1 message.unread_batch count=2", "web:google:1 message.read_batch count=2", "web:google:1 message.read_batch count=0",
	}
	if !slices.Equal(got, want) {
		t.Fatal(got)
	}
}
