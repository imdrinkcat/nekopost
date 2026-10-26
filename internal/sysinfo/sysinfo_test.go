package sysinfo

import (
	"testing"
	"time"
)

func TestCollectFixture(t *testing.T) {
	s := Collector{ProcRoot: "testdata/proc", DiskPath: t.TempDir(), CPUSample: time.Millisecond}.Collect()
	if !s.HostUptimeOK || s.HostUptime.Round(time.Second) != 12346*time.Second {
		t.Errorf("uptime %v", s.HostUptime)
	}
	if !s.LoadOK || s.Load1 != 0.5 || s.Load15 != 0.3 {
		t.Errorf("load %+v", s)
	}
	if !s.MemOK || s.MemTotal != 1000000*1024 || s.MemAvailable != 400000*1024 || s.SwapFree != 1999000*1024 {
		t.Errorf("mem %+v", s)
	}
	if !s.ProcRSSOK || s.ProcRSS != 20480*1024 {
		t.Errorf("rss %d", s.ProcRSS)
	}
	if !s.DiskOK || s.DiskTotal == 0 {
		t.Error("disk")
	}
	// The fixture /proc/stat does not change between samples: no valid delta.
	if s.CPUOK {
		t.Error("cpu should be unavailable without a delta")
	}
}

func TestCollectMissing(t *testing.T) {
	s := Collector{ProcRoot: "testdata/nonexistent", DiskPath: "/nonexistent/path", CPUSample: time.Millisecond}.Collect()
	if s.HostUptimeOK || s.LoadOK || s.MemOK || s.DiskOK || s.CPUOK || s.ProcRSSOK {
		t.Errorf("unreadable metrics must be flagged unavailable: %+v", s)
	}
}

func TestCollectReal(t *testing.T) {
	s := Collector{DiskPath: "/", CPUSample: 100 * time.Millisecond}.Collect()
	if !s.HostUptimeOK || !s.MemOK || !s.DiskOK || !s.ProcRSSOK || !s.CPUOK {
		t.Errorf("real /proc should be readable: %+v", s)
	}
	if s.CPUPercent < 0 || s.CPUPercent > 100 {
		t.Errorf("cpu %f", s.CPUPercent)
	}
}
