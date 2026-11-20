// Package app wires the gateway's components together and runs them.
package app

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"time"

	"nekopost/internal/auth"
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
	"nekopost/internal/sealbox"
	"nekopost/internal/status"
	"nekopost/internal/store"
	"nekopost/internal/sysinfo"
	"nekopost/internal/telegram"
	"nekopost/internal/webpanel"
)

// Run starts the service and blocks until ctx is cancelled.
func Run(ctx context.Context, cfg *config.Config, version string, log *slog.Logger) error {
	startedAt := time.Now()
	mat, err := sealbox.LoadMaterial(sealbox.Location{})
	if err != nil {
		return err
	}
	var fcm *delivery.FCM
	fcmPath := cfg.FCMFile
	if fcmPath == "" && os.Getenv("CREDENTIALS_DIRECTORY") != "" {
		candidate := filepath.Join(os.Getenv("CREDENTIALS_DIRECTORY"), "nekopost-fcm-sa")
		if fi, err := os.Stat(candidate); err == nil && fi.Mode().IsRegular() {
			fcmPath = candidate
		}
	}
	if fcmPath != "" {
		fcm, err = delivery.NewFCM(ctx, nil, fcmPath, cfg.FCMProject)
		if err != nil {
			log.Warn("FCM disabled", "reason", err)
		} else {
			fcm.Log = log.With("component", "fcm")
		}
	}
	options := cfg.StoreOptions(mat.Current, mat.Previous)
	options.EnablePush = fcm != nil
	st, err := store.Open(cfg.DBPath(), options)
	if err != nil {
		return fmt.Errorf("打开数据库失败: %w", err)
	}
	defer st.Close()

	startInfo, err := st.RecordStart(ctx, startedAt)
	if err != nil {
		return err
	}
	if n, err := st.ApplyRetentionMode(ctx, startedAt); err != nil {
		return err
	} else if n > 0 {
		log.Info("body retention mode applied", "auto_delete", cfg.AutoDelete, "bodies", n)
	}
	dedupeKey := st.DedupeKey()
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

	if fcm != nil {
		fcm.Store = st
		worker.Channels[fcm.Name()] = fcm
	} else {
		// Push rows left from a run with FCM (now app-primary ones decide the
		// message status) are finalized instead of waiting forever.
		worker.Unconfigured = append(worker.Unconfigured, "fcm")
	}

	var emailCh *delivery.EmailChannel
	if cfg.Email.Warning != "" {
		log.Warn("email disabled", "reason", cfg.Email.Warning)
	} else if cfg.Email.Enabled() {
		if password, err := cfg.Email.Password(); err != nil {
			log.Warn("email disabled", "reason", err)
		} else {
			emailCh = delivery.NewEmailChannel(cfg.Email, password, cfg.Location)
			worker.Channels[emailCh.Name()] = emailCh
			log.Info("email enabled", "host", cfg.Email.Host, "port", cfg.Email.Port, "security", cfg.Email.Security,
				"recipients", len(cfg.Email.To), "privacy", cfg.Email.Privacy)
		}
	}
	if emailCh == nil {
		// Rows left from a run with email are finalized instead of waiting.
		worker.Unconfigured = append(worker.Unconfigured, "email")
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

	telegramMode := "all"
	switch {
	case cfg.TelegramAll():
	case len(cfg.TelegramTypes) == 0:
		telegramMode = "none"
	default:
		telegramMode = "some"
	}
	if telegramMode != "all" && fcm == nil {
		if emailCh != nil {
			log.Warn("some message types are excluded from Telegram and App push (FCM) is not configured; they are only emailed (an auxiliary channel), so their status stays 未转发",
				"telegram_types", telegramMode)
		} else {
			log.Warn("some message types are excluded from Telegram but App push (FCM) is not configured; those messages will not be delivered",
				"telegram_types", telegramMode)
		}
	}
	msgRouter := router.Chain{router.Default{Channel: "telegram", Target: "default", Types: cfg.TelegramTypes}}
	if emailCh != nil {
		msgRouter = append(msgRouter, router.Default{Channel: "email", Target: "default", Types: cfg.Email.Types, Aux: true})
	}
	statusSvc := &status.Service{
		TelegramMode:  telegramMode,
		PushEnabled:   fcm != nil,
		Store:         st,
		Health:        tracker,
		Sys:           sysinfo.Collector{DiskPath: cfg.DataDir, CPUSample: 300 * time.Millisecond, ProcessStart: startedAt},
		TLSMode:       cfg.TLSMode,
		Cert:          listener.CertInfo,
		Location:      cfg.Location,
		StartedAt:     startedAt,
		StartInfo:     startInfo,
		Version:       version,
		RetentionDays: cfg.MessageRetentionDays(),
	}

	burst := max(10, cfg.SourceRatePerMin/3)
	publicURL := cfg.Web.PublicURL
	if publicURL == "" && cfg.Domain != "" && cfg.TLSMode == "autocert" {
		publicURL = "https://" + cfg.Domain
	}
	api := &httpapi.Server{
		DPoP:           auth.NewDPoP(),
		PublicURL:      publicURL,
		Store:          st,
		Router:         msgRouter,
		TelegramTypes:  cfg.TelegramTypes,
		Worker:         worker,
		Notifier:       notifier,
		Health:         tracker,
		Status:         statusSvc,
		Log:            log.With("component", "http"),
		DedupeKey:      dedupeKey,
		DedupeWindow:   cfg.DedupeWindow,
		MaxBodyChars:   cfg.MaxBodyChars,
		Location:       cfg.Location,
		Version:        version,
		HSTS:           cfg.TLSMode == "autocert",
		TrustedProxies: cfg.TrustedProxies,
		SourceLimiter:  ratelimit.NewLimiter(cfg.SourceRatePerMin, burst),
		FailGuard:      ratelimit.NewFailureGuard(20, 10*time.Minute, 15*time.Minute),
	}
	if fl != nil {
		api.Filter = fl
		api.FilterTypes = map[string]bool{}
		for _, t := range cfg.FilterTypes {
			api.FilterTypes[t] = true
		}
	}

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
			AdminCount: len(cfg.AdminIDs), RetentionDays: cfg.MessageRetentionDays(), DeliveryMaxAge: cfg.DeliveryMaxAge,
			DedupeWindow: cfg.DedupeWindow, RatePerMin: cfg.SourceRatePerMin, MaxBodyChars: cfg.MaxBodyChars,
			Filter: bot.FilterSettings{Enabled: cfg.FilterEnabled(), Model: cfg.DeepSeekModel, Types: cfg.FilterTypes,
				Drop: cfg.FilterDrop, KeepDays: cfg.FilterKeepDays},
		},
		Delivery:  worker,
		PublicURL: publicURL,
	}

	panel := &webpanel.Panel{
		Store: st, Status: statusSvc, Version: version, ClientIP: api.ClientIP,
		Settings: webpanel.Settings{Timezone: cfg.TimezoneName, AutoDelete: cfg.AutoDelete, RetentionDays: cfg.RetentionDays,
			BodyRetentionDays: cfg.BodyRetentionDays, CodeRetentionHours: cfg.CodeRetentionHours},
		Allow: cfg.Web.Allow, NotifyApproval: b.SendWebApproval, NotifySecurity: b.NotifyWebSecurity,
	}
	if cfg.Web.Warning != "" {
		log.Warn("web panel disabled", "reason", cfg.Web.Warning)
	}
	if cfg.Web.Enabled() {
		secret, err := cfg.Web.GoogleSecret()
		if err != nil {
			log.Warn("web panel disabled", "reason", err)
		} else {
			panel.Keys, err = sealbox.Derive(mat.Current)
			if err != nil {
				return err
			}
			panel.Google = webpanel.NewGoogle(cfg.Web.GoogleClientID, secret, cfg.Web.PublicURL+"/auth/google/callback")
		}
	}
	if fcm != nil {
		api.TestPush = fcm.TestPush
		signal := func(kind string, ids []string, except string) {
			go func() {
				sctx, scancel := context.WithTimeout(context.Background(), 30*time.Second)
				defer scancel()
				fcm.Signal(sctx, kind, ids, except)
			}()
		}
		api.PushSignal = signal
		b.PushSignal = signal
	}
	api.PairNotify = b.SendPairApproval
	api.SecurityNotify = b.NotifyWebSecurity
	api.CookieAdmin = panel.AuthorizeAdmin
	api.WebSessionHash = panel.SessionHash
	api.Web = panel.Handler()
	listener.Handler = api.Handler()

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
	fatal := make(chan error, 1)
	go watchdog(runCtx, st, tracker, log, fatal)

	_ = sdnotify.Ready()
	if cfg.RetentionAliasWarn != "" {
		log.Warn(cfg.RetentionAliasWarn)
	}
	if cfg.AutoDeleteWarn != "" {
		log.Warn(cfg.AutoDeleteWarn)
	}
	log.Info("nekopost started", "version", version, "auto_delete", cfg.AutoDelete, "tls", cfg.TLSMode, "domain", cfg.Domain,
		"start_count", startInfo.StartCount, "filter", cfg.FilterEnabled())

	var runErr error
	select {
	case <-ctx.Done():
		log.Info("shutting down")
	case runErr = <-fatal:
		log.Error("watchdog: shutting down", "err", runErr)
	}
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
			return runErr
		}
	}
	log.Info("stopped")
	return runErr
}

