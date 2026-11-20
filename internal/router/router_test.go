package router

import (
	"testing"

	"nekopost/internal/model"
)

func TestChainSendsToEveryRouterAndMarksEmailAuxiliary(t *testing.T) {
	r := Chain{
		Default{Channel: "telegram", Target: "default"},
		Default{Channel: "email", Target: "default", Aux: true, Types: map[string]bool{"sms": true}},
	}
	sms := &model.Message{Kind: "sms", Priority: model.PriorityLow}
	got := r.Route(model.Source{}, sms)
	if len(got) != 2 || got[0].Channel != "telegram" || got[0].Aux || !got[0].Silent ||
		got[1].Channel != "email" || !got[1].Aux {
		t.Fatalf("%+v", got)
	}
	got = r.Route(model.Source{}, &model.Message{Kind: "text"})
	if len(got) != 1 || got[0].Channel != "telegram" {
		t.Fatalf("a type email does not take must only reach Telegram: %+v", got)
	}
	none := Default{Channel: "email", Target: "default", Aux: true, Types: map[string]bool{}}
	if got := none.Route(model.Source{}, sms); len(got) != 0 {
		t.Fatalf("an empty type set means no types: %+v", got)
	}
}
