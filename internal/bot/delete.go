package bot

import (
	"context"
	"errors"
	"strconv"
	"strings"
	"time"

	"nekopost/internal/model"
	"nekopost/internal/render"
	"nekopost/internal/store"
	"nekopost/internal/telegram"
)

const (
	deleteConfirmFor    = 5 * time.Minute
	telegramDeleteLimit = 48 * time.Hour
	deletedPlaceholder  = "此消息已删除"
)

func (b *Bot) handleDelete(ctx context.Context, cq *telegram.CallbackQuery) {
	if cq.Message == nil {
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "这条消息已经不在了", true)
		return
	}
	chatID := cq.Message.Chat.ID
	tgID := cq.Message.MessageID
	switch {
	case strings.HasPrefix(cq.Data, render.DeleteAskPrefix):
		id, part, total, ok := parsePartRef(strings.TrimPrefix(cq.Data, render.DeleteAskPrefix))
		if !ok {
			_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "该按钮已失效", true)
			return
		}
		rows := render.DeleteConfirmButtons(id, part, total, b.now().Unix())
		_ = b.Client.EditMessageReplyMarkup(ctx, chatID, tgID, markup(rows))
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "5 分钟内再次确认才会删除", false)
	case strings.HasPrefix(cq.Data, render.DeleteNoPrefix):
		id, part, total, ok := parsePartRef(strings.TrimPrefix(cq.Data, render.DeleteNoPrefix))
		if !ok {
			_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "该按钮已失效", true)
			return
		}
		kb := b.restoredMarkup(ctx, id, part, total)
		_ = b.Client.EditMessageReplyMarkup(ctx, chatID, tgID, kb)
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "已取消", false)
	case strings.HasPrefix(cq.Data, render.DeleteYesPrefix):
		id, unix, part, total, ok := parseConfirmRef(strings.TrimPrefix(cq.Data, render.DeleteYesPrefix))
		if !ok {
			_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "该按钮已失效", true)
			return
		}
		issued := time.Unix(unix, 0)
		if b.now().Sub(issued) > deleteConfirmFor || b.now().Before(issued.Add(-time.Minute)) {
			kb := b.restoredMarkup(ctx, id, part, total)
			_ = b.Client.EditMessageReplyMarkup(ctx, chatID, tgID, kb)
			_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "确认已过期，请重新操作", true)
			return
		}
		b.commitDelete(ctx, cq, id, tgID)
	default:
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "该按钮已失效", true)
	}
}

func (b *Bot) commitDelete(ctx context.Context, cq *telegram.CallbackQuery, id string, clicked int64) {
	mi, err := b.Store.DeleteMessage(ctx, id, b.now())
	if errors.Is(err, store.ErrNotFound) {
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "这条消息已经删除", true)
		return
	}
	if errors.Is(err, store.ErrDeliveryInFlight) {
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "这条消息正在投递，请稍后重试删除", true)
		return
	}
	if err != nil {
		b.Log.Error("delete message failed", "message", id, "err", err)
		_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "删除失败，请稍后重试", true)
		return
	}
	targets := telegramTargets(mi, clicked)
	chatID := cq.Message.Chat.ID
	for tgID, sentAt := range targets {
		b.eraseTelegram(ctx, chatID, tgID, sentAt)
	}
	b.Log.Info("message deleted via telegram", "message", id, "admin", cq.From.ID, "parts", len(targets))
	_ = b.Client.AnswerCallbackQuery(ctx, cq.ID, "已删除", false)
}

func telegramTargets(mi store.MessageInfo, clicked int64) map[int64]time.Time {
	out := map[int64]time.Time{}
	for _, d := range mi.Deliveries {
		if d.Channel != "telegram" {
			continue
		}
		at := d.DeliveredAt
		if at.IsZero() {
			at = d.CreatedAt
		}
		for _, id := range d.TGMessageIDs {
			out[id] = at
		}
	}
	if _, ok := out[clicked]; !ok {
		out[clicked] = mi.Message.ReceivedAt
	}
	return out
}

func (b *Bot) eraseTelegram(ctx context.Context, chatID, messageID int64, sentAt time.Time) {
	tooOld := !sentAt.IsZero() && b.now().Sub(sentAt) > telegramDeleteLimit
	if !tooOld {
		err := b.Client.DeleteMessage(ctx, chatID, messageID)
		if err == nil {
			return
		}
		var ae *telegram.APIError
		if errors.As(err, &ae) && strings.Contains(ae.Description, "message to delete not found") {
			return
		}
	}
	empty := &telegram.InlineKeyboardMarkup{InlineKeyboard: [][]telegram.InlineKeyboardButton{}}
	_ = b.Client.EditMessageText(ctx, chatID, messageID, deletedPlaceholder, "", empty)
}

func (b *Bot) restoredMarkup(ctx context.Context, id string, part, total int) *telegram.InlineKeyboardMarkup {
	mi, err := b.Store.GetMessage(ctx, id)
	if err != nil {
		return nil
	}
	src, err := b.Store.GetSource(ctx, mi.Message.SourceID)
	if err != nil {
		src = model.Source{ID: mi.Message.SourceID, Name: mi.Message.SourceID}
	}
	rows := render.MessageButtons(render.Input{
		SourceID: src.ID, SourceName: src.Name, SourceIcon: src.Icon, Message: mi.Message, Location: b.Location,
	}, part, total)
	return markup(rows)
}

func markup(rows [][]render.Button) *telegram.InlineKeyboardMarkup {
	if len(rows) == 0 {
		return &telegram.InlineKeyboardMarkup{}
	}
	kb := &telegram.InlineKeyboardMarkup{}
	for _, r := range rows {
		var row []telegram.InlineKeyboardButton
		for _, b := range r {
			row = append(row, telegram.InlineKeyboardButton{Text: b.Text, CallbackData: b.Data})
		}
		kb.InlineKeyboard = append(kb.InlineKeyboard, row)
	}
	return kb
}

// parsePartRef parses "<id>:<part>:<total>".
func parsePartRef(rest string) (id string, part, total int, ok bool) {
	id, partS, ok := strings.Cut(rest, ":")
	if !ok || !validMessageID(id) {
		return "", 0, 0, false
	}
	partS, totalS, ok := strings.Cut(partS, ":")
	if !ok {
		return "", 0, 0, false
	}
	part, err1 := strconv.Atoi(partS)
	total, err2 := strconv.Atoi(totalS)
	if err1 != nil || err2 != nil || part < 1 || total < 1 || part > total {
		return "", 0, 0, false
	}
	return id, part, total, true
}

// parseConfirmRef parses "<id>:<unix>:<part>:<total>".
func parseConfirmRef(rest string) (id string, unix int64, part, total int, ok bool) {
	id, tail, ok := strings.Cut(rest, ":")
	if !ok || !validMessageID(id) {
		return "", 0, 0, 0, false
	}
	unixS, partRef, ok := strings.Cut(tail, ":")
	if !ok {
		return "", 0, 0, 0, false
	}
	unix, err := strconv.ParseInt(unixS, 10, 64)
	if err != nil {
		return "", 0, 0, 0, false
	}
	_, part, total, ok = parsePartRef(id + ":" + partRef)
	if !ok {
		return "", 0, 0, 0, false
	}
	return id, unix, part, total, true
}

func validMessageID(id string) bool {
	if len(id) < 4 || len(id) > 40 || !strings.HasPrefix(id, "m_") {
		return false
	}
	for _, r := range id[2:] {
		if (r < '0' || r > '9') && (r < 'A' || r > 'Z') {
			return false
		}
	}
	return true
}
