package health

import (
	"errors"
	"strings"
	"testing"
	"time"

	"nekopost/internal/model"
	"nekopost/internal/store"
	"nekopost/internal/sysinfo"
)

var now = time.Date(2026, 10, 25, 12, 0, 0, 0, time.UTC)

func healthySys() *sysinfo.Snapshot {
	return &sysinfo.Snapshot{MemOK: true, MemTotal: 1000, MemAvailable: 500, DiskOK: true, DiskTotal: 1000, DiskAvail: 500,
		LoadOK: true, Load5: 0.1, NumCPU: 1}
}

func healthy() Inputs {
	return Inputs{
		Now: now,
		State: State{StartedAt: now.Add(-time.Hour), WorkerBeat: now, Listening: true,
			SendOK: now.Add(-time.Minute), PollOK: now.Add(-10 * time.Second)},
		Sources: []model.Source{{ID: "iphone", Name: "iPhone", Enabled: true}},
		Sys:     healthySys(),
		TLSMode: "autocert",
		Cert:    CertInfo{NotAfter: now.Add(60 * 24 * time.Hour)},
	}
}

func TestAllHealthy(t *testing.T) {
	r := Evaluate(healthy())
	if r.Overall != OK || r.OverallText != "正常运行" {
		t.Fatalf("%+v", r)
	}
	for _, c := range r.Components {
		if c.Level != OK {
			t.Errorf("%s: %v %s", c.Key, c.Level, c.Summary)
		}
	}
}

// Requirement: "消息接入正常，但 Telegram 暂时不可用"
func TestIngestOKTelegramDown(t *testing.T) {
	in := healthy()
	in.State.SendFail = now
	in.State.SendFailKind = FailNetwork
	in.State.SendConsecutiveFails = 3
	in.State.PollFail = now
	in.State.PollFailKind = FailNetwork
	in.Queue = store.QueueInfo{InProgress: 2, Retrying: 2, OldestPendingAt: now.Add(-10 * time.Minute)}
	r := Evaluate(in)
	if r.Component("ingest").Level != OK {
		t.Error("ingest is unaffected by Telegram outages")
	}
	if c := r.Component("delivery"); c.Level != Down || !strings.Contains(c.Summary, "不可达") {
		t.Errorf("delivery %+v", c)
	}
	if r.Component("bot").Level != Down || r.Overall != Down {
		t.Error("bot and overall should be down")
	}
	if !strings.Contains(strings.Join(r.Component("delivery").Details, " "), "待处理 2 条") {
		t.Error("backlog should be reported")
	}
}

// Requirement: "Telegram 通知正常，但机器人管理功能异常"
func TestDeliveryOKBotBroken(t *testing.T) {
	in := healthy()
	in.State.PollFail = now
	in.State.PollFailKind = FailConfig
	in.State.PollFailMsg = "冲突：另一个程序正在使用同一个 Bot Token 接收消息"
	r := Evaluate(in)
	if r.Component("delivery").Level != OK || r.Component("bot").Level != ConfigError || r.Overall != ConfigError {
		t.Errorf("%+v", r)
	}
}

// Requirement: "某个消息来源认证失败，但其他来源正常"
func TestSourceAuthFailure(t *testing.T) {
	in := healthy()
	in.Sources = append(in.Sources, model.Source{ID: "linux", Name: "Linux", Enabled: true, LastAuthFailAt: now.Add(-time.Hour)})
	r := Evaluate(in)
	c := r.Component("sources")
	if c.Level != Degraded || !strings.Contains(strings.Join(c.Details, ""), "Linux") || strings.Contains(strings.Join(c.Details, ""), "iPhone") {
		t.Errorf("%+v", c)
	}
	if r.Component("delivery").Level != OK || r.Component("ingest").Level != OK {
		t.Error("other components unaffected")
	}
}

