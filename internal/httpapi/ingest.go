package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"

	"msggw/internal/auth"
	"msggw/internal/model"
	"msggw/internal/store"
)

// maxRequestBytes bounds the raw request body (JSON escaping can expand text).
const maxRequestBytes = 256 << 10

const (
	maxTitleChars   = 256
	maxIdemKeyChars = 128
	maxMetaEntries  = 16
	maxMetaValue    = 256
)

var metaKeyRe = regexp.MustCompile(`^[A-Za-z0-9_.-]{1,32}$`)

// authSource authenticates a source credential. On failure it writes the
// response and returns false.
func (s *Server) authSource(w http.ResponseWriter, r *http.Request) (model.Source, bool) {
	ip := clientIP(r)
	if blocked, ra := s.FailGuard.Blocked(ip); blocked {
		writeError(w, r, &apiError{Status: http.StatusTooManyRequests, Code: "too_many_auth_failures",
			Message: "认证失败次数过多，请稍后再试", RetryAfter: ra})
		return model.Source{}, false
	}
	now := s.now()
	tok, ok := auth.BearerToken(r.Header.Get("Authorization"))
	if !ok {
		s.FailGuard.Fail(ip)
		s.Health.AuthFailure(now)
		writeError(w, r, errUnauthorized)
		return model.Source{}, false
	}
	lk, err := s.Store.LookupSourceToken(r.Context(), auth.Hash(tok))
	if errors.Is(err, store.ErrNotFound) {
		s.FailGuard.Fail(ip)
		s.Health.AuthFailure(now)
		writeError(w, r, errUnauthorized)
		return model.Source{}, false
	}
	if err != nil {
		s.Log.Error("token lookup failed", "err", err)
		writeError(w, r, errInternal)
		return model.Source{}, false
	}
	info(r).Source = lk.Source.ID
	if lk.Revoked {
		s.FailGuard.Fail(ip)
		s.Health.AuthFailure(now)
		_ = s.Store.RecordSourceAuthFailure(r.Context(), lk.Source.ID, now)
		s.Log.Warn("revoked source credential used", "source", lk.Source.ID, "ip", ip)
		writeError(w, r, errUnauthorized)
		return model.Source{}, false
	}
	if err := s.Store.TouchSource(r.Context(), lk.Source.ID, now); err != nil {
		s.Log.Error("touch source failed", "err", err)
	}
	if !lk.Source.Enabled {
		writeError(w, r, &apiError{Status: http.StatusForbidden, Code: "source_disabled", Message: "该消息来源已被停用"})
		return model.Source{}, false
	}
	return lk.Source, true
}

// submission is the decoded request before validation.
type submission struct {
	Title          string
	Body           string
	Kind           string
	Priority       string
	OccurredAt     string
	IdempotencyKey string
	Meta           map[string]string
}

