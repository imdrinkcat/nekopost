# 服务监控与故障排查

## 1. 监控手段一览

| 手段 | 用途 | 依赖 Telegram？ |
|---|---|---|
| Telegram Bot「🏠 系统总览」 | 日常查看各组件状态 | 是 |
| `GET https://osaka.drinkcat.com/healthz` | 外部存活探测（UptimeRobot 等） | 否 |
| `GET /v1/admin/status`（管理密钥） | 完整状态 JSON，供脚本 / 监控使用 | 否 |
| `sudo nekopost check` | 在服务器上自检配置、数据库、Telegram | 否 |
| `systemctl status nekopost` / `journalctl -u nekopost` | 进程状态与日志 | 否 |
| systemd watchdog | 进程卡死时自动重启 | 否 |

> **注意**：当 Telegram 本身不可达时，Bot 无法回复你，也就看不到「Telegram 不可达」这个状态。因此建议额外配置一个不依赖 Telegram 的外部探测（例如免费的 UptimeRobot 监控 `/healthz`），或在需要时 SSH 登录查看。

### `/healthz` 的含义

返回 `200 {"status":"ok"}` 的条件：数据库可访问 **且** 投递循环在 5 分钟内有心跳。它只说明「网关进程健康」，**不**代表 Telegram 可达——Telegram 故障时消息会排队重试，不需要重启网关。此接口无需认证，也不返回任何其他信息。

### 组件状态

Bot 与 `/v1/admin/status` 使用同一套状态评估（详见 [architecture.md](architecture.md)）：

| 组件 | 🟢 正常的依据 | 常见异常 |
|---|---|---|
| 消息网关服务 | 数据库可访问，投递循环有心跳 | 🔴 数据库不可用 / 队列处理停止 |
| 消息接入 | 端口在监听，证书有效 | ⚪ 证书尚未取得；🟡 证书将在 7 天内过期；🔴 证书过期 |
| Telegram 转发 | 最近一次投递成功 | 🟡 正在重试 / 积压超过 5 分钟；🔴 连续网络失败；🟠 Token / 聊天 / 被屏蔽 |
| 管理 Bot | 3 分钟内有成功的长轮询 | 🔴 无法连接；🟠 Token 无效 / 多实例冲突 |
| 消息来源 | 至少一个已启用 | 🟡 24 小时内有人使用已吊销凭证；🟠 没有任何来源 |
| 服务器资源 | 内存 ≥10%、磁盘 ≥10% 可用 | 🟡 低于 10%；🔴 低于 5%（内存）/ 3%（磁盘） |

---

## 2. 日志

```bash
sudo journalctl -u nekopost -f                     # 实时
sudo journalctl -u nekopost -p warning --since today   # 今天的警告和错误
sudo journalctl -u nekopost | grep 'msg=delivery'      # 投递记录
```

日志示例：

```
level=INFO msg="nekopost started" version=0.1.0 tls=autocert domain=osaka.drinkcat.com start_count=3
level=INFO msg=http component=http method=POST path=/v1/messages status=202 ms=412 ip=203.0.113.5 source=iphone request_id=...
level=INFO msg=delivery component=delivery message=m_01K... source=iphone channel=telegram state=delivered status=delivered attempt=1 parts=1 err=""
level=WARN msg="telegram getUpdates failed" component=bot err="网络错误（...）"
```

日志**从不包含**：消息标题 / 正文 / meta、任何凭证或 Bot Token、URL 查询参数、请求头。可以用请求 ID（响应头 `X-Request-Id`，以及错误响应中的 `request_id`）在日志中定位某个请求。

journald 的日志总大小由系统配置限制（`/etc/systemd/journald.conf` 中的 `SystemMaxUse`）。

---

## 3. 故障排查

### 服务无法启动

```bash
sudo systemctl status nekopost
sudo journalctl -u nekopost -n 50 --no-pager
sudo nekopost check
```

| 日志 | 处理 |
|---|---|
| `配置不完整，无法启动 ... 未设置 NEKOPOST_TELEGRAM_BOT_TOKEN` | 编辑 `/etc/nekopost/nekopost.env` |
| `... 格式不正确` | Token 应形如 `123456:ABC...`，不要带引号以外的空格 |
| `监听端口失败: ... address already in use` | 80/443 被其他程序（nginx、apache）占用：`sudo ss -ltnp 'sport = :443'` |
| `打开数据库失败` | 检查 `/var/lib/nekopost` 属主是否为 `nekopost`：`sudo chown -R nekopost:nekopost /var/lib/nekopost` |

### 证书申请失败（状态显示「HTTPS 证书尚未取得」）

