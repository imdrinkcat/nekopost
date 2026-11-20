package delivery

import (
	"bufio"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"fmt"
	"io"
	"math/big"
	"mime"
	"mime/quotedprintable"
	"net"
	"net/mail"
	"strings"
	"sync"
	"testing"
	"time"

	"nekopost/internal/config"
	"nekopost/internal/health"
	"nekopost/internal/model"
	"nekopost/internal/store"
)

// fakeSMTP is a small in-process SMTP server for the email channel tests.
type fakeSMTP struct {
	t             *testing.T
	ln            net.Listener
	cert          tls.Certificate
	pool          *x509.CertPool
	implicit      bool   // TLS from the first byte
	startTLS      bool   // advertise STARTTLS
	user          string // required AUTH PLAIN / LOGIN credentials ("" = no auth offered)
	pass          string
	loginOnly     bool
	rcptReply     string // e.g. "550 no such user"
	dataReply     string // e.g. "451 try later"
	dropAfterData bool

	mu          sync.Mutex
	messages    [][]byte
	rcpts       []string
	authed      bool
	authInClear bool
	sawHello    string
}

func newFakeSMTP(t *testing.T, mod func(*fakeSMTP)) *fakeSMTP {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "127.0.0.1"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}, IsCA: true, BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	leaf, _ := x509.ParseCertificate(der)
	pool := x509.NewCertPool()
	pool.AddCert(leaf)
	f := &fakeSMTP{t: t, cert: tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, pool: pool, startTLS: true}
	if mod != nil {
		mod(f)
	}
	f.ln, err = net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { f.ln.Close() })
	go func() {
		for {
			c, err := f.ln.Accept()
			if err != nil {
				return
			}
			go f.serve(c)
		}
	}()
	return f
}

func (f *fakeSMTP) channel() *EmailChannel {
	port := f.ln.Addr().(*net.TCPAddr).Port
	sec := "starttls"
	if f.implicit {
		sec = "tls"
	}
	return &EmailChannel{
		Host: "127.0.0.1", Port: port, Security: sec, Username: f.user, Password: f.pass,
		From: "nekopost@example.com", To: []string{"me@example.org"}, Privacy: config.EmailPrivacyFull,
		Location: time.UTC, TLSConfig: &tls.Config{RootCAs: f.pool},
	}
}

func (f *fakeSMTP) serve(conn net.Conn) {
	defer func() { conn.Close() }()
	tlsConf := &tls.Config{Certificates: []tls.Certificate{f.cert}}
	tlsOn := false
	if f.implicit {
		conn = tls.Server(conn, tlsConf)
		tlsOn = true
	}
	r := bufio.NewReader(conn)
	reply := func(s string) { fmt.Fprintf(conn, "%s\r\n", s) }
	reply("220 fake ESMTP")
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return
		}
		line = strings.TrimRight(line, "\r\n")
		up := strings.ToUpper(line)
		switch {
		case strings.HasPrefix(up, "EHLO"), strings.HasPrefix(up, "HELO"):
			f.mu.Lock()
			f.sawHello = strings.TrimSpace(line[4:])
			f.mu.Unlock()
			reply("250-fake")
			if f.startTLS && !tlsOn {
				reply("250-STARTTLS")
			}
			if f.user != "" {
				if f.loginOnly {
					reply("250-AUTH LOGIN")
				} else {
					reply("250-AUTH PLAIN LOGIN")
				}
			}
			reply("250 8BITMIME")
		case up == "STARTTLS":
			reply("220 go ahead")
			conn = tls.Server(conn, tlsConf)
			r = bufio.NewReader(conn)
			tlsOn = true
		case strings.HasPrefix(up, "AUTH PLAIN"):
			raw, _ := base64.StdEncoding.DecodeString(strings.TrimSpace(line[len("AUTH PLAIN"):]))
			f.finishAuth(reply, !tlsOn, string(raw) == "\x00"+f.user+"\x00"+f.pass)
		case up == "AUTH LOGIN":
			reply("334 VXNlcm5hbWU6")
			u, _ := r.ReadString('\n')
			reply("334 UGFzc3dvcmQ6")
			p, _ := r.ReadString('\n')
			ud, _ := base64.StdEncoding.DecodeString(strings.TrimSpace(u))
			pd, _ := base64.StdEncoding.DecodeString(strings.TrimSpace(p))
			f.finishAuth(reply, !tlsOn, string(ud) == f.user && string(pd) == f.pass)
		case strings.HasPrefix(up, "MAIL FROM:"):
			reply("250 ok")
		case strings.HasPrefix(up, "RCPT TO:"):
			if f.rcptReply != "" {
				reply(f.rcptReply)
				continue
			}
			f.mu.Lock()
			f.rcpts = append(f.rcpts, strings.Trim(line[len("RCPT TO:"):], " <>"))
			f.mu.Unlock()
			reply("250 ok")
		case up == "DATA":
			reply("354 end with .")
			var data []byte
			for {
				l, err := r.ReadBytes('\n')
				if err != nil {
					return
				}
				if string(l) == ".\r\n" {
					break
				}
				data = append(data, l...)
			}
			if f.dropAfterData {
				return
			}
			if f.dataReply != "" {
				reply(f.dataReply)
				continue
			}
			f.mu.Lock()
			f.messages = append(f.messages, data)
			f.mu.Unlock()
			reply("250 queued")
		case up == "QUIT":
			reply("221 bye")
			return
		default:
			reply("502 unknown")
		}
	}
}