// Requirement: "服务器内存不足，影响消息处理"
func TestLowMemory(t *testing.T) {
	in := healthy()
	in.Sys.MemAvailable = 30
	in.Sys.DiskAvail = 50
	r := Evaluate(in)
	c := r.Component("server")
	if c.Level != Down || !strings.Contains(c.Summary, "内存") || len(c.Details) == 0 {
		t.Errorf("%+v", c)
	}
}

func TestUnknownIsNotHealthy(t *testing.T) {
	in := healthy()
	in.State.SendOK = time.Time{}
	in.State.PollOK = time.Time{}
	in.Sys = nil
	in.Cert = CertInfo{}
	r := Evaluate(in)
	for _, k := range []string{"delivery", "bot", "server", "ingest"} {
		if r.Component(k).Level != Unknown {
			t.Errorf("%s should be unknown without evidence: %+v", k, r.Component(k))
		}
	}
	if r.Overall != OK || !strings.Contains(r.OverallText, "无法确认") {
		t.Errorf("overall %v %s", r.Overall, r.OverallText)
	}
	in.Sys = &sysinfo.Snapshot{}
	if c := Evaluate(in).Component("server"); !strings.Contains(strings.Join(c.Details, ""), "不可用") {
		t.Error("unreadable metrics must be shown as unavailable")
	}
}

func TestProcessProblems(t *testing.T) {
	in := healthy()
	in.State.WorkerBeat = now.Add(-10 * time.Minute)
	if c := Evaluate(in).Component("gateway"); c.Level != Down {
		t.Errorf("stalled worker: %+v", c)
	}
	in = healthy()
	in.DBErr = errors.New("disk I/O error")
	if c := Evaluate(in).Component("gateway"); c.Level != Down || !strings.Contains(c.Summary, "数据库") {
		t.Errorf("db: %+v", c)
	}
	in = healthy()
	in.State.Listening = false
	if Evaluate(in).Component("ingest").Level != Down {
		t.Error("listener down")
	}
	in = healthy()
	in.Cert.NotAfter = now.Add(3 * 24 * time.Hour)
	if Evaluate(in).Component("ingest").Level != Degraded {
		t.Error("expiring certificate")
	}
	in = healthy()
	in.State.PollOK = now.Add(-10 * time.Minute)
	if c := Evaluate(in).Component("bot"); c.Level != Degraded {
		t.Errorf("stale poll: %+v", c)
	}
	in = healthy()
	in.Sources = nil
	if Evaluate(in).Component("sources").Level != ConfigError {
		t.Error("no sources configured")
	}
}

func TestFormatting(t *testing.T) {
	cases := map[time.Duration]string{
		30 * time.Second:              "30 秒",
		5 * time.Minute:               "5 分钟",
		2*time.Hour + 5*time.Minute:   "2 小时 5 分钟",
		3*24*time.Hour + 12*time.Hour: "3 天 12 小时",
		24 * time.Hour:                "1 天",
	}
	for d, want := range cases {
		if got := Dur(d); got != want {
			t.Errorf("Dur(%v) = %q, want %q", d, got, want)
		}
	}
	if Ago(now, time.Time{}) != "从未" || Ago(now, now) != "刚刚" || Ago(now, now.Add(-10*time.Minute)) != "10 分钟前" {
		t.Error("Ago")
	}
}

func TestTrackerErrorRing(t *testing.T) {
	tr := NewTracker(now)
	for i := 0; i < 30; i++ {
		tr.DeliveryFailed(now.Add(time.Duration(i)*time.Second), FailOther, "e")
	}
	if errs := tr.RecentErrors(5); len(errs) != 5 || !errs[0].At.After(errs[1].At) {
		t.Error("recent errors should be newest first")
	}
	if len(tr.errors) != maxErrors {
		t.Error("error ring must be bounded")
	}
	tr.DeliveryOK(now)
	if tr.Snapshot().SendConsecutiveFails != 0 {
		t.Error("success resets consecutive failures")
	}
}
