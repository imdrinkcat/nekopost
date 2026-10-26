package bot

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"time"

	"nekopost/internal/health"
	"nekopost/internal/model"
	"nekopost/internal/status"
	"nekopost/internal/telegram"
)

type view struct {
	text string
	rows [][]telegram.InlineKeyboardButton
}

func (v view) keyboard() *telegram.InlineKeyboardMarkup {
	if len(v.rows) == 0 {
		return nil
	}
	return &telegram.InlineKeyboardMarkup{InlineKeyboard: v.rows}
}

func btn(text, data string) telegram.InlineKeyboardButton {
	return telegram.InlineKeyboardButton{Text: text, CallbackData: data}
}

func navRow(refresh string) []telegram.InlineKeyboardButton {
	return []telegram.InlineKeyboardButton{btn("🔄 刷新", refresh), btn("⬅️ 主菜单", "m")}
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }

func parseInt(s string) (int64, error) { return strconv.ParseInt(s, 10, 64) }

// footer stamps every view with the time its data was read.
func (b *Bot) footer() string {
	return "\n\n<i>🕒 数据更新于 " + b.now().In(b.Location).Format("15:04:05") + "（" + e(b.Location.String()) + "）</i>"
}

func (b *Bot) fmtTime(t time.Time) string {
	if t.IsZero() {
		return "—"
	}
	return t.In(b.Location).Format("2006-01-02 15:04:05")
}

func (b *Bot) fmtShort(t time.Time) string {
	if t.IsZero() {
		return "—"
	}
	lt, now := t.In(b.Location), b.now().In(b.Location)
	if lt.Year() == now.Year() && lt.YearDay() == now.YearDay() {
		return lt.Format("15:04")
	}
	return lt.Format("01-02 15:04")
}

func (b *Bot) ago(t time.Time) string { return health.Ago(b.now(), t) }

func bytesHuman(n uint64) string {
	const (
		mb = 1 << 20
		gb = 1 << 30
	)
	switch {
	case n >= gb:
		return fmt.Sprintf("%.1f GB", float64(n)/gb)
	case n >= mb:
		return fmt.Sprintf("%.0f MB", float64(n)/mb)
	default:
		return fmt.Sprintf("%d KB", n>>10)
	}
}

func (b *Bot) sourceLabel(ctx context.Context, id string) string {
	if src, err := b.Store.GetSource(ctx, id); err == nil {
		return src.DisplayName()
	}
	return "🗑 " + id + "（已删除）"
}

func deliveryIcon(st model.DeliveryState) string {
	switch st {
	case model.DeliveryDelivered:
		return "✅"
	case model.DeliveryRetrying, model.DeliveryPending:
		return "🔁"
	case model.DeliverySending:
		return "📤"
	case model.DeliveryUncertain:
		return "❔"
	}
	return "❌"
}

func deliveryLabel(st model.DeliveryState) string {
	switch st {
	case model.DeliveryDelivered:
		return "已送达"
	case model.DeliveryRetrying:
		return "重试中"
	case model.DeliveryPending:
		return "等待投递"
	case model.DeliverySending:
		return "发送中"
	case model.DeliveryUncertain:
		return "结果不确定"
	}
	return "失败"
}

func (b *Bot) viewMain(ctx context.Context) view {
	rep := b.Status.Report(ctx)
	text := "<b>📋 消息网关 · 主菜单</b>\n" +
		"整体状态：" + rep.Overall.Emoji() + " " + e(rep.OverallText) + "\n\n选择要查看的内容："
	last := []telegram.InlineKeyboardButton{btn("❓ 帮助", "h")}
	if b.Settings.Filter.Enabled {
		last = append([]telegram.InlineKeyboardButton{btn("🗑 已过滤", "fl:0")}, last...)
	}
	return view{
		text: text + b.footer(),
		rows: [][]telegram.InlineKeyboardButton{
			{btn("🏠 系统总览", "o"), btn("🤖 机器人状态", "b")},
			{btn("🖥 服务器状态", "sv"), btn("📨 消息来源", "src")},
			{btn("📊 消息统计", "st:today"), btn("⚙️ 系统设置", "set")},
			last,
		},
	}
}

