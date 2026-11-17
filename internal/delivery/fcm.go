package delivery

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/jwt"

	"nekopost/internal/health"
	"nekopost/internal/model"
	"nekopost/internal/store"
)

var firebaseProject = regexp.MustCompile(`^[a-z][a-z0-9-]{4,62}$`)

type FCM struct {
	Store    *store.Store
	endpoint string
	tokens   oauth2.TokenSource
	http     *http.Client
	Now      func() time.Time
}

func NewFCM(ctx context.Context, st *store.Store, path, override string) (*FCM, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, errors.New("无法打开 FCM 服务账号凭证")
	}
	defer file.Close()
	raw, err := io.ReadAll(io.LimitReader(file, 64<<10))
	if err != nil {
		return nil, errors.New("无法读取 FCM 服务账号凭证")
	}
	var account struct {
		Type         string `json:"type"`
		Email        string `json:"client_email"`
		PrivateKey   string `json:"private_key"`
		PrivateKeyID string `json:"private_key_id"`
		Project      string `json:"project_id"`
	}
	if json.Unmarshal(raw, &account) != nil || account.Type != "service_account" || account.Email == "" || account.PrivateKey == "" {
		return nil, errors.New("FCM 服务账号凭证格式无效")
	}
	project := account.Project
	if override != "" {
		project = override
	}
	if !firebaseProject.MatchString(project) {
		return nil, errors.New("FCM 项目 ID 无效")
	}
	client := &http.Client{Timeout: 10 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	conf := jwt.Config{Email: account.Email, PrivateKey: []byte(account.PrivateKey), PrivateKeyID: account.PrivateKeyID,
		Scopes: []string{"https://www.googleapis.com/auth/firebase.messaging"}, TokenURL: "https://oauth2.googleapis.com/token"}
	return &FCM{Store: st, endpoint: "https://fcm.googleapis.com/v1/projects/" + project + "/messages:send",
		tokens: conf.TokenSource(context.WithValue(ctx, oauth2.HTTPClient, client)), http: client, Now: time.Now}, nil
}

func (f *FCM) Name() string { return "fcm" }

func (f *FCM) Deliver(ctx context.Context, job store.Job, progress func(int, int, int64)) Result {
	registration, skip, err := f.Store.PushRegistration(ctx, job.Delivery.Target, job.Message, f.Now())
	if err != nil {
		return Result{Outcome: RetrySafe, FailKind: health.FailOther, Err: "无法读取推送设置"}
	}
	if skip != "" {
		return Result{Outcome: Skipped, Err: skip}
	}
	priority := "HIGH"
	if job.Message.Priority == model.PriorityLow {
		priority = "NORMAL"
	}
	result, invalid := f.send(ctx, registration, map[string]string{"v": "1", "t": "msg", "id": job.Message.ID, "seq": strconv.FormatInt(job.Seq, 10)}, priority, "86400s")
	if invalid {
		if err := f.Store.RejectPushRegistration(ctx, job.Delivery.Target, registration); err != nil {
			return Result{Outcome: RetrySafe, FailKind: health.FailOther, Err: "无法标记失效推送凭证"}
		}
	}
	if result.Outcome == Delivered {
		progress(1, 1, 0)
	}
	return result
}

