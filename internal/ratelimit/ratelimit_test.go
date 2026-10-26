package ratelimit

import (
	"testing"
	"time"
)

func TestLimiter(t *testing.T) {
	now := time.Unix(0, 0)
	l := NewLimiter(60, 3)
	l.Now = func() time.Time { return now }
	for i := 0; i < 3; i++ {
		if ok, _ := l.Allow("a"); !ok {
			t.Fatalf("burst request %d denied", i)
		}
	}
	ok, wait := l.Allow("a")
	if ok || wait <= 0 || wait > time.Second {
		t.Fatalf("expected denial with ~1s wait, got %v %v", ok, wait)
	}
	if ok, _ := l.Allow("b"); !ok {
		t.Error("keys must be independent")
	}
	now = now.Add(time.Second)
	if ok, _ := l.Allow("a"); !ok {
		t.Error("token should refill after 1s")
	}
	now = now.Add(time.Hour)
	l.Cleanup()
	if len(l.buckets) != 0 {
		t.Error("cleanup should drop refilled buckets")
	}
}

func TestFailureGuard(t *testing.T) {
	now := time.Unix(0, 0)
	g := NewFailureGuard(3, time.Minute, 10*time.Minute)
	g.Now = func() time.Time { return now }
	g.Fail("ip")
	g.Fail("ip")
	if b, _ := g.Blocked("ip"); b {
		t.Fatal("blocked too early")
	}
	g.Fail("ip")
	if b, d := g.Blocked("ip"); !b || d != 10*time.Minute {
		t.Fatalf("should be blocked: %v %v", b, d)
	}
	now = now.Add(11 * time.Minute)
	if b, _ := g.Blocked("ip"); b {
		t.Error("block should expire")
	}
	// Failures outside the window do not accumulate.
	g.Fail("x")
	now = now.Add(2 * time.Minute)
	g.Fail("x")
	g.Fail("x")
	if b, _ := g.Blocked("x"); b {
		t.Error("old failure should have expired")
	}
}
