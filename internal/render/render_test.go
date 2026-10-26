package render

import (
	"strconv"
	"strings"
	"testing"
	"time"

	"nekopost/internal/model"
)

var tokyo, _ = time.LoadLocation("Asia/Tokyo")

func in(body string) Input {
	return Input{
		SourceID: "iphone", SourceName: "iPhone", SourceIcon: "📱", Location: tokyo,
		Message: model.Message{Kind: "sms", Priority: model.PriorityNormal, Title: "10086",
			ID: "m_TEST", Body: body, ReceivedAt: time.Date(2026, 10, 25, 1, 2, 3, 0, time.UTC)},
	}
}

func TestRenderSingle(t *testing.T) {
	body := "你好，世界\n日本語のテキスト\nEmoji 👍🏽👨‍👩‍👧 <b>&amp;</b>"
	parts := Render(in(body))
	if len(parts) != 1 {
		t.Fatalf("want 1 part, got %d", len(parts))
	}
	h := parts[0].HTML
	// The text is exactly the content; source and type are buttons.
	if want := "<b>10086</b>\n" + strings.ReplaceAll(strings.ReplaceAll(strings.ReplaceAll(body, "&", "&amp;"), "<", "&lt;"), ">", "&gt;"); h != want {
		t.Errorf("HTML should be only the content:\n%s", h)
	}
	if got := buttonTexts(parts[0]); got != "📱 iPhone | 💬 短信" {
		t.Errorf("buttons: %q", got)
	}
	if len(parts[0].Buttons) != 1 || len(parts[0].Buttons[0]) != 2 {
		t.Fatalf("source and type should share one row: %+v", parts[0].Buttons)
	}
	for _, b := range parts[0].Buttons[0] {
		if b.Data != InfoPrefix+"m_TEST" {
			t.Errorf("button payload: %+v", b)
		}
	}
	if !strings.Contains(parts[0].Plain, "<b>&amp;</b>") {
		t.Error("plain text should keep the original characters")
	}
}

func TestRenderDeviceTimeAndMeta(t *testing.T) {
	x := in("hi")
	x.Message.OccurredAt = x.Message.ReceivedAt.Add(-10 * time.Minute)
	x.Message.Meta = map[string]string{"host": "web-1", "exit": "0"}
	x.Message.Priority = model.PriorityHigh
	p := Render(x)[0]
	if want := "❗ <b>10086</b>\nhi\n\n<code>exit</code>: 0\n<code>host</code>: web-1"; p.HTML != want {
		t.Errorf("HTML:\n%s", p.HTML)
	}
	if got := buttonTexts(p); strings.Contains(got, "🕒") || strings.Contains(got, "设备") {
		t.Errorf("times belong in the popup, not the buttons: %q", got)
	}
}

func TestRenderUnknownKindShownVerbatim(t *testing.T) {
	x := in("hi")
	x.Message.Kind = "ci.build"
	x.Message.Title = ""
	x.Message.Priority = model.PriorityHigh
	if p := Render(x)[0].Plain; p != "❗ hi" {
		t.Errorf("body-only message layout: %q", p)
	}
	x.Message.Priority = model.PriorityNormal
	if !strings.HasSuffix(buttonTexts(Render(x)[0]), " | ci.build") {
		t.Error("unknown kinds should be displayed verbatim")
	}
	x.Message.Kind = "text"
	if got := Render(x)[0].Buttons; len(got) != 1 || len(got[0]) != 1 || got[0][0].Text != "📱 iPhone" {
		t.Errorf("plain text kind should only have the source button: %+v", got)
	}
}

func TestRenderSplitsLongMessages(t *testing.T) {
	// Emoji are 2 UTF-16 units each: 3000 emoji = 6000 units > 4096.
	var sb strings.Builder
	for i := 0; i < 300; i++ {
		sb.WriteString("第" + strings.Repeat("😀", 9) + "行\n")
	}
	body := strings.TrimRight(sb.String(), "\n")
	parts := Render(in(body))
	if len(parts) < 2 {
		t.Fatalf("expected split, got %d parts", len(parts))
	}
	var rebuilt strings.Builder
	for i, p := range parts {
		if n := UTF16Len(p.Plain); n > MaxTextLen {
			t.Errorf("part %d has %d UTF-16 units", i, n)
		}
		if !strings.Contains(buttonTexts(p), " ("+strconv.Itoa(i+1)+"/"+strconv.Itoa(len(parts))+")") {
			t.Errorf("part %d lacks numbering", i)
		}
		if strings.Contains(p.Plain, "\uFFFD") {
			t.Errorf("part %d contains broken characters", i)
		}
	}
	// Body content must be fully preserved across parts (ignoring headers).
	for _, p := range parts {
		lines := strings.Split(p.Plain, "\n")
		for _, l := range lines {
			if strings.HasPrefix(l, "第") {
				rebuilt.WriteString(l + "\n")
			}
		}
	}
	if strings.TrimRight(rebuilt.String(), "\n") != body {
		t.Error("split lost or altered content")
	}
	if !strings.HasPrefix(parts[0].Plain, "<b>") && !strings.HasPrefix(parts[0].Plain, "10086\n第") {
		t.Errorf("title should lead the first part: %q", parts[0].Plain[:20])
	}
	if strings.HasPrefix(parts[1].Plain, "10086") {
		t.Error("title should only be on the first part")
	}
}

func TestRenderSplitNoNewlines(t *testing.T) {
	body := strings.Repeat("あ", 9000)
	parts := Render(in(body))
	total := 0
	for _, p := range parts {
		if UTF16Len(p.Plain) > MaxTextLen {
			t.Fatal("part too long")
		}
		total += strings.Count(p.Plain, "あ")
	}
	if total != 9000 {
		t.Errorf("content lost: %d", total)
	}
}

func TestRenderCategory(t *testing.T) {
	x := in("包裹已到驿站")
	x.Message.Category = model.CatLogistics
	if got := buttonTexts(Render(x)[0]); got != "📱 iPhone | 💬 短信 · 快递/出行" {
		t.Errorf("category should follow the type label: %q", got)
	}
}

func buttonTexts(p Part) string {
	var out []string
	for _, row := range p.Buttons {
		for _, b := range row {
			out = append(out, b.Text)
		}
	}
	return strings.Join(out, " | ")
}
