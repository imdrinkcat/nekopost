package httpapi

import (
	"net/http"
	"testing"
	"time"

	"nekopost/internal/auth"
	"nekopost/internal/store"
)

func TestWebSessionRoutes(t *testing.T) {
	var highCalls int
	current := "a1"
	e := setup(t, func(e *env) {
		e.api.DPoP = auth.NewDPoP()
		e.api.PublicURL = "https://fixture.invalid"
		e.api.CookieAdmin = func(w http.ResponseWriter, r *http.Request, high bool) (string, bool) {
			if high {
				highCalls++
			}
			return "web:google:1", true
		}
		e.api.WebSessionHash = func(*http.Request) string { return current }
	})
	now := time.Now()
	for i, id := range []string{"a1", "a2"} {
		identity := "google:" + id
		pending := "p" + id
		if _, err := e.st.StartWebSession(bg, store.WebSession{Hash: pending, Identity: identity, CSRF: "c", Browser: "Chrome"},
			store.WebApproval{ID: "ap" + id, Code: "42", Choices: []string{"21", "42", "33"}, Expires: now.Add(5 * time.Minute)}, "", now); err != nil {
			t.Fatal(i, err)
		}
		if ok, err := e.st.DecideWebApproval(bg, "ap"+id, "42", "cli", false, now); !ok || err != nil {
			t.Fatal(ok, err)
		}
		session, err := e.st.WebSession(bg, pending, now)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := e.st.ActivateWebSession(bg, session, id, "c", "", now); err != nil {
			t.Fatal(err)
		}
	}

	r := e.do(t, "GET", "/v1/admin/sessions", "", "", "")
	if sessions, _ := r.body["sessions"].([]any); r.code != 200 || len(sessions) != 2 {
		t.Fatal(r.code, r.body)
	}
	if r := e.do(t, "DELETE", "/v1/admin/sessions/not-a-hash", "", "", ""); r.code != 404 {
		t.Fatal(r.code, r.body)
	}
	// Without its own session the web caller cannot tell which one to keep.
	current = ""
	if r := e.do(t, "POST", "/v1/admin/sessions/revoke-others", "", "", ""); r.code != 401 {
		t.Fatal(r.code, r.body)
	}
	current = "a1"
	if r := e.do(t, "POST", "/v1/admin/sessions/revoke-others", "", "", ""); r.code != 204 {
		t.Fatal(r.code, r.body)
	}
	if _, err := e.st.WebSession(bg, "a1", now); err != nil {
		t.Fatal("own session revoked:", err)
	}
	if _, err := e.st.WebSession(bg, "a2", now); err == nil {
		t.Fatal("other session kept")
	}
	if highCalls == 0 {
		t.Fatal("revocation did not require re-authentication")
	}
}
