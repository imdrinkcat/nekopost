package main

import (
	"context"
	"crypto/rand"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"syscall"

	"nekopost/internal/config"
	"nekopost/internal/sealbox"
	"nekopost/internal/store"

	_ "modernc.org/sqlite"
)

func cmdKeys(cfg *config.Config, args []string) int {
	if len(args) == 0 {
		fmt.Fprint(os.Stderr, usage)
		return 2
	}
	switch args[0] {
	case "init":
		return keysInit()
	case "gen":
		return keysWriteRaw(os.Stdout)
	case "export":
		return keysExport(os.Stdout)
	case "status":
		return withStore(cfg, func(st *store.Store) int { return keysStatus(st) })
	case "rotate":
		if len(args) == 2 && args[1] == "--finish" {
			return keysFinish(cfg)
		}
		if len(args) != 1 {
			fmt.Fprintln(os.Stderr, "用法: nekopost keys rotate [--finish]")
			return 2
		}
		return keysRotate()
	case "shred-all":
		return withStore(cfg, func(st *store.Store) int { return keysShred(st) })
	case "reset":
		if len(args) != 2 || args[1] != "--i-understand" {
			fmt.Fprintln(os.Stderr, "用法: nekopost keys reset --i-understand")
			return 2
		}
		return keysReset(cfg)
	default:
		fmt.Fprintf(os.Stderr, "未知的 keys 子命令 %q\n", args[0])
		return 2
	}
}

func keysStatus(st *store.Store) int {
	cs, err := st.CryptoStatus(context.Background())
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	fmt.Printf("主密钥 %s\n数据密钥 %d 把\n", cs.Prefix, cs.DEKs)
	if cs.Oldest != "" {
		fmt.Printf("最早一天 %s\n", cs.Oldest)
	}
	if !cs.Since.IsZero() {
		fmt.Printf("主密钥已使用 %d 天\n", cs.AgeDays)
		if cs.AgeDays > 365 {
			fmt.Println("建议轮换（nekopost keys rotate）")
		}
	}
	if !cs.Ready {
		fmt.Println(cs.Detail)
		return 1
	}
	return 0
}

func keysWriteRaw(w io.Writer) int {
	if err := refuseTerminal(w); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if _, err := w.Write(b); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	return 0
}

func keysExport(w io.Writer) int {
	if err := refuseTerminal(w); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	mat, err := sealbox.LoadMaterial(sealbox.Location{AllowSystemd: os.Geteuid() == 0})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if _, err := w.Write(sealbox.FormatKeys(mat.Current, nil)); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	return 0
}

func keysInit() int {
	const dest = "/etc/credstore.encrypted/nekopost-kek"
	if _, err := os.Stat(dest); err == nil {
		fmt.Fprintln(os.Stderr, "主密钥已存在。若要更换，请使用 nekopost keys rotate")
		return 1
	}
	if err := os.MkdirAll("/etc/credstore.encrypted", 0o700); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if err := sealbox.EncryptSystemd(dest, b); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	fmt.Println("已生成主密钥。请离线备份，并与数据库备份分开存放。")
	return 0
}

func keysRotate() int {
	mat, err := sealbox.LoadMaterial(sealbox.Location{AllowSystemd: os.Geteuid() == 0})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if len(mat.Previous) == 32 {
		fmt.Fprintln(os.Stderr, "上一轮轮换尚未完成。重启服务后再执行 keys rotate --finish")
		return 1
	}
	next := make([]byte, 32)
	if _, err := rand.Read(next); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if err := sealbox.SaveMaterial(next, mat.Current); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	fmt.Println("已写入新的主密钥。请重启服务，确认正常后再执行 nekopost keys rotate --finish")
	return 0
}

func keysFinish(cfg *config.Config) int {
	mat, err := sealbox.LoadMaterial(sealbox.Location{AllowSystemd: os.Geteuid() == 0})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if len(mat.Previous) != 32 {
		fmt.Println("当前只有一把主密钥，无需完成轮换")
		return 0
	}
	keys, err := sealbox.Derive(mat.Current)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	db, err := sql.Open("sqlite", "file:"+cfg.DBPath()+"?mode=ro")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	defer db.Close()
	rows, err := db.Query("SELECT DISTINCT kek_id FROM data_keys")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	defer rows.Close()
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		if id != keys.KEKIDHex() {
			fmt.Fprintln(os.Stderr, "数据密钥尚未用新主密钥包裹完成。请先重启服务")
			return 1
		}
	}
	if err := rows.Err(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	var auditKey string
	err = db.QueryRow("SELECT v FROM kv WHERE k = 'audit_key_id'").Scan(&auditKey)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if err == nil && auditKey != keys.KEKIDHex() {
		fmt.Fprintln(os.Stderr, "审计链尚未用新主密钥签名。请先重启服务")
		return 1
	}
	if err := sealbox.SaveMaterial(mat.Current, nil); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	fmt.Println("已去掉旧主密钥")
	return 0
}

func keysReset(cfg *config.Config) int {
	mat, err := sealbox.LoadMaterial(sealbox.Location{AllowSystemd: os.Geteuid() == 0})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	st, err := store.Open(cfg.DBPath(), cfg.StoreOptions(mat.Current, mat.Previous))
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	err = st.ShredAll(context.Background())
	st.Close()
	restoreDataOwner(cfg.DataDir)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if err := sealbox.SaveMaterial(b, nil); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	fmt.Println("已销毁正文并生成新的主密钥。请重启服务，并重新离线备份主密钥。")
	return 0
}

func restoreDataOwner(dataDir string) {
	if os.Geteuid() != 0 {
		return
	}
	fi, err := os.Stat(dataDir)
	if err != nil {
		return
	}
	stat, ok := fi.Sys().(*syscall.Stat_t)
	if !ok || stat.Uid == 0 {
		return
	}
	for _, name := range []string{"nekopost.db", "nekopost.db-wal", "nekopost.db-shm"} {
		_ = os.Chown(filepath.Join(dataDir, name), int(stat.Uid), int(stat.Gid))
	}
}

func keysShred(st *store.Store) int {
	if err := st.ShredAll(context.Background()); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	fmt.Println("已销毁全部正文和数据密钥，元数据仍保留")
	return 0
}

func refuseTerminal(w io.Writer) error {
	f, ok := w.(*os.File)
	if !ok {
		return nil
	}
	fi, err := f.Stat()
	if err != nil {
		return err
	}
	if fi.Mode()&os.ModeCharDevice != 0 {
		return fmt.Errorf("拒绝把主密钥写到终端。请重定向到文件或密码管理器")
	}
	return nil
}
