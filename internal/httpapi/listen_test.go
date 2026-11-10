package httpapi

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"golang.org/x/crypto/acme/autocert"
)

func TestPlainHandler(t *testing.T) {
	api := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusTeapot) })
	m := &autocert.Manager{HostPolicy: autocert.HostWhitelist("example.com")}
	h := plainHandler(api, m.HTTPHandler(nil))

	for path, want := range map[string]int{
		"/healthz":     http.StatusTeapot, // liveness probe served over plain HTTP
		"/v1/messages": http.StatusFound,  // the API itself only over HTTPS
		"/":            http.StatusFound,
	} {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest("GET", "http://example.com"+path, nil))
		if rec.Code != want {
			t.Errorf("%s: got %d, want %d", path, rec.Code, want)
		}
	}
}
