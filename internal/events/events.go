// Package events reads one ordered stream of changes and deletions.
package events

import (
	"context"
	"database/sql"
	"time"
)

type Change struct {
	Seq       int64
	MessageID string
	At        time.Time
	Deleted   bool
}

type Page struct {
	Changes []Change
	Next    int64
	More    bool
	Reset   bool
}

type DB interface {
	QueryContext(context.Context, string, ...any) (*sql.Rows, error)
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

// ChangesSince merges before applying the page size so a tombstone on a later
// page cannot move the cursor past an unreturned message.
func ChangesSince(ctx context.Context, db DB, since int64, limit int) (Page, error) {
	if limit < 1 || limit > 500 {
		limit = 200
	}
	page := Page{Next: since, Changes: make([]Change, 0, limit)}
	if since > 0 {
		var horizon int64
		if err := db.QueryRowContext(ctx, `SELECT COALESCE((SELECT CAST(v AS INTEGER) FROM kv WHERE k='tombstone_horizon'),0)`).Scan(&horizon); err != nil {
			return page, err
		}
		if since < horizon {
			page.Reset = true
			return page, nil
		}
	}
	rows, err := db.QueryContext(ctx, `SELECT seq,id,at,deleted FROM (
		SELECT seq,id,received_at AS at,0 AS deleted FROM messages WHERE seq>?
		UNION ALL SELECT seq,message_id,at,1 FROM tombstones WHERE seq>?
	) ORDER BY seq LIMIT ?`, since, since, limit+1)
	if err != nil {
		return page, err
	}
	defer rows.Close()
	for rows.Next() {
		if len(page.Changes) == limit {
			page.More = true
			break
		}
		var c Change
		var at int64
		if err := rows.Scan(&c.Seq, &c.MessageID, &at, &c.Deleted); err != nil {
			return page, err
		}
		c.At = time.UnixMilli(at)
		page.Next = c.Seq
		page.Changes = append(page.Changes, c)
	}
	return page, rows.Err()
}
