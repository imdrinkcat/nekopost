// Package telegram is a minimal Telegram Bot API client covering only what
// the gateway uses. It never includes the bot token in returned errors.
package telegram

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"strings"
	"sync/atomic"
	"time"
)

// Client talks to the Bot API.
type Client struct {
	base  string
	token string
	hc    *http.Client
}

// New returns a client. base is normally "https://api.telegram.org".
func New(base, token string) *Client {
	tr := &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		DialContext:           (&net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 0, // per-request contexts bound the total time (long polling)
		MaxIdleConns:          4,
		MaxIdleConnsPerHost:   4,
		IdleConnTimeout:       90 * time.Second,
		ForceAttemptHTTP2:     true,
	}
	return &Client{base: strings.TrimRight(base, "/"), token: token, hc: &http.Client{Transport: tr}}
}

// APIError is an error response from Telegram.
type APIError struct {
	Method      string
	Code        int
	Description string
	RetryAfter  time.Duration
}

func (e *APIError) Error() string {
	return fmt.Sprintf("telegram %s: %d %s", e.Method, e.Code, e.Description)
}

// NetError is a transport failure. Written reports whether the request had
// been fully sent before the failure: if so, Telegram may have acted on it.
type NetError struct {
	Method  string
	Written bool
	Timeout bool
	Msg     string
}

func (e *NetError) Error() string {
	return fmt.Sprintf("telegram %s: network error: %s", e.Method, e.Msg)
}

type envelope struct {
	OK          bool            `json:"ok"`
	Result      json.RawMessage `json:"result"`
	ErrorCode   int             `json:"error_code"`
	Description string          `json:"description"`
	Parameters  *struct {
		RetryAfter int `json:"retry_after"`
	} `json:"parameters"`
}

