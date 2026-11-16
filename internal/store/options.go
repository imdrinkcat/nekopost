package store

import (
	"time"

	"nekopost/internal/sealbox"
)

// Options configures migration and body retention. KEK must be 32 bytes.
type Options struct {
	EnablePush          bool
	KEK                 []byte
	PreviousKEK         []byte
	Now                 func() time.Time
	BodyRetention       time.Duration // 0 = delete the body when delivery finishes (V1)
	CodeRetention       time.Duration // 0 = same as BodyRetention
	FilteredKeep        time.Duration // used only when BodyRetention is 0
	DedupeWindow        time.Duration
	KeepMigrationBackup bool
}

func (o Options) now() time.Time {
	if o.Now != nil {
		return o.Now()
	}
	return time.Now()
}

// TestingOptions is a fixed key and the default retention, for tests.
func TestingOptions() Options {
	return Options{
		KEK:           bytesRepeat(0x7e),
		BodyRetention: 7 * 24 * time.Hour,
		CodeRetention: 24 * time.Hour,
		DedupeWindow:  time.Minute,
	}
}

func bytesRepeat(b byte) []byte {
	out := make([]byte, 32)
	for i := range out {
		out[i] = b
	}
	return out
}

func (s *Store) keys() sealbox.Keys { return s.box }

// DedupeKey is the HMAC key for content fingerprints and idempotency keys.
func (s *Store) DedupeKey() []byte { return append([]byte(nil), s.box.Dedupe...) }
