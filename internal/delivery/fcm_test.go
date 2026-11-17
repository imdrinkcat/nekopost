package delivery

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"golang.org/x/oauth2"
)

func TestFCMIDsOnlyAndRetryClassification(t *testing.T) {
	cases := []struct {
		name    string
		code    int
		detail  string
		kind    Outcome
		invalid bool
		wait    time.Duration
	}{
		{"accepted", 200, "", Delivered, false, 0},
		{"unregistered", 404, "UNREGISTERED", Permanent, true, 0},
		{"invalid registration", 400, "INVALID_ARGUMENT", Permanent, true, 0},
		{"bad payload", 400, "", Permanent, false, 0},
		{"wrong project", 403, "SENDER_ID_MISMATCH", Permanent, false, 0},
		{"auth", 401, "", RetrySafe, false, 0},
		{"rate limited", 429, "", RetrySafe, false, 23 * time.Second},
		{"unavailable", 503, "", RetrySafe, false, 23 * time.Second},
		{"server error", 500, "", RetrySafe, false, 0},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Header.Get("Authorization") != "Bearer fixture" {
					t.Error("missing fixture access token")
				}
				var body struct{ Message map[string]json.RawMessage }
				if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
					t.Error(err)
				}
				if len(body.Message) != 3 || body.Message["notification"] != nil {
					t.Error("unexpected FCM shape")
				}
				const data = `{"id":"m_fixture","seq":"1","t":"msg","v":"1"}`
				if strings.TrimSpace(string(body.Message["data"])) != data {
					t.Error("unexpected FCM data payload")
				}
				w.Header().Set("Retry-After", "23")
				w.WriteHeader(c.code)
				if c.detail != "" {
					_ = json.NewEncoder(w).Encode(map[string]any{"error": map[string]any{"details": []map[string]string{
						{"@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", "errorCode": c.detail},
					}}})
				}
			}))
			defer srv.Close()
			f := &FCM{endpoint: srv.URL, http: srv.Client(), tokens: oauth2.StaticTokenSource(&oauth2.Token{AccessToken: "fixture"}), Now: time.Now}
			got, invalid := f.send(context.Background(), "fixture_registration", map[string]string{"v": "1", "t": "msg", "id": "m_fixture", "seq": "1"}, "HIGH", "86400s")
			if got.Outcome != c.kind || invalid != c.invalid || got.RetryAfter != c.wait {
				t.Fatal(got, invalid)
			}
		})
	}
}

func TestFCMPermissionDeniedReason(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(403)
		_ = json.NewEncoder(w).Encode(map[string]any{"error": map[string]any{"status": "PERMISSION_DENIED", "message": "echoed text must not leak"}})
	}))
	defer srv.Close()
	f := &FCM{endpoint: srv.URL, http: srv.Client(), tokens: oauth2.StaticTokenSource(&oauth2.Token{AccessToken: "fixture"}), Now: time.Now}
	got, _ := f.send(context.Background(), "fixture_registration", map[string]string{"v": "1", "t": "ping"}, "HIGH", "300s")
	if !strings.Contains(got.Err, "缺少权限") || strings.Contains(got.Err, "echoed") {
		t.Fatal(got.Err)
	}
	var pe *PushError
	if err := error(&PushError{Reason: got.Err}); !errors.As(err, &pe) || pe.Reason != got.Err {
		t.Fatal(err)
	}
}