func (s *Server) handleSubmit(w http.ResponseWriter, r *http.Request) {
	src, ok := s.authSource(w, r)
	if !ok {
		return
	}
	if allowed, wait := s.SourceLimiter.Allow(src.ID); !allowed {
		writeError(w, r, &apiError{Status: http.StatusTooManyRequests, Code: "rate_limited",
			Message: "该来源提交过于频繁，请稍后重试", RetryAfter: wait})
		return
	}

	waitFor, err := parseWait(r.URL.Query().Get("wait"))
	if err != nil {
		writeError(w, r, &apiError{Status: http.StatusBadRequest, Code: "invalid_parameter", Message: err.Error()})
		return
	}

	mt, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type"))
	r.Body = http.MaxBytesReader(w, r.Body, maxRequestBytes)
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			writeError(w, r, &apiError{Status: http.StatusRequestEntityTooLarge, Code: "payload_too_large",
				Message: fmt.Sprintf("请求体超过 %d KB", maxRequestBytes>>10)})
			return
		}
		writeError(w, r, &apiError{Status: http.StatusBadRequest, Code: "invalid_body", Message: "读取请求体失败"})
		return
	}

	var sub submission
	var warnings []string
	var fieldErrs []FieldError
	switch mt {
	case "application/json":
		sub, warnings, fieldErrs, err = decodeJSON(raw)
		if err != nil {
			writeError(w, r, &apiError{Status: http.StatusBadRequest, Code: "invalid_json", Message: "请求体不是有效的 JSON 对象：" + err.Error()})
			return
		}
	case "text/plain":
		q := r.URL.Query()
		sub = submission{Body: string(raw), Title: q.Get("title"), Kind: q.Get("type"), Priority: q.Get("priority")}
	default:
		writeError(w, r, &apiError{Status: http.StatusUnsupportedMediaType, Code: "unsupported_media_type",
			Message: "Content-Type 必须是 application/json 或 text/plain"})
		return
	}
	if h := strings.TrimSpace(r.Header.Get("Idempotency-Key")); h != "" {
		if sub.IdempotencyKey != "" && sub.IdempotencyKey != h {
			fieldErrs = append(fieldErrs, FieldError{"idempotency_key", "与 Idempotency-Key 请求头不一致"})
		}
		sub.IdempotencyKey = h
	}

	now := s.now()
	msg, moreWarn, moreErrs := s.normalize(sub, now)
	warnings = append(warnings, moreWarn...)
	fieldErrs = append(fieldErrs, moreErrs...)
	if len(fieldErrs) > 0 {
		writeError(w, r, &apiError{Status: http.StatusUnprocessableEntity, Code: "validation_failed",
			Message: "请求字段校验失败", Details: fieldErrs})
		return
	}
	msg.ID = model.NewMessageID(now)
	msg.SourceID = src.ID
	msg.ReceivedAt = now
	msg.ContentHash = auth.ContentHash(s.DedupeKey, src.ID, msg.Kind, msg.Title, msg.Body)

	held := s.Filter != nil && s.FilterTypes[msg.Kind]
	var targets []store.NewDelivery
	for _, t := range s.Router.Route(src, msg) {
		targets = append(targets, store.NewDelivery{Channel: t.Channel, Target: t.Target, Silent: t.Silent, Held: held})
	}
	res, err := s.Store.InsertMessage(r.Context(), msg, targets, s.DedupeWindow)
	if err != nil {
		s.Log.Error("insert message failed", "source", src.ID, "err", err)
		writeError(w, r, errInternal)
		return
	}
	s.Health.IngestOK(now)

	st := res.Status
	var unsub func()
	var changed <-chan struct{}
	if waitFor > 0 && !st.Terminal() {
		changed, unsub = s.Notifier.Subscribe(res.ID)
		defer unsub()
	}
	if !res.Duplicate {
		if held {
			s.Filter.Wake()
		} else {
			s.Worker.Wake()
		}
	}
	if changed != nil {
		st = s.waitForStatus(r.Context(), res.ID, st, changed, waitFor)
	}

	code := http.StatusAccepted
	if res.Duplicate {
		code = http.StatusOK
		warnings = append(warnings, "重复提交：已返回先前消息的状态，未再次转发")
	}
	summary := st.Label()
	if res.Duplicate {
		summary += "（重复提交，未再次转发）"
	}
	resp := map[string]any{
		"ok":          true,
		"message":     summary,
		"id":          res.ID,
		"status":      st,
		"status_text": st.Label(),
		"delivered":   st == model.StatusDelivered,
		"duplicate":   res.Duplicate,
		"received_at": res.ReceivedAt.In(s.Location).Format(time.RFC3339),
	}
	if len(warnings) > 0 {
		resp["warnings"] = warnings
	}
	writeJSON(w, code, resp)
}

func (s *Server) waitForStatus(ctx context.Context, id string, st model.Status, changed <-chan struct{}, d time.Duration) model.Status {
	timer := time.NewTimer(d)
	defer timer.Stop()
	for !st.Terminal() {
		select {
		case <-ctx.Done():
			return st
		case <-timer.C:
			return st
		case <-changed:
			if cur, err := s.Store.MessageStatus(ctx, id); err == nil {
				st = cur
			}
		}
	}
	return st
}

func parseWait(v string) (time.Duration, error) {
	if v == "" {
		return 0, nil
	}
	n, err := strconv.ParseFloat(v, 64)
	if err != nil || n < 0 {
		return 0, errors.New("wait 必须是 0 到 25 之间的秒数")
	}
	d := time.Duration(n * float64(time.Second))
	if d > maxWait {
		d = maxWait
	}
	return d, nil
}

