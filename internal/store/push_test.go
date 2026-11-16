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