// filterLine summarises the content filter's recent behaviour.
func (b *Bot) filterLine() string {
	st := b.Health.Snapshot()
	switch {
	case !st.FilterFail.IsZero() && st.FilterFail.After(st.FilterOK) && b.now().Sub(st.FilterFail) < time.Hour:
		return "🟡 短信过滤：最近调用失败（" + e(st.FilterFailMsg) + "），失败期间短信直接转发"
	case st.FilterOK.IsZero():
		return "⚪ 短信过滤：已启用，本次启动后尚未调用 DeepSeek"
	}
	return "🟢 短信过滤：正常（最近调用 " + e(b.ago(st.FilterOK)) + "）"
}

func (b *Bot) viewOverview(ctx context.Context) view {
	rep := b.Status.Report(ctx)
	var sb strings.Builder
	sb.WriteString("<b>🏠 系统总览</b>\n")
	sb.WriteString("整体：" + rep.Overall.Emoji() + " <b>" + e(rep.OverallText) + "</b>\n\n")
	for _, c := range rep.Components {
		sb.WriteString(c.Level.Emoji() + " " + e(c.Name) + "：" + e(c.Summary) + "\n")
	}
	if b.Settings.Filter.Enabled {
		sb.WriteString(b.filterLine() + "\n")
	}
	if res, err := b.Status.Stats(ctx, status.RangeToday); err == nil {
		t := res.Stats.Total
		fmt.Fprintf(&sb, "\n<b>今日</b>：接收 %d · 送达 %d · 失败 %d · 处理中 %d",
			t.Received, t.Delivered, t.Failed+t.Uncertain+t.Partial, res.Queue.InProgress)
		if t.Filtered > 0 {
			fmt.Fprintf(&sb, " · 已过滤 %d", t.Filtered)
		}
		sb.WriteString("\n")
		sb.WriteString("最近消息：" + e(b.ago(res.Stats.LastMessageAt)) + "\n")
	} else {
		sb.WriteString("\n今日统计：暂时无法读取\n")
	}
	if st := b.Health.Snapshot(); !st.PollOK.IsZero() && b.now().Sub(st.PollOK) > health.PollStaleAfter {
		sb.WriteString("\n⚠️ Bot 已有 " + e(health.Dur(b.now().Sub(st.PollOK))) + " 未与 Telegram 通信，以上信息可能不是最新状态。\n")
	}
	return view{text: sb.String() + b.footer(), rows: [][]telegram.InlineKeyboardButton{navRow("o")}}
}

// botHeadline combines the bot and delivery components into one of the
// headline states defined in the requirements.
func botHeadline(botC, dv health.Component) string {
	switch {
	case botC.Level == health.ConfigError || dv.Level == health.ConfigError:
		return "🟠 配置错误"
	case botC.Level == health.Down && dv.Level == health.Down:
		return "🔴 Telegram 服务不可达"
	case botC.Level >= health.Degraded || dv.Level >= health.Degraded:
		return "🟡 部分功能异常"
	case botC.Level == health.Unknown:
		return "⚪ 状态暂时无法确认"
	case dv.Level == health.Unknown:
		return "🟢 正常运行（尚无投递记录，转发功能未经验证）"
	}
	return "🟢 正常运行"
}

