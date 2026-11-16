package bot

import (
	"context"
	"fmt"
	"regexp"
	"strings"
	"time"

	"nekopost/internal/store"
	"nekopost/internal/telegram"
)

const pairPrefix = "pa:"

var pairID = regexp.MustCompile(`^p_[A-Z2-7]{26}$`)

func (b *Bot) SendPairApproval(ctx context.Context, p store.Pairing) {
	ctx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	buttons := make([]telegram.InlineKeyboardButton, 0, 4)
	for _, choice := range p.Choices {
		buttons = append(buttons, telegram.InlineKeyboardButton{Text: choice, CallbackData: pairPrefix + p.ID + ":" + choice})
	}
	buttons = append(buttons, telegram.InlineKeyboardButton{Text: "拒绝", CallbackData: pairPrefix + p.ID + ":deny"})
	text := "有设备请求配对。请选出设备上显示的数字。若不是你发起的，请点拒绝。\n请求权限：" + p.Scopes
	for _, id := range b.Admins {
		_, err := b.Client.SendMessage(ctx, telegram.SendMessageParams{ChatID: id, Text: text, ReplyMarkup: &telegram.InlineKeyboardMarkup{
			InlineKeyboard: [][]telegram.InlineKeyboardButton{buttons},
		}})
		if err != nil {
			b.Log.Warn("pairing Telegram notification failed")
		}
	}
}

func (b *Bot) handlePairApproval(ctx context.Context, cq *telegram.CallbackQuery, data string) {
	if cq.Message == nil || cq.Message.Chat.Type != "private" || cq.Message.Chat.ID != cq.From.ID {
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "请在与 Bot 的私聊中使用", true)
		return
	}
	id, choice, ok := strings.Cut(data, ":")
	if !ok || !pairID.MatchString(id) || (choice != "deny" && (len(choice) != 2 || strings.Trim(choice, "0123456789") != "")) {
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "该确认已失效", true)
		return
	}
	approved, err := b.Store.DecidePairing(ctx, id, choice, fmt.Sprintf("bot:tg:%d", cq.From.ID), b.now())
	if err != nil {
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "配对已结束，或当前需要 CLI 确认", true)
		return
	}
	text := "已拒绝配对"
	if approved {
		text = "已确认配对"
	}
	_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, text, false)
	_ = b.Client.EditMessageText(ctx, cq.Message.Chat.ID, cq.Message.MessageID, text, "",
		&telegram.InlineKeyboardMarkup{InlineKeyboard: [][]telegram.InlineKeyboardButton{}})
}
