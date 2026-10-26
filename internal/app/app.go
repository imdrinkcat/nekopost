// Package app wires the gateway's components together and runs them.
package app

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"nekopost/internal/bot"
	"nekopost/internal/config"
	"nekopost/internal/delivery"
	"nekopost/internal/filter"
	"nekopost/internal/health"
	"nekopost/internal/httpapi"
	"nekopost/internal/model"
	"nekopost/internal/ratelimit"
	"nekopost/internal/router"
	"nekopost/internal/sdnotify"
	"nekopost/internal/status"
	"nekopost/internal/store"
	"nekopost/internal/sysinfo"
	"nekopost/internal/telegram"
)

// Run starts the service and blocks until ctx is cancelled.
func Run(ctx context.Context, cfg *config.Config, version string, log *slog.Logger) error {
	startedAt := time.Now()
	st, err := store.Open(cfg.DBPath())
	if err != nil {
		return fmt.Errorf("打开数据库失败: %w", err)
	}
	defer st.Close()

	startInfo, err := st.RecordStart(ctx, startedAt)
	if err != nil {
		return err
	}
	dedupeKey, err := st.Secret(ctx, "dedupe")
	if err != nil {
		return err
	}
	if n, err := st.RecoverInFlight(ctx, startedAt); err != nil {
		return err
	} else if n > 0 {
		log.Warn("deliveries interrupted by previous shutdown will be retried (may duplicate)", "count", n)
	}

	tracker := health.NewTracker(startedAt)
	if h, err := st.LastDeliveryHistory(ctx); err == nil {
		tracker.Seed(h.LastDelivered, h.LastProblem, h.LastProblemErr)
	}
	tracker.WorkerBeat(startedAt)

	tg := telegram.New(cfg.TelegramAPIBase, cfg.BotToken)
	notifier := delivery.NewNotifier()
	worker := &delivery.Worker{
		Store: st,
		Channels: map[string]delivery.Channel{
			"telegram": &delivery.TelegramChannel{
				Client:      tg,
				Targets:     map[string]int64{"default": cfg.ChatID},
				Location:    cfg.Location,
				MinInterval: 1100 * time.Millisecond,
			},
		},
		Health:   tracker,
		Notifier: notifier,
		Log:      log.With("component", "delivery"),
		MaxAge:   cfg.DeliveryMaxAge,
	}

	var fl *filter.Filter
	if cfg.FilterEnabled() {
		drop := map[model.Category]bool{}
		for _, c := range cfg.FilterDrop {
			drop[c] = true
		}
		fl = &filter.Filter{
			Store:    st,
			AI:       &filter.DeepSeek{BaseURL: cfg.DeepSeekAPIBase, APIKey: cfg.DeepSeekAPIKey, Model: cfg.DeepSeekModel},
			Drop:     drop,
			Health:   tracker,
			Notifier: notifier,
			Delivery: worker,
			Log:      log.With("component", "filter"),
		}
	} else if n, err := st.ReleaseAllHeld(ctx, startedAt); err != nil {
		return err
	} else if n > 0 {
		log.Warn("content filter disabled; held messages released without classification", "count", n)
	}

	listener := &httpapi.Listener{
		Cfg: httpapi.ListenConfig{
			TLSMode: cfg.TLSMode, Domain: cfg.Domain, HTTPSAddr: cfg.HTTPSAddr, HTTPAddr: cfg.HTTPAddr,
			CacheDir: cfg.ACMECacheDir(), Email: cfg.ACMEEmail,
		},
		Health: tracker,
		Log:    log.With("component", "http"),
	}

	statusSvc := &status.Service{
		Store:         st,
		Health:        tracker,
		Sys:           sysinfo.Collector{DiskPath: cfg.DataDir, CPUSample: 300 * time.Millisecond, ProcessStart: startedAt},
		TLSMode:       cfg.TLSMode,
		Cert:          listener.CertInfo,
		Location:      cfg.Location,
		StartedAt:     startedAt,
		StartInfo:     startInfo,
		Version:       version,
		RetentionDays: cfg.RetentionDays,
	}

	burst := max(10, cfg.SourceRatePerMin/3)
	api := &httpapi.Server{
		Store:         st,
		Router:        router.Default{Channel: "telegram", Target: "default"},
		Worker:        worker,
		Notifier:      notifier,
		Health:        tracker,
		Status:        statusSvc,
		Log:           log.With("component", "http"),
		DedupeKey:     dedupeKey,
		DedupeWindow:  cfg.DedupeWindow,
		MaxBodyChars:  cfg.MaxBodyChars,
		Location:      cfg.Location,
		Version:       version,
		HSTS:          cfg.TLSMode == "autocert",
		SourceLimiter: ratelimit.NewLimiter(cfg.SourceRatePerMin, burst),
		FailGuard:     ratelimit.NewFailureGuard(20, 10*time.Minute, 15*time.Minute),
	}
	if fl != nil {
		api.Filter = fl
		api.FilterTypes = map[string]bool{}
		for _, t := range cfg.FilterTypes {
			api.FilterTypes[t] = true
		}
	}
	listener.Handler = api.Handler()

	b := &bot.Bot{
		Client:   tg,
		IsAdmin:  cfg.IsAdmin,
		Admins:   cfg.AdminIDs,
		Status:   statusSvc,
		Store:    st,
		Health:   tracker,
		Log:      log.With("component", "bot"),
		Location: cfg.Location,
		Version:  version,
		Settings: bot.Settings{
			Domain: cfg.Domain, TLSMode: cfg.TLSMode, Timezone: cfg.TimezoneName, ChatID: cfg.ChatID,
			AdminCount: len(cfg.AdminIDs), RetentionDays: cfg.RetentionDays, DeliveryMaxAge: cfg.DeliveryMaxAge,
			DedupeWindow: cfg.DedupeWindow, RatePerMin: cfg.SourceRatePerMin, MaxBodyChars: cfg.MaxBodyChars,
			Filter: bot.FilterSettings{Enabled: cfg.FilterEnabled(), Model: cfg.DeepSeekModel, Types: cfg.FilterTypes,
				Drop: cfg.FilterDrop, KeepDays: cfg.FilterKeepDays},
		},
		Delivery: worker,
	}

	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	if err := listener.Start(runCtx); err != nil {
		return fmt.Errorf("监听端口失败: %w", err)
	}

	workers := 3
	done := make(chan struct{}, 4)
	go func() { worker.Run(runCtx); done <- struct{}{} }()
	if fl != nil {
		workers++
		go func() { fl.Run(runCtx); done <- struct{}{} }()
	}
	go func() { b.Run(runCtx); done <- struct{}{} }()
	go func() { maintenance(runCtx, st, api, cfg, log); done <- struct{}{} }()
	go watchdog(runCtx, st, tracker, log)

	_ = sdnotify.Ready()
	log.Info("nekopost started", "version", version, "tls", cfg.TLSMode, "domain", cfg.Domain,
		"start_count", startInfo.StartCount, "filter", cfg.FilterEnabled())

	<-ctx.Done()
	log.Info("shutting down")
	_ = sdnotify.Stopping()
	shutCtx, shutCancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer shutCancel()
	listener.Shutdown(shutCtx)
	cancel()
	for i := 0; i < workers; i++ {
		select {
		case <-done:
		case <-shutCtx.Done():
			log.Warn("shutdown timed out")
			return nil
		}
	}
	log.Info("stopped")
	return nil
}

