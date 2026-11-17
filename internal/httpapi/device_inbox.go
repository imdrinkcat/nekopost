package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"nekopost/internal/auth"
	"nekopost/internal/filter"
	"nekopost/internal/msgrule"
	"nekopost/internal/service"
	"nekopost/internal/store"
)

var inboxID = regexp.MustCompile(`^m_[0-9A-HJKMNP-TV-Z]{26}$`)

func (s *Server) inboxItem(ctx context.Context, id string, detail bool) (map[string]any, error) {
	info, err := s.Store.GetMessage(ctx, id)
	if err != nil {
		return nil, err
	}
	st, err := s.Store.InboxState(ctx, id)
	if err != nil {
		return nil, err
	}
	src, err := s.Store.GetSource(ctx, info.Message.SourceID)
	if errors.Is(err, store.ErrNotFound) {
		src.ID = info.Message.SourceID
	} else if err != nil {
		return nil, err
	}
	t, b, meta, ok, err := s.Store.ReadBody(ctx, id)
	if err != nil {
		return nil, err
	}
	if ok && !st.BodyExpires.After(s.now()) {
		t, b, meta, ok = "", "", nil, false
		st.BodyState = "expired"
	}
	m := info.Message
	deliveries := make([]map[string]any, 0, len(info.Deliveries))
	for _, d := range info.Deliveries {
		deliveries = append(deliveries, map[string]any{"channel": d.Channel, "aux": d.Aux, "state": d.State, "acked": !d.AckedAt.IsZero(),
			"attempts": d.Attempts, "parts_sent": d.PartsSent, "parts_total": d.PartsTotal, "uncertain": d.Uncertain})
	}
	// sender is the unmasked title for a conversation list (SMS address or
	// "<app> · <title>"); title and preview stay masked for compatibility.
	var sender any
	if ok {
		sender = msgrule.Sender(t, st.HasCode, m.Kind == "sms")
	}
	item := map[string]any{"id": m.ID, "seq": st.Seq, "source": map[string]string{"id": src.ID, "name": src.Name, "icon": src.Icon},
		"type": m.Kind, "priority": m.Priority, "category": m.Category, "category_text": m.Category.Label(),
		"title": filter.Preview(t, ""), "preview": filter.Preview(t, b), "sender": sender, "has_code": st.HasCode, "received_at": s.ts(m.ReceivedAt),
		"occurred_at": s.ts(m.OccurredAt), "late": st.Late, "status": m.Status, "status_text": m.Status.LabelVia(info.AppOnly),
		"app_only": info.AppOnly, "read": st.Read,
		"deliveries": deliveries, "body_state": st.BodyState, "body_expires_at": s.ts(st.BodyExpires), "redacted": !detail}
	if detail && ok {
		item["title"], item["body"], item["meta"] = t, b, meta
		if code := msgrule.VerificationCode(t + "\n" + b); code != "" {
			item["otp"] = map[string]string{"code": code}
		}
	}
	return item, nil
}

func (s *Server) inboxList(w http.ResponseWriter, r *http.Request, d store.Device) {
	q := r.URL.Query()
	var at time.Time
	id := q.Get("before_id")
	if v := q.Get("before"); v != "" {
		ms, err := strconv.ParseInt(v, 10, 64)
		if err != nil || !inboxID.MatchString(id) {
			deviceError(w, r, 400, "invalid_cursor", "翻页参数无效")
			return
		}
		at = time.UnixMilli(ms)
	}
	f := store.InboxFilter{Unread: q.Get("unread") == "1", HasCode: q.Get("has_code") == "1"}
	list, err := s.Store.InboxPageFiltered(r.Context(), at, id, 31, f)
	if err != nil {
		writeError(w, r, errInternal)
		return
	}
	unread, err := s.Store.UnreadCount(r.Context())
	if err != nil {
		writeError(w, r, errInternal)
		return
	}
	var next any
	if len(list) > 30 {
		list = list[:30]
		last := list[len(list)-1]
		next = map[string]any{"before": last.ReceivedAt.UnixMilli(), "before_id": last.ID}
	}
	items := make([]map[string]any, 0, len(list))
	for _, m := range list {
		item, err := s.inboxItem(r.Context(), m.ID, false)
		if errors.Is(err, store.ErrNotFound) {
			continue
		}
		if err != nil {
			writeError(w, r, errInternal)
			return
		}
		items = append(items, item)
	}
	writeJSON(w, 200, map[string]any{"items": items, "next_cursor": next, "unread_count": unread})
}