func (f *fakeSMTP) finishAuth(reply func(string), clear, ok bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if clear {
		f.authInClear = true
	}
	if !ok {
		reply("535 5.7.8 authentication failed")
		return
	}
	f.authed = true
	reply("235 ok")
}

func (f *fakeSMTP) received() [][]byte {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([][]byte(nil), f.messages...)
}

func emailJob(title, body string) store.Job {
	return store.Job{
		Delivery: model.Delivery{Channel: "email", Target: "default", Aux: true},
		Message: model.Message{ID: "m_20261120_abc", SourceID: "iphone", Kind: "sms", Priority: model.PriorityNormal,
			Title: title, Body: body, ReceivedAt: time.Date(2026, 11, 20, 8, 0, 0, 0, time.UTC)},
		Source: model.Source{ID: "iphone", Name: "iPhone"},
	}
}

func deliverEmail(ch *EmailChannel, job store.Job) (Result, int) {
	calls := 0
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	res := ch.Deliver(ctx, job, func(total, sent int, _ int64) { calls++ })
	return res, calls
}

func parseMail(t *testing.T, raw []byte) (*mail.Message, string) {
	t.Helper()
	m, err := mail.ReadMessage(strings.NewReader(string(raw)))
	if err != nil {
		t.Fatal(err)
	}
	body, err := io.ReadAll(quotedprintable.NewReader(m.Body))
	if err != nil {
		t.Fatal(err)
	}
	return m, string(body)
}

func TestEmailStartTLSDeliversAndAuthenticatesOnlyAfterTLS(t *testing.T) {
	f := newFakeSMTP(t, func(f *fakeSMTP) { f.user, f.pass = "neko", "s3cret-pass" })
	res, progress := deliverEmail(f.channel(), emailJob("招商银行", "您的验证码是 123456，5 分钟内有效。"))
	if res.Outcome != Delivered || res.PartsSent != 1 || progress != 1 {
		t.Fatalf("%+v progress=%d", res, progress)
	}
	if !f.authed || f.authInClear {
		t.Fatalf("authed=%v inClear=%v: credentials must only go over TLS", f.authed, f.authInClear)
	}
	if f.sawHello != "example.com" {
		t.Errorf("EHLO name %q", f.sawHello)
	}
	if len(f.rcpts) != 1 || f.rcpts[0] != "me@example.org" {
		t.Fatalf("rcpts %v", f.rcpts)
	}
	msgs := f.received()
	if len(msgs) != 1 {
		t.Fatal(len(msgs))
	}
	m, body := parseMail(t, msgs[0])
	subj, _ := new(mime.WordDecoder).DecodeHeader(m.Header.Get("Subject"))
	if subj != "[iPhone] 招商银行" {
		t.Errorf("subject %q", subj)
	}
	if !strings.Contains(body, "您的验证码是 123456，5 分钟内有效。") || !strings.Contains(body, "消息 ID：m_20261120_abc") {
		t.Errorf("body:\n%s", body)
	}
	if got := m.Header.Get("Message-ID"); got != "<m_20261120_abc.email@example.com>" {
		t.Errorf("Message-ID %q", got)
	}
	if strings.Contains(string(msgs[0]), "s3cret-pass") {
		t.Error("the password leaked into the message")
	}
}

