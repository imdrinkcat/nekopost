// Package model defines the gateway's unified data model: sources, messages
// and per-target deliveries, plus the state vocabulary shared by every layer.
package model

import (
	"crypto/rand"
	"regexp"
	"time"
)

// Priority influences how a message is presented (e.g. silent notification).
type Priority string

const (
	PriorityLow    Priority = "low"
	PriorityNormal Priority = "normal"
	PriorityHigh   Priority = "high"
)

// ValidPriority reports whether p is one of the known priorities.
func ValidPriority(p Priority) bool {
	return p == PriorityLow || p == PriorityNormal || p == PriorityHigh
}

// Status is the aggregate processing state of a message across all of its
// deliveries. A message being "accepted" by the server never implies that it
// was delivered: only StatusDelivered means every target confirmed receipt.
type Status string

const (
	StatusQueued    Status = "queued"     // accepted and persisted, waiting for delivery
	StatusSending   Status = "sending"    // a delivery attempt is in progress
	StatusRetrying  Status = "retrying"   // last attempt failed, another one is scheduled
	StatusDelivered Status = "delivered"  // every target confirmed receipt
	StatusPartial   Status = "partial"    // some targets confirmed, others failed / uncertain
	StatusFailed    Status = "failed"     // gave up; no target is known to have received it
	StatusUncertain Status = "uncertain"  // gave up, but an attempt may have reached the target
	StatusNotRouted Status = "not_routed" // routing produced no targets (e.g. muted)
	// StatusClassifying: held until the content filter decides whether to
	// forward it.
	StatusClassifying Status = "classifying"
	// StatusFiltered: the content filter judged it unwanted; not forwarded.
	// The payload is kept for a few days so it can be rescued.
	StatusFiltered Status = "filtered"
)

// Terminal reports whether no further processing will happen.
func (s Status) Terminal() bool {
	switch s {
	case StatusDelivered, StatusPartial, StatusFailed, StatusUncertain, StatusNotRouted, StatusFiltered:
		return true
	}
	return false
}

// Label is the Chinese, user-facing description of a status.
func (s Status) Label() string {
	switch s {
	case StatusQueued:
		return "已接收，等待投递"
	case StatusSending:
		return "正在投递"
	case StatusRetrying:
		return "投递暂未成功，正在重试"
	case StatusDelivered:
		return "已送达 Telegram"
	case StatusPartial:
		return "部分目标已送达"
	case StatusFailed:
		return "投递失败"
	case StatusUncertain:
		return "投递结果不确定"
	case StatusNotRouted:
		return "无投递目标（未转发）"
	case StatusClassifying:
		return "已接收，正在识别是否为广告"
	case StatusFiltered:
		return "已过滤（不需要推送的短信），未转发"
	}
	return string(s)
}

// DeliveryState is the state of one message towards one target.
type DeliveryState string

const (
	DeliveryPending   DeliveryState = "pending"
	DeliverySending   DeliveryState = "sending"
	DeliveryRetrying  DeliveryState = "retrying"
	DeliveryDelivered DeliveryState = "delivered"
	DeliveryFailed    DeliveryState = "failed"
	DeliveryUncertain DeliveryState = "uncertain"
	DeliveryHeld      DeliveryState = "held"     // waiting for the content filter
	DeliveryFiltered  DeliveryState = "filtered" // dropped by the content filter
)

// Terminal reports whether the delivery is finished.
func (d DeliveryState) Terminal() bool {
	return d == DeliveryDelivered || d == DeliveryFailed || d == DeliveryUncertain || d == DeliveryFiltered
}

// AggregateStatus derives a message status from its delivery states.
func AggregateStatus(states []DeliveryState) Status {
	if len(states) == 0 {
		return StatusNotRouted
	}
	var sending, retrying, pending, delivered, failed, uncertain, held, filtered int
	for _, s := range states {
		switch s {
		case DeliverySending:
			sending++
		case DeliveryRetrying:
			retrying++
		case DeliveryPending:
			pending++
		case DeliveryDelivered:
			delivered++
		case DeliveryFailed:
			failed++
		case DeliveryUncertain:
			uncertain++
		case DeliveryHeld:
			held++
		case DeliveryFiltered:
			filtered++
		}
	}
	switch {
	case sending > 0:
		return StatusSending
	case retrying > 0:
		return StatusRetrying
	case held > 0:
		return StatusClassifying
	case filtered == len(states):
		return StatusFiltered
	case pending > 0:
		return StatusQueued
	case delivered == len(states):
		return StatusDelivered
	case delivered > 0:
		return StatusPartial
	case uncertain > 0:
		return StatusUncertain
	}
	return StatusFailed
}

