package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"net/http"
	"regexp"
	"strings"
	"time"

	"nekopost/internal/auth"
	"nekopost/internal/enroll"
	"nekopost/internal/model"
	"nekopost/internal/store"
)

type controlHandler func(http.ResponseWriter, *http.Request, string)

func (s *Server) control(scope string, high bool, next controlHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if len(r.Header.Values("Authorization")) == 0 && s.CookieAdmin != nil {
			actor, ok := s.CookieAdmin(w, r, high)
			if ok {
				next(w, r, actor)
			}
			return
		}
		d, ok := s.authorizeDevice(w, r, scope, false)
		if !ok {
			return
		}
		if high && (d.ReauthAt.IsZero() || s.now().Sub(d.ReauthAt) > 5*time.Minute) {
			deviceError(w, r, 403, "reauth_required", "请先重新认证")
			return
		}
		next(w, r, "device:"+d.ID)
	}
}

func (s *Server) registerManagement(mux *http.ServeMux) {
	ro := func(h controlHandler) http.HandlerFunc { return s.control(store.ScopeAdminRead, false, h) }
	rw := func(h controlHandler) http.HandlerFunc { return s.control(store.ScopeAdminWrite, false, h) }
	high := func(h controlHandler) http.HandlerFunc { return s.control(store.ScopeAdminWrite, true, h) }
	mux.HandleFunc("GET /v1/admin/filtered", ro(s.filteredView))
	mux.HandleFunc("GET /v1/admin/audit", ro(s.auditView))
	mux.HandleFunc("GET /v1/admin/sessions", ro(s.listWebSessions))
	mux.HandleFunc("DELETE /v1/admin/sessions/{id}", high(s.revokeWebAccess("session")))
	mux.HandleFunc("DELETE /v1/admin/trusted-browsers/{id}", high(s.revokeWebAccess("browser")))
	mux.HandleFunc("POST /v1/admin/sessions/revoke-others", high(s.revokeWebAccess("others")))
	mux.HandleFunc("GET /v1/admin/devices", ro(func(w http.ResponseWriter, r *http.Request, _ string) { s.listDevices(w, r, store.Device{}) }))
	mux.HandleFunc("POST /v1/admin/devices/{id}/test-push", rw(s.testDevicePush))
	mux.HandleFunc("DELETE /v1/admin/devices/{id}", high(s.revokeDevice))
	mux.HandleFunc("POST /v1/admin/sources", high(s.manageSource("create")))
	mux.HandleFunc("PATCH /v1/admin/sources/{id}", rw(s.patchSource))
	mux.HandleFunc("POST /v1/admin/sources/{id}/rotate", high(s.manageSource("rotate")))
	mux.HandleFunc("DELETE /v1/admin/sources/{id}", high(s.manageSource("delete")))
	mux.HandleFunc("GET /v1/admin/keys", ro(s.listAdminKeys))
	mux.HandleFunc("POST /v1/admin/keys", high(s.createAdminKey))
	mux.HandleFunc("DELETE /v1/admin/keys/{prefix}", high(s.deleteAdminKey))
	mux.HandleFunc("POST /v1/admin/pairings", high(s.createPairing))
	mux.HandleFunc("GET /v1/admin/pairings/{id}", ro(s.pairingStatus))
	mux.HandleFunc("POST /v1/admin/pairings/{id}/decision", high(s.decidePairing))
	// Voiding an unused code only reduces exposure: no fresh re-authentication needed.
	mux.HandleFunc("DELETE /v1/admin/pairings/{id}", rw(s.cancelPairing))
	mux.HandleFunc("POST /v1/inbox/{id}/rescue", rw(s.retryMessage(true)))
	mux.HandleFunc("POST /v1/inbox/{id}/redeliver", rw(s.retryMessage(false)))
	mux.HandleFunc("DELETE /v1/inbox/{id}/body", high(s.shredMessage))
	mux.HandleFunc("POST /v1/inbox/export", high(s.exportMessages))
}