func TestEmailImplicitTLSAndLoginAuth(t *testing.T) {
	f := newFakeSMTP(t, func(f *fakeSMTP) {
		f.implicit, f.startTLS, f.user, f.pass, f.loginOnly = true, false, "neko", "pw", true
	})
	res, _ := deliverEmail(f.channel(), emailJob("", "hello"))
	if res.Outcome != Delivered || !f.authed {
		t.Fatalf("%+v authed=%v", res, f.authed)
	}
	if len(f.received()) != 1 {
		t.Fatal("not delivered")
	}
}

func TestEmailRefusesToSendInTheClear(t *testing.T) {
	f := newFakeSMTP(t, func(f *fakeSMTP) { f.startTLS, f.user, f.pass = false, "neko", "pw" })
	res, progress := deliverEmail(f.channel(), emailJob("t", "b"))
	if res.Outcome != RetrySafe || res.FailKind != health.FailConfig || progress != 0 {
		t.Fatalf("%+v", res)
	}
	if f.authed || f.authInClear || len(f.received()) != 0 {
		t.Fatal("nothing may be sent or authenticated without TLS")
	}
}

func TestEmailUntrustedCertificateIsRejected(t *testing.T) {
	f := newFakeSMTP(t, nil)
	ch := f.channel()
	ch.TLSConfig = nil // the system roots do not know the test certificate
	res, _ := deliverEmail(ch, emailJob("t", "b"))
	if res.Outcome != RetrySafe || res.FailKind != health.FailConfig || !strings.Contains(res.Err, "证书") {
		t.Fatalf("%+v", res)
	}
	if len(f.received()) != 0 {
		t.Fatal("delivered over an unverified connection")
	}
}

func TestEmailFailureOutcomes(t *testing.T) {
	cases := []struct {
		name    string
		mod     func(*fakeSMTP)
		pass    string
		want    Outcome
		kind    health.FailKind
		wantErr string
	}{
		{"wrong password", func(f *fakeSMTP) { f.user, f.pass = "neko", "right" }, "wrong", RetrySafe, health.FailConfig, "认证失败"},
		{"recipient rejected", func(f *fakeSMTP) { f.rcptReply = "550 5.1.1 no such user" }, "", Permanent, health.FailConfig, "550"},
		{"temporary failure", func(f *fakeSMTP) { f.dataReply = "451 4.3.0 try again later" }, "", RetrySafe, health.FailOther, "451"},
		{"rejected as spam", func(f *fakeSMTP) { f.dataReply = "554 5.7.1 rejected" }, "", Permanent, health.FailOther, "554"},
		{"connection lost before the answer", func(f *fakeSMTP) { f.dropAfterData = true }, "", RetryUncertain, health.FailNetwork, "未收到"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			f := newFakeSMTP(t, c.mod)
			ch := f.channel()
			if c.pass != "" {
				ch.Password = c.pass
			}
			res, progress := deliverEmail(ch, emailJob("t", "b"))
			if res.Outcome != c.want || res.FailKind != c.kind || !strings.Contains(res.Err, c.wantErr) || progress != 0 {
				t.Fatalf("%+v progress=%d", res, progress)
			}
		})
	}
}

