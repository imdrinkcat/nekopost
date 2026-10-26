package bot

import (
	"context"
	"io"
	"log/slog"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"nekopost/internal/health"
	"nekopost/internal/model"
	"nekopost/internal/status"
	"nekopost/internal/store"
	"nekopost/internal/sysinfo"
	"nekopost/internal/telegram"
	"nekopost/internal/testutil/faketg"
)

const admin = 1001
const stranger = 2002

type env struct {
	b  *Bot
	tg *faketg.Server
	st *store.Store
}

var bg = context.Background()

func setup(t *testing.T) *env {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	now := time.Now()
	st.CreateSource(bg, model.Source{ID: "iphone", Name: "iPhone", Icon: "📱", Enabled: true}, "h", "p", now)
	tg := faketg.New(t)
	tracker := health.NewTracker(now)
	tracker.WorkerBeat(now)
	tracker.SetListening(true, "")
	tokyo, _ := time.LoadLocation("Asia/Tokyo")
	b := &Bot{
		Client:   telegram.New(tg.URL, faketg.Token),
		IsAdmin:  func(id int64) bool { return id == admin },
		Admins:   []int64{admin},
		Store:    st,
		Health:   tracker,
		Log:      slog.New(slog.NewTextHandler(io.Discard, nil)),
		Location: tokyo,
		Version:  "test",
		Settings: Settings{Domain: "gw.example", TLSMode: "autocert", Timezone: "Asia/Tokyo", ChatID: 123456789, AdminCount: 1,
			RetentionDays: 30, DeliveryMaxAge: 24 * time.Hour, DedupeWindow: time.Minute, RatePerMin: 60, MaxBodyChars: 12000},
		Status: &status.Service{Store: st, Health: tracker, Location: tokyo, StartedAt: now, RetentionDays: 30, TLSMode: "autocert",
			Sys: sysinfo.Collector{DiskPath: t.TempDir(), CPUSample: time.Millisecond}, Version: "test"},
	}
	return &env{b: b, tg: tg, st: st}
}

func msg(from int64, chatType, text string) telegram.Update {
	return telegram.Update{Message: &telegram.Message{MessageID: 1, From: &telegram.User{ID: from}, Chat: telegram.Chat{ID: from, Type: chatType}, Text: text}}
}

func cb(from int64, data string) telegram.Update {
	return telegram.Update{CallbackQuery: &telegram.CallbackQuery{ID: "cq", From: telegram.User{ID: from}, Data: data,
		Message: &telegram.Message{MessageID: 77, Chat: telegram.Chat{ID: from, Type: "private"}}}}
}

func lastEdit(t *testing.T, e *env) (string, string) {
	t.Helper()
	calls := e.tg.Calls("editMessageText")
	if len(calls) == 0 {
		t.Fatal("no edit")
	}
	p := calls[len(calls)-1].Params
	kb, _ := p["reply_markup"].(map[string]any)
	return p["text"].(string), flatten(kb)
}

// flatten returns all callback_data values of a keyboard joined by spaces.
func flatten(kb map[string]any) string {
	var out []string
	if kb == nil {
		return ""
	}
	for _, row := range kb["inline_keyboard"].([]any) {
		for _, b := range row.([]any) {
			out = append(out, b.(map[string]any)["callback_data"].(string))
		}
	}
	return strings.Join(out, " ")
}

func TestUnauthorizedUser(t *testing.T) {
	e := setup(t)
	e.b.handle(bg, msg(stranger, "private", "/start"))
	e.b.handle(bg, msg(stranger, "private", "/status"))
	calls := e.tg.Calls("sendMessage")
	if len(calls) != 1 {
		t.Fatalf("stranger should get exactly one (throttled) reply, got %d", len(calls))
	}
	text := calls[0].Params["text"].(string)
	if !strings.Contains(text, "没有使用权限") || !strings.Contains(text, "2002") || calls[0].Params["reply_markup"] != nil {
		t.Errorf("reply %q", text)
	}
	for _, leak := range []string{"iPhone", "CPU", "内存", "主菜单"} {
		if strings.Contains(text, leak) {
			t.Errorf("unauthorized reply leaks %q", leak)
		}
	}
	e.b.handle(bg, cb(stranger, "sv"))
	if len(e.tg.Calls("editMessageText")) != 0 {
		t.Error("stranger callback must not render views")
	}
	ans := e.tg.Calls("answerCallbackQuery")
	if len(ans) != 1 || ans[0].Params["show_alert"] != true {
		t.Errorf("answer %v", ans)
	}
	if e.b.Health.Snapshot().BotUnauthorized != 3 {
		t.Error("unauthorized attempts should be counted")
	}
}

