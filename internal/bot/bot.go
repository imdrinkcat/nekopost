// Package bot implements the Telegram management bot: a menu-driven,
// admin-only interface for status, statistics and a few safe operations.
//
// Security model: only users whose numeric Telegram id is configured as admin
// are served, and only in private chats. Group messages are ignored. Button
// payloads are parsed against a fixed set of actions; no input is ever
// executed or interpolated into commands.
package bot

import (
	"context"
	"errors"
	"html"
	"log/slog"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"

	"nekopost/internal/health"
	"nekopost/internal/model"
	"nekopost/internal/render"
	"nekopost/internal/status"
	"nekopost/internal/store"
	"nekopost/internal/telegram"
)

// Settings is the configuration overview shown in the settings view.
type Settings struct {
	Domain         string
	TLSMode        string
	Timezone       string
	ChatID         int64
	AdminCount     int
	RetentionDays  int
	DeliveryMaxAge time.Duration
	DedupeWindow   time.Duration
	RatePerMin     int
	MaxBodyChars   int
	Filter         FilterSettings
}

// FilterSettings describes the content filter configuration.
type FilterSettings struct {
	Enabled  bool
	Model    string
	Types    []string
	Drop     []model.Category
	KeepDays int
}

// Waker is implemented by the delivery worker.
type Waker interface{ Wake() }

// Bot is the management bot.
type Bot struct {
	Client   *telegram.Client
	IsAdmin  func(int64) bool
	Admins   []int64
	Status   *status.Service
	Store    *store.Store
	Health   *health.Tracker
	Log      *slog.Logger
	Location *time.Location
	Version  string
	Settings Settings
	Delivery Waker // woken after a filtered message is rescued
	Now      func() time.Time

	unauthMu      sync.Mutex
	unauthReplied map[int64]time.Time
}

func (b *Bot) now() time.Time {
	if b.Now != nil {
		return b.Now()
	}
	return time.Now()
}

var commands = []telegram.BotCommand{
	{Command: "menu", Description: "打开主菜单"},
	{Command: "status", Description: "系统总览"},
	{Command: "bot", Description: "机器人状态"},
	{Command: "server", Description: "服务器状态"},
	{Command: "sources", Description: "消息来源"},
	{Command: "stats", Description: "消息统计"},
	{Command: "filtered", Description: "已过滤的短信"},
	{Command: "settings", Description: "系统设置"},
	{Command: "help", Description: "帮助"},
}

// classify maps a Bot API error to a failure kind and a readable message.
func classify(err error) (health.FailKind, string) {
	var ne *telegram.NetError
	if errors.As(err, &ne) {
		return health.FailNetwork, "网络错误（" + ne.Msg + "）"
	}
	var ae *telegram.APIError
	if errors.As(err, &ae) {
		switch ae.Code {
		case 401, 404:
			return health.FailConfig, "Bot Token 无效或已被撤销"
		case 409:
			return health.FailConfig, "冲突：另一个程序正在使用同一个 Bot Token 接收消息"
		case 429:
			return health.FailOther, "被 Telegram 限流"
		}
		if ae.Code >= 500 {
			return health.FailNetwork, "Telegram 服务端错误"
		}
		return health.FailOther, ae.Description
	}
	return health.FailOther, err.Error()
}

func sleepCtx(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}