// decodeJSON decodes a submission leniently: unknown fields produce
// warnings (for forward compatibility), wrong types produce field errors.
func decodeJSON(raw []byte) (submission, []string, []FieldError, error) {
	var sub submission
	var warnings []string
	var errs []FieldError
	dec := json.NewDecoder(bytes.NewReader(raw))
	var obj map[string]json.RawMessage
	if err := dec.Decode(&obj); err != nil {
		return sub, nil, nil, errors.New("无法解析")
	}
	if obj == nil {
		return sub, nil, nil, errors.New("应为 JSON 对象")
	}
	str := func(field string, dst *string) {
		v, ok := obj[field]
		if !ok || string(v) == "null" {
			return
		}
		if err := json.Unmarshal(v, dst); err != nil {
			errs = append(errs, FieldError{field, "必须是字符串"})
		}
	}
	for k, v := range obj {
		switch k {
		case "title":
			str(k, &sub.Title)
		case "body":
			str(k, &sub.Body)
		case "type":
			str(k, &sub.Kind)
		case "priority":
			str(k, &sub.Priority)
		case "idempotency_key":
			str(k, &sub.IdempotencyKey)
		case "occurred_at":
			var n json.Number
			d := json.NewDecoder(bytes.NewReader(v))
			d.UseNumber()
			var anyv any
			if err := d.Decode(&anyv); err == nil {
				switch x := anyv.(type) {
				case string:
					sub.OccurredAt = x
				case json.Number:
					n = x
					sub.OccurredAt = n.String()
				case nil:
				default:
					errs = append(errs, FieldError{k, "必须是时间字符串或 Unix 秒数"})
				}
			}
		case "meta":
			if string(v) == "null" {
				continue
			}
			var m map[string]any
			d := json.NewDecoder(bytes.NewReader(v))
			d.UseNumber()
			if err := d.Decode(&m); err != nil {
				errs = append(errs, FieldError{k, "必须是 JSON 对象"})
				continue
			}
			sub.Meta = map[string]string{}
			for mk, mv := range m {
				switch x := mv.(type) {
				case string:
					sub.Meta[mk] = x
				case json.Number:
					sub.Meta[mk] = x.String()
				case bool:
					sub.Meta[mk] = strconv.FormatBool(x)
				case nil:
				default:
					errs = append(errs, FieldError{"meta." + mk, "值必须是字符串、数字或布尔值"})
				}
			}
		case "source", "source_id":
			warnings = append(warnings, "已忽略字段 "+k+"：来源由凭证决定")
		default:
			warnings = append(warnings, "已忽略未知字段 "+k)
		}
	}
	return sub, warnings, errs, nil
}

// cleanText removes control characters (keeping newlines and tabs when
// multiline) and normalises line endings.
func cleanText(s string, multiline bool) string {
	s = strings.ReplaceAll(s, "\r\n", "\n")
	s = strings.ReplaceAll(s, "\r", "\n")
	return strings.Map(func(r rune) rune {
		if r == '\n' || r == '\t' {
			if multiline {
				return r
			}
			return ' '
		}
		if unicode.IsControl(r) || r == 0xFEFF {
			return -1
		}
		return r
	}, s)
}

var occurredLayouts = []string{
	time.RFC3339Nano,
	"2006-01-02T15:04:05",
	"2006-01-02 15:04:05",
	"2006-01-02 15:04",
	"2006/01/02 15:04:05",
	"2006/01/02 15:04",
}

