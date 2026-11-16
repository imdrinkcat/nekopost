package webpanel

import (
	"errors"
	"net/http"

	"nekopost/internal/store"
)

func (p *Panel) beginReauth(w http.ResponseWriter, r *http.Request) {
	session, ok := p.require(w, r, true)
	if !ok {
		return
	}
	if !csrfOK(r, session) {
		http.Error(w, "请求校验失败", 403)
		return
	}
	session.IP, session.Browser = p.ip(r), browserLabel(r.UserAgent())
	a, err := newApproval(session, p.now())
	if err != nil {
		p.failed(w)
		return
	}
	a, created, err := p.Store.BeginWebReauth(r.Context(), session, a, p.now())
	if errors.Is(err, store.ErrWebRestricted) {
		http.Error(w, "请稍后再试", 429)
		return
	}
	if err != nil {
		p.failed(w)
		return
	}
	if created && p.NotifyApproval != nil {
		p.NotifyApproval(r.Context(), a)
	}
	reply(w, 200, map[string]string{"id": a.ID, "short_code": a.Code})
}

func (p *Panel) finishReauth(w http.ResponseWriter, r *http.Request) {
	session, ok := p.require(w, r, true)
	if !ok {
		return
	}
	if !csrfOK(r, session) {
		http.Error(w, "请求校验失败", 403)
		return
	}
	err := p.Store.ConsumeWebReauth(r.Context(), session, p.now())
	if errors.Is(err, store.ErrNotFound) {
		http.Error(w, "确认尚未完成或已过期", 409)
		return
	}
	if err != nil {
		p.failed(w)
		return
	}
	w.WriteHeader(204)
}