// Call invokes a Bot API method with a JSON body and decodes the result.
func (c *Client) Call(ctx context.Context, method string, params any, out any) error {
	body, err := json.Marshal(params)
	if err != nil {
		return err
	}
	var written atomic.Bool
	trace := &httptrace.ClientTrace{WroteRequest: func(i httptrace.WroteRequestInfo) {
		if i.Err == nil {
			written.Store(true)
		}
	}}
	req, err := http.NewRequestWithContext(httptrace.WithClientTrace(ctx, trace), http.MethodPost,
		c.base+"/bot"+c.token+"/"+method, bytes.NewReader(body))
	if err != nil {
		return &NetError{Method: method, Msg: "invalid request"}
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.hc.Do(req)
	if err != nil {
		return c.netErr(method, written.Load(), err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return c.netErr(method, true, err)
	}
	var env envelope
	if err := json.Unmarshal(raw, &env); err != nil {
		// Non-JSON answer (e.g. a proxy error page): the request did arrive.
		return &APIError{Method: method, Code: resp.StatusCode, Description: "unexpected non-JSON response"}
	}
	if !env.OK {
		e := &APIError{Method: method, Code: env.ErrorCode, Description: env.Description}
		if e.Code == 0 {
			e.Code = resp.StatusCode
		}
		if env.Parameters != nil && env.Parameters.RetryAfter > 0 {
			e.RetryAfter = time.Duration(env.Parameters.RetryAfter) * time.Second
		}
		return e
	}
	if out != nil {
		if err := json.Unmarshal(env.Result, out); err != nil {
			return fmt.Errorf("telegram %s: decode result: %w", method, err)
		}
	}
	return nil
}

func (c *Client) netErr(method string, written bool, err error) error {
	var te interface{ Timeout() bool }
	timeout := errors.As(err, &te) && te.Timeout() || errors.Is(err, context.DeadlineExceeded)
	// Drop the request URL (which embeds the token) that net/http prefixes.
	var ue *url.Error
	if errors.As(err, &ue) {
		err = ue.Err
	}
	msg := err.Error()
	if c.token != "" {
		msg = strings.ReplaceAll(msg, c.token, "<token>")
	}
	return &NetError{Method: method, Written: written, Timeout: timeout, Msg: msg}
}

// User is a Telegram user or bot.
type User struct {
	ID        int64  `json:"id"`
	IsBot     bool   `json:"is_bot"`
	FirstName string `json:"first_name"`
	Username  string `json:"username"`
}

// Chat is a Telegram chat.
type Chat struct {
	ID   int64  `json:"id"`
	Type string `json:"type"`
}

// Message is a Telegram message (only the fields used here).
type Message struct {
	MessageID int64  `json:"message_id"`
	From      *User  `json:"from"`
	Chat      Chat   `json:"chat"`
	Date      int64  `json:"date"`
	Text      string `json:"text"`
}

// CallbackQuery is an inline keyboard button press.
type CallbackQuery struct {
	ID      string   `json:"id"`
	From    User     `json:"from"`
	Message *Message `json:"message"`
	Data    string   `json:"data"`
}

// Update is one incoming event.
type Update struct {
	UpdateID      int64          `json:"update_id"`
	Message       *Message       `json:"message"`
	CallbackQuery *CallbackQuery `json:"callback_query"`
}

// InlineKeyboardButton is a button attached to a message.
type InlineKeyboardButton struct {
	Text         string `json:"text"`
	CallbackData string `json:"callback_data,omitempty"`
}

// InlineKeyboardMarkup is a keyboard attached to a message.
type InlineKeyboardMarkup struct {
	InlineKeyboard [][]InlineKeyboardButton `json:"inline_keyboard"`
}

type linkPreview struct {
	IsDisabled bool `json:"is_disabled"`
}

// SendMessageParams are the arguments of sendMessage.
type SendMessageParams struct {
	ChatID              int64                 `json:"chat_id"`
	Text                string                `json:"text"`
	ParseMode           string                `json:"parse_mode,omitempty"`
	DisableNotification bool                  `json:"disable_notification,omitempty"`
	ReplyMarkup         *InlineKeyboardMarkup `json:"reply_markup,omitempty"`
	LinkPreviewOptions  *linkPreview          `json:"link_preview_options,omitempty"`
}

// GetMe returns the bot's identity.
func (c *Client) GetMe(ctx context.Context) (User, error) {
	var u User
	err := c.Call(ctx, "getMe", struct{}{}, &u)
	return u, err
}

// GetUpdates long-polls for updates.
func (c *Client) GetUpdates(ctx context.Context, offset int64, timeout time.Duration) ([]Update, error) {
	var ups []Update
	ctx, cancel := context.WithTimeout(ctx, timeout+15*time.Second)
	defer cancel()
	err := c.Call(ctx, "getUpdates", map[string]any{
		"offset":          offset,
		"timeout":         int(timeout / time.Second),
		"allowed_updates": []string{"message", "callback_query"},
	}, &ups)
	return ups, err
}

// SendMessage sends a text message. Link previews are always disabled.
func (c *Client) SendMessage(ctx context.Context, p SendMessageParams) (Message, error) {
	p.LinkPreviewOptions = &linkPreview{IsDisabled: true}
	var m Message
	err := c.Call(ctx, "sendMessage", p, &m)
	return m, err
}

// EditMessageText replaces the text and keyboard of an existing message.
func (c *Client) EditMessageText(ctx context.Context, chatID, messageID int64, text, parseMode string, kb *InlineKeyboardMarkup) error {
	return c.Call(ctx, "editMessageText", map[string]any{
		"chat_id":              chatID,
		"message_id":           messageID,
		"text":                 text,
		"parse_mode":           parseMode,
		"reply_markup":         kb,
		"link_preview_options": linkPreview{IsDisabled: true},
	}, nil)
}

// AnswerCallbackQuery acknowledges a button press, optionally with a toast.
func (c *Client) AnswerCallbackQuery(ctx context.Context, id, text string, alert bool) error {
	return c.Call(ctx, "answerCallbackQuery", map[string]any{
		"callback_query_id": id, "text": text, "show_alert": alert,
	}, nil)
}

// BotCommand is an entry of the bot's command menu.
type BotCommand struct {
	Command     string `json:"command"`
	Description string `json:"description"`
}

// SetMyCommands sets the command list; chatID 0 means the default scope.
func (c *Client) SetMyCommands(ctx context.Context, cmds []BotCommand, chatID int64) error {
	p := map[string]any{"commands": cmds}
	if chatID != 0 {
		p["scope"] = map[string]any{"type": "chat", "chat_id": chatID}
	}
	return c.Call(ctx, "setMyCommands", p, nil)
}

// DeleteWebhook removes any webhook so long polling can work.
func (c *Client) DeleteWebhook(ctx context.Context) error {
	return c.Call(ctx, "deleteWebhook", map[string]any{"drop_pending_updates": false}, nil)
}

// IsNotModified reports Telegram's "message is not modified" edit error.
func IsNotModified(err error) bool {
	var ae *APIError
	return errors.As(err, &ae) && strings.Contains(ae.Description, "message is not modified")
}