// Run identifies the bot and long-polls for updates until ctx is cancelled.
func (b *Bot) Run(ctx context.Context) {
	backoff := 2 * time.Second
	for {
		cctx, cancel := context.WithTimeout(ctx, 20*time.Second)
		me, err := b.Client.GetMe(cctx)
		cancel()
		if err == nil {
			b.Health.SetBot(health.BotIdentity{ID: me.ID, Username: me.Username, Name: me.FirstName})
			b.Health.PollOK(b.now())
			b.Log.Info("telegram bot identified", "username", me.Username, "id", me.ID)
			break
		}
		kind, msg := classify(err)
		b.Health.PollFailed(b.now(), kind, msg)
		b.Log.Warn("telegram getMe failed", "err", msg)
		if kind == health.FailConfig {
			backoff = max(backoff, 5*time.Minute)
		}
		if !sleepCtx(ctx, backoff) {
			return
		}
		backoff = min(backoff*2, 5*time.Minute)
	}
	b.setup(ctx)

	var offset int64
	backoff = 2 * time.Second
	for ctx.Err() == nil {
		ups, err := b.Client.GetUpdates(ctx, offset, 50*time.Second)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			kind, msg := classify(err)
			b.Health.PollFailed(b.now(), kind, msg)
			b.Log.Warn("telegram getUpdates failed", "err", msg)
			if kind == health.FailConfig {
				backoff = max(backoff, 30*time.Second)
			}
			if !sleepCtx(ctx, backoff) {
				return
			}
			backoff = min(backoff*2, time.Minute)
			continue
		}
		backoff = 2 * time.Second
		b.Health.PollOK(b.now())
		for _, u := range ups {
			if u.UpdateID >= offset {
				offset = u.UpdateID + 1
			}
			b.handle(ctx, u)
		}
	}
}

// setup removes any webhook and publishes the command menu to admins only.
func (b *Bot) setup(ctx context.Context) {
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	if err := b.Client.DeleteWebhook(ctx); err != nil {
		b.Log.Warn("deleteWebhook failed", "err", err)
	}
	// Non-admins see no command list; admins see it in their private chat.
	if err := b.Client.Call(ctx, "deleteMyCommands", map[string]any{}, nil); err != nil {
		b.Log.Warn("deleteMyCommands failed", "err", err)
	}
	for _, id := range b.Admins {
		if err := b.Client.SetMyCommands(ctx, commands, id); err != nil {
			b.Log.Warn("setMyCommands failed", "admin", id, "err", err)
		}
	}
}

func (b *Bot) handle(ctx context.Context, u telegram.Update) {
	defer func() {
		if v := recover(); v != nil {
			b.Log.Error("panic handling update", "panic", v, "stack", string(debug.Stack()))
		}
	}()
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	switch {
	case u.Message != nil:
		b.handleMessage(ctx, u.Message)
	case u.CallbackQuery != nil:
		b.handleCallback(ctx, u.CallbackQuery)
	}
}

func parseCommand(text string) string {
	text = strings.TrimSpace(text)
	if !strings.HasPrefix(text, "/") {
		return ""
	}
	cmd, _, _ := strings.Cut(text, " ")
	cmd, _, _ = strings.Cut(cmd, "@")
	return strings.ToLower(cmd)
}

func (b *Bot) handleMessage(ctx context.Context, m *telegram.Message) {
	if m.From == nil || m.Chat.Type != "private" {
		return // never respond in groups or channels
	}
	if !b.IsAdmin(m.From.ID) {
		b.replyUnauthorized(ctx, m)
		return
	}
	var v view
	switch parseCommand(m.Text) {
	case "/start":
		v = b.viewMain(ctx)
		v.text = "👋 你好！这是你的个人消息网关管理 Bot。\n" +
			"各设备发送的消息会转发到这里，你也可以随时在这里查看网关、Bot 和服务器的运行状态。\n\n" + v.text
	case "/menu":
		v = b.viewMain(ctx)
	case "/status":
		v = b.viewOverview(ctx)
	case "/bot":
		v = b.viewBot(ctx)
	case "/server":
		v = b.viewServer(ctx)
	case "/sources":
		v = b.viewSources(ctx)
	case "/stats":
		v = b.viewStats(ctx, status.RangeToday)
	case "/filtered":
		v = b.viewFiltered(ctx, 0)
	case "/settings":
		v = b.viewSettings(ctx)
	case "/help":
		v = b.viewHelp()
	default:
		v = b.viewMain(ctx)
		v.text = "🤔 无法识别的输入。请使用下方按钮，或发送 /menu 打开主菜单。\n\n" + v.text
	}
	b.send(ctx, m.Chat.ID, v)
}

func (b *Bot) send(ctx context.Context, chatID int64, v view) {
	_, err := b.Client.SendMessage(ctx, telegram.SendMessageParams{
		ChatID: chatID, Text: v.text, ParseMode: "HTML", ReplyMarkup: v.keyboard(),
	})
	if err != nil {
		kind, msg := classify(err)
		b.Health.PollFailed(b.now(), kind, "发送菜单失败："+msg)
		b.Log.Warn("bot send failed", "err", msg)
	}
}