func (b *Bot) viewBot(ctx context.Context) view {
	rep := b.Status.Report(ctx)
	st := b.Health.Snapshot()
	botC, dv := rep.Component("bot"), rep.Component("delivery")
	var sb strings.Builder
	sb.WriteString("<b>🤖 机器人状态</b>\n")
	sb.WriteString("总体：" + botHeadline(botC, dv) + "\n\n")
	if st.Bot != nil {
		sb.WriteString("身份：@" + e(st.Bot.Username) + "（" + e(st.Bot.Name) + "，ID " + itoa(st.Bot.ID) + "）\n")
	} else {
		sb.WriteString("身份：未知（尚未成功调用 getMe）\n")
	}
	sb.WriteString(botC.Level.Emoji() + " 管理功能：" + e(botC.Summary) + "\n")
	sb.WriteString(dv.Level.Emoji() + " 消息转发：" + e(dv.Summary) + "\n")
	sb.WriteString("最近成功发送：" + e(b.fmtTime(st.SendOK)))
	if !st.SendOK.IsZero() {
		sb.WriteString("（" + e(b.ago(st.SendOK)) + "）")
	}
	sb.WriteString("\n最近发送失败：" + e(b.fmtTime(st.SendFail)))
	if !st.SendFail.IsZero() {
		sb.WriteString("（" + e(b.ago(st.SendFail)) + "）")
	}
	sb.WriteString("\n")

	if recent, err := b.Store.RecentDeliveries(ctx, 5); err == nil && len(recent) > 0 {
		sb.WriteString("\n<b>最近投递</b>\n")
		for _, r := range recent {
			line := deliveryIcon(r.State) + " " + b.fmtShort(r.At) + " " + e(b.sourceLabel(ctx, r.SourceID)) + " · " + deliveryLabel(r.State)
			if r.Attempts > 1 {
				line += fmt.Sprintf("（第 %d 次尝试）", r.Attempts)
			}
			sb.WriteString(line + "\n")
		}
	}

	var issues []string
	for _, c := range rep.Components {
		if c.Level != health.OK {
			issues = append(issues, c.Level.Emoji()+" "+e(c.Name)+"："+e(c.Summary))
		}
	}
	sb.WriteString("\n<b>已知问题</b>\n")
	if len(issues) == 0 {
		sb.WriteString("无\n")
	} else {
		sb.WriteString(strings.Join(issues, "\n") + "\n")
	}
	if errs := b.Health.RecentErrors(3); len(errs) > 0 {
		sb.WriteString("\n<b>最近错误</b>（本次启动后）\n")
		for _, ev := range errs {
			sb.WriteString("• " + b.fmtShort(ev.At) + " " + e(ev.Component) + "：" + e(ev.Msg) + "\n")
		}
	}
	if st.BotUnauthorized > 0 {
		fmt.Fprintf(&sb, "\n🔒 本次启动后有 %d 次未授权用户访问（最近 %s）\n", st.BotUnauthorized, e(b.ago(st.LastBotUnauthorized)))
	}
	return view{text: sb.String() + b.footer(), rows: [][]telegram.InlineKeyboardButton{navRow("b")}}
}

