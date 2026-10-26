# 对外接口说明（API v1）

基础地址：`https://osaka.drinkcat.com`

所有接口都返回 `application/json; charset=utf-8`，并带有以下响应头：

- `X-Request-Id`：本次请求的 ID，排查问题时引用。
- `Cache-Control: no-store`
- `X-Content-Type-Options: nosniff`
- `Strict-Transport-Security`：仅在 HTTPS 模式下。

端口 80 只用于 Let's Encrypt 证书验证，其他请求一律重定向到 HTTPS。

## 1. 认证

| 凭证 | 前缀 | 用途 | 生成方式 |
|---|---|---|---|
| 来源凭证 | `mgs_` | 提交消息、查询**本来源**的消息状态 | `msggw source add <id>` / `msggw source rotate <id>` |
| 管理密钥 | `mga_` | 只读管理接口 `/v1/admin/*` | `msggw admin-key create <名称>` |

- 两种凭证都放在请求头里：`Authorization: Bearer <凭证>`。凭证不能放在 URL 中。
- 两类凭证互不通用：来源凭证访问管理接口会得到 401，管理密钥提交消息也会得到 401。
- 服务器只保存凭证的 SHA-256 摘要，凭证明文只在生成时显示一次。
- 缺失、无效或已吊销的凭证一律返回 `401 unauthorized`。被停用的来源返回 `403 source_disabled`。
- 消息的来源**只由凭证决定**。请求体中的 `source` / `source_id` 字段会被忽略，并在响应中给出警告。

## 2. 限流与保护

| 机制 | 规则 | 响应 |
|---|---|---|
| 按来源限流 | 令牌桶，平均 `MSGGW_SOURCE_RATE_PER_MIN` 条/分钟（默认 60），突发容量为 max(10, 速率/3)（默认 20）。超出的请求也计入，包括校验失败的请求 | `429 rate_limited` + `Retry-After` |
| 认证失败封禁 | 同一 IP 在 10 分钟内认证失败 20 次，封禁 15 分钟（来源接口和管理接口共用） | `429 too_many_auth_failures` + `Retry-After` |
| 并发上限 | 同时处理的请求最多 32 个 | `503 busy` + `Retry-After: 5` |
| 请求体大小 | 原始请求体最大 256 KB | `413 payload_too_large` |

客户端 IP 取自 TCP 连接本身，不信任 `X-Forwarded-For` 等转发头。

## 3. 提交消息 `POST /v1/messages`

### 3.1 JSON 请求

`Content-Type: application/json`

```json
{
  "title": "招商银行",
  "body": "验证码 123456，5 分钟内有效。",
  "type": "sms",
  "priority": "normal",
  "occurred_at": "2026-10-25T10:20:00+09:00",
  "idempotency_key": "sms-20261025-102000-01",
  "meta": {"phone": "主卡", "count": 1}
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | string | 二选一 | 单行文本，最多 256 字符 |
| `body` | string | 二选一 | 正文，支持多行，最多 `MSGGW_MAX_BODY_CHARS` 字符（默认 12000） |
| `type` | string | 否 | 消息类型，默认 `text`。会先转小写，再匹配 `^[a-z][a-z0-9_.-]{0,31}$` |
| `priority` | string | 否 | `low` / `normal`（默认）/ `high` |
| `occurred_at` | string 或 number | 否 | 设备上的事件时间，仅作参考，见[消息模型](message-model.md) |
| `idempotency_key` | string | 否 | 幂等键，最多 128 字符；也可以用请求头 `Idempotency-Key` 提供 |
| `meta` | object | 否 | 附加信息，最多 16 项。键需匹配 `^[A-Za-z0-9_.-]{1,32}$`；值可以是字符串、数字或布尔值，最多 256 字符 |

`title` 和 `body` 至少要有一个非空。完整的字段规则见 [message-model.md](message-model.md)。

未知字段会被忽略，并在 `warnings` 中列出（保证向前兼容）；类型错误的字段返回 422。

### 3.2 纯文本请求

`Content-Type: text/plain`：整个请求体就是 `body`。可选的查询参数有 `title`、`type`、`priority`。

```bash
echo "备份完成" | curl -sS -X POST "https://osaka.drinkcat.com/v1/messages?title=Backup&type=task" \
  -H "Authorization: Bearer $MSGGW_TOKEN" -H "Content-Type: text/plain" --data-binary @-