func TestGroupsIgnored(t *testing.T) {
	e := setup(t)
	e.b.handle(bg, msg(admin, "group", "/status"))
	e.b.handle(bg, msg(admin, "supergroup", "/start"))
	if n := len(e.tg.Calls("")); n != 0 {
		t.Errorf("bot must stay silent in groups, made %d calls", n)
	}
	u := cb(admin, "sv")
	u.CallbackQuery.Message.Chat.Type = "group"
	e.b.handle(bg, u)
	if len(e.tg.Calls("editMessageText")) != 0 {
		t.Error("no views in groups")
	}
}

func TestStartShowsMenu(t *testing.T) {
	e := setup(t)
	e.b.handle(bg, msg(admin, "private", "/start"))
	calls := e.tg.Calls("sendMessage")
	if len(calls) != 1 {
		t.Fatal("no reply")
	}
	p := calls[0].Params
	kb := flatten(p["reply_markup"].(map[string]any))
	for _, want := range []string{"o", "b", "sv", "src", "st:today", "set", "h"} {
		if !strings.Contains(" "+kb+" ", " "+want+" ") {
			t.Errorf("menu lacks %q: %s", want, kb)
		}
	}
	text := p["text"].(string)
	if !strings.Contains(text, "你好") || !strings.Contains(text, "主菜单") || !strings.Contains(text, "数据更新于") {
		t.Errorf("text %q", text)
	}
	e.b.handle(bg, msg(admin, "private", "hello?"))
	if txt := e.tg.Calls("sendMessage")[1].Params["text"].(string); !strings.Contains(txt, "无法识别") {
		t.Errorf("unknown input reply %q", txt)
	}
	e.b.handle(bg, msg(admin, "private", "/server@test_gateway_bot"))
	if txt := e.tg.Calls("sendMessage")[2].Params["text"].(string); !strings.Contains(txt, "服务器状态") {
		t.Errorf("command with @botname: %q", txt)
	}
}

func TestViews(t *testing.T) {
	e := setup(t)
	cases := map[string][]string{
		"o":        {"系统总览", "消息网关服务", "消息接入", "Telegram 转发", "管理 Bot", "消息来源", "服务器资源", "今日"},
		"b":        {"机器人状态", "管理功能", "消息转发", "最近成功发送", "已知问题"},
		"sv":       {"宿主机", "运行时间", "CPU", "内存", "磁盘", "消息网关服务", "版本：test"},
		"src":      {"消息来源", "📱 <b>iPhone</b>", "已启用", "最近消息：从未", "没有心跳"},
		"st:today": {"消息统计 · 今天", "接收：0", "成功送达：0"},
		"st:start": {"本次启动以来"},
		"set":      {"系统设置", "https://gw.example/v1/messages", "Asia/Tokyo", "至少一次", "••••"},
		"h":        {"帮助", "/menu"},
		"s:iphone": {"来源详情", "iPhone", "最近请求"},
	}
	for data, wants := range cases {
		e.b.handle(bg, cb(admin, data))
		text, _ := lastEdit(t, e)
		for _, w := range wants {
			if !strings.Contains(text, w) {
				t.Errorf("view %s lacks %q:\n%s", data, w, text)
			}
		}
	}
	if n := len(e.tg.Calls("answerCallbackQuery")); n != len(cases) {
		t.Errorf("every callback must be answered: %d/%d", n, len(cases))
	}
	// The bot's own status is honest about unverified delivery.
	e.b.handle(bg, cb(admin, "b"))
	if text, _ := lastEdit(t, e); !strings.Contains(text, "尚无投递记录") {
		t.Errorf("unverified delivery must be shown as such:\n%s", text)
	}
	e.b.handle(bg, cb(admin, "bogus:data"))
	ans := e.tg.Calls("answerCallbackQuery")
	if ans[len(ans)-1].Params["text"] != "该按钮已失效" {
		t.Error("unknown callback should be reported")
	}
}

func TestEditFallbackSendsNewMessage(t *testing.T) {
	e := setup(t)
	e.tg.Script("editMessageText", faketg.Response{Status: 400, Description: "Bad Request: message can't be edited"})
	e.b.handle(bg, cb(admin, "o"))
	if len(e.tg.Calls("sendMessage")) != 1 {
		t.Error("should send a fresh message when edit fails")
	}
	e.tg.Script("editMessageText", faketg.Response{Status: 400, Description: "Bad Request: message is not modified"})
	e.b.handle(bg, cb(admin, "o"))
	if len(e.tg.Calls("sendMessage")) != 1 {
		t.Error("'not modified' is not an error")
	}
}

