package auth

import (
	"strings"
	"testing"
)

func TestTokens(t *testing.T) {
	a, b := NewToken(SourcePrefix), NewToken(SourcePrefix)
	if a == b || !strings.HasPrefix(a, "mgs_") || len(a) != 4+43 {
		t.Fatalf("bad tokens %q %q", a, b)
	}
	if Hash(a) == Hash(b) || len(Hash(a)) != 64 || Hash(a) != Hash(a) {
		t.Error("hash not deterministic/unique")
	}
	if DisplayPrefix(a) != a[:10] {
		t.Error("prefix")
	}
}

func TestBearer(t *testing.T) {
	for in, want := range map[string]string{"Bearer abc": "abc", "bearer  abc ": "abc", "Basic abc": "", "Bearer": "", "": ""} {
		got, ok := BearerToken(in)
		if got != want || ok != (want != "") {
			t.Errorf("BearerToken(%q) = %q,%v", in, got, ok)
		}
	}
}

func TestContentHashKeyed(t *testing.T) {
	k1, k2 := []byte("k1"), []byte("k2")
	if ContentHash(k1, "a", "bc") == ContentHash(k1, "ab", "c") {
		t.Error("field boundaries must matter")
	}
	if ContentHash(k1, "123456") == ContentHash(k2, "123456") {
		t.Error("hash must depend on key")
	}
}
