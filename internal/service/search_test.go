package service

import (
	"context"
	"fmt"
	"path/filepath"
	"testing"
	"time"

	"nekopost/internal/model"
	"nekopost/internal/store"
)

func TestSearchFindsFoldedText(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "t.db"), store.TestingOptions())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	now := time.Now()
	for i, body := range []string{"验证码 123456", "普通通知", "HELLO World"} {
		m := &model.Message{ID: model.NewMessageID(now.Add(time.Duration(i) * time.Millisecond)), SourceID: "a",
			Kind: "sms", Priority: model.PriorityNormal, Body: body, ContentHash: body, ReceivedAt: now.Add(time.Duration(i) * time.Millisecond)}
		if _, err := st.InsertMessage(context.Background(), m, []store.NewDelivery{{Channel: "telegram", Target: "default"}}, 0); err != nil {
			t.Fatal(err)
		}
	}
	hits, _, _, err := Search(context.Background(), st, "hello", 10)
	if err != nil || len(hits) != 1 || hits[0].Body != "HELLO World" {
		t.Fatalf("%v %v", hits, err)
	}
	hits, _, _, err = Search(context.Background(), st, `"验证码" -普通`, 10)
	if err != nil || len(hits) != 1 {
		t.Fatalf("phrase: %d %v", len(hits), err)
	}
}

func TestSearchContinuesThroughIdenticalTimestamps(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "t.db"), store.TestingOptions())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	ctx := context.Background()
	now := time.Now()
	for i := range 201 {
		body := "普通通知"
		if i == 0 {
			body = "末尾匹配"
		}
		m := &model.Message{
			ID: fmt.Sprintf("m_%04d", i), SourceID: "a", Kind: "text",
			Priority: model.PriorityNormal, Body: body, ReceivedAt: now,
		}
		if _, err := st.InsertMessage(ctx, m, []store.NewDelivery{{Channel: "telegram", Target: "default"}}, 0); err != nil {
			t.Fatal(err)
		}
	}
	hits, scanned, _, err := search(ctx, st, "末尾匹配", 10, time.Now().Add(10*time.Second))
	if err != nil || scanned != 201 || len(hits) != 1 || hits[0].ID != "m_0000" {
		t.Fatalf("hits=%+v scanned=%d err=%v", hits, scanned, err)
	}
}