// normalize validates a submission and converts it to a message.
func (s *Server) normalize(sub submission, now time.Time) (*model.Message, []string, []FieldError) {
	var warnings []string
	var errs []FieldError
	m := &model.Message{}

	m.Title = strings.TrimSpace(cleanText(sub.Title, false))
	if n := len([]rune(m.Title)); n > maxTitleChars {
		errs = append(errs, FieldError{"title", fmt.Sprintf("不能超过 %d 个字符（当前 %d）", maxTitleChars, n)})
	}
	m.Body = strings.TrimRight(cleanText(sub.Body, true), " \t\n")
	m.Body = strings.TrimLeft(m.Body, "\n")
	if n := len([]rune(m.Body)); n > s.MaxBodyChars {
		errs = append(errs, FieldError{"body", fmt.Sprintf("不能超过 %d 个字符（当前 %d）", s.MaxBodyChars, n)})
	}
	if m.Title == "" && strings.TrimSpace(m.Body) == "" {
		errs = append(errs, FieldError{"body", "title 和 body 至少需要提供一个非空值"})
	}

	m.Kind = strings.ToLower(strings.TrimSpace(sub.Kind))
	if m.Kind == "" {
		m.Kind = "text"
	} else if !model.ValidKind(m.Kind) {
		errs = append(errs, FieldError{"type", "只能包含小写字母、数字和 . _ -，以字母开头，最长 32 个字符"})
	}

	p := model.Priority(strings.ToLower(strings.TrimSpace(sub.Priority)))
	if p == "" {
		p = model.PriorityNormal
	}
	if !model.ValidPriority(p) {
		errs = append(errs, FieldError{"priority", "必须是 low、normal 或 high"})
	}
	m.Priority = p

	m.IdempotencyKey = strings.TrimSpace(cleanText(sub.IdempotencyKey, false))
	if n := len([]rune(m.IdempotencyKey)); n > maxIdemKeyChars {
		errs = append(errs, FieldError{"idempotency_key", fmt.Sprintf("不能超过 %d 个字符", maxIdemKeyChars)})
	}

	if v := strings.TrimSpace(sub.OccurredAt); v != "" {
		t, ok := parseOccurred(v, s.Location)
		switch {
		case !ok:
			warnings = append(warnings, "无法识别 occurred_at 的时间格式，已忽略")
		case t.Sub(now) > 24*time.Hour || now.Sub(t) > 365*24*time.Hour:
			warnings = append(warnings, "occurred_at 与服务器时间相差过大，已忽略")
		default:
			m.OccurredAt = t
		}
	}

	if len(sub.Meta) > maxMetaEntries {
		errs = append(errs, FieldError{"meta", fmt.Sprintf("最多 %d 项", maxMetaEntries)})
	} else if len(sub.Meta) > 0 {
		m.Meta = map[string]string{}
		for k, v := range sub.Meta {
			if !metaKeyRe.MatchString(k) {
				errs = append(errs, FieldError{"meta." + k, "键只能包含字母、数字和 . _ -，最长 32 个字符"})
				continue
			}
			v = strings.TrimSpace(cleanText(v, false))
			if len([]rune(v)) > maxMetaValue {
				errs = append(errs, FieldError{"meta." + k, fmt.Sprintf("值不能超过 %d 个字符", maxMetaValue)})
				continue
			}
			if v != "" {
				m.Meta[k] = v
			}
		}
	}
	return m, warnings, errs
}

func parseOccurred(v string, loc *time.Location) (time.Time, bool) {
	if n, err := strconv.ParseFloat(v, 64); err == nil {
		if n > 1e12 { // milliseconds
			n /= 1000
		}
		return time.Unix(int64(n), 0), true
	}
	for _, l := range occurredLayouts {
		if t, err := time.ParseInLocation(l, v, loc); err == nil {
			return t, true
		}
	}
	return time.Time{}, false
}

func (s *Server) handleGetMessage(w http.ResponseWriter, r *http.Request) {
	src, ok := s.authSource(w, r)
	if !ok {
		return
	}
	mi, err := s.Store.GetMessage(r.Context(), r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) || err == nil && mi.Message.SourceID != src.ID {
		writeError(w, r, &apiError{Status: http.StatusNotFound, Code: "not_found", Message: "消息不存在或不属于该来源"})
		return
	}
	if err != nil {
		s.Log.Error("get message failed", "err", err)
		writeError(w, r, errInternal)
		return
	}
	writeJSON(w, http.StatusOK, s.messageJSON(mi))
}

func (s *Server) ts(t time.Time) any {
	if t.IsZero() {
		return nil
	}
	return t.In(s.Location).Format(time.RFC3339)
}

func (s *Server) messageJSON(mi store.MessageInfo) map[string]any {
	m := mi.Message
	var ds []map[string]any
	for _, d := range mi.Deliveries {
		ds = append(ds, map[string]any{
			"channel":         d.Channel,
			"target":          d.Target,
			"state":           d.State,
			"attempts":        d.Attempts,
			"parts_total":     d.PartsTotal,
			"parts_sent":      d.PartsSent,
			"uncertain":       d.Uncertain,
			"last_error":      d.LastError,
			"last_attempt_at": s.ts(d.LastAttemptAt),
			"next_attempt_at": func() any {
				if d.State.Terminal() {
					return nil
				}
				return s.ts(d.NextAttemptAt)
			}(),
			"delivered_at": s.ts(d.DeliveredAt),
		})
	}
	return map[string]any{
		"ok":                    true,
		"id":                    m.ID,
		"source":                m.SourceID,
		"type":                  m.Kind,
		"priority":              m.Priority,
		"status":                m.Status,
		"status_text":           m.Status.Label(),
		"delivered":             m.Status == model.StatusDelivered,
		"category":              nullIfEmpty(string(m.Category)),
		"category_text":         nullIfEmpty(m.Category.Label()),
		"received_at":           s.ts(m.ReceivedAt),
		"occurred_at":           s.ts(m.OccurredAt),
		"finished_at":           s.ts(m.FinishedAt),
		"duplicate_submissions": m.DupCount,
		"deliveries":            ds,
	}
}

func nullIfEmpty(s string) any {
	if s == "" {
		return nil
	}
	return s
}