// maintenance runs hourly housekeeping: retention, limiter cleanup and WAL
// truncation (so purged message bodies do not linger on disk).
func maintenance(ctx context.Context, st *store.Store, api *httpapi.Server, cfg *config.Config, log *slog.Logger) {
	run := func() {
		if cfg.AutoDelete {
			cutoff := time.Now().Add(-time.Duration(cfg.RetentionDays) * 24 * time.Hour)
			if n, err := st.PurgeExpired(ctx, cutoff); err != nil {
				log.Error("retention purge failed", "err", err)
			} else if n > 0 {
				log.Info("expired message metadata purged", "count", n)
			}
		}
		if n, err := st.ExpireBodies(ctx, time.Now()); err != nil {
			log.Error("body purge failed", "err", err)
		} else if n > 0 {
			log.Info("expired message bodies purged", "count", n)
		}
		if err := st.ClearStaleFingerprints(ctx, time.Now(), cfg.DedupeWindow); err != nil {
			log.Error("fingerprint purge failed", "err", err)
		}
		if err := st.ShredUnusedDEKs(ctx, time.Now()); err != nil {
			log.Error("dek shred failed", "err", err)
		}
		if err := st.DeleteMigrationBackups(cfg.DataDir, time.Now()); err != nil {
			log.Warn("migration backup cleanup failed", "err", err)
		}
		if err := st.Checkpoint(ctx); err != nil {
			log.Warn("wal checkpoint failed", "err", err)
		}
		if err := st.CleanTombstones(ctx, time.Now()); err != nil {
			log.Error("tombstone maintenance failed", "err", err)
		}
		if err := st.CleanDevices(ctx, time.Now()); err != nil {
			log.Error("device maintenance failed", "err", err)
		}
		if err := st.CleanAudit(ctx, time.Now().Add(-180*24*time.Hour)); err != nil {
			log.Error("audit retention failed", "err", err)
		}
		if err := st.CleanWeb(ctx, time.Now()); err != nil {
			log.Error("web maintenance failed", "err", err)
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

// alive reports whether the database answers and the delivery loop is
// running. Telegram being unreachable does not count: restarting would not
// help.
func alive(ctx context.Context, st *store.Store, tracker *health.Tracker) error {
	pctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := st.Ping(pctx); err != nil {
		return fmt.Errorf("database: %w", err)
	}
	if beat := tracker.Snapshot().WorkerBeat; time.Since(beat) >= health.WorkerStaleAfter {
		return fmt.Errorf("delivery loop silent since %s", beat.Format(time.RFC3339))
	}
	return nil
}

// Self-watchdog timing, used when systemd's watchdog is not active.
const (
	selfWatchdogEvery = 30 * time.Second
	selfWatchdogAfter = 5 * time.Minute
)

// watchdog pings systemd only while the process is alive, so a wedged
// process gets restarted. Without systemd's watchdog (e.g. in a container)
// it reports on fatal once the process has been wedged for a while, and the
// service exits so the supervisor's restart policy takes over.
func watchdog(ctx context.Context, st *store.Store, tracker *health.Tracker, log *slog.Logger, fatal chan<- error) {
	check := func() error { return alive(ctx, st, tracker) }
	interval := sdnotify.WatchdogInterval()
	if interval == 0 {
		if err := selfWatchdog(ctx, check, selfWatchdogEvery, selfWatchdogAfter, log); err != nil {
			fatal <- err
		}
		return
	}
	t := time.NewTicker(interval / 3)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if err := check(); err == nil {
				_ = sdnotify.Watchdog()
			} else {
				log.Error("watchdog check failed; withholding keep-alive", "err", err)
			}
		}
	}
}

// selfWatchdog runs check every interval and returns an error once it has
// failed continuously for at least after. It returns nil when ctx ends.
func selfWatchdog(ctx context.Context, check func() error, every, after time.Duration, log *slog.Logger) error {
	t := time.NewTicker(every)
	defer t.Stop()
	var failingSince time.Time
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-t.C:
		}
		err := check()
		if ctx.Err() != nil {
			return nil
		}
		switch {
		case err == nil:
			failingSince = time.Time{}
		case failingSince.IsZero():
			failingSince = time.Now()
			log.Error("watchdog check failed", "err", err)
		case time.Since(failingSince) >= after:
			return fmt.Errorf("服务已卡住 %s，退出以便自动重启: %w", time.Since(failingSince).Round(time.Second), err)
		default:
			log.Error("watchdog check failed", "err", err, "since", failingSince)
		}
	}
}
