package delivery

import (
	"bytes"
	"fmt"
	"mime"
	"mime/quotedprintable"
	"net/mail"
	"regexp"
	"strings"
	"time"
	"unicode"

	"nekopost/internal/config"
	"nekopost/internal/model"
	"nekopost/internal/msgrule"
	"nekopost/internal/render"
	"nekopost/internal/store"
)

const maxSubjectRunes = 100

var idUnsafe = regexp.MustCompile(`[^A-Za-z0-9._-]`)

// singleLine makes untrusted text safe for a header: control characters
// (CR and LF included) become spaces and runs of spaces collapse.
func singleLine(s string) string {
	s = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) || r == ' ' || r == ' ' {
			return ' '
		}
		return r
	}, s)
	return strings.Join(strings.Fields(s), " ")
}

func clip(s string, n int) string {
	if r := []rune(s); len(r) > n {
		return string(r[:n]) + "…"
	}
	return s
}

func firstLine(s string) string {
	for _, l := range strings.Split(s, "\n") {
		if l = strings.TrimSpace(l); l != "" {
			return l
		}
	}
	return ""
}

// emailText returns the subject and plain-text body for a job. What it
// contains depends on privacy: the whole message, the message with digits
// and links hidden, or only that something arrived.
func emailText(job store.Job, privacy string, loc *time.Location) (subject, body string) {
	m := job.Message
	name := singleLine(job.Source.Name)
	if name == "" {
		name = singleLine(job.Source.ID)
	}
	if name == "" {
		name = "Nekopost"
	}
	title, text := m.Title, strings.ReplaceAll(m.Body, "\r\n", "\n")
	if privacy == config.EmailPrivacyMasked {
		title, text = msgrule.Mask(title), msgrule.Mask(text)
	}
	if loc == nil {
		loc = time.UTC
	}

	head := singleLine(title)
	if head == "" {
		head = singleLine(firstLine(text))
	}
	switch {
	case privacy == config.EmailPrivacyPing:
		subject = "[" + name + "] 收到一条新消息"
	case head == "":
		subject = "[" + name + "] 新消息"
	default:
		subject = "[" + name + "] " + clip(head, 60)
	}
	subject = clip(subject, maxSubjectRunes)

	var b strings.Builder
	if privacy == config.EmailPrivacyPing {
		fmt.Fprintf(&b, "%s 收到一条新消息。\n邮件不含消息内容，请到 Telegram 或 App 里查看。\n", name)
	} else {
		if t := strings.TrimSpace(title); t != "" {
			b.WriteString(t + "\n\n")
		}
		b.WriteString(strings.TrimSpace(text) + "\n")
		if privacy == config.EmailPrivacyMasked {
			b.WriteString("\n（为保护隐私，数字和链接已隐藏。）\n")
		}
	}
	b.WriteString("\n--\n")
	fmt.Fprintf(&b, "来源：%s（%s）\n", name, singleLine(job.Source.ID))
	if l := render.KindLabel(m.Kind); l != "" {
		b.WriteString("类型：" + l + "\n")
	}
	if m.Category != "" && privacy != config.EmailPrivacyPing {
		b.WriteString("类别：" + m.Category.Label() + "\n")
	}
	b.WriteString("时间：" + m.ReceivedAt.In(loc).Format("2006-01-02 15:04:05 MST") + "\n")
	b.WriteString("消息 ID：" + singleLine(m.ID) + "\n")
	return subject, b.String()
}

// mimeMessage assembles the RFC 5322 message. Every header value is built
// from validated addresses or encoded words, so nothing in a message can
// add a header.
func mimeMessage(from string, to []string, subject, body, messageID string, date time.Time, high bool) []byte {
	var out bytes.Buffer
	h := func(k, v string) { out.WriteString(k + ": " + v + "\r\n") }
	h("From", (&mail.Address{Name: "Nekopost", Address: from}).String())
	rcpts := make([]string, len(to))
	for i, a := range to {
		rcpts[i] = (&mail.Address{Address: a}).String()
	}
	h("To", strings.Join(rcpts, ", "))
	h("Subject", mime.QEncoding.Encode("utf-8", singleLine(subject)))
	h("Date", date.Format(time.RFC1123Z))
	h("Message-ID", messageID)
	h("MIME-Version", "1.0")
	h("Content-Type", "text/plain; charset=UTF-8")
	h("Content-Transfer-Encoding", "quoted-printable")
	h("Auto-Submitted", "auto-generated")
	h("X-Mailer", "nekopost")
	if high {
		h("X-Priority", "1")
		h("Importance", "high")
	}
	out.WriteString("\r\n")
	w := quotedprintable.NewWriter(&out)
	_, _ = w.Write([]byte(body))
	_ = w.Close()
	return out.Bytes()
}

func messageIDFor(msgID, from string) string {
	domain := "localhost"
	if i := strings.LastIndex(from, "@"); i >= 0 && i+1 < len(from) {
		domain = idUnsafe.ReplaceAllString(from[i+1:], "")
	}
	// The same message always gets the same Message-ID, so a retry that
	// duplicates an email is recognisable as a duplicate.
	return "<" + idUnsafe.ReplaceAllString(msgID, "") + ".email@" + domain + ">"
}

func priorityHigh(m model.Message) bool { return m.Priority == model.PriorityHigh }
