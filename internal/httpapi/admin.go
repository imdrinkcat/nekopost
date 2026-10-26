package httpapi

import (
	"errors"
	"net/http"

	"nekopost/internal/auth"
	"nekopost/internal/status"
	"nekopost/internal/store"
)

// admin wraps a handler with admin-credential authentication. Admin keys are
// separate from source tokens: a source token can never access these routes.
func (s *Server) admin(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ip := clientIP(r)
		if blocked, ra := s.FailGuard.Blocked(ip); blocked {
			writeError(w, r, &apiError{Status: http.StatusTooManyRequests, Code: "too_many_auth_failures",
				Message: "认证失败次数过多，请稍后再试", RetryAfter: ra})
			return
		}
		tok, ok := auth.BearerToken(r.Header.Get("Authorization"))
		if !ok || len(tok) < len(auth.AdminPrefix) || tok[:len(auth.AdminPrefix)] != auth.AdminPrefix {
			s.FailGuard.Fail(ip)
			s.Health.AuthFailure(s.now())
			writeError(w, r, errUnauthorized)
			return
		}
		name, err := s.Store.LookupAdminKey(r.Context(), auth.Hash(tok), s.now())
		if errors.Is(err, store.ErrNotFound) {
			s.FailGuard.Fail(ip)
			s.Health.AuthFailure(s.now())
			writeError(w, r, errUnauthorized)
			return
		}
		if err != nil {
			s.Log.Error("admin key lookup failed", "err", err)
			writeError(w, r, errInternal)
			return
		}
		info(r).Source = "admin:" + name
		next(w, r)
	}
}

func (s *Server) handleAdminStatus(w http.ResponseWriter, r *http.Request) {
	rep := s.Status.Report(r.Context())
	rep.GeneratedAt = rep.GeneratedAt.In(s.Location)
	sys := s.Status.System()
	server := map[string]any{"cpu_count": sys.NumCPU}
	if sys.HostUptimeOK {
		server["host_uptime_seconds"] = int64(sys.HostUptime.Seconds())
	}
	if sys.CPUOK {
		server["cpu_percent"] = sys.CPUPercent
	}
	if sys.LoadOK {
		server["load"] = []float64{sys.Load1, sys.Load5, sys.Load15}
	}
	if sys.MemOK {
		server["memory_total_bytes"] = sys.MemTotal
		server["memory_available_bytes"] = sys.MemAvailable
	}
	if sys.DiskOK {
		server["disk_total_bytes"] = sys.DiskTotal
		server["disk_available_bytes"] = sys.DiskAvail
	}
	if sys.ProcRSSOK {
		server["service_rss_bytes"] = sys.ProcRSS
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":          true,
		"version":     s.Version,
		"started_at":  s.ts(s.Status.StartedAt),
		"start_count": s.Status.StartInfo.StartCount,
		"report":      rep,
		"server":      server,
	})
}

func (s *Server) handleAdminSources(w http.ResponseWriter, r *http.Request) {
	list, err := s.Status.Sources(r.Context())
	if err != nil {
		s.Log.Error("list sources failed", "err", err)
		writeError(w, r, errInternal)
		return
	}
	out := make([]map[string]any, 0, len(list))
	for _, st := range list {
		src := st.Source
		item := map[string]any{
			"id":                  src.ID,
			"name":                src.Name,
			"icon":                src.Icon,
			"enabled":             src.Enabled,
			"created_at":          s.ts(src.CreatedAt),
			"last_seen_at":        s.ts(src.LastSeenAt),
			"last_message_at":     s.ts(st.Last.At),
			"revoked_token_uses":  src.AuthFailCount,
			"last_revoked_use_at": s.ts(src.LastAuthFailAt),
			"today": map[string]int{
				"received":    st.Today.Received,
				"delivered":   st.Today.Delivered,
				"failed":      st.Today.Failed + st.Today.Uncertain + st.Today.Partial,
				"in_progress": st.Today.InProgress,
			},
		}
		if st.Last.Status != "" {
			item["last_message_status"] = st.Last.Status
		}
		out = append(out, item)
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "sources": out})
}

func (s *Server) handleAdminStats(w http.ResponseWriter, r *http.Request) {
	rng := status.RangeToday
	if v := r.URL.Query().Get("range"); v != "" {
		var ok bool
		if rng, ok = status.ParseRange(v); !ok {
			writeError(w, r, &apiError{Status: http.StatusBadRequest, Code: "invalid_parameter",
				Message: "range 必须是 today、24h、7d 或 start"})
			return
		}
	}
	res, err := s.Status.Stats(r.Context(), rng)
	if err != nil {
		s.Log.Error("stats failed", "err", err)
		writeError(w, r, errInternal)
		return
	}
	counts := func(c store.Counts) map[string]int {
		return map[string]int{
			"received": c.Received, "delivered": c.Delivered, "failed": c.Failed, "uncertain": c.Uncertain,
			"partial": c.Partial, "not_routed": c.NotRouted, "filtered": c.Filtered, "in_progress": c.InProgress, "duplicates_ignored": c.Duplicates,
		}
	}
	var by []map[string]any
	for _, sc := range res.Stats.BySource {
		by = append(by, map[string]any{"source": sc.SourceID, "counts": counts(sc.Counts), "last_received_at": s.ts(sc.LastReceivedAt)})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":                true,
		"range":             res.Range,
		"from":              s.ts(res.Stats.From),
		"to":                s.ts(res.Stats.To),
		"totals":            counts(res.Stats.Total),
		"by_source":         by,
		"last_message_at":   s.ts(res.Stats.LastMessageAt),
		"queue_in_progress": res.Queue.InProgress,
		"note":              res.Note,
	})
}
