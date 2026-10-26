// Package ratelimit provides small in-memory limiters for abuse protection.
package ratelimit

import (
	"math"
	"sync"
	"time"
)

type bucket struct {
	tokens float64
	last   time.Time
}

// Limiter is a keyed token bucket.
type Limiter struct {
	mu      sync.Mutex
	rate    float64 // tokens per second
	burst   float64
	buckets map[string]*bucket
	Now     func() time.Time
}

// NewLimiter allows perMinute requests per key on average with the given burst.
func NewLimiter(perMinute, burst int) *Limiter {
	return &Limiter{rate: float64(perMinute) / 60, burst: float64(burst), buckets: map[string]*bucket{}, Now: time.Now}
}

// Allow consumes a token for key, or reports how long until one is available.
func (l *Limiter) Allow(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.Now()
	b, ok := l.buckets[key]
	if !ok {
		b = &bucket{tokens: l.burst, last: now}
		l.buckets[key] = b
	}
	b.tokens = math.Min(l.burst, b.tokens+now.Sub(b.last).Seconds()*l.rate)
	b.last = now
	if b.tokens >= 1 {
		b.tokens--
		return true, 0
	}
	wait := time.Duration((1 - b.tokens) / l.rate * float64(time.Second))
	return false, wait
}

// Cleanup forgets keys whose buckets have refilled, bounding memory.
func (l *Limiter) Cleanup() {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.Now()
	for k, b := range l.buckets {
		if b.tokens+now.Sub(b.last).Seconds()*l.rate >= l.burst {
			delete(l.buckets, k)
		}
	}
}

// FailureGuard blocks a key (client address) after too many failures within
// a window, e.g. repeated invalid credentials.
type FailureGuard struct {
	mu       sync.Mutex
	max      int
	window   time.Duration
	blockFor time.Duration
	failures map[string][]time.Time
	blocked  map[string]time.Time
	maxKeys  int
	Now      func() time.Time
}

// NewFailureGuard blocks a key for blockFor after max failures within window.
func NewFailureGuard(max int, window, blockFor time.Duration) *FailureGuard {
	return &FailureGuard{max: max, window: window, blockFor: blockFor,
		failures: map[string][]time.Time{}, blocked: map[string]time.Time{}, maxKeys: 10000, Now: time.Now}
}

// Blocked reports whether key is currently blocked and for how long.
func (g *FailureGuard) Blocked(key string) (bool, time.Duration) {
	g.mu.Lock()
	defer g.mu.Unlock()
	until, ok := g.blocked[key]
	if !ok {
		return false, 0
	}
	now := g.Now()
	if now.After(until) {
		delete(g.blocked, key)
		return false, 0
	}
	return true, until.Sub(now)
}

// Fail records a failure for key.
func (g *FailureGuard) Fail(key string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	now := g.Now()
	if len(g.failures) >= g.maxKeys {
		g.cleanupLocked(now)
	}
	list := g.failures[key]
	cut := 0
	for cut < len(list) && now.Sub(list[cut]) > g.window {
		cut++
	}
	list = append(list[cut:], now)
	if len(list) >= g.max {
		g.blocked[key] = now.Add(g.blockFor)
		delete(g.failures, key)
		return
	}
	g.failures[key] = list
}

// Cleanup drops expired state.
func (g *FailureGuard) Cleanup() {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.cleanupLocked(g.Now())
}

func (g *FailureGuard) cleanupLocked(now time.Time) {
	for k, l := range g.failures {
		if len(l) == 0 || now.Sub(l[len(l)-1]) > g.window {
			delete(g.failures, k)
		}
	}
	for k, until := range g.blocked {
		if now.After(until) {
			delete(g.blocked, k)
		}
	}
}
