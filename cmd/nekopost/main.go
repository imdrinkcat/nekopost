// Command nekopost is the Personal Message Gateway: the service itself
// ("nekopost serve") and its administration CLI.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"text/tabwriter"
	"time"

	"nekopost/internal/app"
	"nekopost/internal/auth"
	"nekopost/internal/config"
	"nekopost/internal/filter"
	"nekopost/internal/model"
	"nekopost/internal/store"
	"nekopost/internal/telegram"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "dev"

const usage = `nekopost — 个人消息网关

用法:
  nekopost [--env-file 路径] <命令> [参数]

服务:
  serve                               运行网关服务（通常由 systemd 启动）
  check [--send-test]                 检查配置、数据库和 Telegram 连接
  version                             显示版本

消息来源:
  source list                         列出所有来源
  source add <id> [--name 名称] [--icon 图标]
                                      新增来源并生成凭证（凭证只显示一次）
  source show <id>                    查看来源详情与凭证记录
  source set <id> [--name 名称] [--icon 图标]
                                      修改显示名称或图标
  source disable <id>                 停用来源（拒绝其新消息）
  source enable <id>                  重新启用来源
  source rotate <id>                  生成新凭证并立即吊销旧凭证
  source remove <id> --yes            删除来源及其凭证

管理 API 密钥（只读管理接口 /v1/admin/*）:
  admin-key create <名称>             生成管理密钥（只显示一次）
  admin-key list                      列出管理密钥
  admin-key revoke <前缀>             吊销管理密钥

配置文件默认为 ` + config.DefaultEnvFile + `，进程环境变量优先。
`

func main() {
	os.Exit(run(os.Args[1:]))
}

