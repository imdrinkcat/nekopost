package sealbox

import (
	"bytes"
	"testing"
)

func TestRoundTripAndAAD(t *testing.T) {
	kek := bytes.Repeat([]byte{0x11}, 32)
	k, err := Derive(kek)
	if err != nil {
		t.Fatal(err)
	}
	dek, err := NewDEK()
	if err != nil {
		t.Fatal(err)
	}
	wrapped, err := WrapDEK(k, "2026-11-16", dek)
	if err != nil {
		t.Fatal(err)
	}
	opened, err := UnwrapDEK(k, "2026-11-16", wrapped)
	if err != nil || !bytes.Equal(opened, dek) {
		t.Fatalf("unwrap %v", err)
	}
	if _, err := UnwrapDEK(k, "2026-11-17", wrapped); err == nil {
		t.Fatal("a different day must not unwrap")
	}
	other, _ := Derive(bytes.Repeat([]byte{0x22}, 32))
	if _, err := UnwrapDEK(other, "2026-11-16", wrapped); err == nil {
		t.Fatal("a different KEK must not unwrap")
	}

	plain := []byte(`{"v":1,"t":"t","b":"sentinel-body","m":{}}`)
	ct, err := SealBody(dek, "m_abc", "2026-11-16", plain)
	if err != nil || ct[0] != 0x01 {
		t.Fatalf("seal %v %v", ct, err)
	}
	got, err := OpenBody(dek, "m_abc", "2026-11-16", ct)
	if err != nil || !bytes.Equal(got, plain) {
		t.Fatalf("open %s %v", got, err)
	}
	if _, err := OpenBody(dek, "m_other", "2026-11-16", ct); err == nil {
		t.Fatal("a different message id must not open")
	}
	tampered := append([]byte(nil), ct...)
	tampered[len(tampered)-1] ^= 0xff
	if _, err := OpenBody(dek, "m_abc", "2026-11-16", tampered); err == nil {
		t.Fatal("tampered ciphertext must not open")
	}
	if _, err := OpenBody(bytes.Repeat([]byte{0}, 32), "m_abc", "2026-11-16", ct); err == nil {
		t.Fatal("destroyed DEK must not open")
	}
}

func TestKeyMaterialFormats(t *testing.T) {
	raw := bytes.Repeat([]byte{0x03}, 32)
	cur, prev, err := ParseKeyMaterial(raw)
	if err != nil || !bytes.Equal(cur, raw) || prev != nil {
		t.Fatalf("raw: %v %d", err, len(prev))
	}
	text := FormatKeys(raw, bytes.Repeat([]byte{0x04}, 32))
	cur, prev, err = ParseKeyMaterial(text)
	if err != nil || !bytes.Equal(cur, raw) || !bytes.Equal(prev, bytes.Repeat([]byte{0x04}, 32)) {
		t.Fatalf("lines: %v", err)
	}
	if IdemHash(cur, "same") != IdemHash(cur, "same") || IdemHash(cur, "same") == IdemHash(cur, "other") {
		t.Fatal("idem hash")
	}
}

func TestDeriveStable(t *testing.T) {
	k1, _ := Derive(bytes.Repeat([]byte{9}, 32))
	k2, _ := Derive(bytes.Repeat([]byte{9}, 32))
	if k1.KEKIDHex() != k2.KEKIDHex() || len(k1.KEKIDHex()) != 16 {
		t.Fatalf("kek id %s", k1.KEKIDHex())
	}
	if bytes.Equal(k1.Wrap, k1.Dedupe) || bytes.Equal(k1.Audit, k1.OAuth) {
		t.Fatal("subkeys must be separated")
	}
}