func controlError(w http.ResponseWriter, r *http.Request, err error) bool {
	switch {
	case err == nil:
		return false
	case errors.Is(err, store.ErrNotFound), errors.Is(err, store.ErrNotRescuable):
		writeError(w, r, errNotFound)
	case errors.Is(err, store.ErrExists), errors.Is(err, store.ErrDeliveryInFlight):
		deviceError(w, r, 409, "conflict", "当前状态无法执行此操作")
	case errors.Is(err, store.ErrLocked):
		deviceError(w, r, 403, "locked_down", "当前处于紧急锁定")
	default:
		writeError(w, r, errInternal)
	}
	return true
}

func (s *Server) highAlert(ctx context.Context, text string) {
	if s.SecurityNotify != nil {
		s.SecurityNotify(ctx, text)
	}
}

func (s *Server) manageSource(action string) controlHandler {
	return func(w http.ResponseWriter, r *http.Request, actor string) {
		var b struct {
			ID   string `json:"id"`
			Name string `json:"name"`
			Icon string `json:"icon"`
		}
		if action == "create" && readJSON(w, r, &b) != nil {
			deviceError(w, r, 400, "invalid_body", "请求格式无效")
			return
		}
		if action != "create" {
			b.ID = r.PathValue("id")
		}
		if !model.ValidSourceID(b.ID) {
			deviceError(w, r, 400, "invalid_source", "来源 ID 无效")
			return
		}
		if b.Name == "" {
			b.Name = b.ID
		}
		token := ""
		var v store.SourceChange
		v.ID, v.Name, v.Icon = b.ID, deviceText(b.Name), deviceText(b.Icon)
		var link string
		if action == "create" || action == "rotate" {
			token = auth.NewToken(auth.SourcePrefix)
			v.Hash, v.Prefix = auth.Hash(token), auth.DisplayPrefix(token)
			if action == "rotate" {
				src, err := s.Store.GetSource(r.Context(), v.ID)
				if controlError(w, r, err) {
					return
				}
				v.Name = src.Name
			}
			var err error
			link, err = enroll.Link(s.PublicURL, v.ID, token, v.Name)
			if err != nil {
				deviceError(w, r, 400, "invalid_source", "服务器地址或名称不支持登记")
				return
			}
		}
		if controlError(w, r, s.Store.ChangeSource(r.Context(), actor, action, v, s.now())) {
			return
		}
		s.highAlert(r.Context(), "来源凭证已变更 · "+v.ID)
		if token == "" {
			w.WriteHeader(204)
			return
		}
		writeJSON(w, 201, map[string]string{"id": v.ID, "token": token, "qr": link})
	}
}

func (s *Server) patchSource(w http.ResponseWriter, r *http.Request, actor string) {
	id := r.PathValue("id")
	var body struct {
		Name    string `json:"name"`
		Icon    string `json:"icon"`
		Enabled *bool  `json:"enabled"`
	}
	if !model.ValidSourceID(id) || readJSON(w, r, &body) != nil {
		deviceError(w, r, 400, "invalid_body", "来源设置无效")
		return
	}
	apply := func(w http.ResponseWriter, r *http.Request, actor string) {
		v := store.SourceChange{ID: id, Name: deviceText(body.Name), Icon: deviceText(body.Icon), Enabled: body.Enabled}
		if controlError(w, r, s.Store.ChangeSource(r.Context(), actor, "patch", v, s.now())) {
			return
		}
		if v.Enabled != nil {
			s.highAlert(r.Context(), "来源启停设置已变更 · "+id)
		}
		w.WriteHeader(204)
	}
	if body.Enabled != nil {
		// The request proof was already consumed by the first guard.
		if strings.HasPrefix(actor, "device:") {
			token, _ := auth.DeviceAuthorization(r.Header.Get("Authorization"))
			d, err := s.Store.DeviceByToken(r.Context(), auth.Hash(token))
			if controlError(w, r, err) {
				return
			}
			if d.Revoked || d.Suspended || !d.Has(store.ScopeAdminWrite) || d.ReauthAt.IsZero() || s.now().Sub(d.ReauthAt) > 5*time.Minute {
				deviceError(w, r, 403, "reauth_required", "请先重新认证")
				return
			}
		} else if _, ok := s.CookieAdmin(w, r, true); !ok {
			return
		}
	}
	apply(w, r, actor)
}

