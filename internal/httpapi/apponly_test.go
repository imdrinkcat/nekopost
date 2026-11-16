package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"strings"
	"sync"
	"testing"
	"time"

	jose "github.com/go-jose/go-jose/v4"

	"nekopost/internal/delivery"
	"nekopost/internal/enroll"
	"nekopost/internal/router"
	"nekopost/internal/store"
)

// okFCM is an App push channel that accepts every push.
type okFCM struct{}

func (okFCM) Name() string { return "fcm" }
func (okFCM) Deliver(_ context.Context, _ store.Job, progress func(int, int, int64)) delivery.Result {
	progress(1, 1, 0)
	return delivery.Result{Outcome: delivery.Delivered, PartsTotal: 1, PartsSent: 1}
}

// appOnlySetup is a server where Telegram receives no message type.
func appOnlySetup(t *testing.T, opts ...func(*env)) *env {
	t.Helper()
	so := store.TestingOptions()
	so.EnablePush = true
	opts = append([]func(*env){func(e *env) {
		e.api.Router = router.Default{Channel: "telegram", Target: "default", Types: map[string]bool{}}
		e.api.TelegramTypes = map[string]bool{}
	}}, opts...)
	return setupWith(t, preSetup{storeOpts: &so, channels: map[string]delivery.Channel{"fcm": okFCM{}}}, opts...)
}

func TestAppOnlyDelivery(t *testing.T) {
	e := appOnlySetup(t)
	if err := e.st.CreateTestDevice(bg, "d_fixture", "msg:read", "{}", "fixture_registration", time.Now()); err != nil {
		t.Fatal(err)
	}
	r := e.post(t, e.iphone, `{"title":"Bank","body":"app only","type":"notification"}`)
	if r.code != 202 || r.body["status"] != "delivered" || r.body["delivered"] != true ||
		r.body["message"] != "已推送到 App" || r.body["status_text"] != "已推送到 App" {
		t.Fatal(r.code, r.body)
	}
	if len(e.tg.Calls("sendMessage")) != 0 {
		t.Fatal("Telegram must not receive app-only messages")
	}
	g := e.do(t, "GET", "/v1/messages/"+r.body["id"].(string), e.iphone, "", "")
	ds, _ := g.body["deliveries"].([]any)
	if g.code != 200 || len(ds) != 1 || ds[0].(map[string]any)["channel"] != "fcm" || g.body["app_only"] != true ||
		g.body["status_text"] != "已推送到 App" {
		t.Fatal(g.code, g.body)
	}
}

func TestAppOnlyWithoutDevice(t *testing.T) {
	e := appOnlySetup(t)
	r := e.post(t, e.iphone, `{"body":"nobody","type":"notification"}`)
	if r.code != 202 || r.body["status"] != "not_routed" || r.body["delivered"] != false ||
		!strings.Contains(r.body["message"].(string), "没有可推送的 App 设备") {
		t.Fatal(r.code, r.body)
	}
}

func TestTelegramTextPinnedForShortcuts(t *testing.T) {
	e := setup(t)
	r := e.post(t, e.iphone, `{"body":"hello","type":"text"}`)
	if r.code != 202 || r.body["message"] != "已送达 Telegram" || r.body["status_text"] != "已送达 Telegram" {
		t.Fatal(r.code, r.body)
	}
	g := e.do(t, "GET", "/v1/messages/"+r.body["id"].(string), e.iphone, "", "")
	if g.body["status_text"] != "已送达 Telegram" || g.body["app_only"] != false {
		t.Fatal(g.body)
	}
}

// pairTestDevice pairs a device with the given scopes and returns a DPoP
// request helper plus the device ID.
func pairTestDevice(t *testing.T, e *env, scopes string) (func(method, path, payload string) resp, string) {
	t.Helper()
	key, reauth := testKey(t), testKey(t)
	code, short := enroll.NewPairingCodes()
	p := store.Pairing{ID: "p_" + rand.Text(), Name: "Fold8", Scopes: scopes, Expires: time.Now().Add(5 * time.Minute)}
	if strings.Contains(scopes, "msg:send") {
		p.SourceID = "fold8"
	}
	if err := e.st.NewPairing(bg, p, store.PairingCodeHash(code), store.PairingCodeHash(short), "cli", time.Now()); err != nil {
		t.Fatal(err)
	}
	claim, _ := json.Marshal(map[string]any{"code": code, "jwk": jose.JSONWebKey{Key: &key.PublicKey},
		"reauth_jwk": jose.JSONWebKey{Key: &reauth.PublicKey}, "device": map[string]string{"name": "Fold8"}})
	first := e.do(t, "POST", "/v1/pairing/claim", "", "application/json", string(claim))
	if first.code != 200 {
		t.Fatal(first.code, first.body)
	}
	if ok, err := e.st.DecidePairing(bg, p.ID, first.body["short_code"].(string), "cli", time.Now()); !ok || err != nil {
		t.Fatal(ok, err)
	}
	tokenPath := "/v1/pairing/" + p.ID + "/token"
	issued := e.do(t, "POST", tokenPath, "", "application/json", "{}", "DPoP",
		testProof(t, key, "POST", e.api.PublicURL+tokenPath, "", "", time.Now()))
	if issued.code != 200 {
		t.Fatal(issued.code, issued.body)
	}
	token := issued.body["token"].(string)
	return func(method, path, payload string) resp {
		base, _, _ := strings.Cut(path, "?")
		proof := testProof(t, key, method, e.api.PublicURL+base, token, "", time.Now())
		return e.do(t, method, path, "", "application/json", payload, "Authorization", "DPoP "+token, "DPoP", proof)
	}, issued.body["device_id"].(string)
}

