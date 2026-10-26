// Package render turns a unified message into Telegram message text,
// splitting it into several parts when it exceeds Telegram's length limit.
package render

import (
	"html"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf16"
	"unicode/utf8"

	"nekopost/internal/model"
)

// MaxTextLen is Telegram's limit for message text after entity parsing,
// measured in UTF-16 code units.
const MaxTextLen = 4096

// kindLabels gives known message types a readable label. Unknown types are
// shown verbatim, so new types need no code change.
var kindLabels = map[string]string{
	"sms":          "💬 短信",
	"notification": "🔔 通知",
	"alert":        "🚨 告警",
	"event":        "📌 事件",
	"task":         "✅ 任务",
	"log":          "📄 日志",
	"test":         "🧪 测试",
}

// KindLabel returns the display label of a message type ("" for plain text).
func KindLabel(kind string) string {
	if kind == "" || kind == "text" {
		return ""
	}
	if l, ok := kindLabels[kind]; ok {
		return l
	}
	return kind
}

// Input is what the renderer needs.
type Input struct {
	SourceID   string
	SourceName string
	SourceIcon string
	Message    model.Message
	Location   *time.Location
}

// Part is one Telegram message.
type Part struct {
	HTML    string     // for parse_mode=HTML
	Plain   string     // fallback without formatting
	Buttons [][]Button // inline keyboard rows shown under the message
}

// Button is an inline button. Data is the callback payload.
type Button struct {
	Text string
	Data string
}

// InfoPrefix starts the callback payload of the info buttons; the message ID
// follows. The management bot answers it with the message's details.
const InfoPrefix = "i:"

type piece struct {
	text string
	bold bool
	ital bool
	code bool
}

type doc []piece

func (d doc) html() string {
	var b strings.Builder
	for _, p := range d {
		t := html.EscapeString(p.text)
		switch {
		case p.bold:
			b.WriteString("<b>" + t + "</b>")
		case p.ital:
			b.WriteString("<i>" + t + "</i>")
		case p.code:
			b.WriteString("<code>" + t + "</code>")
		default:
			b.WriteString(t)
		}
	}
	return b.String()
}

func (d doc) plain() string {
	var b strings.Builder
	for _, p := range d {
		b.WriteString(p.text)
	}
	return b.String()
}

// UTF16Len returns the length of s in UTF-16 code units.
func UTF16Len(s string) int {
	n := 0
	for _, r := range s {
		n += len(utf16.Encode([]rune{r}))
	}
	return n
}

// Render produces one or more parts for the message.
func Render(in Input) []Part {
	m := in.Message
	loc := in.Location
	if loc == nil {
		loc = time.UTC
	}
	name := in.SourceName
	if name == "" {
		name = in.SourceID
	}
	icon := in.SourceIcon
	if icon == "" {
		icon = "📨"
	}

	// Layout: only the content is in the text, so the notification preview
	// and the chat show nothing else. Source, type and category are inline
	// buttons below the message.
	//
	//	❗ <b>Title</b>
	//	body
	//
	//	key: value
	//
	//	[📱 iPhone] [💬 短信 · 快递/出行 (1/2)]
	lead := doc{}
	if m.Priority == model.PriorityHigh {
		lead = append(lead, piece{text: "❗ "})
	}
	if m.Title != "" {
		lead = append(lead, piece{text: m.Title, bold: true})
		if m.Body != "" {
			lead = append(lead, piece{text: "\n"})
		}
	}

	meta := doc{}
	if len(m.Meta) > 0 {
		keys := make([]string, 0, len(m.Meta))
		for k := range m.Meta {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		if m.Title != "" || m.Body != "" {
			meta = append(meta, piece{text: "\n"})
		}
		for i, k := range keys {
			if i > 0 || m.Title != "" || m.Body != "" {
				meta = append(meta, piece{text: "\n"})
			}
			meta = append(meta, piece{text: k, code: true}, piece{text: ": " + m.Meta[k]})
		}
	}

	// One row: the source, then the type and category. No time: Telegram
	// already shows when the message arrived, and the popup behind the
	// buttons has the exact receive and device times.
	buttons := func(i, n int) [][]Button {
		data := InfoPrefix + m.ID
		row := []Button{{Text: icon + " " + name, Data: data}}
		var kind []string
		if l := KindLabel(m.Kind); l != "" {
			kind = append(kind, l)
		}
		if m.Category != "" {
			kind = append(kind, m.Category.Label())
		}
		if len(kind) > 0 {
			row = append(row, Button{Text: strings.Join(kind, " · "), Data: data})
		}
		if n > 1 {
			last := &row[len(row)-1]
			last.Text += " (" + strconv.Itoa(i) + "/" + strconv.Itoa(n) + ")"
		}
		return [][]Button{row}
	}

	body := m.Body
	// Try to fit everything into one message first.
	single := append(append(append(doc{}, lead...), piece{text: body}), meta...)
	if UTF16Len(single.plain()) <= MaxTextLen {
		return []Part{{HTML: single.html(), Plain: single.plain(), Buttons: buttons(1, 1)}}
	}

	// Split the body. The first part carries the lead, the last one the meta.
	tail := UTF16Len(meta.plain()) + 8
	budgetFirst := MaxTextLen - UTF16Len(lead.plain()) - tail
	budgetRest := MaxTextLen - tail
	if budgetFirst < 200 {
		budgetFirst = 200
	}
	chunks := splitText(body, budgetFirst, budgetRest)
	parts := make([]Part, len(chunks))
	for i, c := range chunks {
		d := doc{}
		if i == 0 {
			d = append(d, lead...)
		}
		d = append(d, piece{text: c})
		if i == len(chunks)-1 {
			d = append(d, meta...)
		}
		parts[i] = Part{HTML: d.html(), Plain: d.plain(), Buttons: buttons(i+1, len(chunks))}
	}
	return parts
}

// splitText cuts s into chunks of at most first (then rest) UTF-16 units,
// preferring line breaks, then spaces, and never splitting a rune.
func splitText(s string, first, rest int) []string {
	var out []string
	limit := first
	for s != "" {
		if UTF16Len(s) <= limit {
			out = append(out, s)
			break
		}
		// Find the byte offset where the limit is reached.
		units, cut := 0, 0
		for i, r := range s {
			w := 1
			if r >= 0x10000 {
				w = 2
			}
			if units+w > limit {
				cut = i
				break
			}
			units += w
		}
		if cut == 0 {
			_, size := utf8.DecodeRuneInString(s)
			cut = size
		}
		// Prefer a natural break in the last 25% of the chunk.
		window := s[:cut]
		minPos := len(window) * 3 / 4
		if i := strings.LastIndex(window, "\n"); i >= minPos {
			cut = i + 1
		} else if i := strings.LastIndexAny(window, " 。！？.!?"); i >= minPos {
			_, size := utf8.DecodeRuneInString(window[i:])
			cut = i + size
		}
		if c := strings.TrimRight(s[:cut], "\n"); strings.TrimSpace(c) != "" {
			out = append(out, c)
		}
		s = s[cut:]
		limit = rest
	}
	return out
}