func (b *Bot) viewServer(ctx context.Context) view {
	rep := b.Status.Report(ctx)
	s := b.Status.System()
	srv := rep.Component("server")
	gw := rep.Component("gateway")
	na := "不可用"
	var sb strings.Builder
	sb.WriteString("<b>🖥 服务器状态</b>\n\n<b>宿主机</b>\n")
	sb.WriteString("运行状态：" + srv.Level.Emoji() + " " + e(srv.Level.Label()))
	if srv.Level != health.OK {
		sb.WriteString("（" + e(srv.Summary) + "）")
	}
	sb.WriteString("\n运行时间：")
	if s.HostUptimeOK {
		sb.WriteString(e(health.Dur(s.HostUptime)))
	} else {
		sb.WriteString(na)
	}
	sb.WriteString("\nCPU：")
	if s.CPUOK {
		fmt.Fprintf(&sb, "%.0f%%（%d 核）", s.CPUPercent, s.NumCPU)
	} else {
		sb.WriteString(na)
	}
	if s.LoadOK {
		fmt.Fprintf(&sb, "\n负载：%.2f / %.2f / %.2f", s.Load1, s.Load5, s.Load15)
	}
	sb.WriteString("\n内存：")
	if s.MemOK {
		sb.WriteString(bytesHuman(s.MemTotal-s.MemAvailable) + " / " + bytesHuman(s.MemTotal) + "（可用 " + bytesHuman(s.MemAvailable) + "）")
		if s.SwapTotal > 0 {
			sb.WriteString("\nSwap：" + bytesHuman(s.SwapTotal-s.SwapFree) + " / " + bytesHuman(s.SwapTotal))
		}
	} else {
		sb.WriteString(na)
	}
	sb.WriteString("\n磁盘：")
	if s.DiskOK {
		sb.WriteString(bytesHuman(s.DiskTotal-s.DiskFree) + " / " + bytesHuman(s.DiskTotal) + "（剩余 " + bytesHuman(s.DiskAvail) + "）")
	} else {
		sb.WriteString(na)
	}

	sb.WriteString("\n\n<b>消息网关服务</b>\n")
	sb.WriteString("状态：" + gw.Level.Emoji() + " " + e(gw.Summary) + "\n")
	sb.WriteString("启动时间：" + e(b.fmtTime(b.Status.StartedAt)) + "\n")
	sb.WriteString("版本：" + e(b.Version) + "\n")
	sb.WriteString("内存占用：")
	if s.ProcRSSOK {
		sb.WriteString(bytesHuman(s.ProcRSS))
	} else {
		sb.WriteString(na)
	}
	fmt.Fprintf(&sb, "\n累计启动次数：%d", b.Status.StartInfo.StartCount)
	if !b.Status.StartInfo.PreviousStart.IsZero() {
		sb.WriteString("\n上次启动：" + e(b.fmtTime(b.Status.StartInfo.PreviousStart)))
	}
	sb.WriteString("\n\n<i>CPU 为约 0.3 秒采样的瞬时值；不保存历史数据。</i>")
	return view{text: sb.String() + b.footer(), rows: [][]telegram.InlineKeyboardButton{navRow("sv")}}
}

func (b *Bot) sourceBlock(st status.SourceStatus) string {
	src := st.Source
	var sb strings.Builder
	sb.WriteString(e(src.Icon+" ") + "<b>" + e(src.Name) + "</b>  <code>" + e(src.ID) + "</code>\n")
	if src.Enabled {
		sb.WriteString("状态：✅ 已启用\n")
	} else {
		sb.WriteString("状态：⏸ 已停用\n")
	}
	sb.WriteString("最近消息：" + e(b.ago(st.Last.At)))
	if st.Last.Status != "" {
		sb.WriteString(" · " + e(st.Last.Status.Label()))
	}
	fmt.Fprintf(&sb, "\n今日：接收 %d · 失败 %d", st.Today.Received, st.Today.Failed+st.Today.Uncertain+st.Today.Partial)
	if st.Today.InProgress > 0 {
		fmt.Fprintf(&sb, " · 处理中 %d", st.Today.InProgress)
	}
	sb.WriteString("\n")
	if !src.LastAuthFailAt.IsZero() && b.now().Sub(src.LastAuthFailAt) < 24*time.Hour {
		sb.WriteString("⚠️ " + e(b.ago(src.LastAuthFailAt)) + "有请求使用了该来源已吊销的凭证\n")
	}
	return sb.String()
}

const activityNote = "\n<i>“最近消息”仅表示最近一次收到该来源的消息。来源没有心跳或主动健康检查，长时间没有消息不代表故障。</i>"

