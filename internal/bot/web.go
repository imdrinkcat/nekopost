package bot

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"

	"nekopost/internal/store"
	"nekopost/internal/telegram"
)

const webApprovalPrefix = "wa:"

var webApprovalID = regexp.MustCompile(`^a_[A-Z2-7]{26}$`)

// SendWebApproval delivers choices only to administrators' private chats.
func (b *Bot) SendWebApproval(ctx context.Context, a store.WebApproval) {
	ctx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	var buttons []telegram.InlineKeyboardButton
	for _, choice := range a.Choices {
		buttons = append(buttons, telegram.InlineKeyboardButton{Text: choice, CallbackData: webApprovalPrefix + a.ID + ":" + choice})
	}
	buttons = append(buttons, telegram.InlineKeyboardButton{Text: "拒绝", CallbackData: webApprovalPrefix + a.ID + ":deny"})
	title := "猫邮局新浏览器登录"
	if a.Kind == "reauth" {
		title = "猫邮局网页管理重新认证"
	}
	text := fmt.Sprintf("%s\n%s · %s\n请选出浏览器显示的数字。若不是你发起的，请点拒绝。", title, a.Browser, a.IP)
	for _, id := range b.Admins {
		_, err := b.Client.SendMessage(ctx, telegram.SendMessageParams{ChatID: id, Text: text,
			ReplyMarkup: &telegram.InlineKeyboardMarkup{InlineKeyboard: [][]telegram.InlineKeyboardButton{buttons}}})
		if err != nil {
			b.Log.Warn("web approval Telegram notification failed")
		}
	}
}

func (b *Bot) NotifyWebSecurity(ctx context.Context, text string) {
	ctx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	for _, id := range b.Admins {
		_, err := b.Client.SendMessage(ctx, telegram.SendMessageParams{ChatID: id, Text: text, DisableNotification: true})
		if err != nil {
			b.Log.Warn("web security Telegram notification failed")
		}
	}
}

func (b *Bot) handleWebApproval(ctx context.Context, cq *telegram.CallbackQuery, data string) {
	if cq.Message == nil || cq.Message.Chat.Type != "private" || cq.Message.Chat.ID != cq.From.ID {
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "请在与 Bot 的私聊中使用", true)
		return
	}
	id, choice, valid := strings.Cut(data, ":")
	if !valid || !webApprovalID.MatchString(id) || (choice != "deny" &&
		(len(choice) != 2 || choice[0] < '0' || choice[0] > '9' || choice[1] < '0' || choice[1] > '9')) {
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "该确认已失效", true)
		return
	}
	ok, err := b.Store.DecideWebApproval(ctx, id, choice, fmt.Sprintf("bot:tg:%d", cq.From.ID), false, b.now())
	if errors.Is(err, store.ErrNotFound) || errors.Is(err, store.ErrWebRestricted) {
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "该确认已失效或已处理", true)
		return
	}
	if err != nil {
		b.Log.Warn("web approval could not be recorded")
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "暂时无法确认，请重试", true)
		return
	}
	text := "已拒绝网页登录"
	if ok {
		text = "已确认网页登录"
	}
	_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, text, !ok)
	_ = b.Client.EditMessageText(ctx, cq.Message.Chat.ID, cq.Message.MessageID, text, "",
		&telegram.InlineKeyboardMarkup{InlineKeyboard: [][]telegram.InlineKeyboardButton{}})
}
