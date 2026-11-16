package status

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"nekopost/internal/health"
	"nekopost/internal/store"
	"nekopost/internal/sysinfo"
)

func service(t *testing.T, mode string, push bool) *Service {
	t.Helper()
	opts := store.TestingOptions()
	opts.EnablePush = push
	st, err := store.Open(filepath.Join(t.TempDir(), "t.db"), opts)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	now := time.Now()
	tracker := health.NewTracker(now)
	tracker.WorkerBeat(now)
	return &Service{Store: st, Health: tracker, Location: time.UTC, StartedAt: now, RetentionDays: 30, TLSMode: "off",
		Sys: sysinfo.Collector{DiskPath: t.TempDir(), CPUSample: time.Millisecond}, TelegramMode: mode, PushEnabled: push}
}

func component(r health.Report, key string) (health.Component, bool) {
	for _, c := range r.Components {
		if c.Key == key {
			return c, true
		}
	}
	return health.Component{}, false
}

func TestTelegramNoneIsAdminOnly(t *testing.T) {
	r := service(t, "none", true).Report(context.Background())
	c, ok := component(r, "delivery")
	if !ok || c.Level != health.OK || c.Summary != "Telegram 仅用于管理与审批，消息只推送到 App" {
		t.Fatal(c)
	}
}

func TestAppOnlyTypesWithoutDevice(t *testing.T) {
	r := service(t, "some", true).Report(context.Background())
	c, ok := component(r, "push")
	if !ok || c.Level != health.ConfigError || r.Overall < health.ConfigError {
		t.Fatal(c, r.Overall)
	}
}

func TestAppOnlyTypesWithoutFCM(t *testing.T) {
	r := service(t, "none", false).Report(context.Background())
	c, ok := component(r, "push")
	if !ok || c.Level != health.ConfigError || r.Overall < health.ConfigError {
		t.Fatal(c, r.Overall)
	}
}

func TestTelegramAllLeavesPushOptional(t *testing.T) {
	r := service(t, "all", false).Report(context.Background())
	if _, ok := component(r, "push"); ok {
		t.Fatal("no push component without FCM when Telegram takes everything")
	}
	r = service(t, "all", true).Report(context.Background())
	if c, _ := component(r, "push"); c.Level != health.Unknown {
		t.Fatal(c)
	}
}