func (b *Bot) replyUnauthorized(ctx context.Context, m *telegram.Message) {
	now := b.now()
	b.Health.BotUnauthorized(now)
	b.Log.Warn("unauthorized bot access", "user_id", m.From.ID)
	b.unauthMu.Lock()
	if b.unauthReplied == nil {
		b.unauthReplied = map[int64]time.Time{}
	}
	last := b.unauthReplied[m.From.ID]
	shouldReply := now.Sub(last) > 10*time.Minute
	if shouldReply {
		b.unauthReplied[m.From.ID] = now
		if len(b.unauthReplied) > 1000 {
			for k, t := range b.unauthReplied {
				if now.Sub(t) > 10*time.Minute {
					delete(b.unauthReplied, k)
				}
			}
		}
	}
	b.unauthMu.Unlock()
	if !shouldReply {
		return
	}
	text := "⛔ 这是一个私人机器人，你没有使用权限。\n\n" +
		"如果你是这台网关的管理员，请将下面的用户 ID 加入服务器配置 <code>NEKOPOST_TELEGRAM_ADMIN_IDS</code>：\n" +
		"<code>" + itoa(m.From.ID) + "</code>"
	_, _ = b.Client.SendMessage(ctx, telegram.SendMessageParams{ChatID: m.Chat.ID, Text: text, ParseMode: "HTML"})
}

func (b *Bot) handleCallback(ctx context.Context, cq *telegram.CallbackQuery) {
	if !b.IsAdmin(cq.From.ID) {
		b.Health.BotUnauthorized(b.now())
		b.Log.Warn("unauthorized bot callback", "user_id", cq.From.ID)
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "⛔ 无权限", true)
		return
	}
	if id, ok := strings.CutPrefix(cq.Data, render.InfoPrefix); ok {
		// Info button under a forwarded message: answer with a popup and
		// leave the message itself untouched.
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, b.messageInfo(ctx, id), true)
		return
	}
	if cq.Message == nil || cq.Message.Chat.Type != "private" {
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "请在与 Bot 的私聊中使用", true)
		return
	}
	v, toast := b.dispatch(ctx, cq.From.ID, cq.Data)
	err := b.Client.EditMessageText(ctx, cq.Message.Chat.ID, cq.Message.MessageID, v.text, "HTML", v.keyboard())
	if err != nil && !telegram.IsNotModified(err) {
		// Old messages may no longer be editable: send a fresh one instead.
		b.Log.Debug("edit failed, sending new message", "err", err)
		b.send(ctx, cq.Message.Chat.ID, v)
	}
	_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, toast, false)
}

// dispatch maps a button payload to a view. Payloads are short fixed tokens:
//
//	m, o, b, sv, src, set, h       main views
//	st:<range>                     statistics for a range
//	s:<id>                         source detail
//	sa:<on|off>:<id>               ask for confirmation
//	sc:<on|off>:<id>:<unix>        confirmed action (valid for 5 minutes)
//	fl:<offset>                    filtered messages, one page
//	fr:<message id>:<offset>       forward (rescue) a filtered message
//	i:<message id>                 info button under a forwarded message (popup only)
func (b *Bot) dispatch(ctx context.Context, userID int64, data string) (view, string) {
	parts := strings.Split(data, ":")
	switch parts[0] {
	case "m":
		return b.viewMain(ctx), ""
	case "o":
		return b.viewOverview(ctx), "已刷新"
	case "b":
		return b.viewBot(ctx), "已刷新"
	case "sv":
		return b.viewServer(ctx), "已刷新"
	case "src":
		return b.viewSources(ctx), "已刷新"
	case "set":
		return b.viewSettings(ctx), ""
	case "h":
		return b.viewHelp(), ""
	case "st":
		if len(parts) == 2 {
			if r, ok := status.ParseRange(parts[1]); ok {
				return b.viewStats(ctx, r), ""
			}
		}
	case "s":
		if len(parts) == 2 {
			return b.viewSource(ctx, parts[1]), ""
		}
	case "sa":
		if len(parts) == 3 && (parts[1] == "on" || parts[1] == "off") {
			return b.viewConfirmToggle(ctx, parts[2], parts[1] == "on"), ""
		}
	case "sc":
		if len(parts) == 4 && (parts[1] == "on" || parts[1] == "off") {
			return b.toggleSource(ctx, userID, parts[2], parts[1] == "on", parts[3])
		}
	case "fl":
		if len(parts) == 2 {
			if off, err := strconv.Atoi(parts[1]); err == nil && off >= 0 {
				return b.viewFiltered(ctx, off), ""
			}
		}
	case "fr":
		if len(parts) == 3 {
			if off, err := strconv.Atoi(parts[2]); err == nil && off >= 0 {
				return b.rescue(ctx, userID, parts[1], off)
			}
		}
	}
	return b.viewMain(ctx), "该按钮已失效"
}

