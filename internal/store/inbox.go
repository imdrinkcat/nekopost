package store

import (
	"context"
	"time"

	"nekopost/internal/model"
)

// InboxMeta is a message without its body.
type InboxMeta struct {
	ID         string
	SourceID   string
	Kind       string
	Status     model.Status
	BodyState  string
	Late       string
	HasCode    bool
	Seq        int64
	ReceivedAt time.Time
	Title      string
	Body       string
}

// ListInbox returns the newest messages, without decrypting them.
func (s *Store) ListInbox(ctx context.Context, limit int) ([]InboxMeta, error) {
	if limit <= 0 || limit > 100 {
		limit = 20
	}
	return s.InboxPage(ctx, time.Time{}, "", limit)
}

// InboxPage lists metadata before an optional (received_at, id) cursor.
func (s *Store) InboxPage(ctx context.Context, at time.Time, id string, limit int) ([]InboxMeta, error) {
	query := `SELECT id, source_id, kind, status, body_state, late, has_code, seq, received_at FROM messages`
	var args []any
	if !at.IsZero() {
		query += " WHERE (received_at, id) < (?, ?)"
		args = append(args, at.UnixMilli(), id)
	}
	query += " ORDER BY received_at DESC, id DESC LIMIT ?"
	rows, err := s.db.QueryContext(ctx, query, append(args, limit)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanInbox(rows)
}

// SearchCandidates returns stored messages older than the cursor, newest first.
func (s *Store) SearchCandidates(ctx context.Context, before time.Time, beforeID string, limit int) ([]InboxMeta, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT m.id,m.source_id,m.kind,m.status,m.body_state,m.late,m.has_code,m.seq,m.received_at
		FROM messages m JOIN bodies b ON b.message_id=m.id
		WHERE (m.received_at,m.id)<(?,?) AND m.body_state='stored' AND b.expires_at>?
		ORDER BY m.received_at DESC,m.id DESC LIMIT ?`, before.UnixMilli(), beforeID, s.now().UnixMilli(), limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanInbox(rows)
}

func scanInbox(rows interface {
	Next() bool
	Scan(...any) error
	Err() error
}) ([]InboxMeta, error) {
	var out []InboxMeta
	for rows.Next() {
		var m InboxMeta
		var st, late string
		var has int
		var at int64
		if err := rows.Scan(&m.ID, &m.SourceID, &m.Kind, &st, &m.BodyState, &late, &has, &m.Seq, &at); err != nil {
			return nil, err
		}
		m.Status = model.Status(st)
		m.Late = late
		m.HasCode = has == 1
		m.ReceivedAt = time.UnixMilli(at)
		out = append(out, m)
	}
	return out, rows.Err()
}

// ReadBody decrypts one message. ok is false when the body is gone.
func (s *Store) ReadBody(ctx context.Context, id string) (title, body string, meta map[string]string, ok bool, err error) {
	return s.readBody(ctx, id)
}
