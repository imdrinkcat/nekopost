package httpapi

import (
	"context"
	"net/http"
	"testing"

	"nekopost/internal/auth"
	"nekopost/internal/delivery"
)

func TestTestPushReportsFCMReason(t *testing.T) {
	var fail error
	e := setup(t, func(e *env) {
		e.api.DPoP = auth.NewDPoP()
		e.api.PublicURL = "https://fixture.invalid"
		e.api.CookieAdmin = func(http.ResponseWriter, *http.Request, bool) (string, bool) { return "web:google:1", true }
		e.api.TestPush = func(context.Context, string) error { return fail }
	})
	fail = &delivery.PushError{Reason: "FCM 项目与设备不匹配"}
	r := e.do(t, "POST", "/v1/admin/devices/d_fixture/test-push", "", "", "")
	if r.code != 502 || errCode(r) != "push_failed" {
		t.Fatal(r.code, r.body)
	}
	if msg, _ := r.body["error"].(map[string]any)["message"].(string); msg != "FCM 项目与设备不匹配" {
		t.Fatal(r.body)
	}
	fail = nil
	if r := e.do(t, "POST", "/v1/admin/devices/d_fixture/test-push", "", "", ""); r.code != 204 {
		t.Fatal(r.code, r.body)
	}
}