func (b *Bot) toggleSource(ctx context.Context, userID int64, id string, enable bool, ts string) (view, string) {
	issued, err := parseInt(ts)
	if err != nil || b.now().Sub(time.Unix(issued, 0)) > 5*time.Minute || b.now().Before(time.Unix(issued, 0).Add(-time.Minute)) {
		return b.viewSource(ctx, id), "确认已过期，请重新操作"
	}
	src, err := b.Store.GetSource(ctx, id)
	if err != nil {
		return b.viewSources(ctx), "来源不存在"
	}
	if src.Enabled == enable {
		return b.viewSource(ctx, id), "状态未变化"
	}
	if err := b.Store.SetSourceEnabled(ctx, id, enable, b.now()); err != nil {
		b.Log.Error("toggle source failed", "source", id, "err", err)
		return b.viewSource(ctx, id), "操作失败，请稍后重试"
	}
	action := "disabled"
	toast := "已停用 " + src.Name
	if enable {
		action, toast = "enabled", "已启用 "+src.Name
	}
	b.Log.Info("source "+action+" via telegram", "source", id, "admin", userID)
	return b.viewSource(ctx, id), toast
}

func (b *Bot) rescue(ctx context.Context, userID int64, id string, offset int) (view, string) {
	err := b.Store.RescueFiltered(ctx, id, b.now())
	if errors.Is(err, store.ErrNotRescuable) {
		return b.viewFiltered(ctx, offset), "该消息已转发或已过期"
	}
	if err != nil {
		b.Log.Error("rescue filtered message failed", "message", id, "err", err)
		return b.viewFiltered(ctx, offset), "操作失败，请稍后重试"
	}
	b.Log.Info("filtered message forwarded via telegram", "message", id, "admin", userID)
	if b.Delivery != nil {
		b.Delivery.Wake()
	}
	return b.viewFiltered(ctx, offset), "已转发，稍后送达"
}

// messageInfo is the popup text for a forwarded message's info buttons
// (plain text, at most 200 characters).
func (b *Bot) messageInfo(ctx context.Context, id string) string {
	mi, err := b.Store.GetMessage(ctx, id)
	if errors.Is(err, store.ErrNotFound) {
		return "这条消息的记录已超过保留期，无法查看详情"
	}
	if err != nil {
		return "暂时无法读取消息详情，请稍后重试"
	}
	m := mi.Message
	var sb strings.Builder
	sb.WriteString("来源：" + b.sourceLabel(ctx, m.SourceID) + "\n")
	kind := render.KindLabel(m.Kind)
	if kind == "" {
		kind = "文本"
	}
	sb.WriteString("类型：" + kind)
	if m.Category != "" {
		sb.WriteString(" · " + m.Category.Label())
	}
	sb.WriteString("\n接收：" + b.fmtTime(m.ReceivedAt) + "\n")
	if !m.OccurredAt.IsZero() {
		sb.WriteString("设备时间：" + b.fmtTime(m.OccurredAt) + "\n")
	}
	sb.WriteString("状态：" + m.Status.Label() + "\nID：" + m.ID)
	return sb.String()
}

func e(s string) string { return html.EscapeString(s) }
