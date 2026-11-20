package store

import (
	"context"
	"time"
)

// ApplyRetentionMode brings the stored body expiries in line with the
// configured mode. It runs once at start-up.
//
//   - Automatic deletion off: every body that still has an expiry is kept
//     until deleted by hand.
//   - Automatic deletion on: bodies kept forever by an earlier run start a
//     fresh retention period now (the period is counted from now, not from
//     the day the message arrived, so switching the mode on never deletes
//     bodies on the spot).
//
// It returns how many bodies changed.
func (s *Store) ApplyRetentionMode(ctx context.Context, now time.Time) (int64, error) {
	never := NeverExpires.UnixMilli()
	if s.keepForever {
		res, err := s.db.ExecContext(ctx, "UPDATE bodies SET expires_at = ? WHERE expires_at < ?", never, never)
		if err != nil {
			return 0, err
		}
		return res.RowsAffected()
	}
	d := s.bodyRetention
	if s.v1Mode() {
		d = s.filteredKeep
		if d <= 0 {
			d = 7 * 24 * time.Hour
		}
	}
	code := s.codeRetention
	if code == 0 || code > d {
		code = d
	}
	res, err := s.db.ExecContext(ctx, `UPDATE bodies SET expires_at = ? + CASE WHEN EXISTS(
			SELECT 1 FROM messages m WHERE m.id = bodies.message_id AND (m.has_code = 1 OR m.category = 'code'))
			THEN ? ELSE ? END
		WHERE expires_at >= ?`, now.UnixMilli(), code.Milliseconds(), d.Milliseconds(), never)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}
