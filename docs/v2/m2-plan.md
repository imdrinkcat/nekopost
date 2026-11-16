# M2 实现计划：服务端 0.6.0「加密底座」

> 依据：`docs/v2/design.md` §2.1–2.2、§2.5、§3.1（FailGuard）、§4.4（预览打码）、§4.6、§5、§8.4（late / otp_hold）、§12.2、§12.5、§13 的 M2 行，附录 A。
> 交接：`docs/v2/HANDOFF.md` §5.1。
> 本文件只定 M2 怎么做。确认前不改业务代码，不碰生产库，不重启服务。

## 0. 现状（对照代码）

- `user_version = 2`。正文在明文表 `payloads(title, body, meta)`。投递进入终态时立刻 `DELETE`（过滤消息除外，由 `PurgeFinishedPayloads` 按 `NEKOPOST_FILTER_KEEP_DAYS` 再留几天）。
- 去重 HMAC 密钥在 `kv` 的 `secret:dedupe`。`content_hash` 是这个密钥下的 HMAC。`idem_key` 以客户端原文存储，消息行活着就一直有效（元数据默认 30 天），和正文是否已删无关。
- `FailGuard` 先按 IP 查封禁，再验凭证。格式错误、未知凭证、已吊销凭证都计入同一个 IP 计数。
- 投递是单个 worker，`DueJobs` 不按通道过滤。Telegram 发送的返回值被丢掉，`deliveries` 没有 `message_id`。
- 统计是对 `messages` 的实时 `COUNT` / `SUM`，没有单独计数器。
- `VERSION` 仍是 `0.5.0`（M1 已上线，没有单独的 0.5.1）。M2 改为 **0.6.0**。
- 加密只用标准库。不新增 Go 依赖，不跑 `go mod tidy`。

## 1. 这一里程碑做和不做

做：

- v3 迁移（迁移前备份、加密现存正文、去重密钥迁出、回填 `seq` / `late`）。
- `internal/sealbox`、KEK 加载、`keys` 子命令、启动时拒绝错误或缺失的 KEK。
- 新消息加密写入 `bodies`；投递、过滤、Bot 已过滤页改为解密读取。
- 正文按保留期删除（默认 7 天，验证码类 24 小时），并加密粉碎对应 DEK。
- 扩充本地验证码规则和预览打码；写入 `has_code`、`late`。
- FailGuard 新语义；按通道的 worker 循环；`aux` 不参与聚合。
- `internal/events`：分配 `seq`、写墓碑、`ChangesSince` 查询。
- `internal/service`：收件箱读取、搜索、保留期，供 CLI 和 Bot 使用。
- `inbox` CLI、`crypto` 健康组件。
- `install.sh`、systemd unit、Dockerfile、compose、CI、文档。

不做（后面的里程碑，避免把 M3–M5 卷进来）：

- 不提供 `/v1/inbox`、`/v1/sync`、`/v1/events`。这些要等网页会话和设备凭证。`events` 只留下查询，HTTP 以后接上。
- 不做 OAuth、会话、审计链、设备、DPoP、FCM。
- **V1 HTTP 的路径、状态码和 JSON 字段保持不变。** `late`、`has_code`、`body_state` 只入库，不出现在 `/v1/messages/{id}`。
- Telegram 渲染不变（仍是全文，不打码）。预览打码只用于 `inbox` CLI 和以后的 `InboxItem`。
- 推送上的「删除」按钮在 M2 合入之后单独做。为了生产只迁移一次，v3 预先加上 Telegram `message_id` 列（见 §2）。

## 2. v3 迁移

框架改为：

```go
type migration struct {
    sql string
    fn  func(ctx context.Context, tx *sql.Tx, env MigrateEnv) error
}
```

每个版本仍是一个事务。`VACUUM INTO`、`wal_checkpoint`、`VACUUM` 不能放进事务，放在提交前或提交后。

### 2.1 动手顺序

