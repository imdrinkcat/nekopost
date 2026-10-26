package health

import (
	"fmt"
	"time"

	"msggw/internal/model"
	"msggw/internal/store"
	"msggw/internal/sysinfo"
)

// Level is a component state, ordered by severity (except Unknown, which is
// reported but never escalates the overall state on its own).
type Level int

const (
	OK Level = iota
	Unknown
	Degraded
	ConfigError
	Down
)

// Emoji returns a coloured indicator.
func (l Level) Emoji() string {
	return [...]string{"🟢", "⚪", "🟡", "🟠", "🔴"}[l]
}

// Label returns the Chinese label.
func (l Level) Label() string {
	return [...]string{"正常", "无法确认", "部分异常", "配置错误", "不可用"}[l]
}

// String returns a machine-readable name.
func (l Level) String() string {
	return [...]string{"ok", "unknown", "degraded", "config_error", "down"}[l]
}

// MarshalText implements encoding.TextMarshaler for JSON output.
func (l Level) MarshalText() ([]byte, error) { return []byte(l.String()), nil }

// Component is the evaluated state of one part of the system.
type Component struct {
	Key     string   `json:"key"`
	Name    string   `json:"name"`
	Level   Level    `json:"level"`
	Summary string   `json:"summary"`
	Details []string `json:"details,omitempty"`
}

// Report is the evaluated state of the whole gateway.
type Report struct {
	GeneratedAt time.Time   `json:"generated_at"`
	Overall     Level       `json:"overall"`
	OverallText string      `json:"overall_text"`
	Components  []Component `json:"components"`
}

// Component returns the component with the given key.
func (r Report) Component(key string) Component {
	for _, c := range r.Components {
		if c.Key == key {
			return c
		}
	}
	return Component{Key: key, Level: Unknown}
}

// CertInfo describes the current TLS certificate.
type CertInfo struct {
	NotAfter time.Time
	Err      string
}

// Inputs collects everything Evaluate looks at.
type Inputs struct {
	Now      time.Time
	State    State
	Queue    store.QueueInfo
	QueueErr error
	Sources  []model.Source
	Sys      *sysinfo.Snapshot
	DBErr    error
	TLSMode  string
	Cert     CertInfo
}

// Thresholds used by Evaluate.
const (
	WorkerStaleAfter = 5 * time.Minute
	PollStaleAfter   = 3 * time.Minute
	BacklogWarnAge   = 5 * time.Minute
)