func (s *Server) listAdminKeys(w http.ResponseWriter, r *http.Request, actor string) {
	keys, err := s.Store.ListAdminKeys(r.Context())
	if controlError(w, r, err) {
		return
	}
	writeJSON(w, 200, map[string]any{"keys": keys})
}

func (s *Server) createAdminKey(w http.ResponseWriter, r *http.Request, actor string) {
	var body struct {
		Name string `json:"name"`
	}
	if readJSON(w, r, &body) != nil || deviceText(body.Name) == "" {
		deviceError(w, r, 400, "invalid_name", "需要名称")
		return
	}
	token := auth.NewToken(auth.AdminPrefix)
	prefix := auth.DisplayPrefix(token)
	if controlError(w, r, s.Store.ChangeAdminKey(r.Context(), actor, "create", deviceText(body.Name), auth.Hash(token), prefix, s.now())) {
		return
	}
	s.highAlert(r.Context(), "新增了管理 API 凭证")
	writeJSON(w, 201, map[string]string{"token": token, "prefix": prefix})
}

func (s *Server) deleteAdminKey(w http.ResponseWriter, r *http.Request, actor string) {
	if controlError(w, r, s.Store.ChangeAdminKey(r.Context(), actor, "revoke", "", "", r.PathValue("prefix"), s.now())) {
		return
	}
	s.highAlert(r.Context(), "已吊销管理 API 凭证")
	w.WriteHeader(204)
}

func (s *Server) createPairing(w http.ResponseWriter, r *http.Request, actor string) {
	var body struct {
		Name   string `json:"name"`
		Role   string `json:"role"`
		Source string `json:"source"`
	}
	if readJSON(w, r, &body) != nil || deviceText(body.Name) == "" {
		deviceError(w, r, 400, "invalid_body", "配对信息无效")
		return
	}
	var scopes string
	switch body.Role {
	case "full":
		scopes = strings.Join([]string{store.ScopeSend, store.ScopeRead, store.ScopeAdminRead, store.ScopeAdminWrite}, ",")
	case "sender":
		scopes = store.ScopeSend
	case "receiver":
		scopes = store.ScopeRead
		body.Source = ""
	default:
		deviceError(w, r, 400, "invalid_role", "配对角色无效")
		return
	}
	if body.Role != "receiver" && !model.ValidSourceID(body.Source) {
		deviceError(w, r, 400, "invalid_source", "请输入来源 ID")
		return
	}
	code, short := enroll.NewPairingCodes()
	url, err := enroll.DeviceLink(s.PublicURL, code)
	if err != nil {
		writeError(w, r, errInternal)
		return
	}
	now := s.now()
	p := store.Pairing{ID: "p_" + rand.Text(), Name: deviceText(body.Name), SourceID: body.Source, Scopes: scopes, Expires: now.Add(5 * time.Minute)}
	if controlError(w, r, s.Store.NewPairing(r.Context(), p, store.PairingCodeHash(code), store.PairingCodeHash(short), actor, now)) {
		return
	}
	s.highAlert(r.Context(), "创建了新的设备配对")
	symbol, err := enroll.Encode(url)
	if err != nil {
		writeError(w, r, errInternal)
		return
	}
	writeJSON(w, 201, map[string]any{"id": p.ID, "qr": url, "qr_png": base64.StdEncoding.EncodeToString(symbol.PNG()),
		"short_code": short[:4] + "-" + short[4:8] + "-" + short[8:], "expires_in": 300})

}