func (b *Bot) viewSources(ctx context.Context) view {
	list, err := b.Status.Sources(ctx)
	if err != nil {
		return view{text: "<b>📨 消息来源</b>\n\n暂时无法读取来源信息，请稍后重试。" + b.footer(),
			rows: [][]telegram.InlineKeyboardButton{navRow("src")}}
	}
	var sb strings.Builder
	fmt.Fprintf(&sb, "<b>📨 消息来源</b>（%d 个）\n", len(list))
	if len(list) == 0 {
		sb.WriteString("\n尚未配置任何来源。请在服务器上执行：\n<code>nekopost source add iphone --name iPhone --icon 📱</code>\n")
	}
	var rows [][]telegram.InlineKeyboardButton
	var row []telegram.InlineKeyboardButton
	for _, st := range list {
		sb.WriteString("\n" + b.sourceBlock(st))
		row = append(row, btn(st.Source.DisplayName(), "s:"+st.Source.ID))
		if len(row) == 2 {
			rows = append(rows, row)
			row = nil
		}
	}
	if len(row) > 0 {
		rows = append(rows, row)
	}
	if len(list) > 0 {
		sb.WriteString(activityNote)
	}
	rows = append(rows, navRow("src"))
	return view{text: sb.String() + b.footer(), rows: rows}
}

func (b *Bot) viewSource(ctx context.Context, id string) view {
	back := []telegram.InlineKeyboardButton{btn("⬅️ 来源列表", "src"), btn("🏠 主菜单", "m")}
	st, err := b.Status.Source(ctx, id)
	if err != nil {
		return view{text: "该来源不存在或已被删除。" + b.footer(), rows: [][]telegram.InlineKeyboardButton{back}}
	}
	src := st.Source
	var sb strings.Builder
	sb.WriteString("<b>📨 来源详情</b>\n\n")
	sb.WriteString(b.sourceBlock(st))
	sb.WriteString("最近请求：" + e(b.ago(src.LastSeenAt)) + "\n")
	sb.WriteString("创建时间：" + e(b.fmtTime(src.CreatedAt)) + "\n")
	if src.AuthFailCount > 0 {
		fmt.Fprintf(&sb, "已吊销凭证被使用：%d 次（最近 %s）\n", src.AuthFailCount, e(b.ago(src.LastAuthFailAt)))
	}
	sb.WriteString(activityNote)
	toggle := btn("⏸ 停用此来源", "sa:off:"+src.ID)
	if !src.Enabled {
		toggle = btn("▶️ 启用此来源", "sa:on:"+src.ID)
	}
	return view{text: sb.String() + b.footer(), rows: [][]telegram.InlineKeyboardButton{
		{toggle},
		{btn("🔄 刷新", "s:"+src.ID)},
		back,
	}}
}

func (b *Bot) viewConfirmToggle(ctx context.Context, id string, enable bool) view {
	src, err := b.Store.GetSource(ctx, id)
	if err != nil {
		return b.viewSources(ctx)
	}
	ts := itoa(b.now().Unix())
	var text, label, data string
	if enable {
		text = "确定要<b>启用</b> " + e(src.DisplayName()) + " 吗？\n\n启用后，该来源可以重新提交消息。"
		label, data = "✅ 确认启用", "sc:on:"+src.ID+":"+ts
	} else {
		text = "确定要<b>停用</b> " + e(src.DisplayName()) + " 吗？\n\n" +
			"停用后，该来源提交的新消息会被拒绝（HTTP 403），设备端会收到失败提示；已在队列中的消息仍会继续投递。\n可随时重新启用。"
		label, data = "⏸ 确认停用", "sc:off:"+src.ID+":"+ts
	}
	text += "\n\n<i>此确认 5 分钟内有效。</i>"
	return view{text: text, rows: [][]telegram.InlineKeyboardButton{
		{btn(label, data), btn("✖️ 取消", "s:"+src.ID)},
	}}
}

