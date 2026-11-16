package main

import (
	"context"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"nekopost/internal/config"
	"nekopost/internal/filter"
	"nekopost/internal/service"
	"nekopost/internal/store"
)

func cmdInbox(cfg *config.Config, st *store.Store, args []string) int {
	if len(args) == 0 {
		fmt.Fprint(os.Stderr, usage)
		return 2
	}
	ctx := context.Background()
	switch args[0] {
	case "list":
		limit := 20
		if len(args) == 3 && args[1] == "--limit" {
			n, err := strconv.Atoi(args[2])
			if err != nil || n < 1 {
				fmt.Fprintln(os.Stderr, "--limit 必须是正整数")
				return 2
			}
			limit = n
		} else if len(args) != 1 {
			fmt.Fprintln(os.Stderr, "用法: nekopost inbox list [--limit N]")
			return 2
		}
		list, err := st.ListInbox(ctx, limit)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		if len(list) == 0 {
			fmt.Println("没有消息")
			return 0
		}
		for _, m := range list {
			fmt.Printf("%s  %s  %s  %s  %s\n", m.ReceivedAt.In(cfg.Location).Format(time.RFC3339), m.ID, m.SourceID, m.Kind, m.Status)
		}
		return 0
	case "show":
		if len(args) != 2 {
			fmt.Fprintln(os.Stderr, "用法: nekopost inbox show <id>")
			return 2
		}
		title, body, _, ok, err := st.ReadBody(ctx, args[1])
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		if !ok {
			fmt.Println("正文已不在服务器上")
			return 0
		}
		if title != "" {
			fmt.Println(title)
		}
		fmt.Println(body)
		return 0
	case "search":
		if len(args) < 2 {
			fmt.Fprintln(os.Stderr, "用法: nekopost inbox search <词>")
			return 2
		}
		q := strings.Join(args[1:], " ")
		hits, scanned, done, err := service.Search(ctx, st, q, 20)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		for _, h := range hits {
			fmt.Printf("%s  %s  %s\n", h.ID, h.SourceID, filter.Preview(h.Title, h.Body))
		}
		if !done {
			fmt.Fprintf(os.Stderr, "已扫描 %d 条，后面还有更早的消息\n", scanned)
		}
		return 0
	default:
		fmt.Fprintf(os.Stderr, "未知的 inbox 子命令 %q\n", args[0])
		return 2
	}
}