1. 域名是否解析到本机：`dig +short osaka.drinkcat.com` 应返回本机 IP。
2. 80 端口是否对外开放：在**另一台机器**上执行 `curl -I http://osaka.drinkcat.com/` 应返回 `302`（跳转到 HTTPS）。检查 ufw 和云服务商安全组。
3. 日志中搜索 `certificate`：`sudo journalctl -u nekopost | grep -i cert`。
4. Let's Encrypt 对同一域名有频率限制（每周 5 张相同证书），频繁删除 `/var/lib/nekopost/acme` 可能触发限制。

服务会自动重试申请（1 分钟、2 分钟、4 分钟……最长 64 分钟间隔），修复网络后无需重启。

### 消息没有到达 Telegram

1. 快捷指令显示什么？
   - `提交失败：...` → 请求没有被接受，按提示修正（见 [iphone-shortcuts.md](iphone-shortcuts.md)）。
   - `已过滤（不需要推送的短信），未转发` → 短信过滤判定为不需要推送（广告、漫游提醒等），到 Bot「🗑 已过滤」中找回（见 [sms-filter.md](sms-filter.md)）。
   - `已接收…` / `正在重试` → 服务器已收下，问题在投递环节，继续往下看。
2. Bot「🤖 机器人状态」→「消息转发」和「最近投递」。
3. 常见投递错误：

| 错误 | 原因与处理 |
|---|---|
| `找不到投递目标聊天（请先对 Bot 点击 Start）` | 在 Telegram 中打开 Bot 点「开始」；或检查 `NEKOPOST_TELEGRAM_CHAT_ID` |
| `无权向该聊天发送（是否已对 Bot 点击 Start？）` | 你屏蔽了 Bot，或从未点过开始 |
| `Bot Token 无效` | Token 被撤销或填错，到 BotFather 重新获取 |
| `无法连接 Telegram` | 服务器网络问题：`curl -sI https://api.telegram.org` |
| `Telegram 限流` | 短时间消息过多，会按 Telegram 要求的时间自动重试 |

配置类错误修复后，排队中的消息会在下一次重试时自动送达（最长重试 24 小时），无需重新发送。

4. 单条消息的投递详情（需要该来源的凭证）：

```bash
curl -s https://osaka.drinkcat.com/v1/messages/m_xxx -H "Authorization: Bearer mgs_..."
```

### 短信过滤异常

- Bot「🏠 系统总览」显示「🟡 短信过滤：最近调用失败（…）」：DeepSeek 调用失败期间短信会**直接转发**，不会丢失，只是广告也会推送。
- 常见原因：`DeepSeek API Key 无效`（检查 `NEKOPOST_DEEPSEEK_API_KEY`）、`DeepSeek 账户余额不足`（到 DeepSeek 开放平台充值）、`无法连接 DeepSeek` / `DeepSeek 响应超时`（`curl -sI https://api.deepseek.com`）。
- `sudo nekopost check` 会用一条虚构广告测试 DeepSeek。
- 日志：`journalctl -u nekopost | grep component=filter`，每条短信一行，记录类别（`category`）、判定方式（`by=rule|ai|error|skipped`）、是否过滤和耗时，不含正文。

### Bot 不响应菜单

- 确认你的数字 ID 在 `NEKOPOST_TELEGRAM_ADMIN_IDS` 中（未授权时 Bot 会回复你的 ID）。
- 确认是在**私聊**中，Bot 不响应群组。
- 日志中出现 `冲突：另一个程序正在使用同一个 Bot Token 接收消息`：同一 Token 只能有一个程序长轮询。停掉其他使用该 Token 的程序（包括旧服务器上的实例）。
- 菜单消息太旧无法编辑时，Bot 会发送一条新消息。

### Telegram 中出现重复消息

本系统采用「至少一次」投递：如果请求已经发给 Telegram，但在收到确认前网络中断（或服务恰好在此时重启），系统无法知道 Telegram 是否已收到，会选择重发。这类投递在记录中标记为「不确定」。偶尔出现重复是预期行为；频繁出现说明服务器到 Telegram 的网络不稳定。

### 内存或磁盘告警

```bash
free -m
df -h /
sudo du -sh /var/lib/nekopost /var/log/journal
systemctl show nekopost -p MemoryCurrent
```

网关自身数据量很小（每条消息元数据约数百字节，保留 30 天）。磁盘告警通常来自其他程序或系统日志。

---

## 4. 定期维护

无需人工维护。服务每小时自动：

- 删除超过保留期（默认 30 天）的元数据；
- 清理已完成消息残留的正文（双重保险，正常情况下投递结束时已删除）；
- 截断 SQLite WAL 文件，避免已删除的数据残留在磁盘上；
- 清理内存中的限流记录。

证书在到期前 30 天自动续期。
