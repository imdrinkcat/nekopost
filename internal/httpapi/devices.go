package httpapi

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"regexp"
	"slices"
	"strings"
	"time"
	"unicode"

	"nekopost/internal/auth"
	"nekopost/internal/enroll"
	"nekopost/internal/model"
	"nekopost/internal/ratelimit"
	"nekopost/internal/store"
)

var pushToken = regexp.MustCompile(`^[A-Za-z0-9_:\-]+$`)

var pairingID = regexp.MustCompile(`^p_[A-Z2-7]{26}$`)

func (s *Server) registerDevices(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/pairing/claim", s.handlePairClaim)
	mux.HandleFunc("POST /v1/pairing/{id}/token", s.handlePairToken)
	mux.HandleFunc("GET /v1/devices/self", s.device("", true, s.deviceSelf))
	mux.HandleFunc("PATCH /v1/devices/self", s.device(store.ScopeRead, false, s.devicePrefs))
	mux.HandleFunc("PUT /v1/devices/self/push", s.device(store.ScopeRead, false, s.devicePush))
	mux.HandleFunc("DELETE /v1/devices/self", s.device("", true, s.deviceUnpair))
	mux.HandleFunc("POST /v1/devices/self/reauth/challenge", s.device(store.ScopeAdminWrite, false, s.deviceChallenge))
	mux.HandleFunc("POST /v1/devices/self/reauth", s.device(store.ScopeAdminWrite, false, s.deviceReauth))
	mux.HandleFunc("GET /v1/inbox", s.device(store.ScopeRead, false, s.inboxList))
	mux.HandleFunc("POST /v1/inbox/search", s.device(store.ScopeRead, false, s.inboxSearch))
	mux.HandleFunc("GET /v1/inbox/{id}", s.device(store.ScopeRead, false, s.inboxGet))
	mux.HandleFunc("PATCH /v1/inbox/{id}", s.device(store.ScopeRead, false, s.inboxRead))
	mux.HandleFunc("GET /v1/events", s.device(store.ScopeRead, false, s.inboxEvents))
	mux.HandleFunc("GET /v1/sync", s.device(store.ScopeRead, false, s.inboxSync))
	mux.HandleFunc("POST /v1/devices/self/acks", s.device(store.ScopeRead, true, s.deviceAcks))
}

func readJSON(w http.ResponseWriter, r *http.Request, dst any) error {
	r.Body = http.MaxBytesReader(w, r.Body, 8192)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		return err
	}
	var extra any
	if err := dec.Decode(&extra); err != io.EOF {
		return errors.New("invalid trailing data")
	}
	return nil
}

func deviceError(w http.ResponseWriter, r *http.Request, status int, code, msg string) {
	writeError(w, r, &apiError{Status: status, Code: code, Message: msg})
}

func (s *Server) checkedProof(w http.ResponseWriter, r *http.Request, thumb, token string) bool {
	if s.PublicURL == "" || s.DPoP == nil {
		writeError(w, r, errUnauthorized)
		return false
	}
	path := r.URL.EscapedPath()
	lower := strings.ToLower(path)
	if strings.Contains(lower, "%2f") || strings.Contains(lower, "%2e") || strings.Contains(lower, "%5c") ||
		strings.Contains(path, "//") || strings.Contains(path, "..") || len(r.Header.Values("DPoP")) != 1 {
		writeError(w, r, errUnauthorized)
		return false
	}
	err := s.DPoP.Verify(r.Header.Get("DPoP"), thumb, r.Method, s.PublicURL+path, token, s.now())
	if errors.Is(err, auth.ErrDPoPTime) {
		w.Header().Set("DPoP-Nonce", s.DPoP.Nonce(thumb, s.now()))
		w.Header().Set("WWW-Authenticate", `DPoP error="use_dpop_nonce"`)
		deviceError(w, r, 401, "use_dpop_nonce", "请同步时间并重新签名")
		return false
	}
	if err != nil {
		writeError(w, r, errUnauthorized)
		return false
	}
	return true
}

