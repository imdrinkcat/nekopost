package model

import (
	"regexp"
	"sort"
	"testing"
	"time"
)

func TestAggregateStatus(t *testing.T) {
	cases := []struct {
		in   []DeliveryState
		want Status
	}{
		{nil, StatusNotRouted},
		{[]DeliveryState{DeliveryPending}, StatusQueued},
		{[]DeliveryState{DeliverySending, DeliveryDelivered}, StatusSending},
		{[]DeliveryState{DeliveryRetrying}, StatusRetrying},
		{[]DeliveryState{DeliveryDelivered}, StatusDelivered},
		{[]DeliveryState{DeliveryDelivered, DeliveryFailed}, StatusPartial},
		{[]DeliveryState{DeliveryUncertain}, StatusUncertain},
		{[]DeliveryState{DeliveryFailed, DeliveryUncertain}, StatusUncertain},
		{[]DeliveryState{DeliveryFailed}, StatusFailed},
	}
	for _, c := range cases {
		if got := AggregateStatus(c.in); got != c.want {
			t.Errorf("AggregateStatus(%v) = %s, want %s", c.in, got, c.want)
		}
	}
	// Accepted is never terminal; delivered/failed/uncertain are.
	for _, s := range []Status{StatusQueued, StatusSending, StatusRetrying} {
		if s.Terminal() {
			t.Errorf("%s should not be terminal", s)
		}
	}
	for _, s := range []Status{StatusDelivered, StatusFailed, StatusUncertain, StatusPartial, StatusNotRouted} {
		if !s.Terminal() {
			t.Errorf("%s should be terminal", s)
		}
	}
}

func TestNewMessageID(t *testing.T) {
	re := regexp.MustCompile(`^m_[0-9A-HJKMNP-TV-Z]{26}$`)
	seen := map[string]bool{}
	var ids []string
	base := time.Now()
	for i := 0; i < 1000; i++ {
		id := NewMessageID(base.Add(time.Duration(i) * time.Millisecond))
		if !re.MatchString(id) {
			t.Fatalf("bad id %q", id)
		}
		if seen[id] {
			t.Fatalf("duplicate id %q", id)
		}
		seen[id] = true
		ids = append(ids, id)
	}
	if !sort.StringsAreSorted(ids) {
		t.Error("ids generated at increasing times should sort in time order")
	}
}

func TestValidators(t *testing.T) {
	for _, id := range []string{"iphone", "linux-server", "a", "win_pc2"} {
		if !ValidSourceID(id) {
			t.Errorf("%q should be valid", id)
		}
	}
	for _, id := range []string{"", "iPhone", "-x", "a b", "x:y", "abcdefghijabcdefghijabcdefghijabc"} {
		if ValidSourceID(id) {
			t.Errorf("%q should be invalid", id)
		}
	}
	if !ValidKind("sms") || !ValidKind("ci.build-result") || ValidKind("SMS") || ValidKind("1x") {
		t.Error("kind validation wrong")
	}
}

func TestFilterStatuses(t *testing.T) {
	if AggregateStatus([]DeliveryState{DeliveryHeld}) != StatusClassifying || StatusClassifying.Terminal() {
		t.Fatal("held")
	}
	if s := AggregateStatus([]DeliveryState{DeliveryFiltered}); s != StatusFiltered || !s.Terminal() || !DeliveryFiltered.Terminal() {
		t.Fatal("filtered")
	}
	for _, c := range Categories {
		if p, ok := ParseCategory(string(c)); !ok || p != c || c.Label() == string(c) {
			t.Fatalf("category %q", c)
		}
	}
	if _, ok := ParseCategory("ads"); ok {
		t.Fatal("unknown category accepted")
	}
}
