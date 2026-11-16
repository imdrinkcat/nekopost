package bot

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"nekopost/internal/enroll"
	"nekopost/internal/model"
	"nekopost/internal/store"
	"nekopost/internal/telegram"
)

// Adding a device from Telegram (「➕ 添加设备」).
//
// The flow mirrors POST /v1/admin/pairings and `nekopost device pair`: the
// same store.NewPairing call, a 5-minute expiry and the audit entry
// pairing.start, with the actor "bot:tg:<id>". The bot then sends the QR
// code as a photo, the manual short code and the full nekopost://enroll
// link inside <code> blocks (tap to copy, no URL entities or URL buttons).
// When the new phone claims the code, the existing number-matching message
// (SendPairApproval) follows; the code message itself is removed as soon as
// the pairing is claimed, cancelled or expires.
//
// Callback payloads:
//
//	dvn                         choose a role
//	dvr:<role>:<unix>           role chosen (full/receiver/sender)
//	dvs:<role>:<source>:<unix>  source chosen ("+" = new source)
//	dvx:<pairing id>            void an unused pairing code (photo button)

const (
	pairingTTL        = 5 * time.Minute
	pairingRateWindow = 10 * time.Minute
	pairingRateMax    = 3
	cancelPairPrefix  = "dvx:"
	newSourceToken    = "+"
)

var pairRoles = map[string]struct{ label, scopes, help string }{
	"full": {"完整", strings.Join([]string{store.ScopeSend, store.ScopeRead, store.ScopeAdminRead, store.ScopeAdminWrite}, ","),
		"接收消息、转发短信和通知、管理网关"},
	"receiver": {"仅接收", store.ScopeRead, "只在 App 里接收消息"},
	"sender":   {"仅转发", store.ScopeSend, "只把这台手机的短信和通知转发到网关"},
}

func (b *Bot) viewAddDevice() view {
	wire := strconv.FormatInt(b.now().Unix(), 10)
	text := "<b>➕ 添加设备</b>\n选择新设备的权限：\n" +
		"• <b>完整</b>：" + pairRoles["full"].help + "\n" +
		"• <b>仅接收</b>：" + pairRoles["receiver"].help + "\n" +
		"• <b>仅转发</b>：" + pairRoles["sender"].help + "\n\n" +
		"生成后这里会收到二维码、配对码和配对链接，5 分钟内有效。新设备申请配对时，这里会请你核对手机上显示的数字。"
	return view{text: text, rows: [][]telegram.InlineKeyboardButton{
		{btn("完整", "dvr:full:"+wire), btn("仅接收", "dvr:receiver:"+wire), btn("仅转发", "dvr:sender:"+wire)},
		{btn("« 返回设备", "dv")},
	}}
}

func (b *Bot) viewPairSource(ctx context.Context, role string) view {
	wire := strconv.FormatInt(b.now().Unix(), 10)
	text := "<b>➕ 添加设备 · " + pairRoles[role].label + "</b>\n选择这台设备转发消息时使用的来源。\n" +
		"选「新来源」会在配对完成后自动创建，之后可以改名。"
	var rows [][]telegram.InlineKeyboardButton
	if srcs, err := b.Store.ListSources(ctx); err == nil {
		var row []telegram.InlineKeyboardButton
		for _, s := range srcs {
			if len(rows) >= 8 {
				break
			}
			row = append(row, btn(s.DisplayName(), "dvs:"+role+":"+s.ID+":"+wire))
			if len(row) == 2 {
				rows = append(rows, row)
				row = nil
			}
		}
		if len(row) > 0 {
			rows = append(rows, row)
		}
	}
	rows = append(rows, []telegram.InlineKeyboardButton{btn("🆕 新来源", "dvs:"+role+":"+newSourceToken+":"+wire)},
		[]telegram.InlineKeyboardButton{btn("« 重新选择权限", "dvn")})
	return view{text: text, rows: rows}
}

