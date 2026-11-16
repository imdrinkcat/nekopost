package main

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"nekopost/internal/store"
)

func cmdApprove(st *store.Store) int {
	ctx := context.Background()
	pending, err := st.PendingWebApprovals(ctx, time.Now())
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if len(pending) == 0 {
		fmt.Println("没有待确认的网页登录。")
		return 0
	}
	for i, a := range pending {
		fmt.Printf("%d. %s · %s · %s · 候选 %s\n", i+1, a.Identity, a.Browser, a.IP, strings.Join(a.Choices, " / "))
	}
	input := bufio.NewScanner(os.Stdin)
	fmt.Print("选择确认编号（回车退出）：")
	if !input.Scan() || input.Text() == "" {
		return 0
	}
	i, err := strconv.Atoi(strings.TrimSpace(input.Text()))
	if err != nil || i < 1 || i > len(pending) {
		fmt.Fprintln(os.Stderr, "编号无效")
		return 2
	}
	a := pending[i-1]
	fmt.Print("输入浏览器上的两位数字，或输入 deny 拒绝：")
	if !input.Scan() {
		return 2
	}
	choice := strings.TrimSpace(input.Text())
	if choice != "deny" && (len(choice) != 2 || choice[0] < '0' || choice[0] > '9' || choice[1] < '0' || choice[1] > '9') {
		fmt.Fprintln(os.Stderr, "需要两位数字或 deny")
		return 2
	}
	trust := false
	if choice != "deny" {
		fmt.Print("信任此浏览器 30 天？[y/N] ")
		if !input.Scan() {
			return 2
		}
		trust = strings.EqualFold(strings.TrimSpace(input.Text()), "y")
	}
	ok, err := st.DecideWebApproval(ctx, a.ID, choice, "cli", trust, time.Now())
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if ok {
		fmt.Println("已确认网页登录")
	} else {
		fmt.Println("已拒绝网页登录")
	}
	return 0
}

func cmdWeb(st *store.Store, args []string) int {
	ctx := context.Background()
	switch {
	case len(args) == 2 && args[0] == "unlock" && strings.HasPrefix(args[1], "google:"):
		if err := st.UnlockWebIdentity(ctx, args[1], time.Now()); err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		fmt.Println("已解除该身份的网页登录限制")
	case len(args) == 2 && args[0] == "sessions" && args[1] == "revoke-all":
		if err := st.RevokeWebAccess(ctx, "cli", "others", "", "", time.Now()); err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		fmt.Println("已退出所有网页会话")
	case len(args) == 2 && args[0] == "audit" && args[1] == "verify":
		_, valid, err := st.ReadAudit(ctx)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		if !valid {
			fmt.Fprintln(os.Stderr, "审计链校验失败")
			return 1
		}
		fmt.Println("审计链校验通过")
	default:
		fmt.Fprintln(os.Stderr, "用法：nekopost web unlock google:<sub> 或 nekopost web audit verify")
		return 2
	}
	return 0
}