func TestDisableSourceRequiresConfirmation(t *testing.T) {
	e := setup(t)
	e.b.handle(bg, cb(admin, "s:iphone"))
	_, kb := lastEdit(t, e)
	if !strings.Contains(kb, "sa:off:iphone") {
		t.Fatalf("detail should offer disable: %s", kb)
	}
	e.b.handle(bg, cb(admin, "sa:off:iphone"))
	text, kb := lastEdit(t, e)
	if !strings.Contains(text, "确定要<b>停用</b>") {
		t.Fatalf("confirm text %q", text)
	}
	if src, _ := e.st.GetSource(bg, "iphone"); !src.Enabled {
		t.Fatal("asking must not change anything")
	}
	var confirm string
	for _, d := range strings.Fields(kb) {
		if strings.HasPrefix(d, "sc:off:iphone:") {
			confirm = d
		}
	}
	if confirm == "" || len(confirm) > 64 {
		t.Fatalf("bad confirm payload %q", confirm)
	}
	// Expired confirmation is rejected.
	stale := "sc:off:iphone:" + itoa(time.Now().Add(-10*time.Minute).Unix())
	e.b.handle(bg, cb(admin, stale))
	if src, _ := e.st.GetSource(bg, "iphone"); !src.Enabled {
		t.Fatal("expired confirmation must not act")
	}
	// A non-admin replaying the payload is rejected.
	e.b.handle(bg, cb(stranger, confirm))
	if src, _ := e.st.GetSource(bg, "iphone"); !src.Enabled {
		t.Fatal("non-admin must not act")
	}
	e.b.handle(bg, cb(admin, confirm))
	if src, _ := e.st.GetSource(bg, "iphone"); src.Enabled {
		t.Fatal("confirmed action should disable the source")
	}
	ans := e.tg.Calls("answerCallbackQuery")
	if ans[len(ans)-1].Params["text"] != "已停用 iPhone" {
		t.Errorf("toast %v", ans[len(ans)-1].Params)
	}
	text, kb = lastEdit(t, e)
	if !strings.Contains(text, "已停用") || !strings.Contains(kb, "sa:on:iphone") {
		t.Error("view should show the new state and offer re-enable")
	}
}

func TestRunPollsAndIdentifies(t *testing.T) {
	e := setup(t)
	ctx, cancel := context.WithCancel(bg)
	defer cancel()
	done := make(chan struct{})
	go func() { e.b.Run(ctx); close(done) }()
	e.tg.PushUpdates(map[string]any{"update_id": 5, "message": map[string]any{"message_id": 1,
		"from": map[string]any{"id": admin}, "chat": map[string]any{"id": admin, "type": "private"}, "text": "/menu"}})
	e.tg.WaitCalls(t, "sendMessage", 1, 5*time.Second)
	if e.tg.Calls("setMyCommands")[0].Params["scope"].(map[string]any)["chat_id"].(float64) != admin {
		t.Error("commands should be scoped to the admin chat")
	}
	if len(e.tg.Calls("deleteWebhook")) != 1 {
		t.Error("webhook should be removed for long polling")
	}
	st := e.b.Health.Snapshot()
	if st.Bot == nil || st.Bot.Username != "test_gateway_bot" || st.PollOK.IsZero() {
		t.Errorf("health %+v", st)
	}
	// Next poll must acknowledge update 5.
	calls := e.tg.WaitCalls(t, "getUpdates", 2, 5*time.Second)
	if calls[1].Params["offset"].(float64) != 6 {
		t.Errorf("offset %v", calls[1].Params["offset"])
	}
	cancel()
	<-done
}

func TestRunReportsConfigError(t *testing.T) {
	e := setup(t)
	e.tg.Script("getMe", faketg.Response{Status: 401, Description: "Unauthorized"})
	ctx, cancel := context.WithCancel(bg)
	go e.b.Run(ctx)
	defer cancel()
	e.tg.WaitCalls(t, "getMe", 1, 5*time.Second)
	time.Sleep(50 * time.Millisecond)
	rep := e.b.Status.Report(bg)
	if c := rep.Component("bot"); c.Level != health.ConfigError || !strings.Contains(c.Summary, "Token") {
		t.Errorf("bot component %+v", c)
	}
}