func run(args []string) int {
	global := flag.NewFlagSet("nekopost", flag.ContinueOnError)
	envFile := global.String("env-file", envOr("NEKOPOST_ENV_FILE", config.DefaultEnvFile), "配置文件路径")
	global.Usage = func() { fmt.Fprint(os.Stderr, usage) }
	if err := global.Parse(args); err != nil {
		return 2
	}
	args = global.Args()
	if len(args) == 0 {
		fmt.Fprint(os.Stderr, usage)
		return 2
	}

	fileVals := map[string]string{}
	if v, err := config.ReadEnvFile(*envFile); err == nil {
		fileVals = v
	} else if errors.Is(err, os.ErrPermission) {
		// The service runs unprivileged and receives the same values from
		// systemd's EnvironmentFile; the root-only file itself is unreadable.
	} else if !errors.Is(err, os.ErrNotExist) {
		fmt.Fprintf(os.Stderr, "读取配置文件 %s 失败: %v\n", *envFile, err)
		return 1
	}
	cfg, cfgErr := config.Load(config.EnvWithFile(fileVals))

	cmd, rest := args[0], args[1:]
	switch cmd {
	case "version", "--version", "-v":
		fmt.Println("nekopost", version)
		return 0
	case "help", "-h", "--help":
		fmt.Print(usage)
		return 0
	}
	if cfgErr != nil {
		fmt.Fprintf(os.Stderr, "配置错误:\n%v\n", cfgErr)
		return 1
	}
	switch cmd {
	case "serve":
		return cmdServe(cfg)
	case "check":
		return cmdCheck(cfg, rest)
	case "source":
		return withStore(cfg, func(st *store.Store) int { return cmdSource(cfg, st, rest) })
	case "admin-key":
		return withStore(cfg, func(st *store.Store) int { return cmdAdminKey(st, rest) })
	}
	fmt.Fprintf(os.Stderr, "未知命令 %q\n\n%s", cmd, usage)
	return 2
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func newLogger(level slog.Level) *slog.Logger {
	opts := &slog.HandlerOptions{Level: level}
	if os.Getenv("INVOCATION_ID") != "" {
		// Under systemd, journald already timestamps every line.
		opts.ReplaceAttr = func(groups []string, a slog.Attr) slog.Attr {
			if len(groups) == 0 && a.Key == slog.TimeKey {
				return slog.Attr{}
			}
			return a
		}
	}
	return slog.New(slog.NewTextHandler(os.Stderr, opts))
}

func cmdServe(cfg *config.Config) int {
	log := newLogger(cfg.LogLevel)
	slog.SetDefault(log)
	if err := cfg.ValidateServe(); err != nil {
		log.Error("配置不完整，无法启动", "err", err)
		return 1
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := app.Run(ctx, cfg, version, log); err != nil {
		log.Error("服务异常退出", "err", err)
		return 1
	}
	return 0
}

// dropPrivileges switches to the owner of the data directory when the CLI
// is run as root, so files it creates stay readable by the service user.
func dropPrivileges(dataDir string) error {
	if os.Geteuid() != 0 {
		return nil
	}
	fi, err := os.Stat(dataDir)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return err
	}
	stat, ok := fi.Sys().(*syscall.Stat_t)
	if !ok || stat.Uid == 0 {
		return nil
	}
	if err := syscall.Setgroups([]int{}); err != nil {
		return err
	}
	if err := syscall.Setgid(int(stat.Gid)); err != nil {
		return err
	}
	return syscall.Setuid(int(stat.Uid))
}

func withStore(cfg *config.Config, fn func(*store.Store) int) int {
	if err := dropPrivileges(cfg.DataDir); err != nil {
		fmt.Fprintf(os.Stderr, "切换到服务用户失败: %v\n", err)
		return 1
	}
	st, err := store.Open(cfg.DBPath())
	if err != nil {
		fmt.Fprintf(os.Stderr, "打开数据库 %s 失败: %v\n", cfg.DBPath(), err)
		return 1
	}
	defer st.Close()
	return fn(st)
}

func fmtTime(cfg *config.Config, t time.Time) string {
	if t.IsZero() {
		return "-"
	}
	return t.In(cfg.Location).Format("2006-01-02 15:04:05")
}

func endpoint(cfg *config.Config) string {
	if cfg.TLSMode == "autocert" && cfg.Domain != "" {
		return "https://" + cfg.Domain + "/v1/messages"
	}
	return "http://" + cfg.HTTPAddr + "/v1/messages"
}

// parseInterleaved parses flags that may appear before or after positional
// arguments (e.g. "add iphone --name iPhone").
func parseInterleaved(fs *flag.FlagSet, args []string) ([]string, error) {
	var pos []string
	for {
		if err := fs.Parse(args); err != nil {
			return nil, err
		}
		args = fs.Args()
		if len(args) == 0 {
			return pos, nil
		}
		pos = append(pos, args[0])
		args = args[1:]
	}
}

func cmdSource(cfg *config.Config, st *store.Store, args []string) int {
	if len(args) == 0 {
		fmt.Fprint(os.Stderr, usage)
		return 2
	}
	ctx := context.Background()
	now := time.Now()
	sub, args := args[0], args[1:]
	fs := flag.NewFlagSet("source "+sub, flag.ContinueOnError)
	name := fs.String("name", "", "显示名称")
	icon := fs.String("icon", "", "显示图标（一个 Emoji）")
	yes := fs.Bool("yes", false, "确认删除")
	pos, err := parseInterleaved(fs, args)
	if err != nil {
		return 2
	}
	needID := func() (string, bool) {
		if len(pos) != 1 {
			fmt.Fprintf(os.Stderr, "用法: nekopost source %s <id>\n", sub)
			return "", false
		}
		return pos[0], true
	}
	notFound := func(id string) int {
		fmt.Fprintf(os.Stderr, "来源 %q 不存在\n", id)
		return 1
	}

	switch sub {
	case "list":
		srcs, err := st.ListSources(ctx)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		if len(srcs) == 0 {
			fmt.Println("尚未配置任何来源。使用 nekopost source add <id> 新增。")
			return 0
		}
		tw := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
		fmt.Fprintln(tw, "ID\t名称\t状态\t最近请求\t创建时间")
		for _, s := range srcs {
			state := "启用"
			if !s.Enabled {
				state = "停用"
			}
			fmt.Fprintf(tw, "%s\t%s\t%s\t%s\t%s\n", s.ID, s.DisplayName(), state, fmtTime(cfg, s.LastSeenAt), fmtTime(cfg, s.CreatedAt))
		}
		tw.Flush()
		return 0

	case "add":
		id, ok := needID()
		if !ok {
			return 2
		}
		if !model.ValidSourceID(id) {
			fmt.Fprintln(os.Stderr, "来源 ID 只能包含小写字母、数字、- 和 _，以字母或数字开头，最长 32 个字符")
			return 2
		}
		n := *name
		if n == "" {
			n = id
		}
		tok := auth.NewToken(auth.SourcePrefix)
		err := st.CreateSource(ctx, model.Source{ID: id, Name: n, Icon: *icon, Enabled: true}, auth.Hash(tok), auth.DisplayPrefix(tok), now)
		if errors.Is(err, store.ErrExists) {
			fmt.Fprintf(os.Stderr, "来源 %q 已存在。如需新凭证请使用 nekopost source rotate %s\n", id, id)
			return 1
		}
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		fmt.Printf("已创建来源 %s（%s）\n\n", id, model.Source{ID: id, Name: n, Icon: *icon}.DisplayName())
		printToken(cfg, tok)
		return 0

	case "show":
		id, ok := needID()
		if !ok {
			return 2
		}
		s, err := st.GetSource(ctx, id)
		if errors.Is(err, store.ErrNotFound) {
			return notFound(id)
		} else if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		fmt.Printf("ID:        %s\n名称:      %s\n启用:      %v\n创建时间:  %s\n最近请求:  %s\n",
			s.ID, s.DisplayName(), s.Enabled, fmtTime(cfg, s.CreatedAt), fmtTime(cfg, s.LastSeenAt))
		fmt.Printf("已吊销凭证被使用: %d 次（最近 %s）\n\n凭证记录（仅显示前缀）:\n", s.AuthFailCount, fmtTime(cfg, s.LastAuthFailAt))
		toks, _ := st.SourceTokens(ctx, id)
		for _, t := range toks {
			state := "有效"
			if !t.RevokedAt.IsZero() {
				state = "已吊销 " + fmtTime(cfg, t.RevokedAt)
			}
			fmt.Printf("  %s…  创建 %s  %s\n", t.Prefix, fmtTime(cfg, t.CreatedAt), state)
		}
		return 0

	case "set":
		id, ok := needID()
		if !ok {
			return 2
		}
		s, err := st.GetSource(ctx, id)
		if errors.Is(err, store.ErrNotFound) {
			return notFound(id)
		} else if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		if *name != "" {
			s.Name = *name
		}
		if *icon != "" {
			s.Icon = *icon
		}
		if err := st.UpdateSourceInfo(ctx, id, s.Name, s.Icon, now); err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		fmt.Printf("已更新：%s\n", s.DisplayName())
		return 0

	case "enable", "disable":
		id, ok := needID()
		if !ok {
			return 2
		}
		err := st.SetSourceEnabled(ctx, id, sub == "enable", now)
		if errors.Is(err, store.ErrNotFound) {
			return notFound(id)
		} else if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		if sub == "enable" {
			fmt.Printf("已启用来源 %s\n", id)
		} else {
			fmt.Printf("已停用来源 %s：其新消息将被拒绝（HTTP 403）\n", id)
		}
		return 0

	case "rotate":
		id, ok := needID()
		if !ok {
			return 2
		}
		tok := auth.NewToken(auth.SourcePrefix)
		err := st.RotateSourceToken(ctx, id, auth.Hash(tok), auth.DisplayPrefix(tok), now)
		if errors.Is(err, store.ErrNotFound) {
			return notFound(id)
		} else if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		fmt.Printf("已为 %s 生成新凭证，旧凭证已立即失效。\n\n", id)
		printToken(cfg, tok)
		return 0

	case "remove":
		id, ok := needID()
		if !ok {
			return 2
		}
		if !*yes {
			fmt.Fprintf(os.Stderr, "删除来源会使其凭证永久失效。确认请执行: nekopost source remove %s --yes\n（如只想暂时禁止，请用 nekopost source disable %s）\n", id, id)
			return 2
		}
		err := st.DeleteSource(ctx, id)
		if errors.Is(err, store.ErrNotFound) {
			return notFound(id)
		} else if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		fmt.Printf("已删除来源 %s\n", id)
		return 0
	}
	fmt.Fprintf(os.Stderr, "未知的 source 子命令 %q\n", sub)
	return 2
}

func printToken(cfg *config.Config, tok string) {
	fmt.Println("凭证（只显示这一次，请立即保存；服务器只保存其哈希）:")
	fmt.Println()
	fmt.Println("  " + tok)
	fmt.Println()
	fmt.Println("提交地址: " + endpoint(cfg))
	fmt.Println("请求头:   Authorization: Bearer <上面的凭证>")
}

func cmdAdminKey(st *store.Store, args []string) int {
	ctx := context.Background()
	if len(args) == 0 {
		fmt.Fprint(os.Stderr, usage)
		return 2
	}
	switch args[0] {
	case "create":
		if len(args) != 2 {
			fmt.Fprintln(os.Stderr, "用法: nekopost admin-key create <名称>")
			return 2
		}
		tok := auth.NewToken(auth.AdminPrefix)
		if err := st.CreateAdminKey(ctx, args[1], auth.Hash(tok), auth.DisplayPrefix(tok), time.Now()); err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		fmt.Println("管理密钥（只显示这一次）:")
		fmt.Println()
		fmt.Println("  " + tok)
		fmt.Println()
		fmt.Println("用于只读管理接口 /v1/admin/status、/v1/admin/sources、/v1/admin/stats")
		return 0
	case "list":
		keys, err := st.ListAdminKeys(ctx)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		tw := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
		fmt.Fprintln(tw, "前缀\t名称\t状态\t最近使用")
		for _, k := range keys {
			state := "有效"
			if !k.RevokedAt.IsZero() {
				state = "已吊销"
			}
			last := "-"
			if !k.LastUsedAt.IsZero() {
				last = k.LastUsedAt.Format("2006-01-02 15:04")
			}
			fmt.Fprintf(tw, "%s\t%s\t%s\t%s\n", k.Prefix, k.Name, state, last)
		}
		tw.Flush()
		return 0
	case "revoke":
		if len(args) != 2 {
			fmt.Fprintln(os.Stderr, "用法: nekopost admin-key revoke <前缀>")
			return 2
		}
		if err := st.RevokeAdminKey(ctx, args[1], time.Now()); errors.Is(err, store.ErrNotFound) {
			fmt.Fprintln(os.Stderr, "没有找到该前缀的有效密钥")
			return 1
		} else if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		fmt.Println("已吊销")
		return 0
	}
	fmt.Fprintf(os.Stderr, "未知的 admin-key 子命令 %q\n", args[0])
	return 2
}

func cmdCheck(cfg *config.Config, args []string) int {
	fs := flag.NewFlagSet("check", flag.ContinueOnError)
	sendTest := fs.Bool("send-test", false, "向投递目标发送一条测试消息")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	ok := true
	report := func(good bool, label, detail string) {
		mark := "✓"
		if !good {
			mark = "✗"
			ok = false
		}
		fmt.Printf("%s %s %s\n", mark, label, detail)
	}
	if err := cfg.ValidateServe(); err != nil {
		report(false, "配置", "\n"+err.Error())
	} else {
		report(true, "配置", fmt.Sprintf("（TLS=%s，域名=%s，管理员 %d 人，时区 %s）", cfg.TLSMode, cfg.Domain, len(cfg.AdminIDs), cfg.TimezoneName))
	}

	if err := dropPrivileges(cfg.DataDir); err != nil {
		report(false, "权限", err.Error())
	}
	if st, err := store.Open(cfg.DBPath()); err != nil {
		report(false, "数据库", err.Error())
	} else {
		srcs, _ := st.ListSources(context.Background())
		enabled := 0
		for _, s := range srcs {
			if s.Enabled {
				enabled++
			}
		}
		report(true, "数据库", fmt.Sprintf("%s（来源 %d 个，启用 %d 个）", cfg.DBPath(), len(srcs), enabled))
		if len(srcs) == 0 {
			fmt.Println("  提示：尚未配置来源，使用 nekopost source add <id> 新增")
		}
		st.Close()
	}

	if cfg.BotToken != "" {
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		defer cancel()
		tg := telegram.New(cfg.TelegramAPIBase, cfg.BotToken)
		me, err := tg.GetMe(ctx)
		if err != nil {
			report(false, "Telegram", err.Error())
		} else {
			report(true, "Telegram", "Bot @"+me.Username+"（ID "+strconv.FormatInt(me.ID, 10)+"）")
			if *sendTest {
				_, err := tg.SendMessage(ctx, telegram.SendMessageParams{
					ChatID: cfg.ChatID,
					Text:   "🧪 nekopost 测试消息\n如果你看到这条消息，说明 Bot Token 和投递目标配置正确。\n" + time.Now().In(cfg.Location).Format("2006-01-02 15:04:05"),
				})
				if err != nil {
					report(false, "测试消息", err.Error()+"（请确认已在 Telegram 中对 Bot 点击 Start）")
				} else {
					report(true, "测试消息", "已发送到聊天 "+strconv.FormatInt(cfg.ChatID, 10))
				}
			}
		}
	}
	if cfg.FilterEnabled() {
		// A made-up advertisement: no real message content leaves the server.
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		ds := &filter.DeepSeek{BaseURL: cfg.DeepSeekAPIBase, APIKey: cfg.DeepSeekAPIKey, Model: cfg.DeepSeekModel}
		start := time.Now()
		cat, err := ds.Classify(ctx, "某某商城", filter.Mask("【某某商城】双11狂欢，全场5折起，满300减50，点击 https://example.com/x 领券，回T退订"))
		if err != nil {
			report(false, "DeepSeek", err.Error()+"（短信过滤失败时会直接转发，不会丢消息）")
		} else {
			report(true, "DeepSeek", fmt.Sprintf("模型 %s，测试广告短信分类为「%s」，用时 %.1f 秒", cfg.DeepSeekModel, cat.Label(), time.Since(start).Seconds()))
		}
	} else {
		fmt.Println("- 短信过滤 未启用（未设置 NEKOPOST_DEEPSEEK_API_KEY）")
	}
	if !ok {
		return 1
	}
	return 0
}