func TestEmailServerDown(t *testing.T) {
	f := newFakeSMTP(t, nil)
	ch := f.channel()
	f.ln.Close()
	res, _ := deliverEmail(ch, emailJob("t", "b"))
	if res.Outcome != RetrySafe || res.FailKind != health.FailNetwork {
		t.Fatalf("%+v", res)
	}
}

func TestEmailUnknownTargetIsPermanent(t *testing.T) {
	f := newFakeSMTP(t, nil)
	job := emailJob("t", "b")
	job.Delivery.Target = "other"
	if res, _ := deliverEmail(f.channel(), job); res.Outcome != Permanent {
		t.Fatalf("%+v", res)
	}
}

func headerValues(t *testing.T, raw []byte) mail.Header {
	t.Helper()
	m, _ := parseMail(t, raw)
	return m.Header
}

func TestEmailTextCannotInjectHeaders(t *testing.T) {
	job := emailJob("hi\r\nBcc: evil@example.net\r\nX-Evil: 1", "body")
	job.Source.Name = "Phone\r\nBcc: evil@example.net"
	subject, body := emailText(job, config.EmailPrivacyFull, time.UTC)
	raw := mimeMessage("nekopost@example.com", []string{"me@example.org"}, subject, body, "<id@example.com>", time.Now(), false)
	h := headerValues(t, raw)
	for _, bad := range []string{"Bcc", "X-Evil"} {
		if h.Get(bad) != "" {
			t.Errorf("%s header injected", bad)
		}
	}
	if strings.ContainsAny(subject, "\r\n") {
		t.Errorf("subject %q", subject)
	}
	// Whatever the sender wrote, the envelope is only what was configured.
	if got := h.Get("To"); got != "<me@example.org>" {
		t.Errorf("To %q", got)
	}
}

func TestEmailPrivacyLevels(t *testing.T) {
	job := emailJob("招商银行", "您的验证码是 123456。详情 https://example.com/x?t=abc")
	_, full := emailText(job, config.EmailPrivacyFull, time.UTC)
	if !strings.Contains(full, "123456") || !strings.Contains(full, "https://example.com/x?t=abc") {
		t.Errorf("full:\n%s", full)
	}
	subject, masked := emailText(job, config.EmailPrivacyMasked, time.UTC)
	if strings.Contains(masked, "123456") || strings.Contains(masked, "example.com/x") || !strings.Contains(masked, "已隐藏") {
		t.Errorf("masked:\n%s", masked)
	}
	if strings.Contains(subject, "123456") {
		t.Errorf("masked subject %q", subject)
	}
	subject, ping := emailText(job, config.EmailPrivacyPing, time.UTC)
	for _, leak := range []string{"123456", "招商银行", "example.com/x", "验证码"} {
		if strings.Contains(ping, leak) || strings.Contains(subject, leak) {
			t.Errorf("ping mode leaked %q:\n%s\n%s", leak, subject, ping)
		}
	}
	if !strings.Contains(ping, "iPhone") {
		t.Errorf("ping:\n%s", ping)
	}
}

func TestEmailSubjectIsClippedAndEncoded(t *testing.T) {
	job := emailJob(strings.Repeat("很长的标题", 40), "b")
	subject, _ := emailText(job, config.EmailPrivacyFull, time.UTC)
	if n := len([]rune(subject)); n > maxSubjectRunes+1 {
		t.Errorf("subject has %d runes", n)
	}
	raw := mimeMessage("a@example.com", []string{"b@example.org"}, subject, "x", "<i@example.com>", time.Now(), true)
	for _, line := range strings.Split(string(raw), "\r\n") {
		if len(line) > 998 {
			t.Fatalf("header line of %d bytes", len(line))
		}
	}
	h := headerValues(t, raw)
	if h.Get("Importance") != "high" || h.Get("X-Priority") != "1" {
		t.Errorf("priority headers: %v", h)
	}
	dec, err := new(mime.WordDecoder).DecodeHeader(h.Get("Subject"))
	if err != nil || dec != subject {
		t.Errorf("subject round trip: %q err=%v", dec, err)
	}
}