func (b *Bot) viewStats(ctx context.Context, r status.Range) view {
	var rangeRow []telegram.InlineKeyboardButton
	for _, x := range status.Ranges {
		label := map[status.Range]string{status.RangeToday: "今天", status.Range24h: "24 小时", status.Range7d: "7 天", status.RangeStart: "启动以来"}[x]
		if x == r {
			label = "• " + label + " •"
		}
		rangeRow = append(rangeRow, btn(label, "st:"+string(x)))
	}
	rows := [][]telegram.InlineKeyboardButton{rangeRow, navRow("st:" + string(r))}
	res, err := b.Status.Stats(ctx, r)
	if err != nil {
		return view{text: "<b>📊 消息统计</b>\n\n暂时无法读取统计数据，请稍后重试。" + b.footer(), rows: rows}
	}
	t := res.Stats.Total
	var sb strings.Builder
	sb.WriteString("<b>📊 消息统计 · " + e(r.Label()) + "</b>\n")
	sb.WriteString("统计区间：" + e(b.fmtShort(res.Stats.From)) + " 起\n\n")
	fmt.Fprintf(&sb, "📥 接收：%d 条\n", t.Received)
	fmt.Fprintf(&sb, "✅ 成功送达：%d\n", t.Delivered)
	fmt.Fprintf(&sb, "❌ 投递失败：%d\n", t.Failed)
	if t.Uncertain > 0 || t.Partial > 0 {
		fmt.Fprintf(&sb, "❔ 结果不确定：%d", t.Uncertain)
		if t.Partial > 0 {
			fmt.Fprintf(&sb, " · 部分送达：%d", t.Partial)
		}
		sb.WriteString("\n")
	}
	if t.NotRouted > 0 {
		fmt.Fprintf(&sb, "🚫 未转发：%d\n", t.NotRouted)
	}
	if t.Filtered > 0 {
		fmt.Fprintf(&sb, "🗑 已过滤（未推送）：%d\n", t.Filtered)
	}
	fmt.Fprintf(&sb, "⏳ 处理中：%d（当前队列共 %d）\n", t.InProgress, res.Queue.InProgress)
	if t.Duplicates > 0 {
		fmt.Fprintf(&sb, "♻️ 重复提交（已忽略）：%d 次\n", t.Duplicates)
	}
	sb.WriteString("最近消息：" + e(b.ago(res.Stats.LastMessageAt)) + "\n")
	if len(res.Stats.BySource) > 0 {
		sb.WriteString("\n<b>按来源</b>\n")
		for _, sc := range res.Stats.BySource {
			c := sc.Counts
			fmt.Fprintf(&sb, "%s：接收 %d · 送达 %d · 失败 %d\n", e(b.sourceLabel(ctx, sc.SourceID)),
				c.Received, c.Delivered, c.Failed+c.Uncertain+c.Partial)
		}
	}
	sb.WriteString("\n<i>“接收”指服务器已接受的消息，“送达”指 Telegram 已确认收到；每条消息只计一次。</i>")
	if res.Note != "" {
		sb.WriteString("\n<i>" + e(res.Note) + "</i>")
	}
	return view{text: sb.String() + b.footer(), rows: rows}
}

func maskID(id int64) string {
	s := itoa(id)
	if len(s) <= 4 {
		return s
	}
	return strings.Repeat("•", len(s)-4) + s[len(s)-4:]
}

