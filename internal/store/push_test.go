package store

import (
	"path/filepath"
	"testing"
	"time"

	"nekopost/internal/model"
)

func TestFCMIsAuxiliaryAndSkipsOrigin(t *testing.T) {
	opts := TestingOptions()
	opts.EnablePush = true
	s, err := Open(filepath.Join(t.TempDir(), "test.db"), opts)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	now := time.Now()
	for _, id := range []string{"d_origin", "d_other"} {
		if _, err := s.db.Exec(`INSERT INTO devices(id,name,model,app_version,scopes,reauth_jwk,created_at)
			VALUES(?,?, '', '', 'msg:read','',?)`, id, id, now.UnixMilli()); err != nil {
			t.Fatal(err)
		}
		if err := s.UpdateDevicePush(ctx, id, "fixture_token", now); err != nil {
			t.Fatal(err)
		}
	}
	msg := newMsg("fixture", "body", "", now)
	msg.OriginDevice = "d_origin"
	res, err := s.InsertMessage(ctx, msg, tg, 0)
	if err != nil || res.Status != model.StatusQueued {
		t.Fatal(res.Status, err)
	}
	info, err := s.GetMessage(ctx, msg.ID)
	if err != nil || len(info.Deliveries) != 2 {
		t.Fatal(info.Deliveries, err)
	}
	fcm := info.Deliveries[1]
	if !fcm.Aux || fcm.Target != "d_other" {
		t.Fatal(fcm)
	}
	if state, err := s.FinishAttempt(ctx, fcm.ID, msg.ID, AttemptResult{State: model.DeliveryFailed}, now); err != nil || state != model.StatusQueued {
		t.Fatal(state, err)
	}
	if state, err := s.FinishAttempt(ctx, info.Deliveries[0].ID, msg.ID, AttemptResult{State: model.DeliveryDelivered}, now); err != nil || state != model.StatusDelivered {
		t.Fatal(state, err)
	}
}

