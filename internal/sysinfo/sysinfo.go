// Package sysinfo reads basic host and process metrics from /proc and
// statfs. It needs no privileges and keeps no history.
package sysinfo

import (
	"bufio"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// Snapshot is a point-in-time view. Each group has an OK flag; when false the
// value could not be read and must be shown as unavailable.
type Snapshot struct {
	At time.Time

	HostUptime   time.Duration
	HostUptimeOK bool

	CPUPercent float64 // host-wide busy percentage over the sample window
	CPUOK      bool
	NumCPU     int

	Load1, Load5, Load15 float64
	LoadOK               bool

	MemTotal, MemAvailable uint64 // bytes
	SwapTotal, SwapFree    uint64
	MemOK                  bool

	DiskPath                       string
	DiskTotal, DiskFree, DiskAvail uint64
	DiskOK                         bool

	ProcRSS      uint64 // this process, bytes
	ProcRSSOK    bool
	GoHeapInUse  uint64
	Goroutines   int
	ProcessStart time.Time
}

// Collector reads metrics. ProcRoot is "/proc" except in tests.
type Collector struct {
	ProcRoot     string
	DiskPath     string
	CPUSample    time.Duration
	ProcessStart time.Time
}

// Collect gathers a snapshot. It sleeps for CPUSample to measure CPU usage.
func (c Collector) Collect() Snapshot {
	root := c.ProcRoot
	if root == "" {
		root = "/proc"
	}
	s := Snapshot{At: time.Now(), NumCPU: runtime.NumCPU(), DiskPath: c.DiskPath, ProcessStart: c.ProcessStart}

	if up, err := readUptime(filepath.Join(root, "uptime")); err == nil {
		s.HostUptime, s.HostUptimeOK = up, true
	}
	if l1, l5, l15, err := readLoad(filepath.Join(root, "loadavg")); err == nil {
		s.Load1, s.Load5, s.Load15, s.LoadOK = l1, l5, l15, true
	}
	if mi, err := readKV(filepath.Join(root, "meminfo")); err == nil {
		total, ok1 := mi["MemTotal"]
		avail, ok2 := mi["MemAvailable"]
		if ok1 && ok2 && total > 0 {
			s.MemTotal, s.MemAvailable, s.MemOK = total*1024, avail*1024, true
			s.SwapTotal, s.SwapFree = mi["SwapTotal"]*1024, mi["SwapFree"]*1024
		}
	}
	if c.DiskPath != "" {
		var st syscall.Statfs_t
		if err := syscall.Statfs(c.DiskPath, &st); err == nil {
			bs := uint64(st.Bsize)
			s.DiskTotal, s.DiskFree, s.DiskAvail, s.DiskOK = st.Blocks*bs, st.Bfree*bs, st.Bavail*bs, true
		}
	}
	if ps, err := readKV(filepath.Join(root, "self", "status")); err == nil {
		if rss, ok := ps["VmRSS"]; ok {
			s.ProcRSS, s.ProcRSSOK = rss*1024, true
		}
	}
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	s.GoHeapInUse = ms.HeapInuse
	s.Goroutines = runtime.NumGoroutine()

	sample := c.CPUSample
	if sample <= 0 {
		sample = 300 * time.Millisecond
	}
	if b1, t1, err := readCPU(filepath.Join(root, "stat")); err == nil {
		time.Sleep(sample)
		if b2, t2, err := readCPU(filepath.Join(root, "stat")); err == nil && t2 > t1 {
			s.CPUPercent = float64(b2-b1) / float64(t2-t1) * 100
			s.CPUOK = true
		}
	}
	return s
}

func readUptime(path string) (time.Duration, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return 0, err
	}
	f := strings.Fields(string(b))
	if len(f) < 1 {
		return 0, errors.New("bad uptime")
	}
	sec, err := strconv.ParseFloat(f[0], 64)
	if err != nil {
		return 0, err
	}
	return time.Duration(sec * float64(time.Second)), nil
}

func readLoad(path string) (float64, float64, float64, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return 0, 0, 0, err
	}
	f := strings.Fields(string(b))
	if len(f) < 3 {
		return 0, 0, 0, errors.New("bad loadavg")
	}
	var v [3]float64
	for i := range v {
		if v[i], err = strconv.ParseFloat(f[i], 64); err != nil {
			return 0, 0, 0, err
		}
	}
	return v[0], v[1], v[2], nil
}

// readKV parses "Key:   123 kB" style files (meminfo, status).
func readKV(path string) (map[string]uint64, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	out := map[string]uint64{}
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		k, v, ok := strings.Cut(sc.Text(), ":")
		if !ok {
			continue
		}
		fields := strings.Fields(v)
		if len(fields) == 0 {
			continue
		}
		if n, err := strconv.ParseUint(fields[0], 10, 64); err == nil {
			out[strings.TrimSpace(k)] = n
		}
	}
	return out, sc.Err()
}

// readCPU returns busy and total jiffies from the aggregate "cpu" line.
func readCPU(path string) (busy, total uint64, err error) {
	f, err := os.Open(path)
	if err != nil {
		return 0, 0, err
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) < 5 || fields[0] != "cpu" {
			continue
		}
		var vals []uint64
		for _, x := range fields[1:] {
			n, err := strconv.ParseUint(x, 10, 64)
			if err != nil {
				return 0, 0, err
			}
			vals = append(vals, n)
		}
		// user nice system idle iowait irq softirq steal guest guest_nice
		for i, v := range vals {
			if i >= 8 { // guest time is already included in user/nice
				break
			}
			total += v
			if i != 3 && i != 4 { // idle, iowait
				busy += v
			}
		}
		return busy, total, nil
	}
	return 0, 0, errors.New("no cpu line")
}
