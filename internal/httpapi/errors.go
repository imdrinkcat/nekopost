package httpapi

import (
	"encoding/json"
	"net/http"
	"strconv"
	"time"
)

// FieldError describes one invalid input field.
type FieldError struct {
	Field   string `json:"field"`
	Problem string `json:"problem"`
}

// apiError is the single error shape returned by every endpoint:
//
//	{"error": {"code": "...", "message": "...", "details": [...], "request_id": "..."}}
type apiError struct {
	Status     int
	Code       string
	Message    string
	Details    []FieldError
	RetryAfter time.Duration
}

func (e *apiError) Error() string { return e.Code + ": " + e.Message }

var (
	errNotFound     = &apiError{Status: http.StatusNotFound, Code: "not_found", Message: "资源不存在"}
	errInternal     = &apiError{Status: http.StatusInternalServerError, Code: "internal_error", Message: "服务器内部错误，请稍后重试"}
	errUnauthorized = &apiError{Status: http.StatusUnauthorized, Code: "unauthorized", Message: "凭证缺失、无效或已吊销"}
)

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(v)
}

func writeError(w http.ResponseWriter, r *http.Request, e *apiError) {
	if e.RetryAfter > 0 {
		secs := int(e.RetryAfter.Seconds() + 0.999)
		w.Header().Set("Retry-After", strconv.Itoa(secs))
	}
	if e.Status == http.StatusUnauthorized {
		w.Header().Set("WWW-Authenticate", `Bearer realm="nekopost"`)
	}
	body := map[string]any{
		"code":       e.Code,
		"message":    e.Message,
		"request_id": info(r).ID,
	}
	if len(e.Details) > 0 {
		body["details"] = e.Details
	}
	// "message" is duplicated at the top level so simple clients (e.g. iOS
	// Shortcuts) can always display the same field.
	prefix := "请求失败："
	if r.Method == http.MethodPost && r.URL.Path == "/v1/messages" {
		prefix = "提交失败："
	}
	summary := prefix + e.Message
	if len(e.Details) > 0 {
		summary += "（" + e.Details[0].Field + " " + e.Details[0].Problem + "）"
	}
	writeJSON(w, e.Status, map[string]any{"ok": false, "message": summary, "error": body})
}