// maintenance runs hourly housekeeping: retention, limiter cleanup and WAL
// truncation (so purged message bodies do not linger on disk).
func maintenance(ctx context.Context, st *store.Store, api *httpapi.Server, cfg *config.Config, log *slog.Logger) {
	run := func() {
		cutoff := time.Now().Add(-time.Duration(cfg.RetentionDays) * 24 * time.Hour)
		if n, err := st.PurgeExpired(ctx, cutoff); err != nil {
			log.Error("retention purge failed", "err", err)
		} else if n > 0 {
			log.Info("expired message metadata purged", "count", n)
		}
		filteredCutoff := time.Now().Add(-time.Duration(cfg.FilterKeepDays) * 24 * time.Hour)
		if _, err := st.PurgeFinishedPayloads(ctx, filteredCutoff); err != nil {
			log.Error("payload purge failed", "err", err)
		}
		if err := st.Checkpoint(ctx); err != nil {
			log.Warn("wal checkpoint failed", "err", err)
		}
		api.SourceLimiter.Cleanup()
		api.FailGuard.Cleanup()
	}
	t := time.NewTimer(time.Minute)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			run()
			t.Reset(time.Hour)
		}
	}
}

// watchdog pings systemd only while the database answers and the delivery
// loop is alive, so a wedged process gets restarted. Telegram being
// unreachable does not stop the pings: restarting would not help.
func watchdog(ctx context.Context, st *store.Store, tracker *health.Tracker, log *slog.Logger) {
	interval := sdnotify.WatchdogInterval()
	if interval == 0 {
		return
	}
	t := time.NewTicker(interval / 3)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			pctx, cancel := context.WithTimeout(ctx, 5*time.Second)
			err := st.Ping(pctx)
			cancel()
			beat := tracker.Snapshot().WorkerBeat
			if err == nil && time.Since(beat) < health.WorkerStaleAfter {
				_ = sdnotify.Watchdog()
			} else {
				log.Error("watchdog check failed; withholding keep-alive", "db_err", err, "worker_beat", beat)
			}
		}
	}
}
