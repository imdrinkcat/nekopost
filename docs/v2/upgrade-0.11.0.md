# 升级到 0.11.0

0.11.0 有两个改动，**第一个会改变升级后的默认行为**，升级前要知道：

1. **自动删除默认关闭。** 以前正文默认 7 天（验证码 24 小时）后删除、消息记录 30 天后删除；现在这些都不会自动发生，消息和正文（加密保存）一直保留，只能手动删除。
2. **新增邮件通道。** 默认不启用，设置了 `NEKOPOST_SMTP_HOST` 才发邮件。见 [../email.md](../email.md)。

这份文档没有在生产上演练过；升级要先征得用户同意。

## 对现有部署的影响

- **不需要数据库迁移。** 没有表结构变化，`user_version` 不变。回滚只需要换回旧二进制，不需要恢复数据目录。
- **启动时会改一次正文的到期时间。** 自动删除关闭时，所有仍在库里的正文被设成「永不过期」（`bodies.expires_at` = 9999-01-01）。日志里有一行 `body retention mode applied`。已经被删掉的正文不会回来。
- **回滚到 0.10.2 之后**，这些正文的到期时间是 9999 年，旧版本不会删除它们，也不会出错。要恢复旧行为，在 0.11.0 上设置 `NEKOPOST_AUTO_DELETE=true` 再启动一次：之前保留下来的正文会从**那一刻**起重新计时（普通 7 天、验证码 24 小时），不会立刻被删；但超过 `NEKOPOST_RETENTION_DAYS` 的消息记录会在一小时内被维护任务删掉。
- **如果环境文件里已经写了** `NEKOPOST_RETENTION_DAYS`、`NEKOPOST_BODY_RETENTION_DAYS`、`NEKOPOST_CODE_RETENTION_HOURS` 或 `NEKOPOST_FILTER_KEEP_DAYS`，它们在自动删除关闭时被忽略，启动日志会警告。想继续自动删除，加上 `NEKOPOST_AUTO_DELETE=true`。
- **数据会一直增长。** 以约 3500 条 / 7 天估算，一年约 18 万条，每条消息的元数据加密正文通常几百字节到几 KB，仍是 SQLite 的小数据量，但磁盘和备份会随时间变大。
- 内容指纹（去重用的 HMAC）不受开关影响，仍然在去重窗口之后清空。

## 行为变化清单

| 位置 | 变化 |
|---|---|
| Bot「设置」 | 「隐私」一行说明自动删除是否开启 |
| 网页面板「设置」 | 关闭时只显示「自动删除：已关闭」 |
| `GET /v1/admin/settings` | `retention_days` 在关闭时为 `null`，新增 `auto_delete` |
| `GET /v1/inbox/{id}`、`/v1/sync` | 关闭时 `body_expires_at` 为 `9999-01-01…`，不是 `null`（`null` 表示正文已不存在）。App 0.3.2 及以前会把它显示成「正文将于 9999-01-01 删除」；之后的 App 版本隐藏这个日期 |
| 统计页 | 关闭时不再提示「仅统计保留期内的记录」 |
| 投递 | 新增 `email` 通道（辅助，最长重试 1 小时），失败写入「最近错误」 |

## 升级

和以前一样：`sudo ./deploy/install.sh` 先编译、再换二进制、最后重启。升级前备份 `/var/lib/nekopost`、留下当时的 `/usr/local/bin/nekopost`。

要同时开邮件时，先在服务器上准备好凭证和环境变量（见 [../email.md](../email.md)），再重启，然后执行 `sudo nekopost check --send-test` 看测试邮件。

## 回滚

```bash
sudo systemctl stop nekopost
sudo install -m 0755 <备份目录>/nekopost-0.10.2 /usr/local/bin/nekopost
sudo systemctl start nekopost && sudo nekopost check
```

不需要恢复数据目录。已经入队、尚未发出的邮件投递行，旧版本不认识 `email` 通道，不会处理它们，它们会一直停在「待投递」；邮件是辅助通道，这不影响消息的整体状态。

## 验证记录

- `go build ./...`、`go vet ./...`、`go test -race ./...`：除 `internal/sysinfo`（读 Linux 的 `/proc`，在 macOS 上必然失败）外全部通过。
- 邮件通道用进程内的假 SMTP 服务器测试：STARTTLS 和隐式 TLS、PLAIN 和 LOGIN 认证、服务器不支持 STARTTLS 时拒绝明文、证书校验失败、认证失败、收件人被拒、临时失败、确认前断线（结果不确定）、头部注入、三档内容级别。
- 没有用真实的 SMTP 服务商测过。上线后先 `nekopost check --send-test`。
- Android 的改动（`keptForever`）只有单元测试，没有在本机构建（本机没有 Android SDK），由 CI 验证。