func (s *Server) pairingStatus(w http.ResponseWriter, r *http.Request, actor string) {
	p, err := s.Store.PairingOwned(r.Context(), r.PathValue("id"), actor)
	if controlError(w, r, err) {
		return
	}
	state := p.State
	if !p.Expires.After(s.now()) && state != "done" {
		state = "expired"
	}
	var choices []string
	if state == "claimed" {
		choices = p.Choices
	}
	writeJSON(w, 200, map[string]any{"id": p.ID, "state": state, "name": p.Name, "model": p.Model, "choices": choices,
		"expires_at": p.Expires.UTC().Format(time.RFC3339)})
}

// cancelPairing voids the caller's own pairing while nobody has claimed it
// (「作废配对码」 on the admin phone, like the bot's 「作废此配对码」).
func (s *Server) cancelPairing(w http.ResponseWriter, r *http.Request, actor string) {
	id := r.PathValue("id")
	if _, err := s.Store.PairingOwned(r.Context(), id, actor); controlError(w, r, err) {
		return
	}
	if controlError(w, r, s.Store.CancelPairing(r.Context(), id, actor, s.now())) {
		return
	}
	w.WriteHeader(204)
}

func (s *Server) revokeDevice(w http.ResponseWriter, r *http.Request, actor string) {
	if controlError(w, r, s.Store.RevokeDevice(r.Context(), r.PathValue("id"), actor, s.now())) {
		return
	}
	s.highAlert(r.Context(), "已吊销设备")
	w.WriteHeader(204)
}

func (s *Server) auditView(w http.ResponseWriter, r *http.Request, actor string) {
	rows, valid, err := s.Store.ReadAudit(r.Context())
	if controlError(w, r, err) {
		return
	}
	writeJSON(w, 200, map[string]any{"entries": rows, "chain_ok": valid})
}

func (s *Server) retryMessage(rescue bool) controlHandler {
	return func(w http.ResponseWriter, r *http.Request, actor string) {
		id := r.PathValue("id")
		if !inboxID.MatchString(id) {
			writeError(w, r, errNotFound)
			return
		}
		if controlError(w, r, s.Store.RetryMessage(r.Context(), id, actor, rescue, s.now())) {
			return
		}
		s.Worker.Wake()
		w.WriteHeader(204)
	}
}

func (s *Server) shredMessage(w http.ResponseWriter, r *http.Request, actor string) {
	id := r.PathValue("id")
	if controlError(w, r, s.Store.DestroyBody(r.Context(), id, actor, s.now())) {
		return
	}
	s.highAlert(r.Context(), "已销毁一条消息的正文")
	s.signal("gone", []string{id}, strings.TrimPrefix(actor, "device:"))
	w.WriteHeader(204)
}

func (s *Server) exportMessages(w http.ResponseWriter, r *http.Request, actor string) {
	if strings.HasPrefix(actor, "device:") {
		tok, _ := auth.DeviceAuthorization(r.Header.Get("Authorization"))
		d, err := s.Store.DeviceByToken(r.Context(), auth.Hash(tok))
		if controlError(w, r, err) {
			return
		}
		if !d.Has(store.ScopeRead) || d.Revoked || d.Suspended {
			deviceError(w, r, 403, "insufficient_scope", "没有读取权限")
			return
		}
	}
	var body struct {
		IDs []string `json:"ids"`
	}
	if readJSON(w, r, &body) != nil || len(body.IDs) == 0 || len(body.IDs) > 20 {
		deviceError(w, r, 400, "invalid_ids", "每次可导出 1–20 条消息")
		return
	}
	entries := make([]map[string]any, 0, len(body.IDs))
	for _, id := range body.IDs {
		if !inboxID.MatchString(id) {
			deviceError(w, r, 400, "invalid_ids", "消息 ID 无效")
			return
		}
		item, err := s.inboxItem(r.Context(), id, true)
		if controlError(w, r, err) {
			return
		}
		entries = append(entries, item)
	}
	if controlError(w, r, s.Store.Audit(r.Context(), s.now(), actor, "message.export", "", "ok")) {
		return
	}
	s.highAlert(r.Context(), "已导出消息")
	w.Header().Set("Content-Disposition", `attachment; filename="nekopost-export.json"`)
	writeJSON(w, 200, map[string]any{"items": entries})
}