func (s *Server) authorizeDevice(w http.ResponseWriter, r *http.Request, scope string, suspendedOK bool) (store.Device, bool) {
	tok, ok := auth.DeviceAuthorization(r.Header.Get("Authorization"))
	if !ok || len(r.Header.Values("Authorization")) != 1 {
		writeError(w, r, errUnauthorized)
		return store.Device{}, false
	}
	d, err := s.Store.DeviceByToken(r.Context(), auth.Hash(tok))
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, r, errUnauthorized)
		return d, false
	}
	if err != nil {
		writeError(w, r, errInternal)
		return d, false
	}
	if !s.checkedProof(w, r, d.JKT, tok) {
		return d, false
	}
	if d.Revoked {
		deviceError(w, r, 401, "device_revoked", "此设备已吊销")
		return d, false
	}
	if d.Suspended && !suspendedOK {
		deviceError(w, r, 403, "locked_down", "设备读取已暂停")
		return d, false
	}
	if scope != "" && !d.Has(scope) {
		deviceError(w, r, 403, "insufficient_scope", "当前设备没有该权限")
		return d, false
	}
	return d, true
}

func (s *Server) device(scope string, suspendedOK bool, next func(http.ResponseWriter, *http.Request, store.Device)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		d, ok := s.authorizeDevice(w, r, scope, suspendedOK)
		if ok {
			next(w, r, d)
		}
	}
}

func deviceText(raw string) string {
	raw = strings.TrimSpace(strings.Map(func(r rune) rune {
		if unicode.IsControl(r) || unicode.Is(unicode.Cf, r) {
			return -1
		}
		return r
	}, raw))
	rs := []rune(raw)
	return string(rs[:min(len(rs), 64)])
}

func pairDigits() (string, []string) {
	choices := make([]string, 0, 3)
	for len(choices) < 3 {
		number, err := rand.Int(rand.Reader, big.NewInt(100))
		if err != nil {
			panic(err)
		}
		digits := fmt.Sprintf("%02d", number.Int64())
		if !slices.Contains(choices, digits) {
			choices = append(choices, digits)
		}
	}
	code := choices[0]
	slices.Sort(choices)
	return code, choices
}

func (s *Server) handlePairClaim(w http.ResponseWriter, r *http.Request) {
	if s.PublicURL == "" {
		writeError(w, r, errNotFound)
		return
	}
	var body struct {
		Code   string          `json:"code"`
		JWK    json.RawMessage `json:"jwk"`
		Reauth json.RawMessage `json:"reauth_jwk"`
		Device struct {
			Name    string `json:"name"`
			Model   string `json:"model"`
			Version string `json:"app_version"`
		} `json:"device"`
	}
	if readJSON(w, r, &body) != nil {
		deviceError(w, r, 400, "invalid_body", "请求格式无效")
		return
	}
	key, thumb, err := auth.DeviceJWK(body.JWK)
	reauth, thumb2, reErr := auth.DeviceJWK(body.Reauth)
	code := enroll.NormalizePairingCode(body.Code)
	if err != nil || reErr != nil || thumb == thumb2 {
		deviceError(w, r, 400, "invalid_key", "需要两把不同的 P-256 公钥")
		return
	}
	digits, choices := pairDigits()
	p, err := s.Store.ClaimPairing(r.Context(), store.PairingCodeHash(code), store.PairClaim{
		JWK: key, JKT: thumb, ReauthJWK: reauth, Name: deviceText(body.Device.Name), Model: deviceText(body.Device.Model),
		AppVersion: deviceText(body.Device.Version), Code: digits, Choices: choices,
	}, s.now())
	switch {
	case errors.Is(err, store.ErrNotFound), errors.Is(err, store.ErrPairingGone):
		scope := ratelimit.ScopeKey(s.clientIP(r), "pairing")
		if blocked, wait := s.FailGuard.Blocked(scope); blocked {
			writeError(w, r, &apiError{Status: 429, Code: "rate_limited", Message: "请稍后重试配对", RetryAfter: wait})
			return
		}
		s.FailGuard.Fail(scope)
		deviceError(w, r, 404, "pairing_not_found", "配对码无效或已过期")
	case errors.Is(err, store.ErrPairingContested):
		deviceError(w, r, 409, "pairing_contested", "配对码发生争用，请重新生成")
	case err != nil:
		writeError(w, r, errInternal)
	default:
		if s.PairNotify != nil && p.Code == digits && p.State == "claimed" {
			if locked, e := s.Store.LockdownActive(r.Context()); e == nil && !locked {
				s.PairNotify(r.Context(), p)
			}
		}
		writeJSON(w, 200, map[string]string{"pairing_id": p.ID, "short_code": p.Code})
	}
}

