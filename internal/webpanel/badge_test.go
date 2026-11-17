package webpanel

import (
	"testing"

	"nekopost/internal/model"
)

func TestBadgesForAppOnlyAndSkipped(t *testing.T) {
	if b := statusBadgeVia(model.StatusDelivered, true); b.Label != model.StatusDelivered.LabelVia(true) || b.Label == model.StatusDelivered.Label() || b.Tone != "ok" {
		t.Fatal("app-only message must not read as delivered to Telegram:", b)
	}
	if b := statusBadge(model.StatusDelivered); b.Label != model.StatusDelivered.Label() {
		t.Fatal(b)
	}
	if b := deliveryBadge(model.DeliverySkipped); b.Label == string(model.DeliverySkipped) || b.Tone != "neutral" {
		t.Fatal(b)
	}
}
