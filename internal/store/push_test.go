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
