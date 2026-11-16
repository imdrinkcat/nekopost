# 升级到 0.9.0

2026-11-16 15:02:39 UTC 从 0.7.0 升级到 0.9.0，用户同意的原话是「开始升级」。

## 演练

先把 `/var/lib/nekopost` 复制到临时目录，用新二进制和正式配置执行 `NEKOPOST_DATA_DIR=<副本> nekopost check`（环境变量优先于配置文件），这一步会在副本上完成迁移。

- 数据库 v4 → v5，只新增 `devices`、`device_tokens`、`pairings`、`device_challenges` 四张表，以及 `messages.origin_device`、`web_sessions.reauth_at` 两列，不改动旧数据。
- 配置、数据库、Telegram、DeepSeek 全部通过；`keys status` 能解开数据密钥；`web audit verify` 通过。
- 演练副本随后删除。没有在副本上启动 `serve`：那会和线上 Bot 争抢 `getUpdates`。

## 升级

1. 备份到 `/root/nekopost-backup-before-0.9.0-20261116T150226Z`，内含 `data/`（运行中复制）和 `nekopost-0.7.0`。
2. `sudo ./deploy/install.sh`：先编译、换二进制，最后才重启。unit 文件与线上一致。
3. 迁移程序另外生成了 `/var/lib/nekopost/nekopost.db.pre-v5-20261116T150239Z.bak`，24 小时后由维护任务删除。

停机约 1 秒。之后 `nekopost version` 为 0.9.0，`healthcheck` 正常，`check` 全部通过，公网 `/healthz` 返回 200，内存约 13 MB。

## 生效情况

- 设备接口（配对、DPoP、`/v1/inbox*`、`/v1/sync`）按 `https://<域名>` 自动启用。
- FCM 没有配置，推送通道和「App 推送」健康项都不启动。配置方法见 [android.md](../android.md)。

## 回滚

0.7.0 打不开 v5 库，必须同时换回二进制和数据：

```bash
sudo systemctl stop nekopost
sudo install -m 0755 /root/nekopost-backup-before-0.9.0-20261116T150226Z/nekopost-0.7.0 /usr/local/bin/nekopost
sudo rsync -a --delete /root/nekopost-backup-before-0.9.0-20261116T150226Z/data/ /var/lib/nekopost/
sudo systemctl start nekopost && sudo nekopost check
```
