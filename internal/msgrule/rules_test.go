package msgrule

import (
	"strings"
	"testing"
)

func TestHasCodeAndPreview(t *testing.T) {
	if !HasCode("临时密码 AB12CD") {
		t.Fatal("keyword plus token")
	}
	if !HasCode("Your PIN is 4321") {
		t.Fatal("PIN")
	}
	if HasCode("订单金额 1280 元") {
		t.Fatal("an amount is not a code")
	}
	got := Preview("银行", "验证码 123456，口令 AB12cd")
	if strings.Contains(got, "123456") || strings.Contains(got, "AB12cd") {
		t.Fatalf("preview leaked: %s", got)
	}
}

func TestSender(t *testing.T) {
	cases := map[string]string{
		"95555":                "95555",
		"+8613800138000":       "+8613800138000",
		"  微信 ·  张三 ":          "微信 · 张三",
		"Telegram · Alice2024": "Telegram · Alice2024",
		"验证码 482913":           "验证码 ******",
		"Bank · code is 7731":  "Bank · code is ******",
		"":                     "",
	}
	for in, want := range cases {
		if got := Sender(in, false, false); got != want {
			t.Errorf("Sender(%q) = %q, want %q", in, got, want)
		}
	}
	// With a code: code-shaped tokens are masked wherever they stand, but a
	// bare SMS address stays readable.
	withCode := map[string]string{
		"95555":                     "95555",
		"+86 138 0013 8000":         "+86 138 0013 8000",
		"106575261108":              "106575261108",
		"G-482913 是您的 Google 验证码":   "G-****** 是您的 Google 验证码",
		"482913 is your login code": "****** is your login code",
		"Steam · AB12C 登录":          "Steam · ****** 登录",
		"验证码 482913":                "验证码 ******",
	}
	for in, want := range withCode {
		if got := Sender(in, true, true); got != want {
			t.Errorf("Sender(%q, true) = %q, want %q", in, got, want)
		}
	}
	if got := Sender("482913", true, false); got != "******" {
		t.Errorf("notification title that is only a code: %q", got)
	}
	if got := []rune(Sender(strings.Repeat("长", 100), false, false)); len(got) != 64 {
		t.Fatal(len(got))
	}
}
