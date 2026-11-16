// Package service is the inbox operations shared by the CLI and, later, HTTP.
package service

import (
	"context"
	"strings"
	"time"
	"unicode"

	"golang.org/x/text/unicode/norm"

	"nekopost/internal/filter"
	"nekopost/internal/store"
)

// Hit is one search match.
type Hit struct {
	store.InboxMeta
	Preview string
}

// Search decrypts stored bodies until it fills limit, spends the time budget,
// or scans maxScan rows.
func Search(ctx context.Context, st *store.Store, query string, limit int) ([]Hit, int, bool, error) {
	return search(ctx, st, query, limit, time.Now().Add(300*time.Millisecond))
}

func search(ctx context.Context, st *store.Store, query string, limit int, deadline time.Time) ([]Hit, int, bool, error) {
	if limit <= 0 || limit > 50 {
		limit = 20
	}
	terms := parseQuery(query)
	before := time.Now().Add(time.Hour)
	var beforeID string
	scanned := 0
	var hits []Hit
	for scanned < 5000 && time.Now().Before(deadline) && len(hits) < limit {
		batch, err := st.SearchCandidates(ctx, before, beforeID, 200)
		if err != nil {
			return nil, scanned, false, err
		}
		if len(batch) == 0 {
			return hits, scanned, true, nil
		}
		for _, m := range batch {
			if scanned >= 5000 || time.Now().After(deadline) {
				return hits, scanned, false, nil
			}
			scanned++
			title, body, _, ok, err := st.ReadBody(ctx, m.ID)
			if err != nil {
				return nil, scanned, false, err
			}
			if !ok {
				continue
			}
			if match(terms, title+"\n"+body) {
				m.Title, m.Body = title, body
				hits = append(hits, Hit{InboxMeta: m, Preview: filter.Preview(title, body)})
				if len(hits) >= limit {
					return hits, scanned, false, nil
				}
			}
		}
		last := batch[len(batch)-1]
		before, beforeID = last.ReceivedAt, last.ID
	}
	return hits, scanned, false, nil
}

type term struct {
	text    string
	exclude bool
	phrase  bool
}

func parseQuery(q string) []term {
	var out []term
	var b strings.Builder
	phrase, exclude := false, false
	flush := func() {
		s := strings.TrimSpace(b.String())
		b.Reset()
		if s == "" {
			exclude = false
			return
		}
		out = append(out, term{text: fold(s), exclude: exclude, phrase: phrase})
		exclude = false
	}
	for _, r := range q {
		switch {
		case r == '"':
			flush()
			phrase = !phrase
		case unicode.IsSpace(r) && !phrase:
			flush()
		case r == '-' && b.Len() == 0 && !phrase:
			exclude = true
		default:
			b.WriteRune(r)
		}
	}
	flush()
	return out
}

func match(terms []term, text string) bool {
	h := fold(text)
	if len(terms) == 0 {
		return false
	}
	for _, t := range terms {
		ok := strings.Contains(h, t.text)
		if t.exclude && ok || !t.exclude && !ok {
			return false
		}
	}
	return true
}

func fold(s string) string {
	return strings.ToLower(norm.NFKC.String(s))
}
