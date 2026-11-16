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
		if got := Sender(in); got != want {
			t.Errorf("Sender(%q) = %q, want %q", in, got, want)
		}
	}
	if got := []rune(Sender(strings.Repeat("长", 100))); len(got) != 64 {
		t.Fatal(len(got))
	}
}