// Evaluate derives component states from observations.
func Evaluate(in Inputs) Report {
	now := in.Now
	st := in.State
	r := Report{GeneratedAt: now}

	// Gateway process.
	gw := Component{Key: "gateway", Name: "消息网关服务", Level: OK,
		Summary: "运行中，已运行 " + Dur(now.Sub(st.StartedAt))}
	switch {
	case in.DBErr != nil:
		gw.Level, gw.Summary = Down, "数据库不可用"
	case st.WorkerBeat.IsZero() || now.Sub(st.WorkerBeat) > WorkerStaleAfter:
		gw.Level, gw.Summary = Down, "投递队列处理已停止响应"
	}
	r.Components = append(r.Components, gw)

	// Ingestion.
	in1 := Component{Key: "ingest", Name: "消息接入", Level: OK}
	if !st.Listening {
		in1.Level, in1.Summary = Down, "HTTP 服务未在监听"
		if st.ListenErr != "" {
			in1.Summary += "：" + st.ListenErr
		}
	} else {
		in1.Summary = "接口监听正常"
		if in.TLSMode == "autocert" {
			switch {
			case in.Cert.NotAfter.IsZero():
				in1.Level = Unknown
				in1.Summary = "接口监听正常，HTTPS 证书尚未取得"
				if in.Cert.Err != "" {
					in1.Details = append(in1.Details, "证书错误："+in.Cert.Err)
				}
			case now.After(in.Cert.NotAfter):
				in1.Level, in1.Summary = Down, "HTTPS 证书已过期"
			case in.Cert.NotAfter.Sub(now) < 7*24*time.Hour:
				in1.Level, in1.Summary = Degraded, "HTTPS 证书即将过期且未能续期"
			default:
				in1.Details = append(in1.Details, "证书有效期至 "+in.Cert.NotAfter.Format("2006-01-02"))
			}
		}
	}
	if st.LastIngest.IsZero() {
		in1.Details = append(in1.Details, "本次启动后尚无有效提交（外部可达性未经验证）")
	} else {
		in1.Details = append(in1.Details, "最近一次有效提交："+Ago(now, st.LastIngest))
	}
	if st.AuthFailures > 0 {
		in1.Details = append(in1.Details, fmt.Sprintf("本次启动后无效凭证请求 %d 次（最近 %s）", st.AuthFailures, Ago(now, st.LastAuthFailure)))
	}
	r.Components = append(r.Components, in1)

	// Telegram delivery.
	dv := Component{Key: "delivery", Name: "Telegram 转发"}
	lastFailed := !st.SendFail.IsZero() && st.SendFail.After(st.SendOK)
	switch {
	case st.SendOK.IsZero() && st.SendFail.IsZero():
		dv.Level, dv.Summary = Unknown, "尚无投递记录，无法确认"
	case lastFailed && st.SendFailKind == FailConfig:
		dv.Level, dv.Summary = ConfigError, "Telegram 拒绝投递："+st.SendFailMsg
	case lastFailed && st.SendFailKind == FailNetwork && st.SendConsecutiveFails >= 2:
		dv.Level, dv.Summary = Down, "Telegram 服务不可达"
	case lastFailed:
		dv.Level, dv.Summary = Degraded, "最近一次投递未成功："+st.SendFailMsg
	default:
		dv.Level, dv.Summary = OK, "最近一次投递成功（"+Ago(now, st.SendOK)+"）"
	}
	if !st.SendOK.IsZero() {
		dv.Details = append(dv.Details, "最近成功："+Ago(now, st.SendOK))
	}
	if !st.SendFail.IsZero() {
		dv.Details = append(dv.Details, "最近失败："+Ago(now, st.SendFail))
	}
	if in.QueueErr != nil {
		dv.Details = append(dv.Details, "待处理队列：无法读取")
	} else if in.Queue.InProgress > 0 {
		age := now.Sub(in.Queue.OldestPendingAt)
		dv.Details = append(dv.Details, fmt.Sprintf("待处理 %d 条（重试中 %d 条，最早 %s）", in.Queue.InProgress, in.Queue.Retrying, Ago(now, in.Queue.OldestPendingAt)))
		if age > BacklogWarnAge && dv.Level < Degraded {
			dv.Level = Degraded
			dv.Summary = fmt.Sprintf("有 %d 条消息积压未送达", in.Queue.InProgress)
		}
	} else {
		dv.Details = append(dv.Details, "待处理 0 条")
	}
	r.Components = append(r.Components, dv)

	// Management bot.
	bt := Component{Key: "bot", Name: "管理 Bot"}
	pollFailed := !st.PollFail.IsZero() && st.PollFail.After(st.PollOK)
	switch {
	case pollFailed && st.PollFailKind == FailConfig:
		bt.Level, bt.Summary = ConfigError, st.PollFailMsg
	case pollFailed && st.PollFailKind == FailNetwork:
		bt.Level, bt.Summary = Down, "无法连接 Telegram："+st.PollFailMsg
	case pollFailed:
		bt.Level, bt.Summary = Degraded, "最近一次轮询失败："+st.PollFailMsg
	case st.PollOK.IsZero():
		bt.Level, bt.Summary = Unknown, "尚未完成首次轮询"
	case now.Sub(st.PollOK) > PollStaleAfter:
		bt.Level, bt.Summary = Degraded, "超过 "+Dur(now.Sub(st.PollOK))+" 未与 Telegram 通信"
	default:
		bt.Level, bt.Summary = OK, "长轮询正常（最近 "+Ago(now, st.PollOK)+"）"
	}
	r.Components = append(r.Components, bt)

	// Sources.
	sc := Component{Key: "sources", Name: "消息来源", Level: OK}
	enabled := 0
	for _, s := range in.Sources {
		if s.Enabled {
			enabled++
		}
		if !s.LastAuthFailAt.IsZero() && now.Sub(s.LastAuthFailAt) < 24*time.Hour {
			sc.Level = Degraded
			sc.Details = append(sc.Details, s.DisplayName()+" 在 "+Ago(now, s.LastAuthFailAt)+" 使用了已吊销的凭证")
		}
	}
	switch {
	case len(in.Sources) == 0:
		sc.Level, sc.Summary = ConfigError, "尚未配置任何来源"
	case enabled == 0:
		sc.Level, sc.Summary = Degraded, fmt.Sprintf("全部 %d 个来源均已停用", len(in.Sources))
	default:
		sc.Summary = fmt.Sprintf("%d 个已启用 / 共 %d 个", enabled, len(in.Sources))
	}
	r.Components = append(r.Components, sc)

	// Server resources.
	sv := Component{Key: "server", Name: "服务器资源", Level: OK, Summary: "资源充足"}
	if in.Sys == nil {
		sv.Level, sv.Summary = Unknown, "未采集"
	} else {
		s := in.Sys
		var problems []string
		raise := func(l Level, msg string) {
			if l > sv.Level || sv.Level == Unknown {
				sv.Level = l
			}
			problems = append(problems, msg)
		}
		if s.MemOK {
			pct := float64(s.MemAvailable) / float64(s.MemTotal) * 100
			if pct < 5 {
				raise(Down, fmt.Sprintf("可用内存仅 %.0f%%", pct))
			} else if pct < 10 {
				raise(Degraded, fmt.Sprintf("可用内存偏低（%.0f%%）", pct))
			}
		} else {
			sv.Details = append(sv.Details, "内存信息不可用")
		}
		if s.DiskOK && s.DiskTotal > 0 {
			pct := float64(s.DiskAvail) / float64(s.DiskTotal) * 100
			if pct < 3 {
				raise(Down, fmt.Sprintf("磁盘剩余仅 %.0f%%", pct))
			} else if pct < 10 {
				raise(Degraded, fmt.Sprintf("磁盘剩余偏低（%.0f%%）", pct))
			}
		} else {
			sv.Details = append(sv.Details, "磁盘信息不可用")
		}
		if s.LoadOK && s.Load5 > float64(2*s.NumCPU) {
			raise(Degraded, fmt.Sprintf("系统负载较高（%.2f）", s.Load5))
		}
		if len(problems) > 0 {
			sv.Summary = problems[0]
			sv.Details = append(sv.Details, problems[1:]...)
		}
	}
	r.Components = append(r.Components, sv)

	unknown := false
	for _, c := range r.Components {
		if c.Level == Unknown {
			unknown = true
		} else if c.Level > r.Overall {
			r.Overall = c.Level
		}
	}
	switch r.Overall {
	case OK:
		r.OverallText = "正常运行"
		if unknown {
			r.OverallText = "正常运行（部分项目无法确认）"
		}
	case Degraded:
		r.OverallText = "部分功能异常"
	case ConfigError:
		r.OverallText = "存在配置错误"
	case Down:
		r.OverallText = "存在不可用的功能"
	}
	return r
}

// Dur formats a duration compactly in Chinese.
func Dur(d time.Duration) string {
	if d < 0 {
		d = 0
	}
	switch {
	case d < time.Minute:
		return fmt.Sprintf("%d 秒", int(d.Seconds()))
	case d < time.Hour:
		return fmt.Sprintf("%d 分钟", int(d.Minutes()))
	case d < 24*time.Hour:
		h := int(d.Hours())
		m := int(d.Minutes()) % 60
		if m == 0 {
			return fmt.Sprintf("%d 小时", h)
		}
		return fmt.Sprintf("%d 小时 %d 分钟", h, m)
	default:
		days := int(d.Hours()) / 24
		h := int(d.Hours()) % 24
		if h == 0 {
			return fmt.Sprintf("%d 天", days)
		}
		return fmt.Sprintf("%d 天 %d 小时", days, h)
	}
}

// Ago formats how long ago t was, or "从未" for the zero time.
func Ago(now, t time.Time) string {
	if t.IsZero() {
		return "从未"
	}
	d := now.Sub(t)
	if d < 5*time.Second {
		return "刚刚"
	}
	if d < 0 {
		return "刚刚"
	}
	return Dur(d) + "前"
}