// addDeviceAction handles the dvn/dvr/dvs payloads. In a private chat the
// chat ID equals the user ID, which handleCallback has already checked.
func (b *Bot) addDeviceAction(ctx context.Context, user int64, parts []string, fresh func(string) bool) (view, string, bool) {
	switch parts[0] {
	case "dvn":
		return b.viewAddDevice(), "", true
	case "dvr":
		if len(parts) != 3 || !fresh(parts[2]) {
			return b.viewAddDevice(), "选择已过期，请重新选择", true
		}
		if _, ok := pairRoles[parts[1]]; !ok {
			return b.viewAddDevice(), "权限无效", true
		}
		if parts[1] == "receiver" {
			return b.createBotPairing(ctx, user, parts[1], "")
		}
		return b.viewPairSource(ctx, parts[1]), "", true
	case "dvs":
		if len(parts) != 4 || !fresh(parts[3]) {
			return b.viewAddDevice(), "选择已过期，请重新选择", true
		}
		role, source := parts[1], parts[2]
		if _, ok := pairRoles[role]; !ok || role == "receiver" {
			return b.viewAddDevice(), "权限无效", true
		}
		if source == newSourceToken {
			source = "device-" + strings.ToLower(rand.Text()[:8])
		} else if _, err := b.Store.GetSource(ctx, source); err != nil || !model.ValidSourceID(source) {
			return b.viewPairSource(ctx, role), "来源不存在", true
		}
		return b.createBotPairing(ctx, user, role, source)
	}
	return view{}, "", false
}

func (b *Bot) createBotPairing(ctx context.Context, user int64, role, source string) (view, string, bool) {
	actor := fmt.Sprintf("bot:tg:%d", user)
	now := b.now()
	if b.PublicURL == "" {
		return b.viewDevices(ctx), "未配置 HTTPS 对外地址，无法生成配对码", true
	}
	if n, err := b.Store.RecentPairings(ctx, "bot:tg:", now.Add(-pairingRateWindow)); err != nil {
		return b.viewDevices(ctx), "操作失败，请稍后重试", true
	} else if n >= pairingRateMax {
		return b.viewDevices(ctx), "生成太频繁，请 10 分钟后再试", true
	}
	code, short := enroll.NewPairingCodes()
	link, err := enroll.DeviceLink(b.PublicURL, code)
	if err != nil {
		return b.viewDevices(ctx), "服务器地址不支持配对", true
	}
	symbol, err := enroll.Encode(link)
	if err != nil {
		return b.viewDevices(ctx), "无法生成二维码", true
	}
	r := pairRoles[role]
	p := store.Pairing{ID: "p_" + rand.Text(), Name: "新设备", SourceID: source, Scopes: r.scopes, Expires: now.Add(pairingTTL)}
	switch err := b.Store.NewPairing(ctx, p, store.PairingCodeHash(code), store.PairingCodeHash(short), actor, now); {
	case errors.Is(err, store.ErrLocked):
		return b.viewDevices(ctx), "紧急锁定中，无法添加设备", true
	case err != nil:
		b.Log.Error("create pairing via telegram failed", "err", err)
		return b.viewDevices(ctx), "操作失败，请稍后重试", true
	}
	b.Log.Info("pairing created via telegram", "pairing", p.ID, "role", role, "admin", user)
	b.alertOtherAdmins(ctx, user, "有管理员在 Telegram 中创建了新的设备配对（"+r.label+"）")

	caption := pairingCaption(r.label, source, b.PublicURL, short, link, b.fmtShort(p.Expires))
	kb := &telegram.InlineKeyboardMarkup{InlineKeyboard: [][]telegram.InlineKeyboardButton{{btn("作废此配对码", cancelPairPrefix+p.ID)}}}
	msg, err := b.Client.SendPhoto(ctx, telegram.SendPhotoParams{ChatID: user, PNG: symbol.PNG(), Caption: caption,
		ParseMode: "HTML", ReplyMarkup: kb, ProtectContent: true})
	if err != nil {
		// Without the photo the code and link still work.
		msg, err = b.Client.SendMessage(ctx, telegram.SendMessageParams{ChatID: user, Text: caption, ParseMode: "HTML", ReplyMarkup: kb})
	}
	if err != nil {
		_ = b.Store.CancelPairing(ctx, p.ID, actor, b.now())
		return b.viewDevices(ctx), "发送配对码失败，已作废", true
	}
	go b.watchPairing(b.background(), user, msg.MessageID, p.ID, p.Expires)
	return b.viewDevices(ctx), "配对码已发送，5 分钟内有效", true
}

