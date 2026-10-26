// Package router decides which targets receive a message. It is the single
// place where per-source, per-type or per-priority rules belong.
package router

import "nekopost/internal/model"

// Target is one destination for a message.
type Target struct {
	Channel string // name of a registered delivery channel, e.g. "telegram"
	Target  string // channel-specific destination name, e.g. "default"
	Silent  bool   // deliver without a sound/notification where supported
}

// Router maps a message to zero or more targets. Returning no targets is
// valid (the message is recorded as not routed).
type Router interface {
	Route(src model.Source, msg *model.Message) []Target
}

// Default sends every message to one target. Low-priority messages are
// delivered silently.
type Default struct {
	Channel string
	Target  string
}

// Route implements Router.
func (d Default) Route(_ model.Source, msg *model.Message) []Target {
	return []Target{{Channel: d.Channel, Target: d.Target, Silent: msg.Priority == model.PriorityLow}}
}
