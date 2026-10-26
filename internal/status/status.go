// Package status assembles health reports, statistics and source activity
// for the management bot and the admin API.
package status

import (
	"context"
	"fmt"
	"sync"
	"time"

	"nekopost/internal/health"
	"nekopost/internal/model"
	"nekopost/internal/store"
	"nekopost/internal/sysinfo"
)

// Service gathers status information on demand. Nothing is collected in the
// background; every query reads current data.
type Service struct {
	Store         *store.Store
	Health        *health.Tracker
	Sys           sysinfo.Collector
	TLSMode       string
	Cert          func() health.CertInfo
	Location      *time.Location
	StartedAt     time.Time
	StartInfo     store.StartInfo
	Version       string
	RetentionDays int
	Now           func() time.Time

	mu      sync.Mutex
	sysSnap *sysinfo.Snapshot
}

func (s *Service) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

// System returns a recent system snapshot, reusing one younger than 5 s so
// that repeated refreshes do not repeatedly sample the CPU.
func (s *Service) System() sysinfo.Snapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.sysSnap != nil && time.Since(s.sysSnap.At) < 5*time.Second {
		return *s.sysSnap
	}
	snap := s.Sys.Collect()
	s.sysSnap = &snap
	return snap
}

// Report evaluates the health of every component.
func (s *Service) Report(ctx context.Context) health.Report {
	in := health.Inputs{Now: s.now(), State: s.Health.Snapshot(), TLSMode: s.TLSMode}
	in.DBErr = s.Store.Ping(ctx)
	in.Queue, in.QueueErr = s.Store.Queue(ctx)
	in.Sources, _ = s.Store.ListSources(ctx)
	snap := s.System()
	in.Sys = &snap
	if s.Cert != nil {
		in.Cert = s.Cert()
	}
	return health.Evaluate(in)
}

// Range identifies a statistics period.
type Range string

const (
	RangeToday Range = "today"
	Range24h   Range = "24h"
	Range7d    Range = "7d"
	RangeStart Range = "start"
)

// Ranges lists the supported periods in display order.
var Ranges = []Range{RangeToday, Range24h, Range7d, RangeStart}

// Label returns the Chinese name of a range.
func (r Range) Label() string {
	switch r {
	case RangeToday:
		return "今天"
	case Range24h:
		return "最近 24 小时"
	case Range7d:
		return "最近 7 天"
	case RangeStart:
		return "本次启动以来"
	}
	return string(r)
}

// ParseRange validates a range name.
func ParseRange(v string) (Range, bool) {
	for _, r := range Ranges {
		if string(r) == v {
			return r, true
		}
	}
	return "", false
}

// StartOfDay returns local midnight of t.
func StartOfDay(t time.Time, loc *time.Location) time.Time {
	t = t.In(loc)
	return time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, loc)
}

// StatsResult is the statistics for one range.
type StatsResult struct {
	Range Range
	Stats store.Stats
	Queue store.QueueInfo
	Note  string
}

// Stats computes statistics for a range.
func (s *Service) Stats(ctx context.Context, r Range) (StatsResult, error) {
	now := s.now()
	var from time.Time
	switch r {
	case RangeToday:
		from = StartOfDay(now, s.Location)
	case Range24h:
		from = now.Add(-24 * time.Hour)
	case Range7d:
		from = now.Add(-7 * 24 * time.Hour)
	case RangeStart:
		from = s.StartedAt
	default:
		return StatsResult{}, fmt.Errorf("unknown range %q", r)
	}
	res := StatsResult{Range: r}
	var err error
	if res.Stats, err = s.Store.Stats(ctx, from, now.Add(time.Second)); err != nil {
		return res, err
	}
	if res.Queue, err = s.Store.Queue(ctx); err != nil {
		return res, err
	}
	if retention := time.Duration(s.RetentionDays) * 24 * time.Hour; now.Sub(from) > retention {
		res.Note = fmt.Sprintf("仅统计保留期（%d 天）内的记录", s.RetentionDays)
	}
	return res, nil
}

// SourceStatus combines a source's configuration with its recent activity.
type SourceStatus struct {
	Source model.Source
	Last   store.LastMessage // zero if no retained message
	Today  store.Counts
}

// Sources returns all configured sources with activity information.
func (s *Service) Sources(ctx context.Context) ([]SourceStatus, error) {
	srcs, err := s.Store.ListSources(ctx)
	if err != nil {
		return nil, err
	}
	last, err := s.Store.LastMessages(ctx)
	if err != nil {
		return nil, err
	}
	now := s.now()
	today, err := s.Store.Stats(ctx, StartOfDay(now, s.Location), now.Add(time.Second))
	if err != nil {
		return nil, err
	}
	byID := map[string]store.Counts{}
	for _, sc := range today.BySource {
		byID[sc.SourceID] = sc.Counts
	}
	out := make([]SourceStatus, 0, len(srcs))
	for _, src := range srcs {
		out = append(out, SourceStatus{Source: src, Last: last[src.ID], Today: byID[src.ID]})
	}
	return out, nil
}

// Source returns one source with activity information.
func (s *Service) Source(ctx context.Context, id string) (SourceStatus, error) {
	all, err := s.Sources(ctx)
	if err != nil {
		return SourceStatus{}, err
	}
	for _, st := range all {
		if st.Source.ID == id {
			return st, nil
		}
	}
	return SourceStatus{}, store.ErrNotFound
}
