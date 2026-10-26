package httpapi

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"sync"
	"time"

	"golang.org/x/crypto/acme/autocert"

	"msggw/internal/health"
)

// ListenConfig selects how the API is exposed.
type ListenConfig struct {
	TLSMode   string // "autocert" or "off"
	Domain    string
	HTTPSAddr string
	HTTPAddr  string // HTTP-01 challenges + redirect (autocert) or the API itself (off)
	CacheDir  string
	Email     string
}

// Listener runs the HTTP(S) servers.
type Listener struct {
	Cfg     ListenConfig
	Handler http.Handler
	Health  *health.Tracker
	Log     *slog.Logger

	mgr     *autocert.Manager
	cache   autocert.DirCache
	servers []*http.Server

	certMu   sync.Mutex
	certInfo health.CertInfo
	certAt   time.Time
}

func newServer(addr string, h http.Handler) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           h,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      maxWait + 20*time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    16 << 10,
		ErrorLog:          slog.NewLogLogger(slog.Default().Handler(), slog.LevelDebug),
	}
}

// Start binds the listeners and serves in the background. It returns an
// error if a port cannot be bound.
func (l *Listener) Start(ctx context.Context) error {
	var lns []net.Listener
	bind := func(addr string) (net.Listener, error) {
		ln, err := net.Listen("tcp", addr)
		if err != nil {
			for _, x := range lns {
				x.Close()
			}
			return nil, err
		}
		lns = append(lns, ln)
		return ln, nil
	}

	switch l.Cfg.TLSMode {
	case "off":
		ln, err := bind(l.Cfg.HTTPAddr)
		if err != nil {
			return err
		}
		srv := newServer(l.Cfg.HTTPAddr, l.Handler)
		l.servers = append(l.servers, srv)
		go l.serve(srv, ln, false)
	default:
		l.cache = autocert.DirCache(l.Cfg.CacheDir)
		l.mgr = &autocert.Manager{
			Prompt:      autocert.AcceptTOS,
			HostPolicy:  autocert.HostWhitelist(l.Cfg.Domain),
			Cache:       l.cache,
			Email:       l.Cfg.Email,
			RenewBefore: 30 * 24 * time.Hour,
		}
		httpLn, err := bind(l.Cfg.HTTPAddr)
		if err != nil {
			return err
		}
		httpsLn, err := bind(l.Cfg.HTTPSAddr)
		if err != nil {
			return err
		}
		// Port 80 only answers ACME challenges and redirects to HTTPS.
		httpSrv := newServer(l.Cfg.HTTPAddr, l.mgr.HTTPHandler(nil))
		httpSrv.WriteTimeout = 10 * time.Second
		tlsSrv := newServer(l.Cfg.HTTPSAddr, l.Handler)
		tlsCfg := l.mgr.TLSConfig()
		tlsCfg.MinVersion = tls.VersionTLS12
		tlsSrv.TLSConfig = tlsCfg
		l.servers = append(l.servers, httpSrv, tlsSrv)
		go l.serve(httpSrv, httpLn, false)
		go l.serve(tlsSrv, httpsLn, true)
		go l.prewarm(ctx)
	}
	l.Health.SetListening(true, "")
	return nil
}

func (l *Listener) serve(srv *http.Server, ln net.Listener, useTLS bool) {
	var err error
	if useTLS {
		err = srv.ServeTLS(ln, "", "")
	} else {
		err = srv.Serve(ln)
	}
	if err != nil && !errors.Is(err, http.ErrServerClosed) {
		l.Log.Error("http server stopped", "addr", srv.Addr, "err", err)
		l.Health.SetListening(false, "监听 "+srv.Addr+" 失败")
	}
}

// prewarm obtains (or loads) the certificate at startup so the first client
// request is fast and certificate problems show up in the status report.
func (l *Listener) prewarm(ctx context.Context) {
	for attempt := 0; ; attempt++ {
		hello := &tls.ClientHelloInfo{
			ServerName:        l.Cfg.Domain,
			CipherSuites:      []uint16{tls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256},
			SignatureSchemes:  []tls.SignatureScheme{tls.ECDSAWithP256AndSHA256},
			SupportedCurves:   []tls.CurveID{tls.CurveP256},
			SupportedVersions: []uint16{tls.VersionTLS13, tls.VersionTLS12},
		}
		_, err := l.mgr.GetCertificate(hello)
		if err == nil {
			l.certMu.Lock()
			l.certAt = time.Time{} // force CertInfo refresh
			l.certMu.Unlock()
			return
		}
		l.Log.Warn("certificate not available yet", "err", err)
		l.certMu.Lock()
		l.certInfo.Err = "证书申请失败，稍后自动重试"
		l.certMu.Unlock()
		l.Health.Error(time.Now(), "HTTPS 证书", "证书申请失败，稍后自动重试")
		delay := time.Duration(1<<min(attempt, 6)) * time.Minute // 1m .. 64m
		select {
		case <-ctx.Done():
			return
		case <-time.After(delay):
		}
	}
}

// CertInfo reports the cached certificate's expiry (refreshed every minute).
func (l *Listener) CertInfo() health.CertInfo {
	if l.mgr == nil {
		return health.CertInfo{}
	}
	l.certMu.Lock()
	defer l.certMu.Unlock()
	if !l.certAt.IsZero() && time.Since(l.certAt) < time.Minute {
		return l.certInfo
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	data, err := l.cache.Get(ctx, l.Cfg.Domain)
	if err == nil {
		for rest := data; ; {
			var b *pem.Block
			b, rest = pem.Decode(rest)
			if b == nil {
				break
			}
			if b.Type == "CERTIFICATE" {
				if c, err := x509.ParseCertificate(b.Bytes); err == nil {
					l.certInfo = health.CertInfo{NotAfter: c.NotAfter}
				}
				break
			}
		}
	}
	l.certAt = time.Now()
	return l.certInfo
}

// Shutdown gracefully stops all servers.
func (l *Listener) Shutdown(ctx context.Context) {
	for _, s := range l.servers {
		_ = s.Shutdown(ctx)
	}
	l.Health.SetListening(false, "")
}