// Source is an independently authenticated message producer.
type Source struct {
	ID             string
	Name           string
	Icon           string
	Enabled        bool
	CreatedAt      time.Time
	LastSeenAt     time.Time // last authenticated request (zero = never)
	AuthFailCount  int       // uses of revoked credentials
	LastAuthFailAt time.Time
}

// DisplayName returns icon + name for presentation.
func (s Source) DisplayName() string {
	icon := s.Icon
	if icon == "" {
		icon = "📨"
	}
	name := s.Name
	if name == "" {
		name = s.ID
	}
	return icon + " " + name
}

var sourceIDRe = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{0,31}$`)

// ValidSourceID reports whether id is an acceptable source identifier.
func ValidSourceID(id string) bool { return sourceIDRe.MatchString(id) }

var kindRe = regexp.MustCompile(`^[a-z][a-z0-9_.-]{0,31}$`)

// ValidKind reports whether k is an acceptable message type identifier.
func ValidKind(k string) bool { return kindRe.MatchString(k) }

// Message is the unified representation of one submitted message.
// Title, Body and Meta are the payload; they are kept only until the message
// reaches a terminal state and are then purged.
type Message struct {
	ID             string
	SourceID       string
	IdempotencyKey string
	ContentHash    string
	Kind           string
	Priority       Priority
	Title          string
	Body           string
	Meta           map[string]string
	Category       Category  // set by the content filter ("" = not classified)
	OccurredAt     time.Time // reported by the client; untrusted, informational only
	ReceivedAt     time.Time // set by the server
	Status         Status
	DupCount       int
	FinishedAt     time.Time
}

// Category is the content filter's verdict for a message.
type Category string

const (
	CatCode      Category = "code"      // verification / pickup codes
	CatFinance   Category = "finance"   // bank, card, payment, bills
	CatLogistics Category = "logistics" // parcels, travel, bookings
	CatPersonal  Category = "personal"  // sent by a person
	CatService   Category = "service"   // account, carrier, government, school...
	CatRoaming   Category = "roaming"   // carrier welcome / roaming / customs / consular reminders abroad
	CatMarketing Category = "marketing" // advertising, promotions
	CatSpam      Category = "spam"      // fraud, phishing, junk
)

// Categories lists every category in display order.
var Categories = []Category{CatCode, CatFinance, CatLogistics, CatPersonal, CatService, CatRoaming, CatMarketing, CatSpam}

// Label is the Chinese name of a category.
func (c Category) Label() string {
	switch c {
	case CatCode:
		return "验证码"
	case CatFinance:
		return "银行/支付"
	case CatLogistics:
		return "快递/出行"
	case CatPersonal:
		return "个人"
	case CatService:
		return "通知"
	case CatRoaming:
		return "漫游/出入境提醒"
	case CatMarketing:
		return "广告营销"
	case CatSpam:
		return "垃圾/诈骗"
	}
	return string(c)
}

// ParseCategory validates a category name.
func ParseCategory(s string) (Category, bool) {
	for _, c := range Categories {
		if string(c) == s {
			return c, true
		}
	}
	return "", false
}

// Delivery is one message's journey to one target.
type Delivery struct {
	ID            int64
	MessageID     string
	Channel       string
	Target        string
	Silent        bool
	State         DeliveryState
	Attempts      int
	PartsTotal    int
	PartsSent     int
	NextAttemptAt time.Time
	LastAttemptAt time.Time
	DeliveredAt   time.Time
	LastError     string
	Uncertain     bool // some attempt ended without a definite answer
	CreatedAt     time.Time
}

const crockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

// NewMessageID returns a time-sortable unique id such as "m_01JB...".
func NewMessageID(now time.Time) string {
	var b [16]byte
	ms := uint64(now.UnixMilli())
	for i := 5; i >= 0; i-- {
		b[i] = byte(ms)
		ms >>= 8
	}
	if _, err := rand.Read(b[6:]); err != nil {
		panic(err)
	}
	// 128 bits -> 26 base32 characters.
	out := make([]byte, 26)
	var acc uint64
	var bits uint
	j := 0
	// Pad with 2 leading zero bits so 130 bits encode evenly.
	acc, bits = 0, 2
	for _, x := range b {
		acc = acc<<8 | uint64(x)
		bits += 8
		for bits >= 5 {
			bits -= 5
			out[j] = crockford[(acc>>bits)&31]
			j++
		}
	}
	return "m_" + string(out[:j])
}