func TestPushStatus(t *testing.T) {
	opts := TestingOptions()
	opts.EnablePush = true
	s, err := Open(filepath.Join(t.TempDir(), "test.db"), opts)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	now := time.Now()
	if _, err := s.db.Exec(`INSERT INTO devices(id,name,model,app_version,scopes,reauth_jwk,created_at)
		VALUES('d_one','d_one','','','msg:read','',?)`, now.UnixMilli()); err != nil {
		t.Fatal(err)
	}
	if err := s.UpdateDevicePush(ctx, "d_one", "fixture_token", now); err != nil {
		t.Fatal(err)
	}
	fcmDelivery := func(body string) (string, int64) {
		t.Helper()
		msg := newMsg("fixture", body, "", now)
		if _, err := s.InsertMessage(ctx, msg, tg, 0); err != nil {
			t.Fatal(err)
		}
		info, err := s.GetMessage(ctx, msg.ID)
		if err != nil || len(info.Deliveries) != 2 {
			t.Fatal(info.Deliveries, err)
		}
		// The worker stamps last_attempt_at when it claims the delivery.
		if _, err := s.db.Exec("UPDATE deliveries SET last_attempt_at=? WHERE id=?", now.UnixMilli(), info.Deliveries[1].ID); err != nil {
			t.Fatal(err)
		}
		return msg.ID, info.Deliveries[1].ID
	}

	id, d := fcmDelivery("skipped by the device's settings")
	if _, err := s.FinishAttempt(ctx, d, id, AttemptResult{State: model.DeliveryFailed, LastError: PushSkipped}, now); err != nil {
		t.Fatal(err)
	}
	st, err := s.PushStatus(ctx)
	if err != nil || st.Registered != 1 || !st.LastFailure.IsZero() || !st.LastDelivered.IsZero() {
		t.Fatal(st, err)
	}

	id, d = fcmDelivery("failed")
	if _, err := s.FinishAttempt(ctx, d, id, AttemptResult{State: model.DeliveryFailed, LastError: "FCM 拒绝了请求"}, now); err != nil {
		t.Fatal(err)
	}
	if st, err = s.PushStatus(ctx); err != nil || st.Error != "FCM 拒绝了请求" || st.LastFailure.IsZero() {
		t.Fatal(st, err)
	}

	id, d = fcmDelivery("delivered")
	if _, err := s.FinishAttempt(ctx, d, id, AttemptResult{State: model.DeliveryDelivered}, now.Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	if st, err = s.PushStatus(ctx); err != nil || st.LastDelivered.Before(st.LastFailure) || st.LastDelivered.IsZero() {
		t.Fatal(st, err)
	}
}

// pushStore opens a store with App push enabled; mod adjusts the options.
func pushStore(t *testing.T, mod func(*Options)) *Store {
	t.Helper()
	opts := TestingOptions()
	opts.EnablePush = true
	if mod != nil {
		mod(&opts)
	}
	s, err := Open(filepath.Join(t.TempDir(), "test.db"), opts)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func addPushDevice(t *testing.T, s *Store, id, prefs string, now time.Time) {
	t.Helper()
	if prefs == "" {
		prefs = "{}"
	}
	if _, err := s.db.Exec(`INSERT INTO devices(id,name,model,app_version,scopes,reauth_jwk,created_at,push_prefs)
		VALUES(?,?,'','','msg:read','',?,?)`, id, id, now.UnixMilli(), prefs); err != nil {
		t.Fatal(err)
	}
	if err := s.UpdateDevicePush(ctx, id, "fixture_token_"+id, now); err != nil {
		t.Fatal(err)
	}
}

func deliveriesOf(t *testing.T, s *Store, id string) MessageInfo {
	t.Helper()
	info, err := s.GetMessage(ctx, id)
	if err != nil {
		t.Fatal(err)
	}
	return info
}

func TestFCMPrimaryWhenNoTelegramTarget(t *testing.T) {
	for _, outcome := range []model.DeliveryState{model.DeliveryDelivered, model.DeliveryFailed} {
		t.Run(string(outcome), func(t *testing.T) {
			s := pushStore(t, nil)
			now := time.Now()
			addPushDevice(t, s, "d_other", "", now)
			msg := newMsg("fixture", "app only", "", now)
			res, err := s.InsertMessage(ctx, msg, nil, 0)
			if err != nil || res.Status != model.StatusQueued || !res.AppOnly {
				t.Fatal(res, err)
			}
			info := deliveriesOf(t, s, msg.ID)
			if len(info.Deliveries) != 1 || info.Deliveries[0].Aux || info.Deliveries[0].Channel != "fcm" || !info.AppOnly {
				t.Fatal(info.Deliveries)
			}
			var bodyState string
			s.db.QueryRow("SELECT body_state FROM messages WHERE id=?", msg.ID).Scan(&bodyState)
			if bodyState != "stored" {
				t.Fatal(bodyState)
			}
			want := model.StatusDelivered
			if outcome == model.DeliveryFailed {
				want = model.StatusFailed
			}
			st, err := s.FinishAttempt(ctx, info.Deliveries[0].ID, msg.ID, AttemptResult{State: outcome}, now)
			if err != nil || st != want {
				t.Fatal(st, err)
			}
			if info := deliveriesOf(t, s, msg.ID); info.Message.FinishedAt.IsZero() || info.Message.Status != want {
				t.Fatal(info.Message)
			}
		})
	}
}

func TestFCMStaysAuxWithTelegram(t *testing.T) {
	s := pushStore(t, nil)
	now := time.Now()
	addPushDevice(t, s, "d_other", "", now)
	msg := newMsg("fixture", "both", "", now)
	res, err := s.InsertMessage(ctx, msg, tg, 0)
	if err != nil || res.AppOnly {
		t.Fatal(res, err)
	}
	info := deliveriesOf(t, s, msg.ID)
	if len(info.Deliveries) != 2 || !info.Deliveries[1].Aux || info.AppOnly {
		t.Fatal(info.Deliveries)
	}
}

func TestPushPrefsSkippedNotFailed(t *testing.T) {
	s := pushStore(t, nil)
	now := time.Now()
	addPushDevice(t, s, "d_sms_only", `{"types":["sms"]}`, now)
	msg := newMsg("fixture", "a notification", "", now)
	msg.Kind = "notification"
	res, err := s.InsertMessage(ctx, msg, nil, 0)
	if err != nil || res.Status != model.StatusNotRouted {
		t.Fatal(res, err)
	}
	info := deliveriesOf(t, s, msg.ID)
	if len(info.Deliveries) != 1 || info.Deliveries[0].State != model.DeliverySkipped || info.Deliveries[0].LastError != SkipType {
		t.Fatal(info.Deliveries)
	}
	if jobs, _ := s.DueJobs(ctx, now, "", 10); len(jobs) != 0 {
		t.Fatal("skipped deliveries must not be attempted", jobs)
	}

	// One device skips, another receives: delivered, not partial.
	addPushDevice(t, s, "d_all", "", now)
	msg = newMsg("fixture", "second", "", now)
	msg.Kind = "notification"
	if _, err := s.InsertMessage(ctx, msg, nil, 0); err != nil {
		t.Fatal(err)
	}
	info = deliveriesOf(t, s, msg.ID)
	var pending model.Delivery
	for _, d := range info.Deliveries {
		if d.State == model.DeliveryPending {
			pending = d
		}
	}
	if len(info.Deliveries) != 2 || pending.Target != "d_all" {
		t.Fatal(info.Deliveries)
	}
	if st, err := s.FinishAttempt(ctx, pending.ID, msg.ID, AttemptResult{State: model.DeliveryDelivered}, now); err != nil || st != model.StatusDelivered {
		t.Fatal(st, err)
	}
	// A send-time skip (e.g. category) is stored as skipped, not failed.
	msg = newMsg("fixture", "third", "", now)
	msg.Kind = "notification"
	if _, err := s.InsertMessage(ctx, msg, nil, 0); err != nil {
		t.Fatal(err)
	}
	info = deliveriesOf(t, s, msg.ID)
	for _, d := range info.Deliveries {
		if d.State == model.DeliveryPending {
			pending = d
		}
	}
	if st, err := s.FinishAttempt(ctx, pending.ID, msg.ID, AttemptResult{State: model.DeliverySkipped, LastError: SkipCategory}, now); err != nil || st != model.StatusNotRouted {
		t.Fatal(st, err)
	}
}

func TestPushPrefsMinPriority(t *testing.T) {
	s := pushStore(t, nil)
	now := time.Now()
	addPushDevice(t, s, "d_quiet", `{"min_priority":"high"}`, now)
	msg := newMsg("fixture", "normal", "", now)
	if _, err := s.InsertMessage(ctx, msg, nil, 0); err != nil {
		t.Fatal(err)
	}
	if d := deliveriesOf(t, s, msg.ID).Deliveries; len(d) != 1 || d[0].LastError != SkipPriority {
		t.Fatal(d)
	}
	msg = newMsg("fixture", "urgent", "", now)
	msg.Priority = model.PriorityHigh
	if res, err := s.InsertMessage(ctx, msg, nil, 0); err != nil || res.Status != model.StatusQueued {
		t.Fatal(res, err)
	}
}

func TestOriginSkippedOnlyWhenAppPrimary(t *testing.T) {
	s := pushStore(t, nil)
	now := time.Now()
	addPushDevice(t, s, "d_origin", "", now)
	msg := newMsg("fixture", "own sms", "", now)
	msg.OriginDevice = "d_origin"
	res, err := s.InsertMessage(ctx, msg, nil, 0)
	if err != nil || res.Status != model.StatusNotRouted || !res.AppOnly {
		t.Fatal(res, err)
	}
	info := deliveriesOf(t, s, msg.ID)
	if len(info.Deliveries) != 1 || info.Deliveries[0].State != model.DeliverySkipped || info.Deliveries[0].LastError != SkipOrigin {
		t.Fatal(info.Deliveries)
	}
	// The body is kept so the source device can still read it in the App.
	if _, _, _, ok, err := s.ReadBody(ctx, msg.ID); err != nil || !ok {
		t.Fatal(ok, err)
	}
	// With Telegram primary the origin is simply left out, as before.
	msg = newMsg("fixture", "own sms 2", "", now)
	msg.OriginDevice = "d_origin"
	if _, err := s.InsertMessage(ctx, msg, tg, 0); err != nil {
		t.Fatal(err)
	}
	if d := deliveriesOf(t, s, msg.ID).Deliveries; len(d) != 1 || d[0].Channel != "telegram" {
		t.Fatal(d)
	}
}

func TestHeldAppOnly(t *testing.T) {
	for _, drop := range []bool{false, true} {
		s := pushStore(t, nil)
		now := time.Now()
		addPushDevice(t, s, "d_other", "", now)
		msg := newMsg("fixture", "held", "", now)
		msg.NeedsClassification = true
		res, err := s.InsertMessage(ctx, msg, nil, 0)
		if err != nil || res.Status != model.StatusClassifying {
			t.Fatal(res, err)
		}
		st, err := s.ResolveHeld(ctx, msg.ID, model.CatService, drop, now)
		want := model.StatusQueued
		if drop {
			want = model.StatusFiltered
		}
		if err != nil || st != want {
			t.Fatal(drop, st, err)
		}
	}
}

func TestNoPushTargetsStillStoresBodyWithRetention(t *testing.T) {
	s := pushStore(t, nil)
	msg := newMsg("fixture", "nobody to push to", "", time.Now())
	res, err := s.InsertMessage(ctx, msg, nil, 0)
	if err != nil || res.Status != model.StatusNotRouted {
		t.Fatal(res, err)
	}
	_, body, _, ok, err := s.ReadBody(ctx, msg.ID)
	if err != nil || !ok || body != "nobody to push to" {
		t.Fatal(body, ok, err)
	}
}

func TestV1ModeKeepsBodyForPush(t *testing.T) {
	s := pushStore(t, func(o *Options) { o.BodyRetention, o.CodeRetention = 0, 0 })
	now := time.Now()
	addPushDevice(t, s, "d_other", "", now)
	msg := newMsg("fixture", "pushed", "", now)
	if _, err := s.InsertMessage(ctx, msg, nil, 0); err != nil {
		t.Fatal(err)
	}
	d := deliveriesOf(t, s, msg.ID).Deliveries[0]
	if st, err := s.FinishAttempt(ctx, d.ID, msg.ID, AttemptResult{State: model.DeliveryDelivered}, now); err != nil || st != model.StatusDelivered {
		t.Fatal(st, err)
	}
	if _, _, _, ok, err := s.ReadBody(ctx, msg.ID); err != nil || !ok {
		t.Fatal("the device fetches the body after the push", ok, err)
	}
	if n, err := s.ExpireBodies(ctx, now.Add(8*24*time.Hour)); err != nil || n != 1 {
		t.Fatal(n, err)
	}
	// A Telegram-only message is still purged at once in V1 mode.
	msg = newMsg("fixture", "telegram", "", now)
	if _, err := s.InsertMessage(ctx, msg, []NewDelivery{{Channel: "telegram", Target: "default"}}, 0); err != nil {
		t.Fatal(err)
	}
	for _, d := range deliveriesOf(t, s, msg.ID).Deliveries {
		if d.Channel == "telegram" {
			if _, err := s.FinishAttempt(ctx, d.ID, msg.ID, AttemptResult{State: model.DeliveryDelivered}, now); err != nil {
				t.Fatal(err)
			}
		}
	}
	var bodyState string
	s.db.QueryRow("SELECT body_state FROM messages WHERE id=?", msg.ID).Scan(&bodyState)
	if bodyState != "stored" {
		// The aux push to d_other keeps it too; only a message without any
		// App delivery is purged immediately.
		t.Fatal(bodyState)
	}
}

func TestV1ModeStoresNoBodyWhenEveryPushIsSkipped(t *testing.T) {
	s := pushStore(t, func(o *Options) { o.BodyRetention, o.CodeRetention = 0, 0 })
	now := time.Now()
	addPushDevice(t, s, "d_origin", "", now)
	msg := newMsg("fixture", "own sms", "", now)
	msg.OriginDevice = "d_origin"
	res, err := s.InsertMessage(ctx, msg, nil, 0)
	if err != nil || res.Status != model.StatusNotRouted {
		t.Fatal(res, err)
	}
	if _, _, _, ok, err := s.ReadBody(ctx, msg.ID); err != nil || ok {
		t.Fatal("V1 mode keeps no body nobody will fetch", ok, err)
	}
}

func TestV1ModePurgesTelegramOnlyBody(t *testing.T) {
	s := pushStore(t, func(o *Options) { o.BodyRetention, o.CodeRetention, o.EnablePush = 0, 0, false })
	now := time.Now()
	msg := newMsg("fixture", "telegram", "", now)
	if _, err := s.InsertMessage(ctx, msg, tg, 0); err != nil {
		t.Fatal(err)
	}
	d := deliveriesOf(t, s, msg.ID).Deliveries[0]
	if _, err := s.FinishAttempt(ctx, d.ID, msg.ID, AttemptResult{State: model.DeliveryDelivered}, now); err != nil {
		t.Fatal(err)
	}
	if _, _, _, ok, _ := s.ReadBody(ctx, msg.ID); ok {
		t.Fatal("V1 mode must purge a Telegram-only body once delivered")
	}
}

func TestRecoverInFlightFCMNotUncertain(t *testing.T) {
	s := pushStore(t, nil)
	now := time.Now()
	addPushDevice(t, s, "d_other", "", now)
	app := newMsg("fixture", "app", "", now)
	if _, err := s.InsertMessage(ctx, app, nil, 0); err != nil {
		t.Fatal(err)
	}
	tgMsg := newMsg("fixture", "telegram", "", now)
	if _, err := s.InsertMessage(ctx, tgMsg, tg, 0); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.Exec("UPDATE deliveries SET state='sending'"); err != nil {
		t.Fatal(err)
	}
	if n, err := s.RecoverInFlight(ctx, now); err != nil || n != 3 {
		t.Fatal(n, err)
	}
	for _, d := range append(deliveriesOf(t, s, app.ID).Deliveries, deliveriesOf(t, s, tgMsg.ID).Deliveries...) {
		if d.State != model.DeliveryRetrying || d.Uncertain != (d.Channel == "telegram") {
			t.Error(d.Channel, d.State, d.Uncertain)
		}
	}
}

func TestRetryMessageAppOnly(t *testing.T) {
	s := pushStore(t, nil)
	now := time.Now()
	addPushDevice(t, s, "d_other", "", now)
	addPushDevice(t, s, "d_sms_only", `{"types":["sms"]}`, now)
	msg := newMsg("fixture", "app", "", now)
	if _, err := s.InsertMessage(ctx, msg, nil, 0); err != nil {
		t.Fatal(err)
	}
	var pushed model.Delivery
	for _, d := range deliveriesOf(t, s, msg.ID).Deliveries {
		if d.State == model.DeliveryPending {
			pushed = d
		}
	}
	if _, err := s.FinishAttempt(ctx, pushed.ID, msg.ID, AttemptResult{State: model.DeliveryDelivered}, now); err != nil {
		t.Fatal(err)
	}
	if err := s.AckDevice(ctx, "d_other", []string{msg.ID}, now); err != nil {
		t.Fatal(err)
	}
	if err := s.RetryMessage(ctx, msg.ID, "test", false, now); err != nil {
		t.Fatal(err)
	}
	info := deliveriesOf(t, s, msg.ID)
	for _, d := range info.Deliveries {
		switch d.Target {
		case "d_other":
			if d.State != model.DeliveryPending || !d.AckedAt.IsZero() {
				t.Error(d)
			}
		case "d_sms_only":
			if d.State != model.DeliverySkipped {
				t.Error("skipped rows stay skipped", d)
			}
		}
	}
	if info.Message.Status != model.StatusQueued {
		t.Fatal(info.Message.Status)
	}
}

func TestPushStatusIgnoresSkipped(t *testing.T) {
	s := pushStore(t, nil)
	now := time.Now()
	addPushDevice(t, s, "d_sms_only", `{"types":["sms"]}`, now)
	msg := newMsg("fixture", "note", "", now)
	if _, err := s.InsertMessage(ctx, msg, nil, 0); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.Exec("UPDATE deliveries SET last_attempt_at=?", now.UnixMilli()); err != nil {
		t.Fatal(err)
	}
	st, err := s.PushStatus(ctx)
	if err != nil || !st.LastFailure.IsZero() {
		t.Fatal(st, err)
	}
	if recent, err := s.RecentDeliveries(ctx, 5); err != nil || len(recent) != 0 {
		t.Fatal(recent, err)
	}
}

func TestLastDeliveryHistoryTelegramOnly(t *testing.T) {
	s := pushStore(t, nil)
	now := time.Now()
	addPushDevice(t, s, "d_other", "", now)
	msg := newMsg("fixture", "app", "", now)
	if _, err := s.InsertMessage(ctx, msg, nil, 0); err != nil {
		t.Fatal(err)
	}
	d := deliveriesOf(t, s, msg.ID).Deliveries[0]
	if _, err := s.db.Exec("UPDATE deliveries SET last_attempt_at=?", now.UnixMilli()); err != nil {
		t.Fatal(err)
	}
	if _, err := s.FinishAttempt(ctx, d.ID, msg.ID, AttemptResult{State: model.DeliveryFailed, LastError: "FCM 拒绝了请求"}, now); err != nil {
		t.Fatal(err)
	}
	h, err := s.LastDeliveryHistory(ctx)
	if err != nil || !h.LastProblem.IsZero() || !h.LastDelivered.IsZero() {
		t.Fatal(h, err)
	}
	recent, err := s.RecentDeliveries(ctx, 5)
	if err != nil || len(recent) != 1 || recent[0].Channel != "fcm" || recent[0].Target != "d_other" {
		t.Fatal(recent, err)
	}
}

func TestInboxMetaAppOnlyAndUnread(t *testing.T) {
	s := pushStore(t, nil)
	now := time.Now()
	app := newMsg("fixture", "app 验证码 123456", "", now)
	if _, err := s.InsertMessage(ctx, app, nil, 0); err != nil {
		t.Fatal(err)
	}
	tgMsg := newMsg("fixture", "telegram", "", now.Add(time.Second))
	if _, err := s.InsertMessage(ctx, tgMsg, tg, 0); err != nil {
		t.Fatal(err)
	}
	list, err := s.InboxPage(ctx, time.Time{}, "", 10)
	if err != nil || len(list) != 2 || list[0].ID != tgMsg.ID || list[0].AppOnly || !list[1].AppOnly {
		t.Fatal(list, err)
	}
	if n, err := s.UnreadCount(ctx); err != nil || n != 2 {
		t.Fatal(n, err)
	}
	if changed, err := s.SetMessageRead(ctx, tgMsg.ID, true, now); err != nil || !changed {
		t.Fatal(changed, err)
	}
	if changed, err := s.SetMessageRead(ctx, tgMsg.ID, true, now); err != nil || changed {
		t.Fatal("second read is not a change", changed, err)
	}
	if n, _ := s.UnreadCount(ctx); n != 1 {
		t.Fatal(n)
	}
	unread, err := s.InboxPageFiltered(ctx, time.Time{}, "", 10, InboxFilter{Unread: true})
	if err != nil || len(unread) != 1 || unread[0].ID != app.ID || unread[0].Read {
		t.Fatal(unread, err)
	}
	codes, err := s.InboxPageFiltered(ctx, time.Time{}, "", 10, InboxFilter{HasCode: true})
	if err != nil || len(codes) != 1 || codes[0].ID != app.ID {
		t.Fatal(codes, err)
	}
}

func TestPushDevicesExcludesActor(t *testing.T) {
	s := pushStore(t, nil)
	now := time.Now()
	addPushDevice(t, s, "d_a", "", now)
	addPushDevice(t, s, "d_b", "", now)
	got, err := s.PushDevices(ctx, "d_a", now)
	if err != nil || len(got) != 1 || got[0].ID != "d_b" || got[0].Token == "" {
		t.Fatal(got, err)
	}
}
