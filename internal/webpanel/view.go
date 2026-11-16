package webpanel

import (
	"strings"
	"unicode"

	"nekopost/internal/health"
	"nekopost/internal/model"
)

// badge is the presentation of a state: a tone (colour role), an icon from
// the sprite in panel.html and a text label. Colour never carries meaning on
// its own, so every badge always renders all three.
type badge struct{ Tone, Icon, Label string }

var viewFuncs = map[string]any{
	"statusBadge":   statusBadge,
	"deliveryBadge": deliveryBadge,
	"levelBadge":    levelBadge,
	"categoryIcon":  categoryIcon,
	"resultBadge":   resultBadge,
	"sessionBadge":  sessionBadge,
	"kindLabel":     kindLabel,
	"channelLabel":  channelLabel,
	"initial":       initial,
	"digits":        digits,
}

// digits spaces out a code so screen readers announce it one digit at a time
// (§9.6), for example "74" becomes "7 4".
func digits(code string) string {
	return strings.Join(strings.Split(code, ""), " ")
}

func statusBadge(s model.Status) badge {
	b := badge{Label: s.Label()}
	switch s {
	case model.StatusDelivered:
		b.Tone, b.Icon = "ok", "i-paw-check"
	case model.StatusQueued, model.StatusSending, model.StatusClassifying:
		b.Tone, b.Icon = "info", "i-mail"
	case model.StatusRetrying, model.StatusPartial:
		b.Tone, b.Icon = "warn", "i-retry"
	case model.StatusUncertain:
		b.Tone, b.Icon = "unsure", "i-mail-question"
	case model.StatusFailed:
		b.Tone, b.Icon = "err", "i-mail-x"
	case model.StatusFiltered:
		b.Tone, b.Icon = "neutral", "i-bin"
	default:
		b.Tone, b.Icon = "neutral", "i-dash"
	}
	return b
}

func deliveryBadge(d model.DeliveryState) badge {
	switch d {
	case model.DeliveryDelivered:
		return badge{"ok", "i-paw-check", "已送达"}
	case model.DeliveryPending:
		return badge{"info", "i-mail", "等待投递"}
	case model.DeliverySending:
		return badge{"info", "i-mail", "正在投递"}
	case model.DeliveryHeld:
		return badge{"info", "i-mail", "等待识别"}
	case model.DeliveryRetrying:
		return badge{"warn", "i-retry", "正在重试"}
	case model.DeliveryUncertain:
		return badge{"unsure", "i-mail-question", "结果不确定"}
	case model.DeliveryFailed:
		return badge{"err", "i-mail-x", "投递失败"}
	case model.DeliveryFiltered:
		return badge{"neutral", "i-bin", "已过滤"}
	}
	return badge{"neutral", "i-dash", string(d)}
}

func levelBadge(l health.Level) badge {
	b := badge{Label: l.Label()}
	switch l {
	case health.OK:
		b.Tone, b.Icon = "ok", "i-check"
	case health.Unknown:
		b.Tone, b.Icon = "unsure", "i-question"
	case health.Degraded:
		b.Tone, b.Icon = "warn", "i-warn"
	default:
		b.Tone, b.Icon = "err", "i-x"
	}
	return b
}

func categoryIcon(c model.Category) string {
	switch c {
	case model.CatSpam:
		return "i-warn"
	case model.CatCode:
		return "i-key"
	}
	return "i-tag"
}

func resultBadge(r string) badge {
	switch r {
	case "ok":
		return badge{"ok", "i-check", "成功"}
	case "denied":
		return badge{"err", "i-x", "已拒绝"}
	}
	return badge{"neutral", "i-dash", r}
}

func sessionBadge(state string) badge {
	switch state {
	case "active":
		return badge{"ok", "i-check", "已登录"}
	case "pending":
		return badge{"info", "i-clock", "等待确认"}
	}
	return badge{"neutral", "i-dash", state}
}

func kindLabel(kind string) string {
	switch kind {
	case "sms":
		return "短信"
	case "notification":
		return "通知"
	case "call":
		return "来电"
	case "email":
		return "邮件"
	}
	return kind
}

func channelLabel(channel string) string {
	switch channel {
	case "telegram":
		return "Telegram"
	case "fcm":
		return "手机推送"
	}
	return channel
}

// initial is the avatar letter for a source or device name.
func initial(s string) string {
	for _, r := range strings.TrimSpace(s) {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			return strings.ToUpper(string(r))
		}
	}
	return "#"
}