type signalRecorder struct {
	mu   sync.Mutex
	sent []string
}

func (r *signalRecorder) record(kind string, ids []string, except string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.sent = append(r.sent, kind+":"+strings.Join(ids, ",")+":"+except)
}

func (r *signalRecorder) all() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.sent...)
}

func TestInboxSenderUnreadAndPushFetch(t *testing.T) {
	signals := &signalRecorder{}
	e := deviceSetup(t)
	e.api.PushSignal = signals.record
	call, deviceID := pairTestDevice(t, e, "msg:read")
	sub := e.post(t, e.iphone, `{"title":"95555","body":"您的验证码 482913，5 分钟内有效","type":"sms"}`)
	if sub.code != 202 {
		t.Fatal(sub.code, sub.body)
	}
	id := sub.body["id"].(string)

	list := call("GET", "/v1/inbox", "")
	items, _ := list.body["items"].([]any)
	if list.code != 200 || len(items) != 1 || list.body["unread_count"] != float64(1) {
		t.Fatal(list.code, list.body)
	}
	item := items[0].(map[string]any)
	if item["sender"] != "95555" || item["title"] != "******" || strings.Contains(item["preview"].(string), "482913") ||
		item["app_only"] != false || item["read"] != false || item["body"] != nil {
		t.Fatal(item)
	}
	if r := call("GET", "/v1/inbox?unread=1&has_code=1", ""); len(r.body["items"].([]any)) != 1 {
		t.Fatal(r.body)
	}

	detail := call("GET", "/v1/inbox/"+id+"?via=push&ack=1", "")
	otp, _ := detail.body["otp"].(map[string]any)
	if detail.code != 200 || otp["code"] != "482913" || detail.body["sender"] != "95555" || detail.body["title"] != "95555" {
		t.Fatal(detail.code, detail.body)
	}
	entries, _, err := e.st.ReadAudit(bg)
	if err != nil {
		t.Fatal(err)
	}
	var actions []string
	for _, a := range entries {
		actions = append(actions, a.Action)
	}
	if joined := strings.Join(actions, ","); !strings.Contains(joined, "message.push_fetch") || strings.Contains(joined, "message.view") {
		t.Fatal(joined)
	}

	if r := call("PATCH", "/v1/inbox/"+id, `{"read":true}`); r.code != 204 {
		t.Fatal(r.code, r.body)
	}
	if r := call("PATCH", "/v1/inbox/"+id, `{"read":true}`); r.code != 204 {
		t.Fatal(r.code, r.body)
	}
	if got := signals.all(); len(got) != 1 || got[0] != "read:"+id+":"+deviceID {
		t.Fatal("one read signal to the other devices", got)
	}
	if r := call("GET", "/v1/inbox?unread=1", ""); r.body["unread_count"] != float64(0) || len(r.body["items"].([]any)) != 0 {
		t.Fatal(r.body)
	}

	self := call("GET", "/v1/devices/self", "")
	if self.code != 200 || self.body["id"] != deviceID || self.body["telegram_types"] != nil {
		t.Fatal(self.body)
	}
}

func TestDeviceSelfReportsTelegramTypes(t *testing.T) {
	e := appOnlySetup(t, func(e *env) {
		e.api.DPoP, e.api.PublicURL = deviceSetupDPoP(), "https://fixture.invalid"
		e.api.TelegramTypes = map[string]bool{"sms": true}
	})
	call, _ := pairTestDevice(t, e, "msg:read")
	self := call("GET", "/v1/devices/self", "")
	types, _ := self.body["telegram_types"].([]any)
	if self.code != 200 || len(types) != 1 || types[0] != "sms" {
		t.Fatal(self.body)
	}
}