func (s *Server) inboxGet(w http.ResponseWriter, r *http.Request, d store.Device) {
	id := r.PathValue("id")
	if !inboxID.MatchString(id) {
		writeError(w, r, errNotFound)
		return
	}
	// A fetch triggered by a push is not a person viewing the message:
	// audit it separately and let it acknowledge the push in the same trip.
	q := r.URL.Query()
	action := "message.view"
	if q.Get("via") == "push" {
		action = "message.push_fetch"
		if q.Get("ack") == "1" {
			if err := s.Store.AckDevice(r.Context(), d.ID, []string{id}, s.now()); err != nil {
				writeError(w, r, errInternal)
				return
			}
		}
	}
	item, err := s.inboxItem(r.Context(), id, true)
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, r, errNotFound)
		return
	}
	if err != nil {
		writeError(w, r, errInternal)
		return
	}
	if err := s.Store.Audit(r.Context(), s.now(), "device:"+d.ID, action, id, "ok"); err != nil {
		writeError(w, r, errInternal)
		return
	}
	writeJSON(w, 200, item)
}

func (s *Server) inboxSearch(w http.ResponseWriter, r *http.Request, d store.Device) {
	var body struct {
		Query string `json:"q"`
	}
	if readJSON(w, r, &body) != nil || body.Query == "" || len([]rune(body.Query)) > 200 {
		deviceError(w, r, 400, "invalid_query", "搜索词无效")
		return
	}
	hits, scan, exhausted, err := service.Search(r.Context(), s.Store, body.Query, 20)
	if err != nil {
		writeError(w, r, errInternal)
		return
	}
	if err := s.Store.Audit(r.Context(), s.now(), "device:"+d.ID, "message.search", "", "ok"); err != nil {
		writeError(w, r, errInternal)
		return
	}
	items := make([]map[string]any, 0, len(hits))
	for _, hit := range hits {
		item, err := s.inboxItem(r.Context(), hit.ID, false)
		if errors.Is(err, store.ErrNotFound) {
			continue
		}
		if err != nil {
			writeError(w, r, errInternal)
			return
		}
		items = append(items, item)
	}
	writeJSON(w, 200, map[string]any{"items": items, "scanned": scan, "exhausted": exhausted})
}

func (s *Server) inboxRead(w http.ResponseWriter, r *http.Request, d store.Device) {
	id := r.PathValue("id")
	if !inboxID.MatchString(id) {
		writeError(w, r, errNotFound)
		return
	}
	var body struct {
		Read *bool `json:"read"`
	}
	if readJSON(w, r, &body) != nil || body.Read == nil {
		deviceError(w, r, 400, "invalid_body", "需要已读状态")
		return
	}
	changed, err := s.Store.SetMessageRead(r.Context(), id, *body.Read, s.now())
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, r, errNotFound)
		return
	}
	if err != nil {
		writeError(w, r, errInternal)
		return
	}
	if changed && *body.Read {
		s.signal("read", []string{id}, d.ID)
	}
	w.WriteHeader(204)
}

func (s *Server) inboxSync(w http.ResponseWriter, r *http.Request, d store.Device) {
	since := int64(0)
	if val := r.URL.Query().Get("since"); val != "" {
		var err error
		since, err = strconv.ParseInt(val, 10, 64)
		if err != nil || since < 0 {
			deviceError(w, r, 400, "invalid_cursor", "同步位置无效")
			return
		}
	}
	page, err := s.Store.ChangesSince(r.Context(), since, 50)
	if err != nil {
		writeError(w, r, errInternal)
		return
	}
	items := make([]map[string]any, 0, len(page.Changes))
	deleted := make([]string, 0)
	for _, change := range page.Changes {
		if change.Deleted {
			deleted = append(deleted, change.MessageID)
			continue
		}
		item, err := s.inboxItem(r.Context(), change.MessageID, false)
		if errors.Is(err, store.ErrNotFound) {
			deleted = append(deleted, change.MessageID)
			continue
		}
		if err != nil {
			writeError(w, r, errInternal)
			return
		}
		items = append(items, item)
	}
	writeJSON(w, 200, map[string]any{"changes": items, "deleted": deleted, "next_since": page.Next, "more": page.More, "reset": page.Reset})
}

func (s *Server) deviceAcks(w http.ResponseWriter, r *http.Request, d store.Device) {
	var body struct {
		IDs []string `json:"ids"`
	}
	if readJSON(w, r, &body) != nil || len(body.IDs) > 50 {
		deviceError(w, r, 400, "invalid_body", "回执参数无效")
		return
	}
	for _, id := range body.IDs {
		if !inboxID.MatchString(id) {
			deviceError(w, r, 400, "invalid_body", "消息 ID 无效")
			return
		}
	}
	if err := s.Store.AckDevice(r.Context(), d.ID, body.IDs, s.now()); err != nil {
		writeError(w, r, errInternal)
		return
	}
	w.WriteHeader(204)
}

