package webpanel

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
	"unicode"

	"nekopost/internal/health"
	"nekopost/internal/service"
	"nekopost/internal/status"
	"nekopost/internal/store"
)

type pageData struct {
	Sessions   []store.WebSessionInfo
	Trusted    []store.TrustedBrowserInfo
	DeviceRows []store.Device
	KeyRows    []store.AdminKey

	Mode, Version, Error, Identity, Tab, CSRF, Next, Received string
	// UpTo bounds "mark all read" to the newest message on the first page.
	UpTo                                 string
	Unread                               int
	Enabled, HasBody, ChainOK, Exhausted bool
	Approval                             store.WebApproval
	Inbox                                []store.InboxMeta
	Filtered                             []store.FilteredMessage
	Hits                                 []service.Hit
	Scanned                              int
	Message                              store.MessageInfo
	Title, Body                          string
	Meta                                 map[string]string
	Report                               health.Report
	Stats                                status.StatsResult
	Sources                              []status.SourceStatus
	Audit                                []store.AuditEntry
	Settings                             Settings
}

func (p *Panel) page(w http.ResponseWriter, code int, data pageData) {
	data.Enabled, data.Version = p.enabled(), p.Version
	var b bytes.Buffer
	if err := pages.Execute(&b, data); err != nil {
		http.Error(w, "网页渲染失败", 500)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(code)
	_, _ = b.WriteTo(w)
}

func (p *Panel) confirmPage(w http.ResponseWriter, r *http.Request) {
	session, ok := p.require(w, r, false)
	if !ok {
		return
	}
	if session.State == store.WebActive {
		http.Redirect(w, r, "/panel", 303)
		return
	}
	a, err := p.Store.WebApproval(r.Context(), session.Hash, p.now())
	if errors.Is(err, store.ErrNotFound) {
		http.Redirect(w, r, "/", 303)
		return
	}
	if err != nil {
		p.failed(w)
		return
	}
	p.page(w, 200, pageData{Mode: "confirm", Approval: a, CSRF: session.CSRF, Identity: session.Identity})
}

func (p *Panel) dashboard(w http.ResponseWriter, r *http.Request) {
	sess, ok := p.require(w, r, true)
	if !ok {
		return
	}
	data := pageData{Mode: "panel", Tab: r.URL.Query().Get("tab"), CSRF: sess.CSRF, Identity: sess.Identity, Settings: p.Settings}
	ctx := r.Context()
	var err error
	switch data.Tab {
	case "", "inbox":
		data.Tab = "inbox"
		at := time.Time{}
		after := r.URL.Query().Get("before_id")
		if raw := r.URL.Query().Get("before"); raw != "" {
			ms, e := strconv.ParseInt(raw, 10, 64)
			if e != nil || !messageID.MatchString(after) {
				http.Error(w, "翻页参数无效", 400)
				return
			}
			at = time.UnixMilli(ms)
		}
		data.Inbox, err = p.Store.InboxPage(ctx, at, after, 51)
		if len(data.Inbox) > 50 {
			data.Inbox = data.Inbox[:50]
			last := data.Inbox[len(data.Inbox)-1]
			data.Next = "/panel?" + url.Values{"before": {strconv.FormatInt(last.ReceivedAt.UnixMilli(), 10)}, "before_id": {last.ID}}.Encode()
		}
		if err == nil {
			data.Unread, err = p.Store.UnreadCount(ctx)
		}
		if at.IsZero() && len(data.Inbox) > 0 {
			data.UpTo = data.Inbox[0].ID
		}
	case "filtered":
		offset := 0
		if raw := r.URL.Query().Get("offset"); raw != "" {
			offset, err = strconv.Atoi(raw)
			if err != nil || offset < 0 || offset > 1000000 {
				http.Error(w, "翻页参数无效", 400)
				return
			}
		}
		var total int
		data.Filtered, total, err = p.Store.FilteredMessages(ctx, offset, 20)
		if offset+20 < total {
			data.Next = fmt.Sprintf("/panel?tab=filtered&offset=%d", offset+20)
		}
	case "status":
		data.Report = p.Status.Report(ctx)
	case "stats":
		rng := status.Range7d
		if raw := r.URL.Query().Get("range"); raw != "" {
			var valid bool
			rng, valid = status.ParseRange(raw)
			if !valid {
				http.Error(w, "统计时段无效", 400)
				return
			}
		}
		data.Stats, err = p.Status.Stats(ctx, rng)
	case "sources":
		data.Sources, err = p.Status.Sources(ctx)
	case "devices":
		data.DeviceRows, err = p.Store.ListDevices(ctx)
	case "keys":
		data.KeyRows, err = p.Store.ListAdminKeys(ctx)
	case "security":
		data.Audit, data.ChainOK, err = p.Store.ReadAudit(ctx)
		if err == nil {
			data.Sessions, data.Trusted, err = p.Store.ListWebSessions(ctx, p.now())
		}
	case "settings":
	default:
		http.NotFound(w, r)
		return
	}
	if err != nil {
		p.failed(w)
		return
	}
	p.page(w, 200, data)
}

func (p *Panel) messagePage(w http.ResponseWriter, r *http.Request) {
	sess, ok := p.require(w, r, true)
	if !ok {
		return
	}
	id := r.PathValue("id")
	if !messageID.MatchString(id) {
		http.Error(w, "消息 ID 无效", 400)
		return
	}
	mi, err := p.Store.GetMessage(r.Context(), id)
	if errors.Is(err, store.ErrNotFound) {
		http.NotFound(w, r)
		return
	}
	if err != nil {
		p.failed(w)
		return
	}
	state, err := p.Store.InboxState(r.Context(), id)
	if errors.Is(err, store.ErrNotFound) {
		http.NotFound(w, r)
		return
	}
	if err != nil {
		p.failed(w)
		return
	}
	var title, body string
	var meta map[string]string
	var found bool
	if state.BodyExpires.After(p.now()) {
		title, body, meta, found, err = p.Store.ReadBody(r.Context(), id)
		if err != nil {
			p.failed(w)
			return
		}
	}
	if err := p.Store.Audit(r.Context(), p.now(), sess.Identity, "message.view", id, "ok"); err != nil {
		p.failed(w)
		return
	}
	received := mi.Message.ReceivedAt
	if p.Status != nil && p.Status.Location != nil {
		received = received.In(p.Status.Location)
	}
	p.page(w, 200, pageData{Mode: "panel", Tab: "message", CSRF: sess.CSRF, Identity: sess.Identity,
		Message: mi, Title: title, Body: body, Meta: meta, HasBody: found, Received: received.Format("2006-01-02 15:04:05 MST")})
}

func (p *Panel) search(w http.ResponseWriter, r *http.Request) {
	sess, ok := p.require(w, r, true)
	if !ok {
		return
	}
	if !csrfOK(r, sess) {
		http.Error(w, "请求校验失败", 403)
		return
	}
	if _, busy := p.searches.LoadOrStore(sess.Hash, struct{}{}); busy {
		http.Error(w, "已有搜索正在进行", 429)
		return
	}
	defer p.searches.Delete(sess.Hash)
	var data struct {
		Query string `json:"query"`
	}
	r.Body = http.MaxBytesReader(w, r.Body, 2048)
	if err := decodeJSON(r.Body, &data); err != nil || len([]rune(data.Query)) > 200 || strings.TrimSpace(data.Query) == "" {
		http.Error(w, "请输入 1 到 200 个字符的搜索词", 400)
		return
	}
	hits, scanned, exhausted, err := service.Search(r.Context(), p.Store, data.Query, 20)
	if err != nil {
		p.failed(w)
		return
	}
	if err := p.Store.Audit(r.Context(), p.now(), sess.Identity, "message.search", "", "ok"); err != nil {
		p.failed(w)
		return
	}
	type hit struct{ ID, Source, Preview string }
	out := make([]hit, 0, len(hits))
	for _, h := range hits {
		out = append(out, hit{h.ID, visible(h.SourceID), visible(h.Preview)})
	}
	reply(w, 200, map[string]any{"hits": out, "scanned": scanned, "exhausted": exhausted})
}

func visible(s string) string {
	var b strings.Builder
	for _, r := range s {
		if unicode.Is(unicode.Cf, r) || r == '\u034f' || r == '\u115f' || r == '\u1160' || r == '\u2800' {
			fmt.Fprintf(&b, "⟨U+%04X⟩", r)
		} else {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func decodeJSON(r io.Reader, dst any) error {
	dec := json.NewDecoder(r)
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		return err
	}
	var extra any
	if err := dec.Decode(&extra); err != io.EOF {
		return errors.New("trailing request data")
	}
	return nil
}

func auditDate(ms int64) string { return time.UnixMilli(ms).UTC().Format("2006-01-02 15:04 UTC") }