func (b *Bot) viewSettings(ctx context.Context) view {
	cfg := b.Settings
	var sb strings.Builder
	sb.WriteString("<b>⚙️ 系统设置</b>\n\n")
	sb.WriteString("版本：" + e(b.Version) + "\n")
	if cfg.TLSMode == "autocert" {
		sb.WriteString("接入地址：<code>https://" + e(cfg.Domain) + "/v1/messages</code>\n")
		if ci := b.Status.Cert; ci != nil {
			if c := ci(); !c.NotAfter.IsZero() {
				sb.WriteString("HTTPS 证书：有效期至 " + e(c.NotAfter.In(b.Location).Format("2006-01-02")) + "（自动续期）\n")
			} else {
				sb.WriteString("HTTPS 证书：尚未取得\n")
			}
		}
	} else {
		sb.WriteString("接入地址：未启用 HTTPS（TLS 模式 off）\n")
	}
	sb.WriteString("时区：" + e(cfg.Timezone) + "\n")
	sb.WriteString("投递目标：Telegram 聊天 " + maskID(cfg.ChatID) + "\n")
	sb.WriteString("投递策略：至少一次；失败自动重试，最长 " + e(health.Dur(cfg.DeliveryMaxAge)) + "\n")
	if cfg.DedupeWindow > 0 {
		sb.WriteString("去重：相同幂等键不重复转发；无幂等键时 " + e(health.Dur(cfg.DedupeWindow)) + "内相同内容视为重复\n")
	} else {
		sb.WriteString("去重：仅按幂等键\n")
	}
	fmt.Fprintf(&sb, "来源限流：每个来源 %d 条/分钟\n", cfg.RatePerMin)
	fmt.Fprintf(&sb, "正文长度上限：%d 字符\n", cfg.MaxBodyChars)
	if cfg.Filter.Enabled {
		fmt.Fprintf(&sb, "隐私：正文在投递结束后立即删除，被过滤的短信正文保留 %d 天；元数据保留 %d 天\n", cfg.Filter.KeepDays, cfg.RetentionDays)
	} else {
		fmt.Fprintf(&sb, "隐私：正文在投递结束后立即删除；元数据保留 %d 天\n", cfg.RetentionDays)
	}
	fmt.Fprintf(&sb, "管理员：%d 人\n", cfg.AdminCount)
	if srcs, err := b.Store.ListSources(ctx); err == nil {
		enabled := 0
		for _, s := range srcs {
			if s.Enabled {
				enabled++
			}
		}
		fmt.Fprintf(&sb, "消息来源：%d 个已启用 / 共 %d 个\n", enabled, len(srcs))
	}
	sb.WriteString("\n<b>短信过滤</b>\n")
	if cfg.Filter.Enabled {
		var drop []string
		for _, c := range cfg.Filter.Drop {
			drop = append(drop, c.Label())
		}
		sb.WriteString("状态：已启用（DeepSeek <code>" + e(cfg.Filter.Model) + "</code>）\n")
		sb.WriteString("处理的类型：<code>" + e(strings.Join(cfg.Filter.Types, ", ")) + "</code>\n")
		sb.WriteString("不推送的类别：" + e(strings.Join(drop, "、")) + "\n")
		sb.WriteString("验证码类短信在本地识别，不发送给 DeepSeek；其余短信遮盖数字和链接后发送\n")
		sb.WriteString("DeepSeek 不可用时短信直接转发，不会丢失\n")
	} else {
		sb.WriteString("未启用（设置 <code>NEKOPOST_DEEPSEEK_API_KEY</code> 后启用）\n")
	}
	sb.WriteString("\n<b>可执行的操作</b>\n")
	sb.WriteString("• 在「消息来源」中停用 / 启用某个来源（需二次确认）\n")
	if cfg.Filter.Enabled {
		sb.WriteString("• 在「已过滤」中把误判的短信转发出来\n")
	}
	sb.WriteString("• 新增来源、轮换凭证、修改配置：在服务器上使用 <code>nekopost</code> 命令行（见文档）\n")
	return view{text: sb.String() + b.footer(), rows: [][]telegram.InlineKeyboardButton{
		{btn("📨 管理来源", "src")},
		navRow("set"),
	}}
}

func (b *Bot) viewHelp() view {
	text := "<b>❓ 帮助</b>\n\n" +
		"这个 Bot 是你的个人消息网关的通知出口和管理入口。\n\n" +
		"<b>菜单功能</b>\n" +
		"🏠 系统总览：各组件状态和今日概况\n" +
		"🤖 机器人状态：Bot 身份、与 Telegram 的通信、最近投递和错误\n" +
		"🖥 服务器状态：宿主机 CPU / 内存 / 磁盘与网关服务自身状态\n" +
		"📨 消息来源：各来源的启用状态与最近活动，可停用 / 启用\n" +
		"📊 消息统计：今天、24 小时、7 天、启动以来的收发数量\n" +
		"⚙️ 系统设置：当前配置概况\n" +
		"🗑 已过滤：被判定为广告 / 垃圾的短信（启用短信过滤时），可把误判的转发出来\n\n" +
		"<b>命令</b>\n" +
		"/menu 主菜单 · /status 总览 · /bot 机器人 · /server 服务器\n" +
		"/sources 来源 · /stats 统计 · /filtered 已过滤 · /settings 设置 · /help 帮助\n\n" +
		"<b>状态图例</b>\n" +
		"🟢 正常　🟡 部分异常　🟠 配置错误　🔴 不可用　⚪ 无法确认\n\n" +
		"<b>说明</b>\n" +
		"• 每个页面底部显示数据的更新时间，点击「🔄 刷新」获取最新数据。\n" +
		"• 除「已过滤」页面外，管理菜单不会显示消息正文。\n" +
		"• 转发的消息可能包含验证码等敏感内容，请勿把 Bot 加入群组，也不要转发给他人。\n" +
		"• 只有配置为管理员的 Telegram 账号可以使用菜单。"
	return view{text: text, rows: [][]telegram.InlineKeyboardButton{{btn("⬅️ 主菜单", "m")}}}
}

