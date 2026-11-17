package httpapi

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"
)

func TestInboxReadBatch(t *testing.T) {
	signals := &signalRecorder{}
	e := setup(t, func(e *env) {
		e.api.DPoP, e.api.PublicURL = deviceSetupDPoP(), "https://fixture.invalid"
		e.api.CookieAdmin = func(w http.ResponseWriter, r *http.Request, high bool) (string, bool) {
			if high || r.Header.Get("X-Test-Session") != "ok" {
				writeError(w, r, errUnauthorized)
				return "", false
			}
			return "web:google:1", true
		}
	})
	e.api.PushSignal = signals.record
	call, deviceID := pairTestDevice(t, e, "msg:read")
	sender, _ := pairTestDevice(t, e, "msg:send")
	var ids []string
	for _, body := range []string{`{"body":"第一条","type":"sms"}`, `{"body":"验证码 482913","type":"sms"}`, `{"body":"第三条","type":"sms"}`} {
		sub := e.post(t, e.iphone, body)
		if sub.code != 202 {
			t.Fatal(sub.code, sub.body)
		}
		ids = append(ids, sub.body["id"].(string))
	}
	sync := call("GET", "/v1/sync?since=0", "")
	since := int64(sync.body["next_since"].(float64))

	// Validation.
	many, _ := json.Marshal(map[string]any{"ids": make([]string, 501)})
	for _, bad := range []string{``, `{}`, `{"ids":[]}`, `{"ids":["m_bad"]}`, string(many), `{"all":true}`,
		`{"all":true,"up_to_id":"` + ids[1] + `","ids":["` + ids[0] + `"]}`, `{"ids":["` + ids[0] + `"],"has_code":true}`, `{"ids":["` + ids[0] + `"],"x":1}`} {
		if r := call("POST", "/v1/inbox/read", bad); r.code != 400 || errCode(r) != "invalid_body" {
			t.Fatal(bad, r.code, r.body)
		}
	}
	if r := call("POST", "/v1/inbox/unread", `{"all":true,"up_to_id":"`+ids[1]+`"}`); r.code != 400 {
		t.Fatal("unread is by ID only", r.code, r.body)
	}
	if r := call("POST", "/v1/inbox/read", `{"all":true,"up_to_id":"m_00000000000000000000000000"}`); r.code != 409 || errCode(r) != "stale_list" {
		t.Fatal(r.code, r.body)
	}
	// Authorization: a token without msg:read, and no credentials at all.
	if r := sender("POST", "/v1/inbox/read", `{"ids":["`+ids[0]+`"]}`); r.code != 403 || errCode(r) != "insufficient_scope" {
		t.Fatal(r.code, r.body)
	}
	if r := e.do(t, "POST", "/v1/inbox/read", "", "application/json", `{"ids":["`+ids[0]+`"]}`); r.code != 401 {
		t.Fatal(r.code, r.body)
	}
	if len(signals.all()) != 0 {
		t.Fatal(signals.all())
	}

	// All up to the second message: the third (arrived later) stays unread.
	r := call("POST", "/v1/inbox/read", `{"all":true,"up_to_id":"`+ids[1]+`"}`)
	if r.code != 200 || r.body["changed"] != float64(2) || r.body["unread_count"] != float64(1) {
		t.Fatal(r.code, r.body)
	}
	// The changed IDs come back too, newest first, so the caller (skipped by
	// the signal) can clear its own notifications.
	if got, _ := json.Marshal(r.body["ids"]); string(got) != `["`+ids[1]+`","`+ids[0]+`"]` {
		t.Fatal(string(got))
	}
	if got := signals.all(); len(got) != 1 || got[0] != "read:"+ids[1]+","+ids[0]+":"+deviceID {
		t.Fatal("one signal with every changed ID, to the other devices", got)
	}
	// Idempotent: no change, no signal.
	r = call("POST", "/v1/inbox/read", `{"all":true,"up_to_id":"`+ids[1]+`"}`)
	if r.code != 200 || r.body["changed"] != float64(0) || len(r.body["ids"].([]any)) != 0 || len(signals.all()) != 1 {
		t.Fatal(r.code, r.body, signals.all())
	}
	changes := call("GET", "/v1/sync?since="+jsonInt(since), "")
	if items := changes.body["changes"].([]any); len(items) != 2 || items[0].(map[string]any)["read"] != true {
		t.Fatal(changes.body)
	}

	// Multi-select: mark unread, then read by ID (unknown IDs are skipped).
	r = call("POST", "/v1/inbox/unread", `{"ids":["`+ids[0]+`","`+ids[1]+`"]}`)
	if r.code != 200 || r.body["changed"] != float64(2) || r.body["unread_count"] != float64(3) || len(signals.all()) != 1 {
		t.Fatal(r.code, r.body, signals.all())
	}
	// Codes only, from the web panel: no device to skip.
	r = e.do(t, "POST", "/v1/inbox/read", "", "application/json", `{"all":true,"up_to_id":"`+ids[2]+`","has_code":true}`, "X-Test-Session", "ok")
	if r.code != 200 || r.body["changed"] != float64(1) || r.body["unread_count"] != float64(2) {
		t.Fatal(r.code, r.body)
	}
	if got := signals.all(); len(got) != 2 || got[1] != "read:"+ids[1]+":" {
		t.Fatal(got)
	}
	r = call("POST", "/v1/inbox/read", `{"ids":["`+ids[2]+`","m_00000000000000000000000000","`+ids[2]+`"]}`)
	if r.code != 200 || r.body["changed"] != float64(1) || r.body["unread_count"] != float64(1) {
		t.Fatal(r.code, r.body)
	}

	// One audit entry per batch.
	entries, _, err := e.st.ReadAudit(bg)
	if err != nil {
		t.Fatal(err)
	}
	var batches []string
	for _, a := range entries {
		if strings.HasPrefix(a.Action, "message.read") || strings.HasPrefix(a.Action, "message.unread") {
			batches = append(batches, a.Actor+" "+a.Action+" "+a.Target)
		}
	}
	want := "device:" + deviceID + " message.read_all count=2|device:" + deviceID + " message.read_all count=0|device:" + deviceID +
		" message.unread_batch count=2|web:google:1 message.read_all count=1|device:" + deviceID + " message.read_batch count=1"
	if strings.Join(batches, "|") != want {
		t.Fatal(batches)
	}

	// Rate limited per actor; validation failures count too.
	limited := false
	for range 25 {
		r := e.do(t, "POST", "/v1/inbox/unread", "", "application/json", `{}`, "X-Test-Session", "ok")
		if r.code == 429 {
			limited = errCode(r) == "rate_limited"
			break
		}
	}
	if !limited {
		t.Fatal("no rate limit")
	}
	if r := call("POST", "/v1/inbox/unread", `{"ids":["`+ids[2]+`"]}`); r.code != 200 {
		t.Fatal("another actor is not limited", r.code, r.body)
	}
}

func jsonInt(v int64) string {
	b, _ := json.Marshal(v)
	return string(b)
}
