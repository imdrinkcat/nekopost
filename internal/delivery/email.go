package delivery

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"net"
	"net/smtp"
	"net/textproto"
	"strconv"
	"strings"
	"time"

	"nekopost/internal/config"
	"nekopost/internal/health"
	"nekopost/internal/store"
)

// EmailChannel delivers a copy of a message by email through an SMTP server
// the administrator configures. It speaks only TLS: STARTTLS (required, never
// downgraded) or implicit TLS; "none" exists for a relay on the same host.
type EmailChannel struct {
	Host     string
	Port     int
	Security string // "starttls", "tls" or "none"
	Username string
	Password string
	From     string
	To       []string
	Privacy  string
	Location *time.Location
	Now      func() time.Time

	// Test hooks.
	TLSConfig *tls.Config
	Dial      func(ctx context.Context, network, addr string) (net.Conn, error)
}

// NewEmailChannel builds the channel from validated settings and the SMTP
// password (see config.EmailConfig.Password).
func NewEmailChannel(c config.EmailConfig, password string, loc *time.Location) *EmailChannel {
	return &EmailChannel{
		Host: c.Host, Port: c.Port, Security: c.Security, Username: c.Username, Password: password,
		From: c.From, To: c.To, Privacy: c.Privacy, Location: loc,
	}
}

// Name implements Channel.
func (e *EmailChannel) Name() string { return "email" }

// Deliver implements Channel.
func (e *EmailChannel) Deliver(ctx context.Context, job store.Job, progress func(total, sent int, tgMessageID int64)) Result {
	if job.Delivery.Target != "default" {
		return Result{Outcome: Permanent, FailKind: health.FailConfig, Err: "未知的投递目标 " + job.Delivery.Target}
	}
	now := time.Now
	if e.Now != nil {
		now = e.Now
	}
	subject, body := emailText(job, e.Privacy, e.Location)
	raw := mimeMessage(e.From, e.To, subject, body, messageIDFor(job.Message.ID, e.From), now(), priorityHigh(job.Message))
	res := e.send(ctx, raw)
	if res.Outcome == Delivered {
		progress(1, 1, 0)
	}
	return res
}

// SendTest sends a fixed test message (nekopost check --send-test).
func (e *EmailChannel) SendTest(ctx context.Context, text string) error {
	raw := mimeMessage(e.From, e.To, "Nekopost 测试邮件", text,
		messageIDFor("test"+strconv.FormatInt(time.Now().UnixNano(), 36), e.From), time.Now(), false)
	res := e.send(ctx, raw)
	if res.Outcome != Delivered {
		return errors.New(res.Err)
	}
	return nil
}

func (e *EmailChannel) tlsConfig() *tls.Config {
	if e.TLSConfig != nil {
		c := e.TLSConfig.Clone()
		if c.ServerName == "" {
			c.ServerName = e.Host
		}
		return c
	}
	return &tls.Config{ServerName: e.Host, MinVersion: tls.VersionTLS12}
}

type smtpStage int

const (
	stageConnect smtpStage = iota
	stageTLS
	stageAuth
	stageEnvelope
	stageDataWrite
	stageDataCommit // the server's answer to the end of DATA decides acceptance
)

