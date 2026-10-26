package delivery

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"nekopost/internal/health"
	"nekopost/internal/render"
	"nekopost/internal/store"
	"nekopost/internal/telegram"
)

// TelegramChannel delivers to Telegram chats via sendMessage.
type TelegramChannel struct {
	Client   *telegram.Client
	Targets  map[string]int64 // target name -> chat id
	Location *time.Location
	// MinInterval spaces consecutive sends to stay under Telegram's
	// per-chat rate limit (about one message per second).
	MinInterval time.Duration

	mu       sync.Mutex
	lastSend time.Time
}

// Name implements Channel.
func (t *TelegramChannel) Name() string { return "telegram" }

func (t *TelegramChannel) pace(ctx context.Context) error {
	t.mu.Lock()
	wait := t.MinInterval - time.Since(t.lastSend)
	t.mu.Unlock()
	if wait > 0 {
		select {
		case <-time.After(wait):
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	t.mu.Lock()
	t.lastSend = time.Now()
	t.mu.Unlock()
	return nil
}

// Deliver implements Channel.
func (t *TelegramChannel) Deliver(ctx context.Context, job store.Job, progress func(total, sent int)) Result {
	chatID, ok := t.Targets[job.Delivery.Target]
	if !ok {
		return Result{Outcome: Permanent, FailKind: health.FailConfig, Err: "未知的投递目标 " + job.Delivery.Target}
	}
	parts := render.Render(render.Input{
		SourceID:   job.Source.ID,
		SourceName: job.Source.Name,
		SourceIcon: job.Source.Icon,
		Message:    job.Message,
		Location:   t.Location,
	})
	total := len(parts)
	sent := job.Delivery.PartsSent
	if job.Delivery.PartsTotal != 0 && job.Delivery.PartsTotal != total {
		// Rendering changed between attempts (e.g. after an upgrade); the
		// safest choice under at-least-once is to start over.
		sent = 0
	}
	for i := sent; i < total; i++ {
		if err := t.pace(ctx); err != nil {
			return Result{Outcome: RetrySafe, PartsTotal: total, PartsSent: i, FailKind: health.FailOther, Err: "已取消"}
		}
		p := telegram.SendMessageParams{
			ChatID:              chatID,
			Text:                parts[i].HTML,
			ParseMode:           "HTML",
			DisableNotification: job.Delivery.Silent,
			ReplyMarkup:         keyboard(parts[i].Buttons),
		}
		err := t.send(ctx, p)
		var ae *telegram.APIError
		if errors.As(err, &ae) && ae.Code == 400 && strings.Contains(ae.Description, "can't parse entities") {
			// Formatting problem: fall back to plain text rather than failing.
			p.Text, p.ParseMode = parts[i].Plain, ""
			err = t.send(ctx, p)
		}
		if err != nil {
			r := classify(err)
			r.PartsTotal, r.PartsSent = total, i
			return r
		}
		progress(total, i+1)
	}
	return Result{Outcome: Delivered, PartsTotal: total, PartsSent: total}
}

func keyboard(rows [][]render.Button) *telegram.InlineKeyboardMarkup {
	if len(rows) == 0 {
		return nil
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

// send performs one sendMessage call with its own timeout.
func (t *TelegramChannel) send(ctx context.Context, p telegram.SendMessageParams) error {
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	_, err := t.Client.SendMessage(ctx, p)
	return err
}

// classify maps a Bot API error to a delivery outcome.
func classify(err error) Result {
	var ne *telegram.NetError
	if errors.As(err, &ne) {
		if ne.Written {
			return Result{Outcome: RetryUncertain, FailKind: health.FailNetwork,
				Err: "请求已发出但未收到 Telegram 响应（" + shorten(ne.Msg) + "）"}
		}
		return Result{Outcome: RetrySafe, FailKind: health.FailNetwork, Err: "无法连接 Telegram（" + shorten(ne.Msg) + "）"}
	}
	var ae *telegram.APIError
	if errors.As(err, &ae) {
		desc := shorten(ae.Description)
		switch {
		case ae.Code == 429:
			return Result{Outcome: RetrySafe, RetryAfter: ae.RetryAfter, FailKind: health.FailOther,
				Err: fmt.Sprintf("Telegram 限流（%d 秒后重试）", int(ae.RetryAfter.Seconds()))}
		case ae.Code == 401 || ae.Code == 404:
			return Result{Outcome: RetrySafe, FailKind: health.FailConfig, Err: "Bot Token 无效（" + desc + "）"}
		case ae.Code == 403:
			return Result{Outcome: RetrySafe, FailKind: health.FailConfig, Err: "无权向该聊天发送（是否已对 Bot 点击 Start？）"}
		case ae.Code == 400 && strings.Contains(strings.ToLower(ae.Description), "chat not found"):
			return Result{Outcome: RetrySafe, FailKind: health.FailConfig, Err: "找不到投递目标聊天（请先对 Bot 点击 Start）"}
		case ae.Code == 400:
			return Result{Outcome: Permanent, FailKind: health.FailOther, Err: "Telegram 拒绝该消息（" + desc + "）"}
		case ae.Code >= 500:
			// The request reached Telegram; it may or may not have been processed.
			return Result{Outcome: RetryUncertain, FailKind: health.FailOther, Err: fmt.Sprintf("Telegram 服务端错误 %d", ae.Code)}
		}
		return Result{Outcome: RetrySafe, FailKind: health.FailOther, Err: fmt.Sprintf("Telegram 错误 %d（%s）", ae.Code, desc)}
	}
	return Result{Outcome: RetryUncertain, FailKind: health.FailOther, Err: shorten(err.Error())}
}

func shorten(s string) string {
	r := []rune(s)
	if len(r) > 120 {
		return string(r[:120]) + "…"
	}
	return s
}