1. 加载 KEK。缺失或长度不是 32 字节：拒绝启动，提示 `未找到主密钥：请运行 sudo nekopost keys init（见 docs/encryption.md）`。此时还没有改数据库。
2. 有待执行迁移时，先 `VACUUM INTO '$DATA_DIR/nekopost.db.pre-v3-<UTC>.bak'`，模式 0600。这份备份含 V1 明文，维护任务在成功启动 24 小时后删除（`NEKOPOST_KEEP_MIGRATION_BACKUP=true` 时保留）。
3. 在单个事务里跑 v3 的 SQL 和 Go 步骤。
4. 提交后 `PRAGMA wal_checkpoint(TRUNCATE)`，再 `VACUUM`，清掉明文残留页。
5. 启动校验：算出 `kek_id`，和 `data_keys` 比对，并解开最新一把 DEK。不一致则拒绝启动，提示里只带 `kek_id` 前 4 字节的十六进制（这是设计里的公开标识，不是密钥）。

`MigrateEnv` 带上 KEK、正文保留天数、验证码保留小时、去重窗口。测试可以注入固定时钟。

### 2.2 DDL

按 design §2.2 创建 `data_keys`、`bodies`、`tombstones`，给 `messages` 加 `seq`、`read_at`、`body_state`、`has_code`、`late`，给 `deliveries` 加 `aux`、`acked_at`，插入 `kv.seq = 0`，并加上设计中的索引。

另外加一列，供已确认的删除按钮使用，避免再做一次迁移：

```sql
ALTER TABLE deliveries ADD COLUMN tg_message_ids TEXT NOT NULL DEFAULT '';
-- JSON 数组，按分段顺序记录 sendMessage 返回的 message_id。M2 只负责写入。
```

`key_day` 不加外键。`bodies.message_id` 引用 `messages(id) ON DELETE CASCADE`。

### 2.3 现存数据怎么迁

生产库里，已经投递完的消息没有 `payloads` 行；还在队列里的、以及未过保留期的已过滤消息仍有明文。

| 行 | 处理 |
|---|---|
| 仍有明文，且按新保留期未过期 | 用 `received_at` 的 **UTC 日** 生成或复用 DEK，加密写入 `bodies`，`body_state='stored'`，`expires_at` 按 §4 计算。有明文才能算 `has_code` |
| 仍有明文，但已经超过新保留期 | 不写入 `bodies`（避免把过期验证码再包进库），`body_state='expired'`，`content_hash=''`，`idem_key=NULL` |
| 已经没有明文 | `body_state` 保持 `none`。`has_code` 留 0。不编造正文 |

`late` 不需要正文：`received_at - occurred_at` > 10 分钟为 `late`；落在 2 小时 50 分到 3 小时 15 分、且 `has_code=1` 时为 `otp_hold`。没有 `occurred_at` 则留空。

`seq` 按 `received_at, id` 从 1 递增回填，`kv.seq` 写成最后的值。

去重：

- 删除 `kv` 里的 `secret:dedupe`。新密钥是 `HKDF(KEK, "nekopost/v2/dedupe")`。
- 现存 `idem_key` 全部改成 `hex(HMAC(K_dedupe, 原键))[:32]`。客户端继续送原来的键，查找时先做同样的 HMAC。这样升级后，快捷指令和 App 的重试仍命中旧消息。
- 只有「现在还有明文，且年龄小于 `max(去重窗口, 1 小时)`」的行，才用新密钥重算 `content_hash`。其余写成 `''`。旧哈希用的是即将删除的库内密钥，留着既对不上新消息，也还能被离线猜测。
- 正文已经按新规则过期的行，`idem_key` 置 `NULL`。未过期的行保留 HMAC 后的键，哪怕 V1 已经删过明文。这样 7 天内的普通消息、24 小时内的验证码不会在升级后被补漏扫成新消息。

副作用（升级手册里写明）：早于 24 小时、仍在手机 72 小时补漏窗口里的验证码短信，幂等键会在迁移时清掉。若 App 再次补交，会被当成新消息再发一次 Telegram。这是「验证码正文只留 24 小时」的直接结果。

然后 `DROP TABLE payloads`。

历史日期各用自己的 DEK，不把旧正文都塞进「今天」这一把。这样到期后可以按天粉碎。还在队列里的消息通常只有当天和少数几天。

## 3. 包的划分

