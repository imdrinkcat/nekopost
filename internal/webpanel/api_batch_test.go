package webpanel

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"nekopost/internal/auth"
	"nekopost/internal/httpapi"
	"nekopost/internal/model"
	"nekopost/internal/store"
)

// withAPI puts the real httpapi server in front of the panel, wired the way
// internal/app wires it, so the panel's cookie check guards the API routes.
func withAPI(f *panelFixture) http.Handler {
	st := f.panel.Store
	api := &httpapi.Server{Store: st, Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Location: time.UTC,
		Version: "test", DPoP: auth.NewDPoP(), PublicURL: "https://panel.example"}
	api.CookieAdmin = f.panel.AuthorizeAdmin
	api.WebSessionHash = f.panel.SessionHash
	api.Web = f.panel.Handler()
	return api.Handler()
}

// activeSession signs in and approves the session, returning its cookie and
// CSRF token.
func (f *panelFixture) activeSession() (*http.Cookie, string) {
	t := f.t
	t.Helper()
	st := f.panel.Store
	pending := f.signIn(nil)
	a := f.alerts[len(f.alerts)-1]
	s, err := st.WebSession(t.Context(), hash(pending.Value), time.Now())
	if err != nil {
		t.Fatal(err)
	}
	if ok, err := st.DecideWebApproval(t.Context(), a.ID, a.Code, "cli", true, time.Now()); !ok || err != nil {
		t.Fatal(ok, err)
	}
	w := f.request("POST", "/auth/activate", s.CSRF, "{}", pending)
	active := getCookie(w, sidCookie)
	if w.Code != 200 || active == nil {
		t.Fatal(w.Code, w.Body.String())
	}
	s, err = st.WebSession(t.Context(), hash(active.Value), time.Now())
	if err != nil {
		t.Fatal(err)
	}
	return active, s.CSRF
}

// The production pairing of the panel's cookie check with the httpapi batch
// read routes: cross-site requests and requests without the CSRF header are
// refused and change nothing; a proper same-origin request goes through.
func TestPanelBatchReadThroughAPI(t *testing.T) {
	f := setupPanel(t)
	f.handler = withAPI(f)
	st := f.panel.Store
	now := time.Now()
	var ids []string
	for i, body := range []string{"第一条", "第二条"} {
		m := &model.Message{ID: model.NewMessageID(now.Add(time.Duration(i) * time.Millisecond)), SourceID: "test", Kind: "sms",
			Priority: model.PriorityNormal, ReceivedAt: now.Add(time.Duration(i) * time.Millisecond), Body: body}
		if _, err := st.InsertMessage(t.Context(), m, []store.NewDelivery{{Channel: "telegram", Target: "default"}}, 0); err != nil {
			t.Fatal(err)
		}
		ids = append(ids, m.ID)
	}
	active, csrf := f.activeSession()
	readAll := `{"all":true,"up_to_id":"` + ids[1] + `"}`
	byID := `{"ids":["` + ids[0] + `"]}`

	send := func(path, body string, edit func(*http.Request)) *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", "https://panel.example"+path, strings.NewReader(body))
		r.AddCookie(active)
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Origin", "https://panel.example")
		r.Header.Set("Sec-Fetch-Site", "same-origin")
		r.Header.Set("X-Nekopost-CSRF", csrf)
		if edit != nil {
			edit(r)
		}
		w := httptest.NewRecorder()
		f.handler.ServeHTTP(w, r)
		return w
	}
	refusals := []struct {
		name string
		code string
		edit func(*http.Request)
	}{
		{"no CSRF header", "invalid_csrf", func(r *http.Request) { r.Header.Del("X-Nekopost-CSRF") }},
		{"wrong CSRF", "invalid_csrf", func(r *http.Request) { r.Header.Set("X-Nekopost-CSRF", "bad") }},
		{"cross-site origin", "cross_origin", func(r *http.Request) {
			r.Header.Set("Origin", "https://evil.example")
			r.Header.Set("Sec-Fetch-Site", "cross-site")
		}},
		{"cross-site fetch", "cross_origin", func(r *http.Request) { r.Header.Set("Sec-Fetch-Site", "same-site") }},
	}
	for _, c := range refusals {
		for _, call := range [][2]string{{"/v1/inbox/read", readAll}, {"/v1/inbox/read", byID}, {"/v1/inbox/unread", byID}} {
			if w := send(call[0], call[1], c.edit); w.Code != 403 || !strings.Contains(w.Body.String(), `"`+c.code+`"`) {
				t.Fatal(c.name, call[0], w.Code, w.Body.String())
			}
		}
	}
	if n, _ := st.UnreadCount(t.Context()); n != 2 {
		t.Fatal("a refused request changed read state", n)
	}
	if w := send("/v1/inbox/read", readAll, nil); w.Code != 200 || !strings.Contains(w.Body.String(), `"changed":2`) {
		t.Fatal(w.Code, w.Body.String())
	}
	if w := send("/v1/inbox/unread", byID, nil); w.Code != 200 || !strings.Contains(w.Body.String(), `"changed":1`) {
		t.Fatal(w.Code, w.Body.String())
	}
	if n, _ := st.UnreadCount(t.Context()); n != 1 {
		t.Fatal(n)
	}
}