func (s *Server) inboxEvents(w http.ResponseWriter, r *http.Request, d store.Device) {
	since := int64(0)
	if cursor := r.Header.Get("Last-Event-ID"); cursor != "" {
		var err error
		since, err = strconv.ParseInt(cursor, 10, 64)
		if err != nil || since < 0 {
			deviceError(w, r, 400, "invalid_cursor", "事件位置无效")
			return
		}
	}
	ctx, cancel := context.WithTimeout(r.Context(), time.Hour)
	defer cancel()
	controller := http.NewResponseController(w)
	if err := controller.SetWriteDeadline(time.Now().Add(10 * time.Second)); err != nil {
		writeError(w, r, errInternal)
		return
	}
	select {
	case s.streams <- struct{}{}:
		defer func() { <-s.streams }()
	default:
		deviceError(w, r, 429, "stream_limit", "事件连接已满")
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("X-Accel-Buffering", "no")
	w.Header().Set("Cache-Control", "no-store")
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	token, _ := auth.DeviceAuthorization(r.Header.Get("Authorization"))
	tokenHash := auth.Hash(token)
	for {
		current, err := s.Store.DeviceByToken(ctx, tokenHash)
		if err != nil || current.Revoked || current.Suspended || !current.Has(store.ScopeRead) {
			return
		}
		page, err := s.Store.ChangesSince(ctx, since, 50)
		if err != nil {
			return
		}
		if err := controller.SetWriteDeadline(time.Now().Add(10 * time.Second)); err != nil {
			return
		}
		if page.Reset {
			_, _ = fmt.Fprint(w, "event: reset\ndata: {}\n\n")
			_ = controller.Flush()
			return
		}
		for _, event := range page.Changes {
			data, _ := json.Marshal(map[string]string{"id": event.MessageID})
			name := "message.updated"
			if event.Deleted {
				name = "message.deleted"
			}
			if _, err := fmt.Fprintf(w, "id: %d\nevent: %s\ndata: %s\n\n", event.Seq, name, data); err != nil {
				return
			}
		}
		if len(page.Changes) == 0 {
			if _, err := fmt.Fprint(w, ": ping\n\n"); err != nil {
				return
			}
		}
		if err := controller.Flush(); err != nil {
			return
		}
		since = page.Next
		if page.More {
			continue
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

// readBatchSignal bounds the IDs one batch sends in the "read" push signal.
// Older messages rarely still have a notification, and the App reconciles
// its notifications when it syncs anyway.
const readBatchSignal = 500

// inboxReadBatch marks many messages read (read=true) or unread at once,
// for a device (msg:read) or the web panel (active session + CSRF).
func (s *Server) inboxReadBatch(read bool) controlHandler {
	return func(w http.ResponseWriter, r *http.Request, actor string) {
		if allowed, wait := s.readLimiter.Allow(actor); !allowed {
			writeError(w, r, &apiError{Status: 429, Code: "rate_limited", Message: "操作过于频繁，请稍后重试", RetryAfter: wait})
			return
		}
		var body struct {
			IDs     []string `json:"ids"`
			All     bool     `json:"all"`
			UpToID  string   `json:"up_to_id"`
			HasCode bool     `json:"has_code"`
		}
		if readJSONLimit(w, r, &body, 32<<10) != nil {
			deviceError(w, r, 400, "invalid_body", "请求格式无效")
			return
		}
		var changed []string
		var err error
		if body.All {
			if !read || body.IDs != nil || !inboxID.MatchString(body.UpToID) {
				deviceError(w, r, 400, "invalid_body", "需要最新一条消息的 ID")
				return
			}
			changed, err = s.Store.MarkAllRead(r.Context(), body.UpToID, body.HasCode, actor, s.now())
		} else {
			if body.UpToID != "" || body.HasCode || len(body.IDs) == 0 || len(body.IDs) > store.MaxReadBatch {
				deviceError(w, r, 400, "invalid_body", "需要 1 到 500 个消息 ID")
				return
			}
			for _, id := range body.IDs {
				if !inboxID.MatchString(id) {
					deviceError(w, r, 400, "invalid_body", "消息 ID 无效")
					return
				}
			}
			changed, err = s.Store.SetMessagesRead(r.Context(), body.IDs, read, actor, s.now())
		}
		if errors.Is(err, store.ErrStaleBound) {
			deviceError(w, r, 409, "stale_list", "列表已变化，请刷新后重试")
			return
		}
		if err != nil {
			writeError(w, r, errInternal)
			return
		}
		// The newest changed IDs: the read signal carries them to the other
		// devices, and the caller clears its own notifications with them
		// (the signal skips the calling device).
		ids := changed[:min(len(changed), readBatchSignal)]
		if read && len(ids) > 0 {
			except, _ := strings.CutPrefix(actor, "device:")
			if except == actor {
				except = ""
			}
			s.signal("read", ids, except)
		}
		unread, err := s.Store.UnreadCount(r.Context())
		if err != nil {
			writeError(w, r, errInternal)
			return
		}
		if ids == nil {
			ids = []string{}
		}
		writeJSON(w, 200, map[string]any{"changed": len(changed), "unread_count": unread, "ids": ids})
	}
}
