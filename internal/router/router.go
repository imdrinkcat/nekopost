// Package router decides which targets receive a message. It is the single
// place where per-source, per-type or per-priority rules belong.
package router

import "nekopost/internal/model"

// Target is one destination for a message.
type Target struct {
	Channel string // name of a registered delivery channel, e.g. "telegram"
	Target  string // channel-specific destination name, e.g. "default"
	Silent  bool   // deliver without a sound/notification where supported
	// Aux marks a best-effort copy: its outcome never decides the message's
	// status (a failed email does not make the message "failed").
	Aux bool
}

// Router maps a message to zero or more targets. Returning no targets is
// valid (the message is recorded as not routed).
type Router interface {
	Route(src model.Source, msg *model.Message) []Target
}

// Default sends every message to one target. Low-priority messages are
// delivered silently.
type Default struct {
	Types   map[string]bool
	Channel string
	Target  string
	Aux     bool
}

// Route implements Router.
func (d Default) Route(_ model.Source, msg *model.Message) []Target {
	if d.Types != nil && !d.Types[msg.Kind] {
		return nil
	}
	return []Target{{Channel: d.Channel, Target: d.Target, Silent: msg.Priority == model.PriorityLow, Aux: d.Aux}}
}

// Chain sends a message to every target any of its routers returns.
type Chain []Router

// Route implements Router.
func (c Chain) Route(src model.Source, msg *model.Message) []Target {
	var out []Target
	for _, r := range c {
		out = append(out, r.Route(src, msg)...)
	}
	return out
}