func (s *Server) testDevicePush(w http.ResponseWriter, r *http.Request, actor string) {
	if s.TestPush == nil {
		deviceError(w, r, 503, "push_disabled", "服务器尚未启用推送")
		return
	}
	if controlError(w, r, s.TestPush(r.Context(), r.PathValue("id"))) {
		return
	}
	if controlError(w, r, s.Store.Audit(r.Context(), s.now(), actor, "device.test_push", r.PathValue("id"), "ok")) {
		return
	}
	w.WriteHeader(204)
}

func (s *Server) filteredView(w http.ResponseWriter, r *http.Request, actor string) {
	visible := true
	if strings.HasPrefix(actor, "device:") {
		tok, _ := auth.DeviceAuthorization(r.Header.Get("Authorization"))
		d, err := s.Store.DeviceByToken(r.Context(), auth.Hash(tok))
		if controlError(w, r, err) {
			return
		}
		visible = d.Has(store.ScopeRead) && !d.Revoked && !d.Suspended
	}
	rows, total, err := s.Store.FilteredMessages(r.Context(), 0, 20)
	if controlError(w, r, err) {
		return
	}
	items := make([]map[string]any, 0, len(rows))
	for _, f := range rows {
		if !visible {
			items = append(items, map[string]any{"id": f.ID, "source": map[string]string{"id": f.SourceID}, "category": f.Category, "received_at": s.ts(f.ReceivedAt)})
			continue
		}
		item, err := s.inboxItem(r.Context(), f.ID, false)
		if errors.Is(err, store.ErrNotFound) {
			continue
		}
		if controlError(w, r, err) {
			return
		}
		items = append(items, item)
	}
	writeJSON(w, 200, map[string]any{"items": items, "total": total})
}

func (s *Server) decidePairing(w http.ResponseWriter, r *http.Request, actor string) {
	var b struct {
		Choice string `json:"choice"`
	}
	if readJSON(w, r, &b) != nil || b.Choice != "deny" && (len(b.Choice) != 2 || strings.Trim(b.Choice, "0123456789") != "") {
		deviceError(w, r, 400, "invalid_choice", "配对确认格式无效")
		return
	}
	ok, err := s.Store.DecidePairing(r.Context(), r.PathValue("id"), b.Choice, actor, s.now())
	if controlError(w, r, err) {
		return
	}
	writeJSON(w, 200, map[string]bool{"approved": ok})
}

var sessionHashPattern = regexp.MustCompile(`^[a-f0-9]{64}$`)

func (s *Server) listWebSessions(w http.ResponseWriter, r *http.Request, actor string) {
	sessions, browsers, err := s.Store.ListWebSessions(r.Context(), s.now())
	if controlError(w, r, err) {
		return
	}
	writeJSON(w, 200, map[string]any{"sessions": sessions, "trusted_browsers": browsers})
}

func (s *Server) revokeWebAccess(kind string) controlHandler {
	return func(w http.ResponseWriter, r *http.Request, actor string) {
		id := r.PathValue("id")
		keep := ""
		if kind != "others" && !sessionHashPattern.MatchString(id) {
			writeError(w, r, errNotFound)
			return
		}
		if kind == "others" && strings.HasPrefix(actor, "web:") {
			if s.WebSessionHash == nil {
				writeError(w, r, errUnauthorized)
				return
			}
			keep = s.WebSessionHash(r)
			if keep == "" {
				writeError(w, r, errUnauthorized)
				return
			}
		}
		if controlError(w, r, s.Store.RevokeWebAccess(r.Context(), actor, kind, id, keep, s.now())) {
			return
		}
		s.highAlert(r.Context(), "网页会话或可信浏览器已吊销")
		w.WriteHeader(204)
	}
}