const filteredPage = 8

func oneLine(s string, n int) string {
	s = strings.Join(strings.Fields(s), " ")
	r := []rune(s)
	if len(r) > n {
		return string(r[:n]) + "…"
	}
	return s
}

func (b *Bot) viewFiltered(ctx context.Context, offset int) view {
	list, total, err := b.Store.FilteredMessages(ctx, offset, filteredPage)
	if err == nil && len(list) == 0 && offset > 0 {
		offset = 0 // the page emptied (e.g. after forwarding): go back to the start
		list, total, err = b.Store.FilteredMessages(ctx, offset, filteredPage)
	}
	refresh := "fl:" + strconv.Itoa(offset)
	if err != nil {
		return view{text: "<b>🗑 已过滤的短信</b>\n\n暂时无法读取，请稍后重试。" + b.footer(),
			rows: [][]telegram.InlineKeyboardButton{navRow(refresh)}}
	}
	var sb strings.Builder
	fmt.Fprintf(&sb, "<b>🗑 已过滤的短信</b>（共 %d 条）\n", total)
	keep := b.Settings.Filter.KeepDays
	fmt.Fprintf(&sb, "<i>被判定为广告、垃圾或漫游提醒的短信不会推送，内容保留 %d 天。误判的可以点下方编号转发。</i>\n", keep)
	if !b.Settings.Filter.Enabled {
		sb.WriteString("\n短信过滤当前未启用。\n")
	}
	if total == 0 {
		sb.WriteString("\n暂无被过滤的短信。\n")
	}
	var rows [][]telegram.InlineKeyboardButton
	var row []telegram.InlineKeyboardButton
	for i, f := range list {
		n := strconv.Itoa(offset + i + 1)
		fmt.Fprintf(&sb, "\n<b>%s.</b> %s · %s · %s\n", n, e(b.fmtShort(f.ReceivedAt)), e(b.sourceLabel(ctx, f.SourceID)), e(f.Category.Label()))
		if f.Title != "" {
			sb.WriteString("<b>" + e(oneLine(f.Title, 40)) + "</b> ")
		}
		sb.WriteString(e(oneLine(f.Body, 120)) + "\n")
		row = append(row, btn("↩️ 转发 "+n, "fr:"+f.ID+":"+strconv.Itoa(offset)))
		if len(row) == 4 {
			rows = append(rows, row)
			row = nil
		}
	}
	if len(row) > 0 {
		rows = append(rows, row)
	}
	var paging []telegram.InlineKeyboardButton
	if offset > 0 {
		paging = append(paging, btn("⬅️ 上一页", "fl:"+strconv.Itoa(max(0, offset-filteredPage))))
	}
	if offset+len(list) < total {
		paging = append(paging, btn("下一页 ➡️", "fl:"+strconv.Itoa(offset+filteredPage)))
	}
	if len(paging) > 0 {
		rows = append(rows, paging)
	}
	rows = append(rows, navRow(refresh))
	return view{text: sb.String() + b.footer(), rows: rows}
}