| 包 | 职责 |
|---|---|
| `internal/sealbox` | 加载 KEK；HKDF 出 `wrap` / `dedupe` / `audit` / `oauth-flow` / `kek-id`（后两个只派生并测试，M2 不使用）；按 UTC 日缓存 DEK；`Seal` / `Open`；粉碎一把 DEK。不依赖 `store` |
| `internal/store` | 迁移、SQL、加密列的读写、`seq`、墓碑、保留期删除。持有 `*sealbox.Box` |
| `internal/events` | `NextSeq`、元数据删除时写墓碑、`ChangesSince`。M2 没有 HTTP |
| `internal/service` | 在 store 之上的收件箱：列表、详情、搜索、预览打码、保留期入口。CLI 和 Bot 已过滤页走这里。HTTP 接入路径仍在 `httpapi`，通过 store 加密写入 |
| `internal/filter` | 扩充规则；`Mask`（给模型看）；新增 `Preview`（给人看的打码摘要） |
| `internal/ratelimit` | FailGuard 的键改为「IP 前缀 + 类别」 |
| `cmd/nekopost` | `keys`、`inbox` |

`audit` 和 `oauth-flow` 子密钥现在只固定派生方式，避免 M3 再改 KDF。

## 4. 加密与保留期

算法按 design §5.1，只用标准库：

- 正文明文 `{"v":1,"t":"...","b":"...","m":{...}}`，不压缩。
- 格式 `0x01 ‖ nonce(12) ‖ ct ‖ tag(16)`，`cipher.NewGCMWithRandomNonce`。
- AAD：`nekopost/body/v1|<message_id>|<key_day>`。DEK 包裹的 AAD：`nekopost/dek/v1|<day>`。

写路径：`InsertMessage` 同一事务内加密写入 `bodies`。没有投递目标时仍不存正文（与现在「没有 targets 就不写 payload」一致）。

读路径：Telegram worker、过滤器（发给 DeepSeek 之前）、Bot 已过滤页，都经 `sealbox.Open`。明文只留在局部变量里，不进日志，不进 `last_error`。

**投递完成不再删除正文。** 改由每小时维护任务删除 `expires_at < now` 且消息已到终态的 `bodies`，然后 `body_state='expired'`、`seq++`。仍在投递中的不删。

| 数据 | 规则 |
|---|---|
| 普通正文 | `NEKOPOST_BODY_RETENTION_DAYS`，默认 7，范围 0–90。0 表示回到 V1：进入终态就删正文 |
| 验证码类 | `NEKOPOST_CODE_RETENTION_HOURS`，默认 24。0 表示与普通正文相同。条件是 `has_code=1` 或 `category='code'`，取更早的到期时间 |
| `content_hash` | 早于 `max(去重窗口, 1h)` 写成 `''`。正文删除或过期时一并清空 |
| `idem_key` | 正文到期或删除时写成 `NULL` |
| DEK | 某天已无 `bodies` 引用且超过保留期：`wrapped` 先写成 `zeroblob`，再 `DELETE`，然后 checkpoint |
| 元数据 | 仍是 `NEKOPOST_RETENTION_DAYS`（默认 30），且必须 ≥ 正文保留天数。删除消息行时写 `tombstones` |
| 迁移前备份 | 24 小时后删除 |

`NEKOPOST_FILTER_KEEP_DAYS` 变为兼容别名：若它大于正文保留天数，取较大值并在启动日志里警告一次。已过滤消息的正文跟普通正文走同一套到期，不再单独 `DELETE FROM payloads`。

`expires_at` 在写入时算好。分类器稍后把类别改成 `code` 时，若因此更早到期，则把 `expires_at` 提前。

## 5. KEK 与 keys CLI

查找顺序：`NEKOPOST_KEK_FILE` → `$CREDENTIALS_DIRECTORY/nekopost-kek` → `/run/secrets/nekopost_kek`。

服务进程只走这三条，失败就拒绝启动。

管理命令（`check`、`keys status`）在服务没运行、因此没有 `CREDENTIALS_DIRECTORY` 时，若存在 `/etc/credstore.encrypted/nekopost-kek`，用 `systemd-creds decrypt` 读进内存。不写临时文件，不打印内容。这是给「先装二进制、再生成密钥、最后才重启」用的，不改变服务进程的三条路径。

