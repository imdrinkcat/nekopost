package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"errors"
	"flag"
	"fmt"
	"os"
	"strings"
	"time"

	"nekopost/internal/config"
	"nekopost/internal/enroll"
	"nekopost/internal/model"
	"nekopost/internal/store"
)

func cmdDevice(cfg *config.Config, st *store.Store, args []string) int {
	if len(args) == 0 {
		return deviceUsage()
	}
	ctx := context.Background()
	now := time.Now()
	var err error
	switch args[0] {
	case "list":
		devices, e := st.ListDevices(ctx)
		if e != nil {
			err = e
			break
		}
		for _, d := range devices {
			state := "启用"
			if d.Revoked {
				state = "已吊销"
			} else if d.Suspended {
				state = "暂停读取"
			}
			fmt.Printf("%s  %q  %s  %s\n", d.ID, d.Name, state, d.Scopes)
		}
	case "revoke":
		if len(args) != 2 {
			return deviceUsage()
		}
		err = st.RevokeDevice(ctx, args[1], "cli", now)
	case "pair":
		return pairDevice(cfg, st, args[1:])
	default:
		return deviceUsage()
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	return 0
}

func deviceUsage() int {
	fmt.Fprintln(os.Stderr, "用法：nekopost device pair --name Fold8 [--role full|sender|receiver] [--source fold8]；device list；device revoke <id>")
	return 2
}

func pairDevice(cfg *config.Config, st *store.Store, args []string) int {
	flags := flag.NewFlagSet("device pair", flag.ContinueOnError)
	name := flags.String("name", "", "设备名称")
	role := flags.String("role", "full", "权限模板")
	source := flags.String("source", "", "已存在或待创建的来源 ID")
	if flags.Parse(args) != nil || flags.NArg() != 0 || strings.TrimSpace(*name) == "" {
		return deviceUsage()
	}
	clean, err := enroll.NormalizeName(*name, "")
	if err != nil {
		fmt.Fprintln(os.Stderr, "设备名称无效")
		return 2
	}
	if rs := []rune(clean); len(rs) > 64 {
		fmt.Fprintln(os.Stderr, "设备名称最多 64 字符")
		return 2
	}
	scopes := ""
	switch *role {
	case "sender":
		scopes = store.ScopeSend
	case "receiver":
		scopes = store.ScopeRead
	case "full":
		scopes = strings.Join([]string{store.ScopeSend, store.ScopeRead, store.ScopeAdminRead, store.ScopeAdminWrite}, ",")
	default:
		return deviceUsage()
	}
	public := cfg.Web.PublicURL
	if public == "" && cfg.Domain != "" && cfg.TLSMode == "autocert" {
		public = "https://" + cfg.Domain
	}
	code, short := enroll.NewPairingCodes()
	link, err := enroll.DeviceLink(public, code)
	if err != nil {
		fmt.Fprintln(os.Stderr, "请先设置有效的 HTTPS 对外地址")
		return 2
	}
	if *role != "receiver" {
		if *source == "" {
			*source = strings.ToLower(clean)
			if !model.ValidSourceID(*source) {
				*source = "device-" + strings.ToLower(rand.Text()[:8])
			}
		}
		if !model.ValidSourceID(*source) {
			fmt.Fprintln(os.Stderr, "来源 ID 无效")
			return 2
		}
	} else {
		*source = ""
	}
	ctx := context.Background()
	now := time.Now()
	p := store.Pairing{ID: "p_" + rand.Text(), Name: clean, SourceID: *source, Scopes: scopes, Expires: now.Add(5 * time.Minute)}
	if err := st.NewPairing(ctx, p, store.PairingCodeHash(code), store.PairingCodeHash(short), "cli", now); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if err := enroll.RenderTerminal(os.Stdout, link); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	fmt.Printf("服务器 %s\n手动配对码 %s-%s-%s（5 分钟有效）。\n", public, short[:4], short[4:8], short[8:])
	t := time.NewTicker(time.Second)
	defer t.Stop()
	for time.Now().Before(p.Expires) {
		current, err := st.Pairing(ctx, p.ID)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		switch current.State {
		case "open":
			<-t.C
		case "claimed":
			return confirmPairing(st, current)
		case "approved", "done":
			fmt.Println("配对已批准。")
			return 0
		default:
			fmt.Fprintln(os.Stderr, "配对已结束，请重新生成。")
			return 1
		}
	}
	fmt.Fprintln(os.Stderr, "配对码已过期。")
	return 1
}

func confirmPairing(st *store.Store, p store.Pairing) int {
	fmt.Printf("设备自报名称：%q，机型：%q\n候选数字 %s\n请输入手机显示的数字，或输入 deny：", p.Name, p.Model, strings.Join(p.Choices, " / "))
	scanner := bufio.NewScanner(os.Stdin)
	if !scanner.Scan() {
		return 1
	}
	choice := strings.TrimSpace(scanner.Text())
	if choice != "deny" && (len(choice) != 2 || strings.Trim(choice, "0123456789") != "") {
		return 2
	}
	ok, err := st.DecidePairing(context.Background(), p.ID, choice, "cli", time.Now())
	if errors.Is(err, store.ErrNotFound) {
		fmt.Fprintln(os.Stderr, "配对已处理或过期。")
		return 1
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if ok {
		fmt.Println("配对已批准，请在手机上继续。")
	} else {
		fmt.Println("配对已拒绝。")
	}
	return 0
}

func cmdLockdown(st *store.Store, args []string) int {
	f := flag.NewFlagSet("lockdown", flag.ContinueOnError)
	lift := f.Bool("lift", false, "解除紧急锁定")
	revoke := f.Bool("revoke-devices", false, "吊销全部设备")
	keep := f.String("keep", "", "保留的设备 ID")
	if f.Parse(args) != nil || f.NArg() != 0 || (*lift && (*revoke || *keep != "")) {
		return 2
	}
	if err := st.Lockdown(context.Background(), "cli", time.Now(), *lift, *revoke, *keep); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if *lift {
		fmt.Println("已解除紧急锁定。")
	} else {
		fmt.Println("已锁定网页和设备读取。")
	}
	return 0
}
