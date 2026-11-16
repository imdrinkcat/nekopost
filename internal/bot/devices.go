package bot

import (
	"context"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"

	"nekopost/internal/telegram"
)

var botDeviceID = regexp.MustCompile(`^d_[A-Z2-7]{26}$`)

func (b *Bot) viewDevices(ctx context.Context) view {
	list, err := b.Store.ListDevices(ctx)
	if err != nil {
		return view{text: "设备列表暂时不可用", rows: [][]telegram.InlineKeyboardButton{navRow("dv")}}
	}
	lines := []string{"<b>📱 配对设备</b>"}
	var rows [][]telegram.InlineKeyboardButton
	for _, d := range list {
		if d.Revoked {
			continue
		}
		state := "启用"
		if d.Suspended {
			state = "暂停读取"
		}
		lines = append(lines, "<code>"+e(d.ID)+"</code> · "+state)
		rows = append(rows, []telegram.InlineKeyboardButton{btn("吊销 "+d.ID[len(d.ID)-6:], "dva:"+d.ID)})
		if len(rows) >= 12 {
			break
		}
	}
	if len(rows) == 0 {
		lines = append(lines, "暂无配对设备")
	}
	rows = append(rows, navRow("dv"))
	return view{text: strings.Join(lines, "\n"), rows: rows}
}

func (b *Bot) viewLockdown() view {
	wire := strconv.FormatInt(b.now().Unix(), 10)
	return view{text: "<b>🚨 紧急锁定</b>\n网页会话将退出，设备暂停读取，最近配对的设备将吊销。短信转发仍可工作。仅可在服务器解除。",
		rows: [][]telegram.InlineKeyboardButton{{btn("确认紧急锁定", "ldc:"+wire), btn("取消", "m")}}}
}

func (b *Bot) deviceAction(ctx context.Context, user int64, parts []string) (view, string, bool) {
	actor := fmt.Sprintf("bot:tg:%d", user)
	validTime := func(raw string) bool {
		seconds, err := strconv.ParseInt(raw, 10, 64)
		age := b.now().Sub(time.Unix(seconds, 0))
		return err == nil && age >= 0 && age < 5*time.Minute
	}
	switch parts[0] {
	case "dv":
		return b.viewDevices(ctx), "", true
	case "ld":
		return b.viewLockdown(), "", true
	case "ldc":
		if len(parts) != 2 || !validTime(parts[1]) {
			return b.viewLockdown(), "确认已过期", true
		}
		if err := b.Store.Lockdown(ctx, actor, b.now(), false, false, ""); err != nil {
			return b.viewMain(ctx), "锁定失败", true
		}
		b.NotifyWebSecurity(ctx, "紧急锁定已启用")
		return b.viewMain(ctx), "紧急锁定已启用", true
	case "dva":
		if len(parts) != 2 || !botDeviceID.MatchString(parts[1]) {
			return b.viewDevices(ctx), "设备无效", true
		}
		wire := strconv.FormatInt(b.now().Unix(), 10)
		return view{text: "确认吊销此设备？设备需要重新配对。",
			rows: [][]telegram.InlineKeyboardButton{{btn("确认吊销", "dvc:"+parts[1]+":"+wire), btn("取消", "dv")}}}, "", true
	case "dvc":
		if len(parts) != 3 || !botDeviceID.MatchString(parts[1]) || !validTime(parts[2]) {
			return b.viewDevices(ctx), "确认已失效", true
		}
		if err := b.Store.RevokeDevice(ctx, parts[1], actor, b.now()); err != nil {
			return b.viewDevices(ctx), "设备不可用", true
		}
		b.NotifyWebSecurity(ctx, "已吊销一台设备")
		return b.viewDevices(ctx), "已吊销", true
	default:
		return view{}, "", false
	}
}