| 命令 | 行为 |
|---|---|
| `keys init` | 内存中生成 32 字节，经管道交给 `systemd-creds encrypt --name=nekopost-kek`。已存在则拒绝，避免覆盖。不打印密钥 |
| `keys gen` | 把 32 字节写到 stdout，供 Docker 重定向。stdout 是终端时拒绝 |
| `keys export` | 解密后写入 stdout。stdout 是终端时拒绝 |
| `keys status` | 打印 `kek_id` 前缀、DEK 数量、最早一天、主密钥已使用天数。不打印密钥 |
| `keys rotate` | 凭证改写为两行（新、旧）。下次启动用新 KEK 重新包裹全部 `data_keys`（通常不超过保留天数 + 2 行） |
| `keys rotate --finish` | 只保留新 KEK。尚未重包裹完则拒绝 |
| `keys shred-all` | 销毁全部 DEK，元数据保留 |
| `keys reset --i-understand` | 丢弃全部 `bodies` 和 DEK，生成新 KEK |

两行凭证的格式：每行标准 base64 的 32 字节，第一行是当前 KEK，第二行是轮换中的旧 KEK。单行表示没有旧密钥。

`keys init` / `export` / `gen` 的输出可能含密钥。实现和测试都不在代理终端里调用会打印密钥的路径；文档里给出用户自己执行的命令。

## 6. 数据流

### 6.1 接入

`POST /v1/messages` 的请求和响应 JSON 不变。服务端额外：

- 用新的 `K_dedupe` 计算并存储 `content_hash` 和 `idem_key`。
- 加密正文，设置 `expires_at`、`has_code`、`late`。
- 同一事务里 `seq++`，写入 `messages.seq`。

`has_code` 只由本地规则决定：`codeRe` 命中，或关键词后紧跟 `otpToken`。`codeRe` 增加 `暗証番号|临时密码|初始密码|动态口令|인증번호|\bPIN\b|account code|sign-in code`。`otpToken` 是连续 4–8 位数字，或长度 4–16 且同时含字母和数字。

### 6.2 预览打码和给模型的 Mask

两套函数分开，避免改变发给 DeepSeek 的现有遮盖，也避免 Telegram 被打码：

- `Mask`：维持现在的链接和 3 位以上数字遮盖，并额外遮盖关键词后的 `otpToken` 以及 4–16 位字母数字 token。验证码在出站到模型之前被盖住。
- `Preview`：最多 120 个码点。遮盖连续 4 位以上数字、4–16 位字母数字 token、关键词后紧跟的 token。供 `inbox` 使用。

### 6.3 投递

- `DueJobs(ctx, now, channel, limit)`。每个 channel 一个循环。现在只有 `telegram`，1.1 秒间隔只作用在这个通道上。
- 发送前解密。解不开则永久失败，错误文字不含正文。
- `sendMessage` 的 `message_id` 追加进 `tg_message_ids`。重试从 `parts_sent` 续传时不覆盖已记录的 id。
- 聚合状态忽略 `aux=1`。M2 不产生辅助投递，列先在，行为与现在一致。

### 6.4 FailGuard

1. 先验证凭证。有效的 `mgs_` / `mga_` 永远不受封禁影响。
2. 计入「可能是猜测」的失败：格式像凭证、但库里没有的 `mgs_` / `mga_`。缺省 Authorization、明显格式错误、已吊销的凭证只返回 401，不计入。已吊销仍写现有的警告日志。
3. 计数键是 `(IP 前缀, 类别)`。IPv4 /32，IPv6 /64。类别先有 `source` 和 `admin_key`。`device` / `oauth` / `pairing` 只定义常量，M2 没有这些请求。
4. 客户端 IP 本身就是某个 `NEKOPOST_TRUSTED_PROXIES` 条目时，不封禁，健康报告把接入标为配置错误。

阈值不变：10 分钟 20 次，封 15 分钟。

### 6.5 搜索

`inbox search` 走 design §5.5：元数据过滤后按批解密，NFKC + 小写，子串，多词 AND，引号短语，`-词` 排除。单次最多 5000 行或 300 毫秒。查询词不进日志。M2 没有 HTTP 搜索。

## 7. CLI 与健康检查

`inbox list`、`inbox show <id>`、`inbox search <词>`。`show` 打印正文，所以文档要求只在用户自己的终端执行。测试用临时库和假正文。

`crypto` 组件（「正文加密」）：KEK 可用、`kek_id` 前缀、DEK 数量、最早一天、主密钥已使用天数。解不开最新 DEK 时为「不可用」。超过 365 天提示建议轮换。报告里没有密钥和正文。

`nekopost check` 在现有检查之外确认 KEK 能解开最新 DEK。不打印密钥。

## 8. 安装、单元、镜像、CI

`install.sh` 的顺序固定为：