```

其他 `Content-Type` 返回 `415 unsupported_media_type`。

### 3.3 查询参数 `wait`

`?wait=N`（单位为秒，可以带小数，大于 25 时按 25 处理）：服务器会最多等待 N 秒，直到消息进入终态（送达 / 失败 / 不确定）再返回，这样客户端能直接看到 Telegram 的投递结果。不带 `wait` 时立即返回，状态通常是 `queued`。非数字或负数返回 `400 invalid_parameter`。

### 3.4 幂等与去重

- 提供了幂等键时：同一来源下，相同的幂等键在元数据保留期内（默认 30 天）只转发一次。如果请求头和请求体中都提供了幂等键且两者不同，返回 422。
- 没有幂等键时：同一来源在 `MSGGW_DEDUPE_WINDOW`（默认 60 秒，设为 `0s` 可关闭）内提交了相同内容，视为重复。内容由类型、标题、正文共同决定。
- 重复提交返回 **HTTP 200**，`duplicate: true`，以及原消息的 `id` 和当前状态，不会再次转发。

### 3.5 成功响应

新消息返回 **202 Accepted**，重复提交返回 **200 OK**：

```json
{
  "ok": true,
  "message": "已送达 Telegram",
  "id": "m_01JB7Q4ZK3M8X2N5R6T7V8W9YA",
  "status": "delivered",
  "status_text": "已送达 Telegram",
  "delivered": true,
  "duplicate": false,
  "received_at": "2026-10-25T10:20:01+09:00",
  "warnings": ["已忽略未知字段 tittle"]
}
```

- `ok: true` 只表示**服务器已接受**，不代表已送达。是否送达请看 `delivered`（只有 `status == "delivered"` 时为 `true`）。
- `message` 是给人看的一句话总结，简单客户端（例如快捷指令）直接显示它即可。重复提交时会附加“（重复提交，未再次转发）”。
- 时间使用配置的时区（默认 `Asia/Tokyo`），格式为 RFC 3339。
- `warnings` 仅在有警告时出现。

`status` 的取值：`queued`、`sending`、`retrying`、`delivered`、`partial`、`failed`、`uncertain`、`not_routed`；启用[短信过滤](sms-filter.md)时还有 `classifying`（正在识别）和 `filtered`（已过滤，未转发）。含义见 [message-model.md](message-model.md#状态)。

## 4. 查询消息状态 `GET /v1/messages/{id}`

需要来源凭证，并且只能查询本来源提交的消息，否则返回 404。响应中不包含任何正文。

```json
{
  "ok": true,
  "id": "m_01JB7Q4ZK3M8X2N5R6T7V8W9YA",
  "source": "iphone",
  "type": "sms",
  "priority": "normal",
  "status": "delivered",
  "status_text": "已送达 Telegram",
  "delivered": true,
  "category": "finance",
  "category_text": "银行/支付",
  "received_at": "2026-10-25T10:20:01+09:00",
  "occurred_at": "2026-10-25T10:20:00+09:00",
  "finished_at": "2026-10-25T10:20:02+09:00",
  "duplicate_submissions": 0,
  "deliveries": [
    {
      "channel": "telegram",
      "target": "default",
      "state": "delivered",
      "attempts": 1,
      "parts_total": 1,
      "parts_sent": 1,
      "uncertain": false,
      "last_error": "",
      "last_attempt_at": "2026-10-25T10:20:01+09:00",
      "next_attempt_at": null,
      "delivered_at": "2026-10-25T10:20:02+09:00"
    }
  ]
}
```

- `state` 的取值：`pending`、`sending`、`retrying`、`delivered`、`failed`、`uncertain`；启用短信过滤时还有 `held`、`filtered`。
- `category` / `category_text`：短信过滤的分类结果，未分类时为 `null`。
- `uncertain: true` 表示某次尝试的结果未知，这条消息在 Telegram 里可能出现了两次。
- 未设置的时间字段为 `null`。
- 元数据超过保留期后会被删除，此时返回 404。

## 5. 只读管理接口 `/v1/admin/*`

需要管理密钥（`mga_`），只支持 `GET`，用其他方法会返回 405。

### `GET /v1/admin/status`

```json
{
  "ok": true,
  "version": "0.1.0",
  "started_at": "2026-10-25T09:00:00+09:00",
  "start_count": 3,
  "report": {
    "generated_at": "2026-10-25T10:30:00.123456789+09:00",
    "overall": "ok",
    "overall_text": "正常运行",
    "components": [
      {"key": "gateway", "name": "消息网关服务", "level": "ok", "summary": "运行中，已运行 1 小时 30 分钟"},
      {"key": "ingest", "name": "消息接入", "level": "ok", "summary": "接口监听正常", "details": ["证书有效期至 2027-01-20", "最近一次有效提交：10 分钟前"]},
      {"key": "delivery", "name": "Telegram 转发", "level": "ok", "summary": "最近一次投递成功（10 分钟前）", "details": ["最近成功：10 分钟前", "待处理 0 条"]},
      {"key": "bot", "name": "管理 Bot", "level": "ok", "summary": "长轮询正常（最近 12 秒前）"},
      {"key": "sources", "name": "消息来源", "level": "ok", "summary": "1 个已启用 / 共 1 个"},
      {"key": "server", "name": "服务器资源", "level": "ok", "summary": "资源充足"}
    ]
  },
  "server": {
    "cpu_count": 1,
    "host_uptime_seconds": 302400,
    "cpu_percent": 3.2,
    "load": [0.05, 0.08, 0.06],
    "memory_total_bytes": 1001390080,
    "memory_available_bytes": 443000000,
    "disk_total_bytes": 24000000000,
    "disk_available_bytes": 16500000000,
    "service_rss_bytes": 21000000
  }
}
```

- `level` 的取值：`ok`、`unknown`、`degraded`、`config_error`、`down`。
- `server` 中读取失败的指标会直接省略，而不是填 0。
- 所有时间字段都使用配置的时区（`MSGGW_TIMEZONE`）。

### `GET /v1/admin/sources`

```json
{
  "ok": true,
  "sources": [
    {
      "id": "iphone", "name": "iPhone", "icon": "📱", "enabled": true,
      "created_at": "2026-10-25T08:00:00+09:00",
      "last_seen_at": "2026-10-25T10:20:01+09:00",
      "last_message_at": "2026-10-25T10:20:01+09:00",
      "last_message_status": "delivered",
      "revoked_token_uses": 0,
      "last_revoked_use_at": null,
      "today": {"received": 12, "delivered": 12, "failed": 0, "in_progress": 0}
    }
  ]
}
```

`today.failed` 包含 `failed`、`uncertain` 和 `partial` 三种状态。没有消息时不返回 `last_message_status` 字段。

### `GET /v1/admin/stats?range=today|24h|7d|start`

`range` 默认为 `today`（按配置时区的当天 0 点起算）。`start` 表示本次服务启动以来。其他取值返回 `400 invalid_parameter`。

```json
{
  "ok": true,
  "range": "today",
  "from": "2026-10-25T00:00:00+09:00",
  "to": "2026-10-25T10:30:01+09:00",
  "totals": {"received": 12, "delivered": 11, "failed": 0, "uncertain": 0, "partial": 0, "not_routed": 0, "in_progress": 1, "duplicates_ignored": 2},
  "by_source": [
    {"source": "iphone", "counts": {"received": 12, "delivered": 11, "failed": 0, "uncertain": 0, "partial": 0, "not_routed": 0, "in_progress": 1, "duplicates_ignored": 2}, "last_received_at": "2026-10-25T10:20:01+09:00"}
  ],
  "last_message_at": "2026-10-25T10:20:01+09:00",
  "queue_in_progress": 1,
  "note": ""
}
```

- 每条消息只计一次，重复提交单独计入 `duplicates_ignored`。
- 当查询区间超出元数据保留期时，`note` 会说明“仅统计保留期内的记录”。

## 6. 存活探针 `GET|HEAD /healthz`

无需认证。只返回 `{"status":"ok"}`（200）或 `{"status":"unavailable"}`（503）。

判断条件只有两个：数据库可用，且投递 Worker 在 5 分钟内有心跳。**Telegram 是否可用不影响这个结果**，Telegram 的状态请查 `/v1/admin/status` 或 Bot。

## 7. 统一错误格式

所有错误都使用同一种结构：

```json
{
  "ok": false,
  "message": "提交失败：请求字段校验失败（priority 必须是 low、normal 或 high）",
  "error": {
    "code": "validation_failed",
    "message": "请求字段校验失败",
    "details": [{"field": "priority", "problem": "必须是 low、normal 或 high"}],
    "request_id": "5b3dc1865a18513a"
  }
}
```

- 顶层的 `message` 是给人看的一句话（如果有 `details`，会附上第一条）。`POST /v1/messages` 的错误以「提交失败：」开头，其他接口以「请求失败：」开头。程序判断请使用 `error.code`。
- `details` 只在字段校验失败时出现。
- 401 响应会附带 `WWW-Authenticate: Bearer realm="msggw"`；429 和 503 响应会附带 `Retry-After`。

| HTTP | `code` | 含义 |
|---|---|---|
| 400 | `invalid_json` | 请求体不是 JSON 对象 |
| 400 | `invalid_body` | 读取请求体失败 |
| 400 | `invalid_parameter` | 查询参数无效（`wait`、`range`） |
| 401 | `unauthorized` | 凭证缺失、无效或已吊销 |
| 403 | `source_disabled` | 来源已被停用 |
| 404 | `not_found` | 路径不存在，或消息不存在 / 不属于该来源 |
| 405 | `method_not_allowed` | HTTP 方法不支持（响应附 `Allow` 头） |
| 413 | `payload_too_large` | 请求体超过 256 KB |
| 415 | `unsupported_media_type` | `Content-Type` 不是 JSON 或纯文本 |
| 422 | `validation_failed` | 字段校验失败，见 `details` |
| 429 | `rate_limited` | 该来源提交过于频繁 |
| 429 | `too_many_auth_failures` | 此 IP 认证失败次数过多，暂时封禁 |
| 500 | `internal_error` | 服务器内部错误 |
| 503 | `busy` | 并发请求过多 |

## 8. 版本策略

- 所有业务接口都位于 `/v1` 下。在 v1 内只做**向后兼容的增量修改**：可以新增可选的请求字段、新增响应字段、新增 `type` 或状态值等。
- 客户端应忽略不认识的响应字段。服务端会忽略不认识的请求字段并给出 `warnings`，因此新客户端发给旧服务器也不会失败。
- 需要做不兼容修改时，会新增 `/v2`，`/v1` 继续保留。
- `/healthz` 不属于版本化接口。

## 9. curl 示例

```bash
TOKEN=mgs_xxxxxxxx

# 提交并等待投递结果
curl -sS -X POST "https://osaka.drinkcat.com/v1/messages?wait=8" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"测试","body":"你好 👋\n日本語もOK","type":"notification"}'

# 带幂等键（重复执行只会转发一次）
curl -sS -X POST https://osaka.drinkcat.com/v1/messages \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: job-42-run-1" \
  -d '{"body":"任务 42 完成","type":"task"}'

# 查询状态
curl -sS https://osaka.drinkcat.com/v1/messages/m_01JB7Q4ZK3M8X2N5R6T7V8W9YA \
  -H "Authorization: Bearer $TOKEN"

# 管理接口
curl -sS "https://osaka.drinkcat.com/v1/admin/stats?range=7d" -H "Authorization: Bearer mga_xxxxxxxx"

# 存活探针
curl -sS https://osaka.drinkcat.com/healthz
```