// pairingCaption is the HTML caption of the pairing photo. Everything a
// person may need to copy is in <code> blocks, which Telegram copies with a
// tap and never turns into links.
func pairingCaption(role, source, server, short, link, until string) string {
	var sb strings.Builder
	sb.WriteString("<b>📱 新设备配对</b>（" + e(role))
	if source != "" {
		sb.WriteString(" · 来源 <code>" + e(source) + "</code>")
	}
	sb.WriteString("）\n服务器：<code>" + e(server) + "</code>\n")
	sb.WriteString("配对码：<code>" + e(short[:4]+"-"+short[4:8]+"-"+short[8:]) + "</code>\n")
	sb.WriteString("配对链接（轻点复制）：\n<code>" + e(link) + "</code>\n\n")
	sb.WriteString("在新手机的猫邮局中扫描二维码。如果就是这台手机，复制链接或配对码，再在 App 里点「粘贴配对链接」。\n")
	sb.WriteString(e(until) + " 前有效，用过或过期后这条消息会自动删除。新设备申请后，这里会请你核对数字。")
	return sb.String()
}

func (b *Bot) alertOtherAdmins(ctx context.Context, creator int64, text string) {
	ctx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	for _, id := range b.Admins {
		if id == creator {
			continue
		}
		if _, err := b.Client.SendMessage(ctx, telegram.SendMessageParams{ChatID: id, Text: text}); err != nil {
			b.Log.Warn("pairing Telegram notification failed")
		}
	}
}

func (b *Bot) background() context.Context {
	b.runMu.Lock()
	defer b.runMu.Unlock()
	if b.runCtx != nil {
		return b.runCtx
	}
	return context.Background()
}

// watchPairing removes the pairing message once the code is used, voided
// or expired, so it does not linger in the chat.
func (b *Bot) watchPairing(ctx context.Context, chatID, messageID int64, id string, expires time.Time) {
	poll := b.PairPoll
	if poll <= 0 {
		poll = 2 * time.Second
	}
	t := time.NewTicker(poll)
	defer t.Stop()
	// Wall-clock bound, so a watcher never outlives its code by much even
	// if the store keeps failing.
	giveUp := time.Now().Add(time.Until(expires) + time.Minute)
	note := ""
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		p, err := b.Store.Pairing(ctx, id)
		if errors.Is(err, store.ErrNotFound) {
			break
		}
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			if time.Now().After(giveUp) {
				break
			}
			continue
		}
		if p.State == "open" && b.now().Before(expires) && time.Now().Before(giveUp) {
			continue
		}
		switch p.State {
		case "open":
			note = "⌛ 配对码已过期，没有设备使用。需要时可在「📱 设备」中重新添加。"
		case "cancelled":
			note = "" // the button handler already confirmed it
		case "claimed", "approved", "done":
			note = "✅ 配对码已被新设备使用，已从聊天中移除。请在设备上核对数字后确认。"
		default:
			note = "配对未完成，配对码已失效。"
		}
		break
	}
	dctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
	defer cancel()
	if err := b.Client.DeleteMessage(dctx, chatID, messageID); err != nil {
		// Could not delete: at least remove the code and link.
		_ = b.Client.EditMessageCaption(dctx, chatID, messageID, "配对码已失效", "",
			&telegram.InlineKeyboardMarkup{InlineKeyboard: [][]telegram.InlineKeyboardButton{}})
	}
	if note != "" {
		_, _ = b.Client.SendMessage(dctx, telegram.SendMessageParams{ChatID: chatID, Text: note, DisableNotification: true})
	}
}

func (b *Bot) handleCancelPairing(ctx context.Context, cq *telegram.CallbackQuery, id string) {
	if cq.Message == nil || cq.Message.Chat.Type != "private" || cq.Message.Chat.ID != cq.From.ID || !pairID.MatchString(id) {
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "该按钮已失效", true)
		return
	}
	err := b.Store.CancelPairing(ctx, id, fmt.Sprintf("bot:tg:%d", cq.From.ID), b.now())
	if errors.Is(err, store.ErrNotFound) {
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "配对码已被使用或已过期", true)
		return
	}
	if err != nil {
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "操作失败，请稍后重试", true)
		return
	}
	_ = b.Client.DeleteMessage(ctx, cq.Message.Chat.ID, cq.Message.MessageID)
	_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "已作废配对码", false)
}