1. `go build` 到 `bin/nekopost`（服务仍在跑）。
2. 安装二进制到 `/usr/local/bin/nekopost`。
3. 创建 `/etc/credstore.encrypted`（0700）。凭证文件不存在时执行 `nekopost keys init`。失败则停止，不重启。
4. 安装 unit，`daemon-reload`。
5. 最后才 `systemctl restart`，然后 `nekopost check`。

unit 增加：`ImportCredential=nekopost-*`、`Environment=GOMEMLIMIT=96MiB`、`MemoryMax=192M`、`LimitCORE=0`。其余沙箱不变。

Dockerfile 的 `GOMEMLIMIT` 改为 `96MiB`。`deploy/docker/compose.yaml` 和 `compose.proxy.yaml` 的 `mem_limit` 改为 `192m`，并给出 `secrets` 挂载 `/run/secrets/nekopost_kek`（属主 65532、模式 0400）的示例。发布说明写明：先 `keys gen` 并挂载，再升级镜像。

新增 `.github/workflows/ci.yml`：从 `docker.yml` 拆出 gofmt、`go vet`、`go test -race`。`docker.yml` 去掉两处 `type=gha` 缓存。冒烟测试生成临时 KEK 并挂进容器，断言没有 KEK 时进程拒绝启动；日志断言不依赖打印密钥。

## 9. 文档

- 新增 `docs/encryption.md`：KEK 放哪、`keys` 命令、保留期、粉碎的限度、备份必须和 KEK 分开。
- `docs/api.md`：`Idempotency-Key` 必须是随机值或带密钥的 HMAC；服务端只存 HMAC；正文到期后同一键会被当成新消息。
- 更新 `docs/architecture.md`、`docs/docker.md`、`docs/operations.md`、`docs/security.md` 里「投递完成即删正文」「去重密钥在 kv」「内存 64MiB / 160M」的说法。
- `README.md` 只加英文句子，文件里不出现中文字符。中文说明写在 `README.zh-CN.md`。

## 10. 分三段实现

每段结束都通过 `gofmt`、`go vet`、`go test -race ./...`。测试用临时数据库。本机同一时间只跑一组 race。

1. **加密底座**：`sealbox`、KEK、`keys`、迁移框架和 v3、启动校验、`crypto` 健康组件、`install.sh`、unit、Dockerfile、compose。
2. **数据流**：接入、投递、过滤、Bot 已过滤页、保留期和粉碎、去重、`late`、FailGuard、`events`、`service`、`inbox`。
3. **验证和文档**：下面的测试、`ci.yml`、文档、版本号 0.6.0。

验收测试：

- AEAD 往返；改 AAD、改密文、换 `message_id` 都失败。
- 错误的 KEK 拒绝打开已有库；DEK 粉碎后旧正文解不开。
- `seq` 在并发插入下仍单调（SQLite 单写者）。
- 验证码语料：中文、日文、英文、`PIN`、字母数字 token；普通数字金额不算 `has_code`。
- 被封禁的 IP 带着有效 `mgs_` 仍能提交；未知凭证才计入；已吊销不计入。
- 用 v2 fixture（有明文、已删明文、带 `idem_key`）迁移。迁移后的库用 `strings` 找不到明文哨兵。
- 搜索 fuzz；1 万条短消息的搜索 p95 < 150 ms。
- 现有 API 测试保持 JSON 形状。日志捕获断言不出现正文哨兵。

三段都绿之后做两遍审查：一遍看加密和失败路径，一遍看迁移、去重和安装顺序。修完再进入生产副本演练。演练用临时目录和临时 KEK，不打印消息内容，结束删除副本。演练才测量停机时间；在那之前不升级生产。

## 11. 演练与升级（M2 和删除按钮都完成之后）

顺序写进升级手册，不在本计划里执行：

1. 在副本上演练：备份、迁移、`check`、抽查行数和 `user_version`、确认哨兵不在库里、回滚到副本备份。
2. 生产步骤：先编译并安装二进制，备份 `/var/lib/nekopost`，`keys init`，最后才重启。回滚是停服务、换回旧二进制、用备份替换数据库。回滚后的库仍是 v2 明文，不能用新二进制打开。
3. 把手册和测得的停机时间交给用户。同意之后才升级。提醒把 KEK 另存到密码管理器，和数据库备份分开。
