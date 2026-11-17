package bot

import (
	"context"
	"encoding/json"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"nekopost/internal/store"
	"nekopost/internal/telegram"
	"nekopost/internal/testutil/faketg"
)

// clock is a settable time source for the bot.
type clock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *clock) now() time.Time { c.mu.Lock(); defer c.mu.Unlock(); return c.t }
func (c *clock) add(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

func pairSetup(t *testing.T) (*env, *clock) {
	t.Helper()
	e := setup(t)
	c := &clock{t: time.Now()}
	e.b.Now = c.now
	e.b.PublicURL = "https://gw.example"
	e.b.PairPoll = 5 * time.Millisecond
	ctx, cancel := context.WithCancel(bg)
	t.Cleanup(cancel)
	e.b.runCtx = ctx
	return e, c
}

// markupJSON renders reply_markup the same way whether it came as JSON or as a multipart string.
func markupJSON(t *testing.T, v any) string {
	t.Helper()
	if s, ok := v.(string); ok {
		return s
	}
	raw, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return string(raw)
}

func wire(c *clock) string { return strconv.FormatInt(c.now().Unix(), 10) }

func lastToast(t *testing.T, e *env) string {
	t.Helper()
	calls := e.tg.Calls("answerCallbackQuery")
	if len(calls) == 0 {
		t.Fatal("no callback answer")
	}
	text, _ := calls[len(calls)-1].Params["text"].(string)
	return text
}

func botPairings(t *testing.T, e *env) []store.Pairing {
	t.Helper()
	entries, _, err := e.st.ReadAudit(bg)
	if err != nil {
		t.Fatal(err)
	}
	var out []store.Pairing
	for _, a := range entries {
		if a.Action == "pairing.start" && a.Actor == "bot:tg:1001" {
			p, err := e.st.Pairing(bg, a.Target)
			if err != nil {
				t.Fatal(err)
			}
			out = append(out, p)
		}
	}
	return out
}

func TestAddDeviceMenu(t *testing.T) {
	e, _ := pairSetup(t)
	e.b.handle(bg, cb(admin, "dv"))
	text, kb := lastEdit(t, e)
	if !strings.Contains(kb, "dvn") || !strings.Contains(text, "配对设备") {
		t.Fatal(text, kb)
	}
	e.b.handle(bg, cb(admin, "dvn"))
	text, kb = lastEdit(t, e)
	for _, want := range []string{"dvr:full:", "dvr:receiver:", "dvr:sender:"} {
		if !strings.Contains(kb, want) {
			t.Fatal(kb)
		}
	}
	if !strings.Contains(text, "添加设备") {
		t.Fatal(text)
	}
	e.b.handle(bg, msg(admin, "private", "/adddevice"))
	sent := e.tg.Calls("sendMessage")
	if len(sent) == 0 || !strings.Contains(sent[len(sent)-1].Params["text"].(string), "添加设备") {
		t.Fatal(sent)
	}
}

func TestAddDeviceReceiver(t *testing.T) {
	e, c := pairSetup(t)
	e.b.handle(bg, cb(admin, "dvr:receiver:"+wire(c)))
	if toast := lastToast(t, e); !strings.Contains(toast, "配对码已发送") {
		t.Fatal(toast)
	}
	ps := botPairings(t, e)
	if len(ps) != 1 || ps[0].Scopes != store.ScopeRead || ps[0].SourceID != "" || ps[0].State != "open" ||
		ps[0].Expires.Sub(c.now()).Round(time.Second) != 5*time.Minute {
		t.Fatal(ps)
	}
	photos := e.tg.Calls("sendPhoto")
	if len(photos) != 1 {
		t.Fatal(photos)
	}
	// The QR photo is protected and carries neither the code nor the link.
	ph := photos[0].Params
	png, _ := ph["photo"].([]byte)
	photoCaption, _ := ph["caption"].(string)
	if ph["chat_id"] != float64(admin) || ph["parse_mode"] != "HTML" || ph["protect_content"] != "true" ||
		len(png) < 100 || string(png[1:4]) != "PNG" {
		t.Fatal(ph["chat_id"], ph["parse_mode"], ph["protect_content"], len(png))
	}
	if strings.Contains(photoCaption, "nekopost://") || strings.Contains(photoCaption, "配对码：") || !strings.Contains(photoCaption, "仅接收") {
		t.Fatal(photoCaption)
	}
	// The code and link come as copyable text: not protected (clients refuse to copy protected text).
	texts := e.tg.Calls("sendMessage")
	if len(texts) != 1 {
		t.Fatal(texts)
	}
	p := texts[0].Params
	caption, _ := p["text"].(string)
	if p["chat_id"] != float64(admin) || p["parse_mode"] != "HTML" || p["protect_content"] != nil {
		t.Fatal(p["chat_id"], p["parse_mode"], p["protect_content"])
	}
	for _, want := range []string{"<code>https://gw.example</code>", "<code>nekopost://enroll?v=1&amp;kind=device&amp;url=",
		"仅接收", "粘贴配对链接"} {
		if !strings.Contains(caption, want) {
			t.Fatalf("caption lacks %q:\n%s", want, caption)
		}
	}
	// Every link and code sits inside <code>, so Telegram makes no URL entity.
	outside := caption
	for {
		a := strings.Index(outside, "<code>")
		if a < 0 {
			break
		}
		b := strings.Index(outside[a:], "</code>")
		outside = outside[:a] + outside[a+b+len("</code>"):]
	}
	if strings.Contains(outside, "://") || strings.Contains(outside, "gw.example") {
		t.Fatal("link outside <code>:", outside)
	}
	if kb := markupJSON(t, p["reply_markup"]); !strings.Contains(kb, `"callback_data":"dvx:`+ps[0].ID+`"`) || strings.Contains(kb, `"url"`) {
		t.Fatal(p["reply_markup"])
	}
}

func TestAddDeviceWithSource(t *testing.T) {
	e, c := pairSetup(t)
	e.b.handle(bg, cb(admin, "dvr:full:"+wire(c)))
	_, kb := lastEdit(t, e)
	if !strings.Contains(kb, "dvs:full:iphone:") || !strings.Contains(kb, "dvs:full:+:") {
		t.Fatal(kb)
	}
	if len(botPairings(t, e)) != 0 {
		t.Fatal("choosing a role alone must not create a pairing")
	}
	e.b.handle(bg, cb(admin, "dvs:full:iphone:"+wire(c)))
	e.b.handle(bg, cb(admin, "dvs:sender:+:"+wire(c)))
	ps := botPairings(t, e)
	if len(ps) != 2 || ps[0].SourceID != "iphone" || !strings.Contains(ps[0].Scopes, store.ScopeAdminWrite) ||
		!strings.HasPrefix(ps[1].SourceID, "device-") || ps[1].Scopes != store.ScopeSend {
		t.Fatal(ps)
	}
	e.b.handle(bg, cb(admin, "dvs:sender:nosuch:"+wire(c)))
	if toast := lastToast(t, e); toast != "来源不存在" || len(botPairings(t, e)) != 2 {
		t.Fatal(toast)
	}
}

func TestAddDeviceRefusals(t *testing.T) {
	e, c := pairSetup(t)
	// Strangers never reach the flow.
	e.b.handle(bg, cb(stranger, "dvr:receiver:"+wire(c)))
	if len(botPairings(t, e)) != 0 || len(e.tg.Calls("sendPhoto")) != 0 {
		t.Fatal("non-admin created a pairing")
	}
	// A menu older than 5 minutes cannot create anything.
	old := strconv.FormatInt(c.now().Add(-6*time.Minute).Unix(), 10)
	e.b.handle(bg, cb(admin, "dvr:receiver:"+old))
	if toast := lastToast(t, e); !strings.Contains(toast, "过期") || len(botPairings(t, e)) != 0 {
		t.Fatal(toast)
	}
	e.b.handle(bg, cb(admin, "dvr:root:"+wire(c)))
	if len(botPairings(t, e)) != 0 {
		t.Fatal("unknown role")
	}
	// Without a public URL there is nothing to pair with.
	e.b.PublicURL = ""
	e.b.handle(bg, cb(admin, "dvr:receiver:"+wire(c)))
	if toast := lastToast(t, e); !strings.Contains(toast, "对外地址") || len(botPairings(t, e)) != 0 {
		t.Fatal(toast)
	}
	e.b.PublicURL = "https://gw.example"
	// Rate limit: three per ten minutes.
	for range 4 {
		e.b.handle(bg, cb(admin, "dvr:receiver:"+wire(c)))
	}
	if toast := lastToast(t, e); !strings.Contains(toast, "太频繁") || len(botPairings(t, e)) != 3 {
		t.Fatal(toast, len(botPairings(t, e)))
	}
}

func TestAddDeviceLockdown(t *testing.T) {
	e, c := pairSetup(t)
	if err := e.st.Lockdown(bg, "cli", c.now(), false, false, ""); err != nil {
		t.Fatal(err)
	}
	e.b.handle(bg, cb(admin, "dvr:receiver:"+wire(c)))
	if toast := lastToast(t, e); !strings.Contains(toast, "紧急锁定") || len(e.tg.Calls("sendPhoto")) != 0 {
		t.Fatal(toast)
	}
}

func TestPairingMessageRemovedOnExpiry(t *testing.T) {
	e, c := pairSetup(t)
	e.b.handle(bg, cb(admin, "dvr:receiver:"+wire(c)))
	photoID := e.tg.Calls("sendPhoto")
	if len(photoID) != 1 {
		t.Fatal(photoID)
	}
	c.add(6 * time.Minute)
	// Both the photo and the code text are removed.
	del := e.tg.WaitCalls(t, "deleteMessage", 2, 5*time.Second)
	if del[0].Params["chat_id"] != float64(admin) || del[0].Params["message_id"] == del[1].Params["message_id"] {
		t.Fatal(del[0].Params, del[1].Params)
	}
	sent := e.tg.WaitCalls(t, "sendMessage", 2, 5*time.Second)
	if !strings.Contains(sent[len(sent)-1].Params["text"].(string), "已过期") {
		t.Fatal(sent[len(sent)-1].Params)
	}
}

func TestPairingMessageRemovedWhenClaimed(t *testing.T) {
	e, c := pairSetup(t)
	e.b.handle(bg, cb(admin, "dvr:receiver:"+wire(c)))
	ps := botPairings(t, e)
	if len(ps) != 1 {
		t.Fatal(ps)
	}
	// Simulate the claim: the state leaves "open".
	if err := e.st.CancelPairing(bg, ps[0].ID, "test", c.now()); err != nil {
		t.Fatal(err)
	}
	e.tg.WaitCalls(t, "deleteMessage", 2, 5*time.Second)
}

func TestCancelPairingButton(t *testing.T) {
	e, c := pairSetup(t)
	e.b.PairPoll = time.Hour // only the button acts here
	e.b.handle(bg, cb(admin, "dvr:receiver:"+wire(c)))
	id := botPairings(t, e)[0].ID
	e.b.handle(bg, telegram.Update{CallbackQuery: &telegram.CallbackQuery{ID: "cq", From: telegram.User{ID: admin}, Data: "dvx:" + id,
		Message: &telegram.Message{MessageID: 501, Chat: telegram.Chat{ID: admin, Type: "private"}}}})
	if toast := lastToast(t, e); toast != "已作废配对码" {
		t.Fatal(toast)
	}
	if p, _ := e.st.Pairing(bg, id); p.State != "cancelled" {
		t.Fatal(p.State)
	}
	if del := e.tg.Calls("deleteMessage"); len(del) != 1 || del[0].Params["message_id"] != float64(501) {
		t.Fatal(del)
	}
	e.b.handle(bg, cb(stranger, "dvx:"+id))
	e.b.handle(bg, cb(admin, "dvx:"+id))
	if toast := lastToast(t, e); !strings.Contains(toast, "已被使用或已过期") {
		t.Fatal(toast)
	}
}

func TestSendPhotoFallsBackToText(t *testing.T) {
	e, c := pairSetup(t)
	e.tg.Script("sendPhoto", faketg.Response{Status: 400, Description: "Bad Request: IMAGE_PROCESS_FAILED"})
	e.b.handle(bg, cb(admin, "dvr:receiver:"+wire(c)))
	sent := e.tg.Calls("sendMessage")
	if len(sent) == 0 || !strings.Contains(sent[len(sent)-1].Params["text"].(string), "nekopost://enroll") ||
		!strings.Contains(markupJSON(t, sent[len(sent)-1].Params["reply_markup"]), "dvx:") {
		t.Fatal(sent)
	}
	if ps := botPairings(t, e); len(ps) != 1 || ps[0].State != "open" {
		t.Fatal(ps)
	}
}
