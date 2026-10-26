// Package httpapi exposes the versioned HTTP interface: message submission
// for sources (/v1/messages), read-only administration (/v1/admin/...) and
// a minimal public liveness probe (/healthz).
package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"log/slog"
	"net"
	"net/http"
	"runtime/debug"
	"time"

	"msggw/internal/delivery"
	"msggw/internal/health"
	"msggw/internal/ratelimit"
	"msggw/internal/router"
	"msggw/internal/status"
	"msggw/internal/store"
)

// Waker is implemented by the delivery worker.
type Waker interface{ Wake() }

// Server holds the dependencies of the HTTP handlers.
type Server struct {
	Store  *store.Store
	Router router.Router
	Worker Waker
	// Filter, when set, receives messages whose type is in FilterTypes
	// before they are delivered.
	Filter       Waker
	FilterTypes  map[string]bool
	Notifier     *delivery.Notifier
	Health       *health.Tracker
	Status       *status.Service
	Log          *slog.Logger
	DedupeKey    []byte
	DedupeWindow time.Duration
	MaxBodyChars int
	Location     *time.Location
	Version      string
	HSTS         bool

	SourceLimiter *ratelimit.Limiter
	FailGuard     *ratelimit.FailureGuard
	Now           func() time.Time

	sem chan struct{}
}

// MaxConcurrent bounds simultaneous requests being handled.
const MaxConcurrent = 32

// maxWait is the longest a submission may wait for its delivery outcome.
const maxWait = 25 * time.Second

func (s *Server) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

// Handler builds the HTTP handler with all routes and middleware.
func (s *Server) Handler() http.Handler {
	s.sem = make(chan struct{}, MaxConcurrent)
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/messages", methods(map[string]http.HandlerFunc{http.MethodPost: s.handleSubmit}))
	mux.HandleFunc("/v1/messages/{id}", methods(map[string]http.HandlerFunc{http.MethodGet: s.handleGetMessage}))
	mux.HandleFunc("/v1/admin/status", methods(map[string]http.HandlerFunc{http.MethodGet: s.admin(s.handleAdminStatus)}))
	mux.HandleFunc("/v1/admin/sources", methods(map[string]http.HandlerFunc{http.MethodGet: s.admin(s.handleAdminSources)}))
	mux.HandleFunc("/v1/admin/stats", methods(map[string]http.HandlerFunc{http.MethodGet: s.admin(s.handleAdminStats)}))
	mux.HandleFunc("/healthz", methods(map[string]http.HandlerFunc{http.MethodGet: s.handleHealthz, http.MethodHead: s.handleHealthz}))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		writeError(w, r, errNotFound)
	})
	return s.middleware(mux)
}

func methods(m map[string]http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if h, ok := m[r.Method]; ok {
			h(w, r)
			return
		}
		allow := ""
		for k := range m {
			if allow != "" {
				allow += ", "
			}
			allow += k
		}
		w.Header().Set("Allow", allow)
		writeError(w, r, &apiError{Status: http.StatusMethodNotAllowed, Code: "method_not_allowed", Message: "不支持该 HTTP 方法"})
	}
}

type ctxKey struct{}

// reqInfo carries per-request data for logging.
type reqInfo struct {
	ID     string
	Source string
}

func info(r *http.Request) *reqInfo {
	if ri, ok := r.Context().Value(ctxKey{}).(*reqInfo); ok {
		return ri
	}
	return &reqInfo{}
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

func (s *Server) middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		var b [8]byte
		_, _ = rand.Read(b[:])
		ri := &reqInfo{ID: hex.EncodeToString(b[:])}
		r = r.WithContext(context.WithValue(r.Context(), ctxKey{}, ri))
		h := w.Header()
		h.Set("X-Request-Id", ri.ID)
		h.Set("Cache-Control", "no-store")
		h.Set("X-Content-Type-Options", "nosniff")
		if s.HSTS {
			h.Set("Strict-Transport-Security", "max-age=31536000")
		}
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}

		defer func() {
			if v := recover(); v != nil {
				s.Log.Error("panic in handler", "panic", v, "stack", string(debug.Stack()), "request_id", ri.ID)
				writeError(rec, r, errInternal)
			}
			// Never log query strings, headers or bodies: they may contain
			// credentials or private message content.
			s.Log.Info("http", "method", r.Method, "path", r.URL.Path, "status", rec.status,
				"ms", time.Since(start).Milliseconds(), "ip", clientIP(r), "source", ri.Source, "request_id", ri.ID)
		}()

		select {
		case s.sem <- struct{}{}:
			defer func() { <-s.sem }()
		default:
			writeError(rec, r, &apiError{Status: http.StatusServiceUnavailable, Code: "busy", Message: "服务繁忙，请稍后重试", RetryAfter: 5 * time.Second})
			return
		}
		next.ServeHTTP(rec, r)
	})
}

// clientIP returns the address of the directly connected peer. Forwarding
// headers are ignored on purpose: the service is exposed directly.
func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func (s *Server) handleHealthz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	st := s.Health.Snapshot()
	if err := s.Store.Ping(ctx); err != nil || s.now().Sub(st.WorkerBeat) > health.WorkerStaleAfter {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "unavailable"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}