func (e *EmailChannel) send(ctx context.Context, raw []byte) Result {
	deadline, ok := ctx.Deadline()
	if !ok || deadline.After(time.Now().Add(2*time.Minute)) {
		deadline = time.Now().Add(2 * time.Minute)
	}
	addr := net.JoinHostPort(e.Host, strconv.Itoa(e.Port))
	dial := e.Dial
	if dial == nil {
		d := net.Dialer{Timeout: 15 * time.Second}
		dial = d.DialContext
	}
	conn, err := dial(ctx, "tcp", addr)
	if err != nil {
		return classifySMTP(stageConnect, err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(deadline)
	// Unblock a stuck read or write when the attempt is cancelled.
	stop := context.AfterFunc(ctx, func() { _ = conn.SetDeadline(time.Now()) })
	defer stop()

	if e.Security == "tls" {
		tc := tls.Client(conn, e.tlsConfig())
		if err := tc.HandshakeContext(ctx); err != nil {
			return classifySMTP(stageTLS, err)
		}
		conn = tc
	}
	c, err := smtp.NewClient(conn, e.Host)
	if err != nil {
		return classifySMTP(stageConnect, err)
	}
	defer c.Close()
	if i := strings.LastIndex(e.From, "@"); i >= 0 {
		if err := c.Hello(e.From[i+1:]); err != nil {
			return classifySMTP(stageConnect, err)
		}
	}
	if e.Security == "starttls" {
		if ok, _ := c.Extension("STARTTLS"); !ok {
			return Result{Outcome: RetrySafe, FailKind: health.FailConfig,
				Err: "邮件服务器不支持 STARTTLS，已拒绝以明文发送（可改用 NEKOPOST_SMTP_SECURITY=tls）"}
		}
		if err := c.StartTLS(e.tlsConfig()); err != nil {
			return classifySMTP(stageTLS, err)
		}
	}
	if e.Username != "" {
		auth, err := e.authFor(c)
		if err != nil {
			return Result{Outcome: RetrySafe, FailKind: health.FailConfig, Err: err.Error()}
		}
		if err := c.Auth(auth); err != nil {
			return classifySMTP(stageAuth, err)
		}
	}
	if err := c.Mail(e.From); err != nil {
		return classifySMTP(stageEnvelope, err)
	}
	for _, to := range e.To {
		if err := c.Rcpt(to); err != nil {
			return classifySMTP(stageEnvelope, err)
		}
	}
	w, err := c.Data()
	if err != nil {
		return classifySMTP(stageEnvelope, err)
	}
	if _, err := w.Write(raw); err != nil {
		return classifySMTP(stageDataWrite, err)
	}
	if err := w.Close(); err != nil {
		return classifySMTP(stageDataCommit, err)
	}
	_ = c.Quit()
	return Result{Outcome: Delivered, PartsTotal: 1, PartsSent: 1}
}

// authFor picks PLAIN, or LOGIN for servers that only offer it. Both are
// used only after TLS (or on a loopback relay).
func (e *EmailChannel) authFor(c *smtp.Client) (smtp.Auth, error) {
	_, mechs := c.Extension("AUTH")
	has := func(m string) bool {
		for _, f := range strings.Fields(strings.ToUpper(mechs)) {
			if f == m {
				return true
			}
		}
		return false
	}
	switch {
	case has("PLAIN"):
		return &plainAuth{user: e.Username, pass: e.Password}, nil
	case has("LOGIN"):
		return &loginAuth{user: e.Username, pass: e.Password}, nil
	}
	return nil, errors.New("邮件服务器不支持 PLAIN 或 LOGIN 认证")
}

// plainAuth is smtp.PlainAuth without the host check: the connection has
// already been verified (TLS, or a loopback relay), and this client never
// authenticates on an unprotected remote connection.
type plainAuth struct{ user, pass string }

func (a *plainAuth) Start(*smtp.ServerInfo) (string, []byte, error) {
	return "PLAIN", []byte("\x00" + a.user + "\x00" + a.pass), nil
}
func (a *plainAuth) Next(_ []byte, more bool) ([]byte, error) {
	if more {
		return nil, errors.New("邮件服务器的认证流程异常")
	}
	return nil, nil
}

type loginAuth struct{ user, pass string }

func (a *loginAuth) Start(*smtp.ServerInfo) (string, []byte, error) { return "LOGIN", nil, nil }
func (a *loginAuth) Next(prompt []byte, more bool) ([]byte, error) {
	if !more {
		return nil, nil
	}
	switch strings.ToLower(strings.TrimSpace(string(prompt))) {
	case "username:":
		return []byte(a.user), nil
	case "password:":
		return []byte(a.pass), nil
	}
	return nil, errors.New("邮件服务器的认证流程异常")
}

// classifySMTP maps a failure to a delivery outcome. Only a network error
// after the message was sent, before the server's final answer, is
// "uncertain": the server may have accepted it.
func classifySMTP(stage smtpStage, err error) Result {
	var tpe *textproto.Error
	if errors.As(err, &tpe) {
		desc := shorten(strings.TrimSpace(tpe.Msg))
		switch {
		case stage == stageAuth && (tpe.Code >= 500 || tpe.Code == 454):
			return Result{Outcome: RetrySafe, FailKind: health.FailConfig,
				Err: "SMTP 认证失败（" + strconv.Itoa(tpe.Code) + "，请检查用户名和密码）"}
		case tpe.Code >= 500:
			kind := health.FailOther
			if stage == stageEnvelope {
				kind = health.FailConfig
			}
			return Result{Outcome: Permanent, FailKind: kind, Err: "邮件服务器拒绝（" + strconv.Itoa(tpe.Code) + " " + desc + "）"}
		default:
			return Result{Outcome: RetrySafe, FailKind: health.FailOther, Err: "邮件服务器暂时无法接收（" + strconv.Itoa(tpe.Code) + " " + desc + "）"}
		}
	}
	var verify *tls.CertificateVerificationError
	var unknown x509.UnknownAuthorityError
	var invalid x509.CertificateInvalidError
	var host x509.HostnameError
	if errors.As(err, &verify) || errors.As(err, &unknown) || errors.As(err, &invalid) || errors.As(err, &host) {
		return Result{Outcome: RetrySafe, FailKind: health.FailConfig, Err: "无法验证邮件服务器的证书（" + shorten(err.Error()) + "）"}
	}
	if stage == stageDataCommit {
		return Result{Outcome: RetryUncertain, FailKind: health.FailNetwork,
			Err: "邮件已发出但未收到服务器的确认（" + shorten(err.Error()) + "）"}
	}
	return Result{Outcome: RetrySafe, FailKind: health.FailNetwork, Err: "无法连接邮件服务器（" + shorten(err.Error()) + "）"}
}
