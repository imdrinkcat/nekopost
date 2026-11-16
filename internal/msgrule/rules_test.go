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