// submitBoth stores a message with a Telegram delivery and an auxiliary
// email delivery, the way the router does when email is enabled.
func (e *env) submitBoth(t *testing.T, body string) string {
	t.Helper()
	m := &model.Message{ID: model.NewMessageID(e.now), SourceID: "iphone", Kind: "sms", Priority: model.PriorityNormal,
		Title: "Bank", Body: body, ContentHash: body, ReceivedAt: e.now}
	_, err := e.st.InsertMessage(ctx, m, []store.NewDelivery{
		{Channel: "telegram", Target: "default"},
		{Channel: "email", Target: "default", Aux: true},
	}, 0)
	if err != nil {
		t.Fatal(err)
	}
	return m.ID
}

func deliveryOf(t *testing.T, e *env, id, channel string) (model.Status, model.Delivery) {
	t.Helper()
	mi, err := e.st.GetMessage(ctx, id)
	if err != nil {
		t.Fatal(err)
	}
	for _, d := range mi.Deliveries {
		if d.Channel == channel {
			return mi.Message.Status, d
		}
	}
	t.Fatalf("no %s delivery", channel)
	return "", model.Delivery{}
}

func TestEmailIsDeliveredNextToTelegram(t *testing.T) {
	e := setup(t, "")
	f := newFakeSMTP(t, nil)
	e.w.Channels["email"] = f.channel()
	id := e.submitBoth(t, "验证码 123456")
	if n, err := e.w.RunOnce(ctx); err != nil || n != 2 {
		t.Fatal(n, err)
	}
	st, mail := deliveryOf(t, e, id, "email")
	_, tgd := deliveryOf(t, e, id, "telegram")
	if st != model.StatusDelivered || mail.State != model.DeliveryDelivered || tgd.State != model.DeliveryDelivered || !mail.Aux {
		t.Fatalf("%s email=%+v telegram=%+v", st, mail, tgd)
	}
	msgs := f.received()
	if len(msgs) != 1 {
		t.Fatal(len(msgs))
	}
	if _, body := parseMail(t, msgs[0]); !strings.Contains(body, "验证码 123456") {
		t.Errorf("body:\n%s", body)
	}
}

func TestEmailFailureNeverChangesTheMessageStatus(t *testing.T) {
	e := setup(t, "")
	f := newFakeSMTP(t, func(f *fakeSMTP) { f.rcptReply = "550 5.1.1 no such user" })
	e.w.Channels["email"] = f.channel()
	id := e.submitBoth(t, "hello")
	if _, err := e.w.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	st, mail := deliveryOf(t, e, id, "email")
	if st != model.StatusDelivered {
		t.Fatalf("message status %s: a failed email must not affect it", st)
	}
	if mail.State != model.DeliveryFailed || !strings.Contains(mail.LastError, "550") {
		t.Fatalf("%+v", mail)
	}
	var seen bool
	for _, ev := range e.tracker.RecentErrors(20) {
		seen = seen || ev.Component == "邮件" && strings.Contains(ev.Msg, "550")
	}
	if !seen {
		t.Error("the failed email is not listed under recent errors")
	}
	// Email trouble must not turn the Telegram health into a problem.
	if snap := e.tracker.Snapshot(); !snap.SendFail.IsZero() || snap.SendConsecutiveFails != 0 {
		t.Errorf("email failure was counted as a Telegram delivery problem: %+v", snap)
	}
}

func TestUnconfiguredEmailRowsAreFinalizedNotStuck(t *testing.T) {
	e := setup(t, "")
	e.w.Unconfigured = []string{"email"}
	id := e.submitBoth(t, "hello")
	if _, err := e.w.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	st, mail := deliveryOf(t, e, id, "email")
	if st != model.StatusDelivered || mail.State != model.DeliveryFailed {
		t.Fatalf("%s %+v", st, mail)
	}
}