func (f *FCM) send(ctx context.Context, registration string, data map[string]string, priority, ttl string) (Result, bool) {
	token, err := f.tokens.Token()
	if err != nil {
		return Result{Outcome: RetrySafe, FailKind: health.FailConfig, Err: "FCM 服务账号认证失败"}, false
	}
	raw, _ := json.Marshal(map[string]any{"message": map[string]any{"token": registration, "data": data,
		"android": map[string]string{"priority": priority, "ttl": ttl}}})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, f.endpoint, bytes.NewReader(raw))
	if err != nil {
		return Result{Outcome: Permanent, FailKind: health.FailConfig, Err: "FCM 请求地址无效"}, false
	}
	req.Header.Set("Content-Type", "application/json")
	token.SetAuthHeader(req)
	r, err := f.http.Do(req)
	if err != nil {
		return Result{Outcome: RetrySafe, FailKind: health.FailNetwork, Err: "FCM 网络请求失败"}, false
	}
	defer r.Body.Close()
	if r.StatusCode == 200 {
		_, _ = io.Copy(io.Discard, io.LimitReader(r.Body, 4096))
		return Result{Outcome: Delivered, PartsSent: 1, PartsTotal: 1}, false
	}
	var response struct {
		Error struct {
			Status  string `json:"status"`
			Details []struct {
				Type string `json:"@type"`
				Code string `json:"errorCode"`
			} `json:"details"`
		} `json:"error"`
	}
	_ = json.NewDecoder(io.LimitReader(r.Body, 8192)).Decode(&response)
	for _, d := range response.Error.Details {
		if d.Type != "type.googleapis.com/google.firebase.fcm.v1.FcmError" {
			continue
		}
		if (r.StatusCode == 404 && d.Code == "UNREGISTERED") || (r.StatusCode == 400 && d.Code == "INVALID_ARGUMENT") {
			return Result{Outcome: Permanent, Err: "FCM 注册凭证已失效"}, true
		}
		if d.Code == "SENDER_ID_MISMATCH" {
			return Result{Outcome: Permanent, FailKind: health.FailConfig, Err: "FCM 项目与设备不匹配"}, false
		}
	}
	switch r.StatusCode {
	case 401, 403:
		reason := "FCM 请求未获授权"
		if response.Error.Status == "PERMISSION_DENIED" {
			reason = "FCM 拒绝访问：服务账号缺少权限、属于其他项目，或项目未启用 FCM API"
		}
		return Result{Outcome: RetrySafe, FailKind: health.FailConfig, Err: reason}, false
	case 429, 503:
		var retry time.Duration
		if secs, err := strconv.Atoi(r.Header.Get("Retry-After")); err == nil && secs >= 0 {
			retry = time.Duration(min(secs, 86400)) * time.Second
		} else if at, err := http.ParseTime(r.Header.Get("Retry-After")); err == nil {
			retry = max(0, at.Sub(f.Now()))
		}
		return Result{Outcome: RetrySafe, RetryAfter: retry, Err: "FCM 暂时不可用或限流"}, false
	default:
		if r.StatusCode >= 500 {
			return Result{Outcome: RetrySafe, Err: "FCM 服务端错误"}, false
		}
		return Result{Outcome: Permanent, FailKind: health.FailConfig, Err: "FCM 拒绝了请求"}, false
	}
}

// Signal kinds sent to devices besides new-message pushes.
const (
	SignalRead = "read" // read on another device or in Telegram
	SignalGone = "gone" // deleted, or its body was destroyed
)

// Signal tells every App device except one that messages changed elsewhere,
// so it can clear their notifications. Data-only, IDs only, normal priority.
// It is best effort: nothing is stored or retried, and the App also
// reconciles its notifications when it syncs.
func (f *FCM) Signal(ctx context.Context, kind string, ids []string, except string) {
	if len(ids) == 0 {
		return
	}
	devices, err := f.Store.PushDevices(ctx, except, f.Now())
	if err != nil {
		return
	}
	for start := 0; start < len(ids); start += 50 {
		chunk := ids[start:min(start+50, len(ids))]
		data := map[string]string{"v": "1", "t": kind, "ids": strings.Join(chunk, ",")}
		for _, d := range devices {
			if _, invalid := f.send(ctx, d.Token, data, "NORMAL", "3600s"); invalid {
				_ = f.Store.RejectPushRegistration(ctx, d.ID, d.Token)
			}
		}
	}
}

func (f *FCM) TestPush(ctx context.Context, deviceID string) error {
	token, err := f.Store.PushTokenForTest(ctx, deviceID, f.Now())
	if err != nil {
		return err
	}
	result, invalid := f.send(ctx, token, map[string]string{"v": "1", "t": "ping"}, "HIGH", "300s")
	if invalid {
		if err := f.Store.RejectPushRegistration(ctx, deviceID, token); err != nil {
			return err
		}
	}
	if result.Outcome != Delivered {
		return &PushError{Reason: result.Err}
	}
	return nil
}

// PushError is a test push FCM did not accept. Reason is one of the fixed
// Chinese texts from send, never anything echoed from the response.
type PushError struct{ Reason string }

func (e *PushError) Error() string { return "FCM 未接受测试消息：" + e.Reason }