func (s *Server) handlePairToken(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !pairingID.MatchString(id) {
		writeError(w, r, errNotFound)
		return
	}
	p, err := s.Store.Pairing(r.Context(), id)
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, r, errNotFound)
		return
	}
	if err != nil {
		writeError(w, r, errInternal)
		return
	}
	if !s.checkedProof(w, r, p.JKT, "") {
		return
	}
	token := auth.NewToken(auth.DevicePrefix)
	d, err := s.Store.ConsumePairing(r.Context(), id, p.JKT, "d_"+rand.Text(), auth.Hash(token), s.now())
	switch {
	case errors.Is(err, store.ErrPairingPending):
		w.Header().Set("Retry-After", "2")
		writeJSON(w, 202, map[string]string{"state": "pending"})
	case errors.Is(err, store.ErrPairingContested):
		deviceError(w, r, 409, "pairing_contested", "配对码发生争用，请重新生成")
	case errors.Is(err, store.ErrPairingGone):
		deviceError(w, r, 410, "pairing_gone", "配对已结束")
	case err != nil:
		writeError(w, r, errInternal)
	default:
		writeJSON(w, 200, map[string]any{"device_id": d.ID, "token": token, "source_id": d.SourceID, "server": s.PublicURL, "scopes": strings.Split(d.Scopes, ",")})
	}
}

func (s *Server) deviceSelf(w http.ResponseWriter, r *http.Request, d store.Device) {
	writeJSON(w, 200, d)
}

func (s *Server) devicePush(w http.ResponseWriter, r *http.Request, d store.Device) {
	var body struct {
		Token string `json:"token"`
	}
	if readJSON(w, r, &body) != nil || len(body.Token) < 20 || len(body.Token) > 4096 || !pushToken.MatchString(body.Token) {
		deviceError(w, r, 400, "invalid_push_token", "推送凭证格式无效")
		return
	}
	if err := s.Store.UpdateDevicePush(r.Context(), d.ID, body.Token, s.now()); err != nil {
		writeError(w, r, errInternal)
		return
	}
	w.WriteHeader(204)
}

func (s *Server) deviceUnpair(w http.ResponseWriter, r *http.Request, d store.Device) {
	if err := s.Store.RevokeDevice(r.Context(), d.ID, "device:"+d.ID, s.now()); err != nil {
		writeError(w, r, errInternal)
		return
	}
	w.WriteHeader(204)
}

func (s *Server) deviceChallenge(w http.ResponseWriter, r *http.Request, d store.Device) {
	challenge := auth.NewToken("")
	if err := s.Store.DeviceChallenge(r.Context(), d.ID, auth.Hash(challenge), s.now().Add(time.Minute)); err != nil {
		writeError(w, r, errInternal)
		return
	}
	writeJSON(w, 200, map[string]string{"challenge": challenge})
}

func (s *Server) deviceReauth(w http.ResponseWriter, r *http.Request, d store.Device) {
	var body struct {
		Challenge string `json:"challenge"`
		Sig       string `json:"sig"`
	}
	if readJSON(w, r, &body) != nil || len(body.Challenge) != 43 || !auth.VerifyDeviceReauth(d.ReauthJWK, d.ID, body.Challenge, body.Sig) {
		writeError(w, r, errUnauthorized)
		return
	}
	err := s.Store.CompleteDeviceReauth(r.Context(), d.ID, auth.Hash(body.Challenge), s.now())
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, r, errUnauthorized)
		return
	}
	if err != nil {
		writeError(w, r, errInternal)
		return
	}
	w.WriteHeader(204)
}

func (s *Server) listDevices(w http.ResponseWriter, r *http.Request, d store.Device) {
	devices, err := s.Store.ListDevices(r.Context())
	if err != nil {
		writeError(w, r, errInternal)
		return
	}
	writeJSON(w, 200, map[string]any{"devices": devices})
}

func (s *Server) devicePrefs(w http.ResponseWriter, r *http.Request, d store.Device) {
	var prefs store.PushPrefs
	if readJSON(w, r, &prefs) != nil || len(prefs.Types) > 32 || len(prefs.Categories) > 32 ||
		(prefs.MinPriority != "" && !model.ValidPriority(prefs.MinPriority)) {
		deviceError(w, r, 400, "invalid_prefs", "推送偏好无效")
		return
	}
	for _, kind := range prefs.Types {
		if !model.ValidKind(kind) {
			deviceError(w, r, 400, "invalid_prefs", "消息类型无效")
			return
		}
	}
	for _, cat := range prefs.Categories {
		if !slices.Contains(model.Categories, model.Category(cat)) {
			deviceError(w, r, 400, "invalid_prefs", "消息类别无效")
			return
		}
	}
	if controlError(w, r, s.Store.UpdatePushPrefs(r.Context(), d.ID, prefs)) {
		return
	}
	w.WriteHeader(204)
}
