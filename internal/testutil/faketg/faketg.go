// Package faketg is an in-process fake of the Telegram Bot API for tests.
package faketg

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// Token is the bot token the fake expects.
const Token = "123456:TEST_TOKEN_abcdefghijklmnopqrstuvwxyz"

// Call is one recorded API call.
type Call struct {
	Method string
	Params map[string]any
}

// Response scripts one reply. Zero Status means 200 OK with Result.
type Response struct {
	Status      int
	Result      any
	Description string
	RetryAfter  int
	Delay       time.Duration // sleep before answering (simulate timeouts)
	Hijack      bool          // close the connection without answering
}

// Server is the fake API.
type Server struct {
	*httptest.Server
	mu       sync.Mutex
	calls    []Call
	scripted map[string][]Response
	updates  chan []map[string]any
	nextID   int64
}

// New starts a fake server; it is closed when the test ends.
func New(t testing.TB) *Server {
	s := &Server{scripted: map[string][]Response{}, updates: make(chan []map[string]any, 16), nextID: 100}
	s.Server = httptest.NewServer(http.HandlerFunc(s.handle))
	t.Cleanup(s.Close)
	return s
}

// Script queues responses for a method; once used up, defaults apply.
func (s *Server) Script(method string, rs ...Response) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.scripted[method] = append(s.scripted[method], rs...)
}

// PushUpdates makes the next getUpdates call return these updates.
func (s *Server) PushUpdates(ups ...map[string]any) { s.updates <- ups }

// Calls returns recorded calls of a method (all methods if empty).
func (s *Server) Calls(method string) []Call {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []Call
	for _, c := range s.calls {
		if method == "" || c.Method == method {
			out = append(out, c)
		}
	}
	return out
}

// WaitCalls waits until at least n calls of method were recorded.
func (s *Server) WaitCalls(t testing.TB, method string, n int, timeout time.Duration) []Call {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if c := s.Calls(method); len(c) >= n {
			return c
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %d %s calls (got %d)", n, method, len(s.Calls(method)))
	return nil
}

func (s *Server) handle(w http.ResponseWriter, r *http.Request) {
	prefix := "/bot" + Token + "/"
	if !strings.HasPrefix(r.URL.Path, prefix) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = io.WriteString(w, `{"ok":false,"error_code":404,"description":"Not Found"}`)
		return
	}
	method := strings.TrimPrefix(r.URL.Path, prefix)
	var params map[string]any
	body, _ := io.ReadAll(r.Body)
	_ = json.Unmarshal(body, &params)

	s.mu.Lock()
	s.calls = append(s.calls, Call{Method: method, Params: params})
	var resp *Response
	if q := s.scripted[method]; len(q) > 0 {
		resp = &q[0]
		s.scripted[method] = q[1:]
	}
	s.mu.Unlock()

	if resp == nil {
		resp = s.defaultResponse(r, method, params)
	}
	if resp.Delay > 0 {
		select {
		case <-time.After(resp.Delay):
		case <-r.Context().Done():
			return
		}
	}
	if resp.Hijack {
		if hj, ok := w.(http.Hijacker); ok {
			conn, _, _ := hj.Hijack()
			conn.Close()
			return
		}
	}
	status := resp.Status
	if status == 0 {
		status = 200
	}
	out := map[string]any{"ok": status == 200}
	if status == 200 {
		out["result"] = resp.Result
	} else {
		out["error_code"] = status
		out["description"] = resp.Description
		if resp.RetryAfter > 0 {
			out["parameters"] = map[string]any{"retry_after": resp.RetryAfter}
		}
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(out)
}

func (s *Server) defaultResponse(r *http.Request, method string, params map[string]any) *Response {
	switch method {
	case "getMe":
		return &Response{Result: map[string]any{"id": 123456, "is_bot": true, "first_name": "Gateway", "username": "test_gateway_bot"}}
	case "getUpdates":
		timeout := 0.0
		if v, ok := params["timeout"].(float64); ok {
			timeout = v
		}
		select {
		case ups := <-s.updates:
			return &Response{Result: ups}
		case <-time.After(time.Duration(min(timeout, 1)) * time.Second):
			return &Response{Result: []any{}}
		case <-r.Context().Done():
			return &Response{Result: []any{}}
		}
	case "sendMessage":
		s.mu.Lock()
		s.nextID++
		id := s.nextID
		s.mu.Unlock()
		return &Response{Result: map[string]any{"message_id": id, "date": time.Now().Unix(),
			"chat": map[string]any{"id": params["chat_id"], "type": "private"}, "text": params["text"]}}
	}
	return &Response{Result: true}
}