func TestFilteredViewAndRescue(t *testing.T) {
	e := setup(t)
	woken := 0
	e.b.Delivery = wakeFunc(func() { woken++ })
	e.b.Settings.Filter = FilterSettings{Enabled: true, Model: "deepseek-flash", Types: []string{"sms"},
		Drop: []model.Category{model.CatMarketing, model.CatSpam}, KeepDays: 7}
	now := time.Now()
	m := &model.Message{ID: model.NewMessageID(now), SourceID: "iphone", Kind: "sms", Priority: model.PriorityNormal,
		Title: "某商城", Body: "双11 <全场> 5折", ContentHash: "h", ReceivedAt: now}
	e.st.InsertMessage(bg, m, []store.NewDelivery{{Channel: "telegram", Target: "default", Held: true}}, 0)
	e.st.ResolveHeld(bg, m.ID, model.CatMarketing, true, now)

	e.b.handle(bg, cb(admin, "m"))
	if _, kb := lastEdit(t, e); !strings.Contains(kb, "fl:0") {
		t.Errorf("main menu should link the filtered page: %s", kb)
	}
	e.b.handle(bg, cb(admin, "fl:0"))
	text, kb := lastEdit(t, e)
	for _, w := range []string{"已过滤的短信</b>（共 1 条）", "保留 7 天", "📱 iPhone · 广告营销", "<b>某商城</b> 双11 &lt;全场&gt; 5折"} {
		if !strings.Contains(text, w) {
			t.Errorf("filtered view lacks %q:\n%s", w, text)
		}
	}
	if !strings.Contains(kb, "fr:"+m.ID+":0") {
		t.Fatalf("rescue button missing: %s", kb)
	}

	e.b.handle(bg, cb(admin, "fr:"+m.ID+":0"))
	ans := e.tg.Calls("answerCallbackQuery")
	if ans[len(ans)-1].Params["text"] != "已转发，稍后送达" || woken != 1 {
		t.Fatalf("rescue: %v woken=%d", ans[len(ans)-1].Params["text"], woken)
	}
	if text, _ := lastEdit(t, e); !strings.Contains(text, "暂无被过滤的短信") {
		t.Errorf("rescued message should leave the list:\n%s", text)
	}
	if mi, _ := e.st.GetMessage(bg, m.ID); mi.Message.Status != model.StatusQueued {
		t.Fatalf("status after rescue: %v", mi.Message.Status)
	}
	e.b.handle(bg, cb(admin, "fr:"+m.ID+":0"))
	ans = e.tg.Calls("answerCallbackQuery")
	if ans[len(ans)-1].Params["text"] != "该消息已转发或已过期" {
		t.Fatalf("second rescue: %v", ans[len(ans)-1].Params["text"])
	}
	// Strangers cannot rescue.
	e.b.handle(bg, cb(stranger, "fr:"+m.ID+":0"))
	if woken != 1 {
		t.Fatal("stranger triggered a rescue")
	}

	e.b.handle(bg, cb(admin, "set"))
	if text, _ := lastEdit(t, e); !strings.Contains(text, "不推送的类别：广告营销、垃圾/诈骗") || !strings.Contains(text, "deepseek-flash") {
		t.Errorf("settings should describe the filter:\n%s", text)
	}
	e.b.handle(bg, cb(admin, "o"))
	if text, _ := lastEdit(t, e); !strings.Contains(text, "短信过滤：已启用") {
		t.Errorf("overview should show the filter:\n%s", text)
	}
}

type wakeFunc func()

func (f wakeFunc) Wake() { f() }

func TestInfoButtonShowsDetailsWithoutEditing(t *testing.T) {
	e := setup(t)
	now := time.Date(2026, 10, 25, 12, 37, 2, 0, time.UTC)
	m := &model.Message{ID: model.NewMessageID(now), SourceID: "iphone", Kind: "sms", Priority: model.PriorityNormal,
		Body: "x", ContentHash: "h", ReceivedAt: now, OccurredAt: now.Add(-time.Minute)}
	e.st.InsertMessage(bg, m, []store.NewDelivery{{Channel: "telegram", Target: "default"}}, 0)

	e.b.handle(bg, cb(admin, "i:"+m.ID))
	if n := len(e.tg.Calls("editMessageText")) + len(e.tg.Calls("sendMessage")); n != 0 {
		t.Fatalf("info button must not change or send messages (%d calls)", n)
	}
	ans := e.tg.Calls("answerCallbackQuery")
	p := ans[len(ans)-1].Params
	text := p["text"].(string)
	for _, w := range []string{"来源：📱 iPhone", "类型：💬 短信", "接收：2026-10-25 21:37:02", "设备时间：2026-10-25 21:36:02", "状态：已接收，等待投递", "ID：" + m.ID} {
		if !strings.Contains(text, w) {
			t.Errorf("popup lacks %q:\n%s", w, text)
		}
	}
	if p["show_alert"] != true || len([]rune(text)) > 200 {
		t.Errorf("popup must be an alert within 200 characters: %v %d", p["show_alert"], len([]rune(text)))
	}

	e.b.handle(bg, cb(admin, "i:m_GONE"))
	ans = e.tg.Calls("answerCallbackQuery")
	if !strings.Contains(ans[len(ans)-1].Params["text"].(string), "超过保留期") {
		t.Error("expired message should be explained")
	}
	e.b.handle(bg, cb(stranger, "i:"+m.ID))
	ans = e.tg.Calls("answerCallbackQuery")
	if ans[len(ans)-1].Params["text"] != "⛔ 无权限" {
		t.Error("strangers must not see message details")
	}
}
