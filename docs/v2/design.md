# 猫邮局 Nekopost V2 设计文档（精简安全版 · 定稿）

> 适用版本：服务端 0.5.0 → **1.0.0**（V2 完成）；Android App 0.1 → **1.0**
> 仓库：`imdrinkcat/nekopost`（私有），Go module `nekopost`，当前 SQLite `user_version = 2`；V2 开发分支 `v2`
> 生产环境：`osaka.drinkcat.com`，Ubuntu 24.04，1 vCPU，955 MB 内存（可用约 360 MB），Go 1.27.3，无 Java / Node / Android SDK
> 设计立场：**安全优先，只用成熟方案，组件尽量少**。仍然只有一个 Go 进程；Web 面板免构建；Android 的编译和测试全部在 GitHub Actions 里完成。
> 安全方案：用户选定的**精简版**（与完整草案的差异见 §0.2）。
> 进度：**M0（Android 工程骨架 + CI）与 M1（转发器 App + 登记二维码）正在 `v2` 分支实现**，登记二维码格式以 §3.7.1 为准；其余里程碑尚未开工。标注「实现时核对」的版本号或平台细节，开工前要再确认一次。
> 日期：2026-11-16

## 0. 摘要与决策记录

### 0.1 一页速览

| 主题 | 决定 |
|---|---|
| 进程模型 | 仍然是**一个 Go 进程**。Web 面板、App API、FCM 推送、确认都在 nekopost 内实现。不新增 Redis、队列、Node 运行时或第二个服务 |
| 新客户端 | ① 网页管理面板（与 App 共用同一套 `/v1` JSON API，后续里程碑交付）；② Android App「Nekopost」（转发短信和通知、FCM 接收、管理功能对等、Fold8 专属交互） |
| 网页技术 | **免构建**：随仓库提交的 `htm/preact` standalone ES 模块（附 `SHA256SUMS`）+ 手写 CSS，`go:embed` 编进二进制。`deploy/install.sh` 仍然**只需要 Go** |
| 网页登录 | GitHub（OAuth App + PKCE S256，scope 留空）/ Google（OIDC + PKCE + nonce，go-oidc 校验）。白名单只认不可变 ID：`github:<数字 id>`、`google:<sub>` |
| 第二因素 | **新浏览器确认**（默认开启）：新浏览器登录后需要在 App（生物识别）、Telegram Bot 或服务器 CLI 中确认，采用**号码匹配**（从 3 个候选中选出浏览器上显示的 2 位数字，选错即拒绝并冷却）。「信任此浏览器 30 天」由确认方决定（Telegram 确认一律「仅本次」） |
| 高危操作 | 要求**最近 5 分钟内重新认证过**：Web 可重新走一次 OAuth，或发起一次号码匹配确认；App 用 BiometricPrompt 绑定的签名。没有按操作的审批、没有操作哈希 |
| 网页会话 | 服务端会话，`__Host-np_sid`（HttpOnly、Secure、**SameSite=Strict**），空闲 12 小时 / 最长 7 天；OAuth 往返状态**不存服务端**，加密后放在单独的 `__Host-np_oauth`（Lax，10 分钟） |
| App 认证 | M1：扫**登记二维码**拿到 `mgs_`（只能发送）。M5 起：扫配对二维码 + 一次性码 + **Telegram / CLI 号码匹配确认** → Keystore 中**一把不可导出的 P-256 密钥** `k_dev` → **DPoP（RFC 9449）绑定**的设备凭证 `mgd_`；权限（scopes）在服务端按设备配置；应用锁（生物识别）在本地实现 |
| 权限 | `msg:send`、`msg:read`、`admin:read`、`admin:write`，高危操作另需「5 分钟内重新认证」。`mgs_` = 固定 `msg:send`；`mga_` = `admin:read` 且**永远不能读正文** |
| 正文 | **加密存储**，默认保留 7 天（验证码类 24 小时），网页和 App 可看可搜。AES-256-GCM 信封加密：KEK（systemd `LoadCredentialEncrypted` / Docker secret）→ 每个 UTC 日一把 DEK → 正文。DEK 过期即销毁（加密粉碎） |
| 检索 | **不建任何明文或盲索引**。先用元数据过滤，再在内存中逐行解密匹配（NFKC + 小写，子串），每次请求有扫描预算 |
| 同步 | 全局单调递增的 `seq` + `tombstones` 表。SSE（`Last-Event-ID` 回放、`event: reset`）和 `/v1/sync` 用**同一个查询**、同一个 `InboxItem` 结构 |
| FCM | HTTP v1，`x/oauth2/google` 获取服务账号令牌，不引入 firebase-admin。只发 data 消息 `{v,t,id,seq}`，App 拉取正文后**一定**弹本地通知。FCM 是辅助通道（`aux=1`），不影响 V1 的消息状态语义；设备回传 ack 作为端到端证据（✓✓） |
| 本地缓存 / 审计 | App 缓存用 **Tink AEAD**（keyset 由 Keystore 包裹），应用锁是本地 UI 闸门；服务端审计为只追加的表 + **HMAC 链**，关键事件同时发 Telegram |
| Android | 包名 `com.drinkcat.nekopost`，启动器名称「Nekopost」，界面中文。Kotlin + Compose + Material 3 + `material3-adaptive` + `androidx.window`；Room + DataStore + WorkManager + OkHttp + Tink；3 个 Gradle 模块，手写 DI |
| ⚠️ SDK | **`targetSdk = 36`、`compileSdk = 37`（工具链支持时）、`minSdk = 30`**。Android 17 对 targetSdk 37 的应用会把含 OTP 的短信扣留 3 小时，这会直接毁掉核心用途。侧载不受 Play 的 target 要求约束 |
| 折叠屏 | 目标机型 **Galaxy Z Fold8（宽机型）**。姿态模型 Cover / Flat / Tabletop / Book / Window，姿态**作为参数注入**界面。P0：展开即达、外屏验证码卡、铰链安全布局；P1：合上即遮挡、桌面值守台、分拣台、拖放找回、验证码拖进其他 App；P2：开信封、桌面配对台、拖入发送（默认关）等（§10） |
| 交付顺序 | **先做 App 转发器**（M0 + M1，进行中；服务器只加登记二维码），第一时间在 Fold8 上验证 Android 17 的 OTP 行为；然后加密底座 → Web 只读 → Web 管理 + 设备 → App 接收 → 管理对等 + 折叠屏 → 1.0 加固 |

### 0.2 精简版：与完整草案的差异

用户在两套安全方案中选择了精简版。下表是被替换掉的机制及其代价，后文只描述精简版。

| 议题 | 完整草案（已放弃） | 精简版（定稿） | 代价（如实说明） |
|---|---|---|---|
| 高危操作 | H1 / H2 两级、按单次操作审批（`op_hash` 绑定、用完即失效），H2 不接受 Telegram | **5 分钟内重新认证**：Web 重新 OAuth 或号码匹配确认；App 生物识别签名 | 一次重新认证在 5 分钟内放行任意高危操作；Telegram 失守 + 偷到 Cookie 可以换来新凭证（§11.2 #9） |
| 设备密钥 | `k_bg` / `k_read` / `k_adm` 三把密钥三枚凭证，服务端按种类分权 | **一把 DPoP 密钥 `k_dev`** + 服务端 scopes；应用锁在本地；另有一把只用于签重认证挑战的 `k_reauth`（不绑定任何凭证，§3.6） | 手机被 root 或 App 被注入后，攻击者可用 `k_dev` 读取该设备 scopes 内的全部正文；应用锁只防「拿到已解锁手机的人」 |
| 配对核对 | 输入式 4 表情 SAS（16 选 4） | **二维码 + 一次性码 + Telegram / CLI 号码匹配确认**（3 选 1） | 号码匹配的熵更低（猜中概率 1/3，猜错即作废配对）；仍有争用检测 |
| App 本地缓存 | HPKE：后台用公钥写、应用锁解开后用私钥读 | **Tink AEAD**，keyset 由无需用户验证的 Keystore 密钥包裹 | root 后可直接解密本地缓存；缓存有本地保留期与 72 小时离线清空 |
| 审计 | HMAC 链 + journal 锚点 + 每日 `audit-verify` 定时器 | **只有 HMAC 链**（`nekopost audit verify` 手动执行） | 删除最新若干行或用旧备份回滚数据库无法被自动发现；Telegram 中的提醒是服务器外的旁证 |
| Passkey | 列为 M4 可选 | **V2 不做**，以后可加（§14.2） | 网页登录仍防不住实时中继钓鱼（§3.3） |

### 0.3 关键决策记录

| # | 议题 | 最终决定 | 理由 |
|---|---|---|---|
| 1 | targetSdk | **36**（compileSdk 37） | targetSdk 37 时，所有 OTP 短信晚到 3 小时。WebOTP 格式（`@domain #123456`）的短信无论 targetSdk 都会被延迟，由补漏扫描在 3 小时后补交并标注（§8.4） |
| 2 | Web 技术栈 | 免构建的 vendored `htm/preact` + `go:embed`（§7.1） | 不需要 Node、不提交构建产物；Preact 只生成文本节点 |
| 3 | Web 第二因素 | 默认开启的**新浏览器确认**（号码匹配，渠道 App / Telegram / CLI） | 复用已认证的渠道。Telegram 本来就收到所有消息明文，用它确认登录不会降低读正文的门槛 |
| 4 | 高危操作 | **5 分钟内重新认证**（§3.8） | 精简版取舍：一套机制、一个时间戳，Web 与 App 语义相同 |
| 5 | 设备认证协议 | **DPoP（RFC 9449）**：凭证与 Keystore 不可导出私钥绑定，每个请求带证明；一台设备一把密钥 | 标准的绑定机制；拒绝未绑定的长期 bearer；不需要 refresh token |
| 6 | App 首次接入 | M1 扫登记二维码（`mgs_`）；M5 起扫码配对 + Telegram / CLI 确认。不做 App 内 OAuth | 避免在 App 中嵌 client secret；信任根是已认证的 Web 会话或服务器 root |
| 7 | 证书锁定 | 锁定 **ISRG 根（X1、X2）+ Let's Encrypt 已公布的下一代根**，1 年过期（过期后失效开放）。**继续使用 Let's Encrypt** | 锁根不锁叶子，autocert 续期不受影响；换 CA 前必须先发版 |
| 8 | KEK 缺失 / 错误 | **拒绝启动**并给出中文提示；`install.sh` 在升级前自动生成 KEK | 只有一条代码路径，绝不明文落盘 |
| 9 | AEAD | **AES-256-GCM**（`cipher.NewGCMWithRandomNonce`），首字节为版本号 | 本机有 AES-NI；只用标准库 |
| 10 | FCM 重试 / TTL | 5xx 和超时 → `RetrySafe`（载荷只有 ID，App 按 ID 去重）；`NEKOPOST_FCM_MAX_AGE=1h`；`msg` TTL 1 天、不设 `collapse_key`，`sync` TTL 1 小时 | 折叠会丢消息 ID；离线一天后上线仍能收到唤醒 |
| 11 | `mgs_` / `mga_` 的管理 | Web 与 App 都能新建、轮换、吊销（高危，需重新认证），**凭证只显示一次**；CLI 继续可用 | 用户要求管理功能对等 |
| 12 | 被系统打码的通知 | **默认转发一条带 `meta.redacted=true` 的占位消息**，可改为「跳过并计数」 | 知道「银行 App 刚发了验证码」本身就有价值 |
| 13 | Android 结构 | **3 个模块**（`app`、`core`、`forwarder`），手写 DI；本地加密用 **Tink**；`minSdk 30` | `forwarder` 不依赖 UI，转发路径最小且可单测；API 30 才有 `setUserAuthenticationParameters` |
| 14 | Telegram 与 App 双重通知 | **两边都发**；M6 增加按类型路由（`NEKOPOST_TELEGRAM_TYPES` + 每台设备的 `push_prefs`）。**不做**「设备未 ack 才发 Telegram」 | 先保证不丢，再优化打扰 |
| 15 | Key Attestation | **V2 不做** | Go 没有成熟的解析库；配对的信任来自一次性码 + 号码匹配确认 + 争用检测 |
| 16 | 紧急锁定 | 吊销全部会话与可信浏览器 + **所有设备降为「只发送」** + 24 小时内新配对的设备直接吊销；锁定期间只接受 CLI 确认，解除只能 CLI | 「锁定后恶意设备仍能读验证码」比「转发中断」糟糕得多 |
| 17 | CI 发布作业 | GitHub **Free** 计划没有 Environment：签名 secrets 放仓库级，**只在 `app-v*` tag 触发的发布作业中引用**；发布作业（含 GHCR 推送）**禁用一切缓存** | 防止缓存投毒影响发布产物或窃取签名密钥 |

### 0.4 版本钉住表（2026-11-16 查询，实现时写入 `go.mod` / `gradle/libs.versions.toml`）

如果某个 Android 版本在 CI 中解析不到，改用能解析到的最新稳定版，并在 PR 中注明。

- **Go**：Go 1.27.x；`rsc.io/qr` v0.2.0（M1 起，终端 / SVG 二维码）；`golang.org/x/oauth2` v0.38.0（PKCE；FCM 用 `google.CredentialsFromJSONWithType`）；`github.com/coreos/go-oidc/v3` v3.21.0（间接引入 `go-jose/v4`，DPoP 校验直接复用）；`golang.org/x/text`（已有，NFKC）。
- **Web**：`htm/preact/standalone` 3.1.1（单文件 ES 模块，内含 Preact 10.x 与 hooks；vendored + SHA256）。
- **Android**（JDK 21，仅 CI）：Gradle wrapper 钉住版本 + `distributionSha256Sum`（实现时核对 AGP 要求的最低版本）；AGP 9.5.1（compileSdk 37 需要 ≥ 9.2）；Kotlin 2.4.21；Compose BOM 2026.09.00；`material3-adaptive` 1.3.0；`androidx.window` / `window-testing` 1.5.1；Tink 1.24.0；Roborazzi 1.79.0；Robolectric 4.18。Navigation 3、DataStore、biometric、Room、WorkManager、OkHttp 5、kotlinx.serialization、Nimbus JOSE+JWT、`play-services-code-scanner`、`firebase-messaging`（M5 才引入）实现时钉住最新稳定版。

### 0.5 术语

- **来源（source）**：V1 概念，用 `mgs_` 提交消息的设备或脚本。**登记（enroll）**：M1 中 App 扫描服务器打印的登记二维码，以某个来源的身份转发（§3.7.1）。**设备（device）**：M5 起已配对的 App 实例，持有 DPoP 绑定的 `mgd_`。
- **确认（approval）**：新浏览器登录、Web 重新认证、设备配对、重认证密钥重登记前的人工确认，渠道为 App / Telegram / CLI，一律号码匹配。**重新认证（re-auth）**：会话或设备的 `reauth_at` 在 5 分钟内，是高危操作的前提。
- **KEK / DEK**：主密钥 / 按天生成的数据密钥。**`seq`**：全局单调递增的变更序号。

## 1. 总体架构

### 1.1 组件与运行位置

```text
 Galaxy Z Fold8 · Nekopost App（侧载）            osaka.drinkcat.com · nekopost 单进程（systemd 主 / Docker 备）
 ┌──────────────────────────────────┐           ┌───────────────────────────────────────────────────────────┐
 │ SmsReceiver + 补漏 ─┐             │  mgs_ /   │ httpapi（/v1/* · /auth/* · / 静态资源，webui go:embed）   │
 │ NotificationListener┴► outbox ────┼─DPoP mgd_►│ authn（mgs_ / mga_ / mgd_+DPoP / 会话）→ approval → audit │
 │ Compose 自适应 UI ◄──── SSE ──────┼───────────│ sealbox（KEK→DEK→AES-GCM）→ SQLite WAL（密文正文 · seq）  │
 │ FirebaseMessagingService ◄─ FCM ◄─┼───────────│ events（seq / SSE / sync） · filter（本地规则 + DeepSeek）│
 │ Keystore：k_dev · k_reauth · ks_main│          │ worker:telegram ─► Telegram（含 Bot：确认 · 提醒 · 锁定） │
 └──────────────────────────────────┘           │ worker:fcm ─► FCM HTTP v1      oidc ◄─► GitHub / Google    │
 iPhone 快捷指令 / 脚本（mgs_）· 监控脚本（mga_）  └───────────────────────────────────────────────────────────┘
 浏览器 Web 面板（Cookie + CSRF · SSE）──────────► 同上
```

- **nekopost 服务端（含 Web 面板）**：本机 systemd（主）/ 任意主机 Docker（备）；本机 `install.sh` → `go build`（只需 Go），GHCR 镜像由 Actions 构建。新增登记二维码、OIDC、会话、确认、设备、DPoP、加密、SSE、FCM、审计、静态面板。
- **Android App**：Fold8（及可选的旧手机，例如只转发第二张 SIM 的短信）；**只在 GitHub Actions 中构建**（JDK 21），发布到私有仓库的 Release。
- **Telegram Bot**（进程内，已有）：新增「📱 设备」菜单、确认按钮、安全提醒、「🚨 紧急锁定」、「在面板中打开」。
- **第三方**：Firebase（只用 FCM，不启用 Analytics / Crashlytics）；GitHub OAuth App 与 Google OAuth Client（只用于登录），都由用户创建。

### 1.2 入口与凭证隔离

1. **一套 API，三个客户端**：Web、App、Bot 共用 `internal/service` 业务层；Web 与 App 共用 `/v1` JSON。附录 C 的对照表是验收门槛。
2. **凭证领域隔离**：
   - 带 `Authorization` 头的请求**完全忽略 Cookie**。
   - 只有不带 `Authorization` 的请求才会读取会话 Cookie，并且必须经过 CSRF 中间件（§3.5）。
   - `mga_` 在 **authn 层**按静态 `(method, path)` 白名单放行（§3.10），其余一律 403，永远拿不到正文。
   - 设备凭证的权限由服务端 `devices.scopes` 决定；高危路由另查 `reauth_at`。
3. **不开 CORS**；面板与 API 同源。

### 1.3 关键请求流

**(a) Fold8 收到短信 → 网关 → Telegram + 其他设备**

1. App：`SmsReceiver`（`goAsync`）合并分段 → 本地规则 → Tink 加密写 outbox → `POST /v1/messages`（`mgs_` 或 DPoP `mgd_`，`Idempotency-Key = HMAC(device_secret, …)`）→ 收到 202 后删除 outbox 行。
2. 服务端单事务：去重 → `messages`（`seq++`）+ `bodies`（AES-GCM 密文）+ `deliveries[telegram, fcm/d_x(aux)]`；SSE 推送 `message`。
3. filter（仅 sms）：held → pending / filtered；worker:telegram 解密 → 渲染 → sendMessage；worker:fcm 向其他设备推 `{t:"msg", id, seq}`（跳过来源设备）。
4. 其他设备 `GET /v1/inbox/{id}` → 本地通知 → `POST /v1/devices/self/acks` → SSE `message.updated`（Fold8 ✓✓）。

**(b) FCM 唤醒**：`onMessageReceived` 在几秒窗口内用 `k_dev` 签 DPoP `GET /v1/inbox/{id}` → Tink 加密写入缓存 → 本地通知（默认只显示「发件人 · 类别」）→ ack；超时或离线时先发「N 条新消息」通用通知，打开 App 后 `/v1/sync` 补齐（§8.8）。**(c) Web 登录**见 §3.2；**(d) 设备配对**见 §3.7.2。

### 1.4 Go 代码结构变更

```text
新  internal/enroll（M1，登记链接与终端二维码）· service（业务层，HTTP 与 Bot 共用）· authn（统一 Principal、
    mga_ 白名单、FailGuard、路径参数校验）· oidc · session（会话、可信浏览器、CSRF、reauth_at）·
    approval（login / reauth / pairing / reauth_key，号码匹配）· devices（配对、DPoP、推送令牌）·
    sealbox（KEK、HKDF、按天 DEK、加密粉碎）· events（seq、SSE、sync）· audit（HMAC 链 + 提醒）·
    push/fcm · webui；web/；design/tokens.json + tools/tokens（M3）；android/（M0）
改  delivery（每个 channel 一个 worker）· router（+ fcm/<device_id>，aux=1）· store（Go 迁移步骤、新表、加密读写、
    搜索）· bot（设备菜单、确认、提醒、紧急锁定）· health（push、crypto、web）· cmd/nekopost（新子命令，§12.5）
```

### 1.5 资源预算

空闲 RSS 从约 20 MB 增到 ≤ 45 MB（静态资源约 150 KB，DEK 缓存 ≤ 保留天数 + 2 把）；搜索峰值 ≤ 100 MB（流式解密 + 扫描预算 + 并发 1）；SSE 每条约 10 KB（全局 8 条）；`GOMEMLIMIT` / `MemoryMax` 从 64 MiB / 160M 调到 **96 MiB / 192M**；二进制约 15 → 19 MB；空闲 CPU < 0.05%（DPoP 校验约 0.1 ms / 次，AES-GCM 有硬件加速）。

## 2. 数据模型变更（SQLite 迁移）

### 2.1 迁移框架

现状：`migrations []string`，每条一个事务，最后写 `PRAGMA user_version`。V2 改为 `type migration struct { sql string; fn func(ctx, tx *sql.Tx, env MigrateEnv) error }`，`fn` 用于需要 KEK 等 Go 逻辑的步骤。

- **迁移前自动备份**：有待执行的迁移时，先 `VACUUM INTO '$DATA_DIR/nekopost.db.pre-v<N>-<UTC时间>.bak'`（0600）。成功启动 24 小时后由维护任务删除（`NEKOPOST_KEEP_MIGRATION_BACKUP=true` 可保留）。v3 之前的备份含 V1 明文 `payloads`，必须按期删除，release notes 中说明。
- 每个版本仍在**单个事务**中完成：SQL 和 Go 步骤要么全部成功，要么全部回滚。只向前升级。
- v3 需要 KEK：未配置时**拒绝启动**并提示 `未找到主密钥：请运行 sudo nekopost keys init（见 docs/encryption.md）`。

### 2.2 v3：加密正文、变更流、保留期

```sql
-- 每个 UTC 日一把 DEK，由 K_wrap 包裹。删除某天的行 = 加密销毁该天所有正文。
CREATE TABLE data_keys (day TEXT PRIMARY KEY,         -- 'YYYY-MM-DD'（UTC，取 received_at 的日期）
  kek_id TEXT NOT NULL,                                -- hex(HKDF(KEK, "nekopost/v2/kek-id"))[:16]
  wrapped BLOB NOT NULL,                               -- 0x01 ‖ nonce ‖ AES-256-GCM(K_wrap, DEK) ‖ tag，AAD="nekopost/dek/v1|"+day
  created_at INTEGER NOT NULL);
-- 加密正文（标题、正文、meta 合成一个 JSON 整体加密），取代 V1 的 payloads。key_day 不加外键，便于单独销毁 DEK。
CREATE TABLE bodies (message_id TEXT PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
  key_day TEXT NOT NULL, ct BLOB NOT NULL, expires_at INTEGER NOT NULL);
CREATE INDEX bodies_expires ON bodies(expires_at); CREATE INDEX bodies_day ON bodies(key_day);
-- 删除墓碑：让客户端删除本地缓存，保留 30 天。
CREATE TABLE tombstones (seq INTEGER PRIMARY KEY, message_id TEXT NOT NULL, at INTEGER NOT NULL);

ALTER TABLE messages ADD COLUMN seq INTEGER NOT NULL DEFAULT 0;          -- 最近一次变更的序号
ALTER TABLE messages ADD COLUMN read_at INTEGER;                         -- 已读（Web / App 共享）
ALTER TABLE messages ADD COLUMN body_state TEXT NOT NULL DEFAULT 'none'; -- none|stored|expired|deleted
ALTER TABLE messages ADD COLUMN has_code INTEGER NOT NULL DEFAULT 0;     -- 本地规则判定含验证码（只存布尔）
ALTER TABLE messages ADD COLUMN late TEXT NOT NULL DEFAULT '';           -- ''|'late'|'otp_hold'（§8.4）
ALTER TABLE deliveries ADD COLUMN aux INTEGER NOT NULL DEFAULT 0;        -- 1 = 辅助通道（fcm），不参与聚合状态
ALTER TABLE deliveries ADD COLUMN acked_at INTEGER;                      -- 设备确认已取回（✓✓）
-- 索引：messages(seq)、(kind, received_at)、(category, received_at)、(read_at, received_at)；tombstones(at)
INSERT INTO kv(k, v) VALUES ('seq', '0');
```

- **`seq` 分配**：写事务中 `UPDATE kv SET v = v + 1 WHERE k='seq' RETURNING v`。新消息、状态变化、已读、正文过期或删除、找回都会更新该消息的 `seq`；元数据被清理时写一条 `tombstones`。SQLite 只有一个写者，`seq` 严格递增。
- **`has_code`** 让列表无需解密就能显示「验证码」标记；验证码本身只在 `msg:read` 的响应中即时提取（复用 `filter` 的本地规则），不落库。`has_code` **只由本地规则决定**。V2 扩充 `filter/rules.go`：
  - `codeRe` 增加 `暗証番号|临时密码|初始密码|动态口令|인증번호|\bPIN\b|account code|sign-in code` 等关键词；
  - 新增 `otpTokenRe`：连续 4–8 位数字，或长度 4–16 且同时含字母和数字的 token；关键词后紧跟的 token 一律视为可能的验证码；
  - `has_code = codeRe 命中 || (关键词后紧跟 otpToken)`。误判只会让消息更早删除、预览打码更多，方向是安全的。
  - **安全控制（保留期、预览打码、遮挡帘、锁屏显示）都不依赖 AI 类别**：AI 类别可以被提示词注入或误判。`category='code'` 只作为「更早删除」的附加条件。
- **Go 步骤**（同一事务）：
  1. 按 `received_at` 顺序给已有消息回填 `seq`。
  2. 生成当天 DEK；把 `payloads` 中尚存的行加密写入 `bodies`，`body_state='stored'`；`DROP TABLE payloads`。
  3. **去重密钥迁出数据库**：V1 的去重 HMAC 密钥存在 `kv` 里，拿到数据库就能对 6 位验证码暴力反推。V2 改用 `HKDF(KEK, "nekopost/v2/dedupe")`，删除旧密钥。
  4. **去重指纹不比正文活得久**（否则「旧备份 + KEK」能反推已粉碎的验证码）：维护任务把早于 `max(去重窗口, 1h)` 的 `content_hash` 写成 `''`；`idem_key` 改存 `hex(HMAC(K_dedupe, 原始键))[:32]`，正文到期或删除时写成 `NULL`。`docs/api.md` 补充：`Idempotency-Key` 必须是随机值或**带密钥**的 HMAC，禁止用不带密钥的内容哈希。
  5. 提交后执行 `PRAGMA wal_checkpoint(TRUNCATE)` 和一次 `VACUUM`，清除明文残留页。

明文格式（加密前）：`{"v":1,"t":"<title>","b":"<body>","m":{…}}`。AAD：`"nekopost/body/v1|" + message_id + "|" + key_day`。

### 2.3 v4：网页会话、可信浏览器、确认、审计

```sql
CREATE TABLE web_sessions (
  id_hash TEXT PRIMARY KEY,                   -- SHA-256(Cookie 值)
  identity TEXT NOT NULL, display TEXT NOT NULL,   -- 'github:12345678' / 'google:1098…'；display 仅显示
  state TEXT NOT NULL,                        -- pending|active
  browser_hash TEXT, csrf TEXT NOT NULL,      -- 关联的可信浏览器；32 字节随机
  reauth_at INTEGER,                          -- 最近一次重新认证（登录完成也算）；高危操作要求 ≤ 5 分钟
  created_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL,     -- last_seen_at 最多每 60 秒写一次
  idle_expires_at INTEGER NOT NULL, abs_expires_at INTEGER NOT NULL,
  ip TEXT NOT NULL DEFAULT '', ua_label TEXT NOT NULL DEFAULT '', -- 服务端解析的「Chrome · Windows」，不存原始 UA
  revoked_at INTEGER);
CREATE TABLE trusted_browsers (
  id_hash TEXT PRIMARY KEY, identity TEXT NOT NULL, label TEXT NOT NULL,
  created_by TEXT NOT NULL,                   -- 确认方：device:d_x 或 cli（Telegram 确认不创建）
  created_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, revoked_at INTEGER);
CREATE TABLE approvals (
  id TEXT PRIMARY KEY,                        -- a_<ULID>
  kind TEXT NOT NULL,                         -- login|reauth|pairing|reauth_key
  subject TEXT NOT NULL,                      -- login/reauth：会话 id_hash；pairing：pairing id；reauth_key：device_id
  pending_jwk TEXT NOT NULL DEFAULT '',       -- reauth_key：待登记的新 k_reauth 公钥
  summary TEXT NOT NULL,                      -- 服务端生成，例如「配对新设备：旧 iPhone（设备自报）」
  requester_ip TEXT NOT NULL DEFAULT '', requester_ua_label TEXT NOT NULL DEFAULT '',
  short_code TEXT NOT NULL,                   -- 2 位数字，只显示在发起端（浏览器，或待配对 / 待重登记的 App）
  choices TEXT NOT NULL,                      -- 确认端的 3 个候选（含 1 个正确），顺序随机
  trust_browser INTEGER NOT NULL DEFAULT 0,   -- login：确认方选择「信任 30 天」（Telegram 恒为 0）
  created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,       -- 5 分钟
  state TEXT NOT NULL,                        -- pending|approved|denied|expired|consumed
  decided_by TEXT, decided_via TEXT, decided_at INTEGER, consumed_at INTEGER, tg_message_id INTEGER);
CREATE TABLE web_identity_guard (            -- 选错号码冷却；1 小时内 2 次拒绝或超时 → 暂停，只能 CLI 解除
  identity TEXT PRIMARY KEY, cooldown_until INTEGER, suspended_at INTEGER, recent_denials TEXT NOT NULL DEFAULT '[]');
CREATE TABLE audit_log (
  id INTEGER PRIMARY KEY, at INTEGER NOT NULL,
  actor TEXT NOT NULL,                        -- web:github:123/s:<会话 id_hash 前 8 位> / device:d_x / cli / bot:tg:123 / system
  action TEXT NOT NULL, target TEXT NOT NULL DEFAULT '', ip TEXT NOT NULL DEFAULT '',
  result TEXT NOT NULL,                       -- ok|denied|error
  detail TEXT NOT NULL DEFAULT '{}',          -- 绝不含正文、搜索词、凭证
  mac BLOB NOT NULL);                         -- HMAC(K_audit, prev.mac ‖ 本行规范化内容)
-- 索引：web_sessions(identity, state)、approvals(subject, state)、audit_log(at)
```

**OAuth 往返状态不进数据库**：`{provider, state, verifier, nonce, return, reauth, exp, jti}` 用 `HKDF(KEK, "nekopost/v2/oauth-flow")` 派生的密钥做 AES-256-GCM 加密，放进 `__Host-np_oauth` Cookie（约 250 字节）。「一次性」由内存中的已用 `jti` 集合保证（TTL 10 分钟），**只有通过 state 校验并成功换到身份的回调才写入**，匿名请求无法填满它。

**pending 会话单独计数**：每个身份最多 2 个 pending 会话（超出淘汰最旧的 pending），永远不能淘汰 active 会话。

### 2.4 v5：设备、凭证、配对

```sql
CREATE TABLE devices (
  id TEXT PRIMARY KEY, name TEXT NOT NULL,    -- d_<ULID>；name 用户可改
  model TEXT NOT NULL DEFAULT '', app_version TEXT NOT NULL DEFAULT '',   -- 设备自报，不可信（≤64 字符，去控制字符）
  scopes TEXT NOT NULL,                       -- 'msg:send,msg:read,admin:read,admin:write' 的子集（服务端唯一事实来源）
  source_id TEXT REFERENCES sources(id) ON DELETE SET NULL,           -- 有 msg:send 时绑定的来源
  reauth_jwk TEXT NOT NULL DEFAULT '', reauth_at INTEGER,             -- k_reauth 公钥；最近一次重新认证
  push_token TEXT, push_state TEXT NOT NULL DEFAULT 'none', push_token_at INTEGER,   -- none|ok|invalid|stale
  push_prefs TEXT NOT NULL DEFAULT '{}',      -- {"types":[…],"categories":[…],"min_priority":"normal","include_own":false}
  settings_json TEXT NOT NULL DEFAULT '{}',   -- 转发配置快照（应用名与开关，不含内容）
  created_at INTEGER NOT NULL, last_seen_at INTEGER, last_ip TEXT,
  suspended_at INTEGER,                       -- 紧急锁定：非空时只放行 msg:send、ack、GET /v1/devices/self
  revoked_at INTEGER, revoked_reason TEXT);
CREATE TABLE device_tokens (
  hash TEXT PRIMARY KEY,                      -- SHA-256(mgd_…)
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE, prefix TEXT NOT NULL,
  jkt TEXT NOT NULL, jwk TEXT NOT NULL,       -- k_dev 公钥及其 RFC 7638 指纹
  created_at INTEGER NOT NULL, last_used_at INTEGER, revoked_at INTEGER);
CREATE TABLE pairings (
  id TEXT PRIMARY KEY,                        -- p_<ULID>
  code_hash TEXT NOT NULL UNIQUE,             -- SHA-256(二维码中的 128 位随机码)
  short_hash TEXT UNIQUE,                     -- SHA-256(手动输入码，10 位 Crockford base32)
  scopes TEXT NOT NULL, source_id TEXT, name_hint TEXT NOT NULL DEFAULT '',
  created_by TEXT NOT NULL,                   -- web:github:123 / cli / device:d_…
  created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,       -- 5 分钟
  state TEXT NOT NULL,                        -- open|claimed|contested|approved|rejected|expired|done
  claim_jwk TEXT, claim_reauth_jwk TEXT, claim_info TEXT,
  approval_id TEXT, device_id TEXT);          -- approvals(kind=pairing)
ALTER TABLE messages ADD COLUMN origin_device TEXT;   -- 通过设备提交时为 d_…（不向该设备推送）
```

### 2.5 数据保留（沿用现有的每小时维护任务）

| 数据 | 默认 | 配置 | 清理方式 |
|---|---|---|---|
| 正文 `bodies` | **7 天** | `NEKOPOST_BODY_RETENTION_DAYS`（0–90；0 = V1 行为） | 删除 `expires_at < now` 且已到终态的行 → `body_state='expired'`、`seq++` |
| 验证码类正文 | **24 小时** | `NEKOPOST_CODE_RETENTION_HOURS`（0 = 同正文） | 按 `has_code=1`（本地规则）或 `category='code'`（附加条件）提前删除 |
| 去重指纹 `content_hash` | `max(去重窗口, 1h)` | 跟随 `NEKOPOST_DEDUPE_WINDOW` | 写成 `''`；正文删除或过期时同步清空 |
| 幂等键 `idem_key`（HMAC 后） | 与正文相同 | — | 正文到期或删除时写成 `NULL` |
| DEK `data_keys` | 保留期 + 1 天 | — | 某天没有 `bodies` 引用且已过保留期：`UPDATE … SET wrapped=zeroblob(…)` → `DELETE` → checkpoint |
| 被过滤短信 | 与正文相同 | `NEKOPOST_FILTER_KEEP_DAYS` 降为兼容别名（取二者较大值并警告） | 同上 |
| 元数据 `messages`/`deliveries` | 30 天 | `NEKOPOST_RETENTION_DAYS`（必须 ≥ 正文保留期，`check` 校验） | 现有逻辑 + 写墓碑 |
| 墓碑 | 30 天 | — | `since` 早于最老墓碑时 sync 返回 `reset:true` |
| 审计日志 | 180 天 | `NEKOPOST_AUDIT_RETENTION_DAYS` | 删除最老的行，在 `kv` 中记录截断点的 mac，使链仍可校验 |

其余：会话 / 可信浏览器过期或吊销 7 天后删除；确认记录 7 天、配对记录 1 天；已吊销设备 30 天后删除（期间仍能识别「已吊销凭证被使用」）；迁移前备份 24 小时。仍在投递中的消息不删正文。每次清理后照常 `wal_checkpoint(TRUNCATE)`。

## 3. 身份认证与授权

### 3.1 凭证全景

| 凭证 | 持有者 | 形态 | 生命周期 | 能力 |
|---|---|---|---|---|
| `mgs_`（V1，不变） | 脚本、iPhone 快捷指令、**M1 的 App** | 静态 Bearer，DB 存 SHA-256 | 直到轮换或吊销 | 固定 `msg:send`：以本来源身份提交、查询本来源消息状态。**不能读正文** |
| `mga_`（V1，不变） | 监控脚本 | 静态 Bearer | 直到吊销 | 固定 `admin:read` + `no_content`：authn 层白名单只放行 5 个 GET（§3.10） |
| 网页会话 | 浏览器 | `__Host-np_sid` Cookie，DB 存哈希 | 空闲 12 小时 / 最长 7 天 | 所有者的 `msg:read` + `admin:*`；高危操作需 5 分钟内重新认证 |
| 可信浏览器 | 浏览器 | `__Host-np_tb` Cookie，DB 存哈希 | 30 天 | 只用于跳过「新浏览器确认」，本身不授予权限；只能由 App 或 CLI 确认时创建 |
| 设备凭证 `mgd_`（M5 起） | App | 不透明 256 位，**DPoP 绑定到 `k_dev`**（Keystore，不可导出，无需用户验证） | 直到设备吊销；90 天未使用标为陈旧，180 天自动吊销 | 服务端 `devices.scopes`；高危操作另需 5 分钟内用 `k_reauth` 重新认证 |

所有凭证 DB 中只存 SHA-256，明文只出现一次。已吊销的凭证再次被使用时沿用 V1 的检测，并发安全提醒。

**认证失败封禁（`FailGuard`）的 V2 语义**（修正 V1「先查封禁、再验凭证」导致的自伤）：

1. **先验凭证、再查封禁**：携带有效凭证的请求（设备另需 DPoP 校验通过）**永远不受 IP 封禁影响**。
2. **只统计「可能是猜测」的失败**：已过期、已吊销或不存在的会话只返回 401，**不计入**；计入的是格式正确但查无此人的 `mgs_` / `mga_` / `mgd_`、DPoP 签名失败、OAuth state 失败、配对码错误。
3. **计数键 = (IP 前缀, 凭证类别)**：IPv4 /32，IPv6 /64；类别为 `source`、`admin_key`、`device`、`oauth`、`pairing`，某一类失败过多只封这一类。
4. clientIP 是可信代理或 Docker 网关地址时（配置错误），健康报告标为**配置错误**，并且对该 IP 不执行封禁。
5. 前端收到 401 后**停止轮询和 SSE 重连**；App 收到 `device_revoked` 后停止重试。

### 3.2 网页登录：OAuth2 / OIDC

1. `GET /auth/github/start?return=%2F%23%2Finbox` → 302 到授权页，同时 `Set-Cookie __Host-np_oauth=<加密 flow>`（Lax，10 分钟）。
2. 用户在 IdP 登录（IdP 自己的 2FA / Passkey）→ 302 回 `/auth/github/callback?code&state`。
3. 服务端解密 flow，校验 exp、提供方与回调路径一致、state、jti 未用过，清除 Cookie；用 `code + code_verifier` 换 token。GitHub：`GET /user` 取数字 id 后立即吊销 token；Google：校验 ID Token 取 `sub`。
4. 不在白名单 → 403 + 审计 + 限频的 Telegram 提醒；身份处于冷却或暂停 → 403。
5. 带有效可信浏览器 Cookie → 新会话 `active`，303 到 `return`，并发低优先级提醒「可信浏览器 Chrome · Windows 刚刚登录」[吊销此浏览器]。
6. 新浏览器 → 新会话 `pending`，303 到 `/#/login/wait`（只在这里显示 2 位码，例如 47）；同时向 App / Telegram / CLI 发出「如果不是你本人刚刚在浏览器上登录，请点拒绝。新浏览器登录 · Chrome · Windows · 1.2.3.4。请选出浏览器上显示的数字」[18][47][93][拒绝]。
7. 选中 47 → 会话换新 ID 并变为 `active`；确认方选了「信任 30 天」时下发 `__Host-np_tb`。选错 → 拒绝并冷却（§3.3）。

**通用要求**

- 每个提供方独立的回调路径，并校验回调路径与 flow 中记录的提供方一致（防 IdP mix-up）。所有提供方都带 `state`（256 位）和 PKCE `S256`；Google 另带 `nonce`。
- `return` 只接受以 `/` 开头、不含 `//`、`\` 和控制字符的站内路径，防开放重定向。
- **白名单为空 = 网页登录关闭**（fail closed）：`/` 显示「网页面板未启用」。
- 被拒页面只显示刚登录者自己的 ID（「此账号未获授权：`github:12345678`」），方便首次配置；Telegram 提醒每小时最多 3 条。

**GitHub（OAuth App，不是 OIDC）**：Callback URL 精确为 `https://osaka.drinkcat.com/auth/github/callback`，关闭通配回调匹配；授权 URL 带 `state`、`code_challenge`、`allow_signup=false`、`prompt=select_account`，**scope 留空**；换 token 时带 `client_secret` 和 `code_verifier`；`GET https://api.github.com/user` **只用整数 `id`**（`login` 只用于显示）；拿到 `id` 后立即 `DELETE /applications/{client_id}/token`，不保存任何 token。

**Google（OIDC）**：`oidc.NewProvider(ctx, "https://accounts.google.com")`，scope `openid email`，带 `nonce`、`state`、PKCE、`prompt=select_account`。校验签名、`iss`、`aud`、`exp`，再常量时间比较 `nonce`；**授权只看 `sub`**，并要求 `email_verified=true`。不申请 `offline_access`。同意屏幕保持 **External / Testing**，只把自己加为测试用户（Google 侧的第二层白名单）。

**白名单**：`NEKOPOST_WEB_ALLOW=github:12345678,google:109876543210987654321`。某身份从配置中移除后，它名下的会话在下一个请求时被吊销。

### 3.3 第二因素：新浏览器确认

**为什么需要**：消息正文含验证码，能读正文就能接管其他账号。仅凭 GitHub / Google 账号，一旦它们被盗就全盘失守。

- 新浏览器（没有有效 `__Host-np_tb`）登录后，会话处于 `pending`，只能访问 `GET /v1/session`、`GET /v1/approvals/{id}`（只限本会话的确认，其他 ID 一律 404）、`POST /auth/logout`。pending 会话不能连 SSE，等待页每 2 秒轮询。
- 服务端创建 `approvals(kind=login)`，生成 2 位数字（只显示在浏览器上）和 3 个候选，同时推送到已启用的渠道，任一渠道选对即生效：
  1. **App**：已配对且有 `admin:write` 的设备收到 FCM `{t:"approve", id}` 并弹通知；确认界面**只能从 App 内「待确认」列表进入**（§8.3）。用户点选数字、选择「仅本次 / 信任此浏览器 30 天」，然后用 `k_reauth` 生物识别签名提交。
  2. **Telegram**：Bot 向管理员私聊发送 3 个候选数字 + [拒绝]，**没有单独的 [批准] 按钮**；恒为「仅本次」。
  3. **CLI**：`sudo nekopost approve` 列出待处理确认，交互输入数字，并询问是否信任该浏览器。
- **选错号码** → 立即 `denied`，该身份冷却 1 小时。**1 小时内 2 次拒绝或超时** → 暂停该身份的网页登录，Telegram 与 App 告警「你的 GitHub/Google 账号疑似被盗」，只能 `sudo nekopost web unlock <identity>` 恢复（App、Bot、CLI 不受影响）。
- 确认 5 分钟过期。名额分池：登录确认每身份 3 次 / 小时、全局同时 ≤ 3 个；重新认证、配对、重登记的确认有**独立**的池（同时 ≤ 3 个），攻击者用 pending 会话占满登录池也挡不住所有者。
- 渠道可配置：`NEKOPOST_APPROVAL_CHANNELS=app,telegram,cli`（默认全开）。App 配对后，用户可以去掉 `telegram`。`NEKOPOST_WEB_NEW_BROWSER_APPROVAL=on`（默认），关闭时启动日志和健康报告警告。
- **确认消息中的不可信字段**（UA、IdP 显示名、设备自报的 model / name）：只显示服务端解析出的「浏览器 · 系统」族名；其余字段截断到 40 字符、放进 `<code>`，并在 `.` `/` 后插入零宽字符。新 Bot 模板统一走 `internal/render` 的构造器，单元测试断言输出中没有 `url` / `text_link` 实体。

**为什么用 Telegram 确认不降低读正文的门槛**：V1 起 Telegram 就收到所有消息明文，Telegram 失守本身已等于正文泄露。精简版中 Telegram 也能确认重新认证与配对，这意味着 **Telegram 失守 + 偷到 Cookie 可以换来新设备或新凭证**；这是选择精简版时接受的取舍，缓解见 §11.2 #9。想要更强就设 `NEKOPOST_APPROVAL_CHANNELS=app,cli`。

**如实说明——确认挡不住实时中继钓鱼**：攻击者用仿冒面板引导你登录，同时在真站发起登录，把真站的数字中继到仿冒页面，你选中的就是攻击者的会话。能防中继的只有与源站绑定的 Passkey，V2 不做（§14.2）。缓解：面板只从书签进入；确认消息显示请求方 IP 与浏览器族名，与你所在位置不符就拒绝。

### 3.4 网页会话设计

| 项 | 设计 |
|---|---|
| ID | 32 字节随机，base64url；DB 只存 SHA-256 |
| Cookie | `__Host-np_sid`；`Secure; HttpOnly; SameSite=Strict; Path=/`；不设 `Domain` |
| 为什么 Strict 可行 | `/` 的 HTML 不含数据；所有数据通过同源 `fetch` 取得，Strict Cookie 一定会带上 |
| 防会话固定 | 每次登录都生成新 ID；pending → active 时再换一次 |
| 过期 | 空闲 12 小时，绝对 7 天；`last_seen_at` 最多每 60 秒写一次 |
| 重新认证 | 登录完成时 `reauth_at = now`。之后两种方式刷新：① **重新走一次 OAuth**：`/auth/{provider}/start?reauth=1`，回调要求身份与当前会话**完全一致**，只更新本会话的 `reauth_at`，不新建会话；Google 另带 `max_age=0` 并校验 `auth_time` 在 5 分钟内（GitHub 不支持强制重新登录，只证明此浏览器仍持有该 GitHub 账号的登录态）；② **号码匹配确认**：`POST /v1/session/reauth` 创建 `approvals(kind=reauth)`，流程同 §3.3。高危操作要求 `now - reauth_at ≤ 5 分钟`，否则 `403 reauth_required` |
| 并发 | 每身份最多 5 个 active 会话（超出淘汰最旧的 active）；pending 单独计数，最多 2 个 |
| IP 绑定 | 不绑定；IP 或 UA 族名明显变化时写审计并提醒 |
| 退出 | `POST /auth/logout` 删除会话行，返回 `Clear-Site-Data: "cache","cookies","storage"` |
| 列表与吊销 | 「安全」页列出会话和可信浏览器；Bot 与 CLI 也能「退出所有会话」 |

### 3.5 CSRF 与跨站防护（只针对 Cookie 认证的请求）

1. Go 标准库 `http.CrossOriginProtection`（基于 `Sec-Fetch-Site` / `Origin`）包住所有可以用 Cookie 认证的路由。
2. 非安全方法必须带 `X-Nekopost-CSRF: <会话 csrf>`（由 `GET /v1/session` 返回，只存在页面内存中），服务端常量时间比较。
3. 非安全方法必须是 `Content-Type: application/json`。
4. Cookie 认证的 GET（包括 SSE）要求 `Sec-Fetch-Site ∈ {same-origin, none}`。
5. 不开启 CORS；带 `Authorization` 的请求从不读 Cookie。GET 永远不改变状态。
6. **防客户端路径穿越（CSPT）**：例如短信内容 `https://osaka.drinkcat.com/#/m/..%2Fadmin%2Fsessions%2Frevoke-others%3F` 会被 Telegram 自动变成链接，面板若用模板字符串拼路径，就会带着合法 CSRF 头打到别的写接口。对策：
   - `web/lib/api.js` 只提供**带类型的端点构造函数**（`api.inbox.get(id)` …），ID 先匹配严格正则（`^m_[0-9A-HJKMNP-TV-Z]{26}$` 等），不匹配直接显示「链接无效」；路径段一律 `encodeURIComponent`；
   - CI grep 门禁：`fetch(` 与 `api.` 调用中禁止模板字符串或拼接路径；
   - 服务端对路径参数做同样的格式校验，原始路径含 `%2F`、`%2E`、`..` 或 `//` 的请求一律 400。

### 3.6 App / 设备认证：DPoP（RFC 9449）+ 本地应用锁

每台设备在 Android Keystore 中生成**一把**不可导出的 EC P-256 签名密钥 `k_dev`（优先 StrongBox，失败回退 TEE），它绑定该设备唯一的 `mgd_` 凭证：

| 密钥 | Keystore 参数 | 用途 |
|---|---|---|
| `k_dev` | `PURPOSE_SIGN`；**不要求用户验证**；`setUnlockedDeviceRequired(false)` | 每个 API 请求的 DPoP 证明（锁屏时也要能转发短信、处理推送） |
| `k_reauth` | `PURPOSE_SIGN`；`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`；`setInvalidatedByBiometricEnrollment(true)` | **不绑定任何凭证**，只用于签服务端下发的重新认证挑战（和 App 中的确认决定）。每次签名都需要 BiometricPrompt + CryptoObject，由 TEE 强制 |

为什么还有 `k_reauth`：精简版取消了三密钥分权，但「App 端重新认证 = BiometricPrompt 绑定的签名」必须由硬件强制才有意义，否则被注入的代码可以跳过弹窗。`k_reauth` 不能单独发起任何请求（服务端只在 `POST /v1/devices/self/reauth` 与确认决定中校验它），所以不构成第二枚凭证。

- **权限只在服务端**：路由声明所需 scope，authn 层对照 `devices.scopes` 检查；高危路由另要求 `devices.reauth_at` 在 5 分钟内（`403 reauth_required`）。
- **重新认证**：App 先 `POST /v1/devices/self/reauth/challenge` 取得 32 字节挑战（60 秒有效、一次性），用 `k_reauth` 在 BiometricPrompt 中签 `"nekopost/reauth/v1|" + device_id + "|" + challenge`，再 `POST /v1/devices/self/reauth {challenge, sig}`；成功后 `reauth_at = now`。App 在用户发起高危操作时自动走这一步。
- **应用锁在本地**：冷启动、无交互超过 N 分钟后用 `BiometricPrompt(BIOMETRIC_STRONG | DEVICE_CREDENTIAL)` 解锁界面（§8.9）。它挡住「拿到已解锁手机的人」，但**挡不住 root 或代码注入**：那种情况下 `k_dev` 可被当作签名预言机，读到该设备 scopes 内的全部正文（§0.2）。补救手段是远程吊销与紧急锁定（§3.9）。
- **`k_reauth` 失效**（新录入指纹）：App 生成新密钥，`POST /v1/devices/self/reauth-key {jwk}` 创建 `approvals(kind=reauth_key)`，App 显示 2 位数字；在 **Telegram、CLI 或其他已配对 App** 上号码匹配确认后服务端登记新公钥，同时向 Telegram 和其他设备发高优先级提醒「Fold8 的重认证密钥已被重新登记」[吊销此设备]。也可以直接重新配对。

每个请求：

```
Authorization: DPoP mgd_<43 字符>
DPoP: <JWS；header {typ:"dpop+jwt", alg:"ES256", jwk:<公钥>}；claims {jti, htm, htu, iat, ath[, nonce]}>
```

服务端（`go-jose/v4`）校验：

1. SHA-256(token) 查 `device_tokens`：不存在 → 401；设备或凭证已吊销 → 401 `device_revoked`，并记录「吊销凭证被使用」。
2. DPoP 证明：只允许 ES256；`jwk` 的 RFC 7638 指纹 == `jkt`；`htm`、`htu`（不含查询串）一致；`iat` 在 ±60 秒内；`ath` == base64url(SHA-256(token))；`jti` 5 分钟内未出现过（内存 LRU，4096 条）。
3. 时钟偏差：所有响应带 `Date`，客户端维护偏移；`iat` 超窗时返回 `401 use_dpop_nonce` + `DPoP-Nonce`，客户端带 `nonce` 重签一次。
4. 权限：scope、重新认证时间；设备处于紧急锁定时只放行 `msg:send`、ack 与 `GET /v1/devices/self`。
5. SSE 只在建立时校验一次，连接最长 1 小时后由服务端关闭，客户端重签重连。

**为什么不加 refresh token**：凭证脱离私钥毫无用处；私钥不可导出；吊销实时生效（每个请求查库）。App 端用 Nimbus JOSE+JWT 构造 JWS（含 DER → JOSE 签名转码）。

### 3.7 登记与配对

#### 3.7.1 登记二维码契约（M1，服务端 CLI 与 App 共享，必须严格一致）

```
nekopost://enroll?v=1&kind=source&url=<percent-encoded https 基址，无结尾斜杠>&id=<来源 id>&token=<mgs_ 凭证>&name=<percent-encoded 显示名>
```

- 服务端：`sudo nekopost source add <id> --qr` 与 `sudo nekopost source rotate <id> --qr` 在终端打印该链接的 ANSI 二维码（`internal/enroll`，`rsc.io/qr`）。凭证只在新建或轮换时出现一次，所以二维码也只能在这两个时刻打印；扫完执行 `clear`。基址取 `--url https://…`，未指定时为 `https://$NEKOPOST_DOMAIN`（M3 引入 `NEKOPOST_PUBLIC_URL` 后改为优先用它），必须是不带路径、查询、片段和结尾斜杠的 https 地址；地址无效时在写数据库之前就报错。
- 编码：所有值 percent-encode（空格为 `%20`，不用 `+`），参数按上面的顺序输出。
- App **拒绝其他一切输入**：scheme 不是 `nekopost`、host 不是 `enroll`、`v` 不是 `1`、`kind` 不是 `source`；`url` 不是 https（**只有 debug 构建**允许 `http://10.0.2.2` 与 `http://localhost`，供模拟器 E2E 使用）；`token` 不匹配 `^mgs_[A-Za-z0-9_-]{20,}$`；缺少或重复参数。拒绝时显示明确的中文原因，不保存任何字段。
- 扫码后显示确认页：醒目显示**目标主机名**、来源 ID 和显示名，用户点「开始转发」后才保存（防钓鱼二维码把短信转发到别人的服务器）。凭证用 Tink 加密保存（§8.9）。
- 建议 Go 与 Kotlin 共用同一组正反例向量（例如 `testdata/enroll/vectors.json`），两端测试都遍历它。
- 将来的版本只通过新的 `kind`（如 M5 的 `kind=device`）或提升 `v` 扩展；旧 App 遇到未知 `kind` 时提示「此二维码需要更新版 App」。

这是不配对的「仅转发模式」：App 持有的是未绑定的 bearer，只能发送、不能读，风险与 V1 的 iPhone 快捷指令相同。M5 配对后 App 改用设备凭证，并提示用户吊销原来的 `mgs_`（同一来源，历史不变）。

#### 3.7.2 设备配对（M5 起）：扫码 + 一次性码 + Telegram / CLI 确认

1. Web（5 分钟内重新认证过）或 CLI 创建配对：`POST /v1/admin/pairings {role:"full", source:"fold8", name:"Fold8"}` → `{id, qr:"nekopost://enroll?v=1&kind=device&url=…&code=<128 位码>", short_code:"7KQ4-M2ZD-9X", expires_in:300}`。
2. App 扫码（Google Code Scanner，无需 CAMERA 权限）或输入服务器地址 + 短码；确认页显示目标主机；Keystore 生成 `k_dev`、`k_reauth`。
3. `POST /v1/pairing/claim {code, jwk, reauth_jwk, device:{model,name,app_version}}` → 配对 `claimed`，创建 `approvals(kind=pairing)`，返回 `{pairing_id, short_code:"62"}`（2 位数字只发给 App）。
4. Telegram / CLI 收到「配对新设备：Fold8（设备自报 SM-F97x）· 权限：发送 / 接收 / 管理。请选出该手机上显示的数字」[17][62][85][拒绝]；选错 → 配对 `rejected`。
5. App 轮询 `POST /v1/pairing/{id}/token`（DPoP 由 `k_dev` 签名，jkt 必须等于领取时登记的）→ 确认后一次性返回 `{device_id, token, source_id, server}`（再请求得 410）→ `PUT /v1/devices/self/push`。
6. 审计 `device.pair` + Telegram「📱 新设备已配对：Fold8 [这不是我 → 吊销]」。

- **一次性码**：二维码中 128 位随机码，5 分钟、只能领取一次；手动短码 10 位 Crockford base32（50 位熵）。限流只按 IP（IPv6 /64）统计**失败**的领取，没有全局上限——匿名者无法借此阻断所有者的恢复配对；码正确的领取从不受限流影响。
- **确认渠道**：Telegram、CLI；由已配对 App 发起的配对（例如 Fold8 给旧手机配对，§10.4 ⑬）也可在发起的 App 上用 `k_reauth` 确认。紧急锁定期间只接受 CLI。
- **争用检测**：配对处于 `claimed` 时又有人用**正确**的码来领取，说明码已泄露（偷拍、录屏）：配对标为 `contested`，**禁止确认**，返回 `409 pairing_contested`，并发 Telegram 告警「配对码已泄露，请重新生成」。正主 App 收到时明确提示。
- `model`、`name` 一律标注「设备自报」并按不可信文本渲染。带 `msg:send` 时绑定一个来源（不存在则确认时创建）。
- **不需要 Web 也能配对**：`sudo nekopost device pair --name Fold8 --role full` 在终端打印二维码和短码，领取后终端直接让你输入 App 上的数字。丢失所有设备时也能用。

**角色模板**：转发器 = `msg:send`（旧手机，只转发短信）；接收器 = `msg:read`（平板，只看消息）；完整 = 全部 scopes（Fold8 默认）。

### 3.8 权限与高危操作

| scope | 允许的操作 |
|---|---|
| `msg:send` | `POST /v1/messages`（来源 = 凭证绑定的 source）；查询本来源消息状态 |
| `msg:read` | 收件箱列表、详情、搜索、sync、SSE、已读、ack、自己的推送设置 |
| `admin:read` | 状态、统计、系统、来源、设置视图、设备、会话、审计、投递队列；已过滤列表的**元数据**（预览另需 `msg:read`） |
| `admin:write` | 找回已过滤消息（≤5 条）、改名改图标、重新投递、测试推送、修改本设备推送偏好、退出当前会话、解除本设备配对；以及下列高危操作（另需重新认证） |

**高危操作**：Web 会话或设备必须在**最近 5 分钟内重新认证过**（§3.4、§3.6）；CLI 直接可用。

- **销毁与批量**：删除消息或立即销毁正文；JSON 导出；一次找回超过 5 条。
- **凭证**：创建配对；扩大或缩小设备权限；吊销其他设备；新建 / 轮换 / 删除 `mgs_`；新建 / 吊销 `mga_`（**只显示一次**）。
- **会话、来源与推送**：吊销**其他**会话或可信浏览器（含「退出其他所有会话」）；启用 / 停用来源；修改**其他**设备的推送偏好。
- **仅 CLI**：解除紧急锁定；`keys *`。

- 每个高危操作执行后立即向 Telegram 和持有 `admin:read` 的设备发提醒（写明操作与执行者），并写审计。
- **免重新认证的只有作用于自身的收紧**：退出当前会话、解除本设备配对。
- 紧急情况下的快捷路径不需要重新认证：Bot「🚨 紧急锁定」与「退出所有网页会话」、`sudo nekopost lockdown` / `web sessions revoke-all`。

Telegram 管理员（V1 的数字 ID 校验）能用 V1 菜单、号码匹配确认、设备列表与吊销、紧急锁定，**仍不显示正文**；CLI（服务器 root）能做一切。

### 3.9 吊销与紧急锁定

- **设备**：Web、App（高危）、Telegram「📱 设备」（手机丢失时最快）或 `nekopost device revoke <id>`，立即生效并清空 `push_token`；吊销前尽力推送 `{t:"revoked"}`，App 收到后调 `/v1/devices/self` 确认已吊销才清除本地数据（防伪造推送）。App「解除配对」= `DELETE /v1/devices/self` + `deleteToken()` + 删除 Keystore 别名 + 清空 Room。
- **会话 / 可信浏览器**：Web「安全」页、Bot、`nekopost web sessions revoke-all`，立即生效并断开 SSE。`mgs_` / `mga_`：同 V1，另可在 Web / App 上操作（高危）。
- **紧急锁定**：Bot「🚨 紧急锁定」（二次确认页列出全部设备，默认「暂停读取」，可逐台改为「吊销」）或 `sudo nekopost lockdown [--revoke-devices] [--keep <id>]`。效果：① 吊销全部网页会话和可信浏览器；② **所有设备降为「只发送」**（`suspended_at`）：只放行 `msg:send`、ack、`GET /v1/devices/self`，FCM 推送暂停——**转发不中断，但任何设备都读不到正文**；③ 最近 24 小时内新配对的设备直接吊销；④ 锁定期间所有确认只接受 CLI；⑤ Telegram 告警。收紧操作照常可用。**解除**只能 `sudo nekopost lockdown --lift` 或逐台 `nekopost device resume <id>`。

### 3.10 现有 `mgs_` / `mga_` 如何融入

- 两者语义完全不变，iPhone 快捷指令和 cron 脚本**无需任何修改**。
- `POST /v1/messages` 同时接受 `mgs_` 和带 `msg:send` 的 `mgd_`；来源由凭证决定，设备提交时写入 `origin_device`。
- **`mga_` 用路由白名单**：authn 层只放行 `GET /v1/admin/{status,sources,stats,system,settings}`，其余一律 403。单元测试遍历完整路由表断言这一点——以后新增的 handler 即使忘了检查，也不会对 `mga_` 开放。
- `GET /v1/admin/settings` 对 `mga_` 返回**精简视图**：去掉白名单身份、确认渠道、`kek_id` 和设备信息。

## 4. API 设计

### 4.1 约定

- **版本**：所有新接口仍在 `/v1` 下，只做**向后兼容的增量**（遵守 `docs/api.md` §8）。`GET /v1/meta`（公开）与 `GET /v1/session` 返回 `api_level`（V2 = 2）和 `min_app_version`。
- **格式**：JSON，`Cache-Control: no-store`；错误沿用 V1 的 `{ok:false, error:{code,…}, message}`。
- **分页**：键集游标 `?limit=`（默认 50，最大 100）+ `?cursor=`（base64url JSON `{"r":received_at_ms,"i":"m_…"}`，严格校验）；响应 `{items, next_cursor}`。
- **时间**：RFC 3339，按 `NEKOPOST_TIMEZONE` 输出。
- **App 标识**：`User-Agent: Nekopost-Android/0.1.0 (sdk 37; SM-F97x)`。
- **鉴权图例**：`P` 公开 · `S` `mgs_` · `K` `mga_`（白名单）· `W` active 网页会话（非 GET 需 CSRF）· `W⁺` 5 分钟内重新认证过的会话 · `D:x` 有 scope x 的设备 · `D⁺` 5 分钟内重新认证过、且有 `admin:write` 的设备。
- **路径参数**：所有 `{id}` 必须匹配对应前缀的严格格式（`m_`/`d_`/`a_`/`p_` + 26 位 ULID），否则 400。

### 4.2 浏览器认证端点（不属于版本化 API）

`GET /`（面板 HTML，不含数据，`no-store`）；`GET /assets/<version>/*`（`immutable`）；`GET /auth/{github|google}/start?return=&reauth=`（发起授权；`reauth=1` 用于已有 active 会话的重新认证）；`GET /auth/{github|google}/callback`；`POST /auth/logout`（W）。

### 4.3 JSON API

| 方法 | 路径 | 鉴权 | 用途 |
|---|---|---|---|
| **公开 / 会话 / 确认** | | | |
| GET | `/v1/meta` | P | `{version, api_level, min_app_version, providers}` |
| GET | `/v1/session` | W（含 pending）/ D / K | 当前身份、scopes、`state`、`csrf`（只给 W）、`reauth_until`、是否锁定、`code_retention_hours` |
| POST | `/v1/session/reauth` | W | 用号码匹配确认做重新认证 → `{approval_id, short_code, channels}` |
| GET | `/v1/approvals/{id}` | W（含 pending，只限本会话）/ D:admin:read | 确认状态；不属于自己的 ID 一律 404 |
| GET | `/v1/approvals?state=pending` | D:admin:write | App「待确认」列表（摘要、请求方 IP 与浏览器族名、3 个候选） |
| POST | `/v1/approvals/{id}/decision` | D:admin:write + `k_reauth` 签名 | `{decision, choice:"47", trust_browser, sig}`；`choice` 错误 → 立即 `denied` |
| **提交（V1，鉴权扩展）** | | | |
| POST | `/v1/messages` | S / D:msg:send | 不变；新增服务端标注 `late`（§8.4）；`Idempotency-Key` 以 HMAC 形式存储 |
| GET | `/v1/messages/{id}` | S / D:msg:send | 不变（只能查本来源，无正文） |
| **收件箱** | | | |
| GET | `/v1/inbox` | W / D:msg:read | 列表：`?source&type&category&status&unread&has_code&from&to&cursor&limit`（**不接受 `q`**） |
| POST | `/v1/inbox/search` | W / D:msg:read | 检索，查询词放在请求体，不进 URL 和日志（§5.5） |
| GET | `/v1/inbox/{id}` | W / D:msg:read | 详情：`body`、`meta`、`otp`、投递时间线、`body_expires_at`；审计 `message.view`（按会话 / 设备每分钟合并） |
| PATCH | `/v1/inbox/{id}` | W / D:msg:read | `{read:true\|false}` |
| POST | `/v1/inbox/mark-read` | W / D:msg:read | `{ids:[≤100]}` 或 `{before_seq:n}` |
| GET | `/v1/sync?since=<seq>&limit=200` | W / D:msg:read | `{changes:[InboxItem], deleted:[id], next_since, more, reset}` |
| GET | `/v1/events` | W / D:msg:read | SSE（§4.5）；pending 会话不可用 |
| POST | `/v1/devices/self/acks` | D:msg:read | `{ids:[≤50]}` → `deliveries.acked_at`（✓✓） |
| POST | `/v1/inbox/{id}/rescue` | W / D:admin:write | 找回被过滤的短信 |
| POST | `/v1/inbox/rescue` | W⁺ / D⁺ | 批量找回 `{ids:[≤20]}` |
| POST | `/v1/inbox/{id}/redeliver` | W / D:admin:write | 重新投递 Telegram |
| DELETE | `/v1/inbox/{id}/body` · `/v1/inbox/{id}` | W⁺ / D⁺ | 立即销毁正文 / 删除正文和元数据（写墓碑） |
| POST | `/v1/inbox/export` | W⁺ / D⁺ | **只导出 JSON**（attachment），不提供 CSV（防公式注入） |
| **配对与设备自身** | | | |
| POST | `/v1/pairing/claim` | P（按 IP 只统计失败） | 提交配对码与公钥 → `{pairing_id, short_code}`；码正确但已被领取 → `409 pairing_contested` |
| POST | `/v1/pairing/{id}/token` | P + DPoP（领取时的 `k_dev`） | 未确认 202 + `Retry-After: 2`；确认后一次性返回凭证 |
| GET / PATCH / DELETE | `/v1/devices/self` | D | 查看（含 `suspended`、`reauth_until`）/ 改名、本设备 `push_prefs`、上报 `settings_json` / 自行解除配对 |
| PUT | `/v1/devices/self/push` | D | `{token}` 登记或更新 FCM 令牌 |
| POST | `/v1/devices/self/reauth/challenge` · `/v1/devices/self/reauth` | D | 取挑战 / 提交 `k_reauth` 签名，刷新 `reauth_at`（§3.6） |
| POST | `/v1/devices/self/reauth-key` | D | `{jwk}` → 创建 `approvals(kind=reauth_key)`，返回 `{approval_id, short_code}` |
| **管理** | | | |
| GET | `/v1/admin/status` · `/stats?range=` · `/system` · `/sources` | W / D:admin:read / K | V1 接口扩展：新增组件 `push`、`crypto`、`web`；按小时 / 类别 / 来源分布；Bot 的服务器与机器人状态；来源的 `device_id` 与 24 小时计数 |
| GET | `/v1/admin/settings` | 同上（K 得到精简视图） | 脱敏配置、保留期、过滤、加密状态、白名单 ID、确认渠道 |
| POST | `/v1/admin/sources` | W⁺ / D⁺ | 新建来源，返回凭证（只显示一次）和登记二维码 SVG（§3.7.1） |
| PATCH | `/v1/admin/sources/{id}` | W / D:admin:write（`name`、`icon`）· W⁺ / D⁺（`enabled`） | 改名改图标；启停来源 |
| POST / DELETE | `/v1/admin/sources/{id}/rotate` · `/v1/admin/sources/{id}` | W⁺ / D⁺ | 轮换（只显示一次，附登记二维码）/ 删除 |
| GET | `/v1/admin/filtered?cursor` · `/deliveries?state=&channel=` | W / D:admin:read | 已过滤列表；投递队列排查 |
| GET | `/v1/admin/devices` | W / D:admin:read | 设备列表：权限、最后在线、推送状态、转发配置快照、App 版本、是否暂停 |
| PATCH | `/v1/admin/devices/{id}` | W / D:admin:write（只改 `name`）· W⁺ / D⁺（`scopes`、他人 `push_prefs`） | `{name, scopes, push_prefs}` |
| DELETE | `/v1/admin/devices/{id}` | W⁺ / D⁺ | 吊销设备 |
| POST | `/v1/admin/devices/{id}/test-push` | W / D:admin:write | 测试推送（`t:"ping"`），回传 ack 后显示往返时间 |
| POST | `/v1/admin/pairings` | W⁺ / D⁺ | 创建配对 |
| GET / DELETE | `/v1/admin/pairings/{id}` | W / D:admin:read · W / D:admin:write | 查看 / 取消 |
| GET | `/v1/admin/sessions` | W / D:admin:read | 会话与可信浏览器 |
| DELETE / POST | `/v1/admin/sessions/{id}` · `/v1/admin/trusted-browsers/{id}` · `/v1/admin/sessions/revoke-others` | W⁺ / D⁺ | 吊销他人会话 / 可信浏览器 / 退出其他所有会话 |
| GET | `/v1/admin/keys` | W / D:admin:read | `mga_` 列表 |
| POST / DELETE | `/v1/admin/keys[/{prefix}]` | W⁺ / D⁺ | 新建（只显示一次）/ 吊销 |
| GET | `/v1/admin/audit?cursor&action` | W / D:admin:read（**K 不可用**） | 审计日志，附 `chain_ok` |

### 4.4 统一的 `InboxItem`（列表、SSE、sync 共用）

```json
{"id": "m_01JCX…", "seq": 18234, "source": {"id": "fold8", "name": "Fold8", "icon": "📱"},
 "type": "sms", "priority": "normal", "category": "code", "category_text": "验证码",
 "title": "招商银行", "preview": "验证码 ******，5 分钟内有效。", "has_code": true,
 "received_at": "2026-11-16T10:20:01+09:00", "occurred_at": "2026-11-16T10:19:58+09:00", "late": "",
 "status": "delivered", "status_text": "已送达 Telegram", "read": false,
 "deliveries": [{"channel": "telegram", "state": "delivered"},
                {"channel": "fcm", "device": "d_01J…", "device_name": "Fold8", "state": "delivered", "acked": true}],
 "body_state": "stored", "body_expires_at": "2026-11-17T10:20:01+09:00", "redacted": false}
```

- `preview` 最多 120 字。**预览打码不依赖 `has_code`**：一律遮盖连续 4 位以上的数字、长度 4–16 且同时含字母和数字的 token、以及 §2.2 关键词后紧跟的 token。明文验证码只出现在详情的 `otp` 字段和正文中（打开详情会写审计）。
- 搜索结果额外带 `match:[[start,end),…]`（**按码点**、在规范化文本上计算），客户端只用它切分文本节点。
- 详情额外带 `body`、`meta`、`otp:{code, valid_minutes?}`、完整投递时间线（attempts、`last_error`、`uncertain`、`parts_sent/parts_total`、下次重试时间）。

### 4.5 实时更新：SSE `/v1/events`

```
id: 18234   event: message           data: <InboxItem>
id: 18235   event: message.updated   data: {"id":"m_…","status":"delivered","read":true,"deliveries":[…]}
id: 18236   event: message.deleted   data: {"id":"m_…"}
            event: health            data: {"overall":"ok","components":{…}}            （变化时，不带 id）
            event: approval          data: {"id":"a_…","state":"approved"}   （只发给相关会话与有 admin:write 的设备）
            event: pairing           data: {"id":"p_…","state":"claimed","model":"SM-F97x","model_self_reported":true}
            event: reset             data: {}        （Last-Event-ID 早于最老墓碑，客户端全量刷新）
: ping                                                （每 25 秒）
```

- 断线重连时浏览器自动带 `Last-Event-ID`；服务端回放与 `/v1/sync` **调用同一个查询函数**（`events.ChangesSince`）。
- 限制：全局 8 条、每会话 / 每设备 2 条（按会话 ID 或设备 ID 计数，不按 identity），不占 V1 的 32 并发名额；单条最长 1 小时；会话或设备被吊销时立即断开。每条连接用 `http.ResponseController` 单独延长写超时。
- App 前台用 OkHttp-SSE + DPoP；后台不连 SSE，靠 FCM。反向代理部署时响应带 `X-Accel-Buffering: no`，`docs/docker.md` 补充 Caddy / nginx 配置。

### 4.6 限流（在现有机制上补充）

| 对象 | 规则 | 响应 |
|---|---|---|
| `/auth/*/start`、callback | 每 IP（IPv6 /64）10 次 / 分钟；state 失败计入 FailGuard（`oauth`）；`start` 不产生服务端状态 | 429 |
| 白名单外登录 | 计入 FailGuard（`oauth`）；Telegram 提醒每小时 ≤3 条 | 403 |
| 登录确认 | 每身份 3 次 / 小时；全局同时 ≤3 个；选错号码后冷却 1 小时 | 429 / 403 |
| 重新认证 / 配对 / 重登记确认 | 每会话 / 设备 5 次 / 10 分钟；独立的全局池，同时 ≤3 个 | 429 |
| `/v1/pairing/*` | 每 IP **失败** 10 次 / 10 分钟；码正确的请求不计数；无全局上限 | 429 |
| DPoP 校验失败 | 计入 FailGuard（`device`） | 401 |
| `POST /v1/inbox/search` | 每会话 / 设备同时 1 个；30 次 / 分钟 | 429 |
| 其他已认证读 / 管理写 | 每会话 / 设备 600 次 / 分钟（突发 100）/ 60 次 / 分钟 | 429 + `Retry-After` |
| SSE | 全局 8、每会话 / 设备 2；pending 会话不能连 | 503 |
| `POST /v1/messages` | 沿用 V1 的按来源令牌桶（设备计入其绑定来源） | 429 |

### 4.7 新增错误码

- 401：`device_revoked`（设备或凭证已吊销）、`invalid_dpop_proof`、`use_dpop_nonce`（附 `DPoP-Nonce`）、`approval_pending`（会话处于 pending）。
- 403：`reauth_required`（需要 5 分钟内重新认证，响应附可用方式 `oauth` / `approval` / `device`）、`device_suspended`（紧急锁定中只能发送）、`web_login_suspended`、`insufficient_scope`、`csrf_failed`。
- 400：`invalid_path_param`、`pairing_code_invalid`（不区分不存在与过期）；409：`pairing_contested`（码已泄露，配对被锁死）；410：`body_expired`、`pairing_consumed`；503：`search_busy`。

## 5. 正文加密与检索

### 5.1 算法

- **KEK**：32 字节随机，进程外文件，通过 systemd credential / Docker secret 注入；**不进数据库，不进环境变量**。
- **子密钥**：HKDF-SHA256（`crypto/hkdf`），info 为 `nekopost/v2/wrap`、`/audit`、`/dedupe`、`/oauth-flow`、`/kek-id`，用途隔离。
- **DEK**：每个 UTC 日一把（32 字节 `crypto/rand`），用 AES-256-GCM(K_wrap) 包裹，AAD 含日期。
- **正文**：AES-256-GCM（`cipher.NewGCMWithRandomNonce`），格式 `0x01 ‖ nonce(12) ‖ ct ‖ tag(16)`（首字节是版本号），AAD = `"nekopost/body/v1|" + message_id + "|" + key_day`（防密文搬家或换钥）；明文 `{"v":1,"t":…,"b":…,"m":…}` **不压缩**（避免压缩比侧信道）。

只用标准库，纯 Go（不引入 cgo，因此不用 SQLCipher）。元数据（类型、时间、长度、类别、来源）保持明文用于统计和筛选，`security.md` 中如实说明。

### 5.2 读写路径

- 写：`store.InsertMessage` 在同一事务里加密写 `bodies`（当天 DEK 已缓存）。
- 读：Telegram worker、filter（DeepSeek 前的本地遮盖）、inbox handler、Bot 已过滤页都通过 `sealbox.Open(msgID)` 解密；明文只存在于局部变量，不进日志、不进 `last_error`。
- 原来的「投递完成即删除 payload」改为「按保留期删除 bodies」；投递逻辑本身不变。

### 5.3 KEK 放在哪里

查找顺序：`NEKOPOST_KEK_FILE` → `$CREDENTIALS_DIRECTORY/nekopost-kek` → `/run/secrets/nekopost_kek` → **拒绝启动**。

**systemd（主环境，Ubuntu 24.04 自带 systemd 255，本机无 TPM）**

unit 增量：`ImportCredential=nekopost-*`（从 `/etc/credstore.encrypted/` 导入，不存在的直接忽略）、`Environment=GOMEMLIMIT=96MiB`、`MemoryMax=192M`、`LimitCORE=0`。

- `sudo nekopost keys init`：在内存中生成 32 字节，经管道交给 `systemd-creds encrypt --name=nekopost-kek - /etc/credstore.encrypted/nekopost-kek`，全程不显示、不写明文文件。`install.sh` 检测到缺失时在升级前自动执行。
- 没有 TPM 时 `systemd-creds` 用主机密钥加密：只拿到数据目录、`/etc/credstore.encrypted` 或其备份的人拿不到 KEK；**拿到 root 或整盘镜像的人仍能取得 KEK**（§11）。
- 建议把 KEK 另外备份到密码管理器（`sudo nekopost keys export | <密码管理器 CLI>`；stdout 是终端时拒绝输出），与数据库备份分开存放。

**Docker**：compose `secrets:` 挂载到 `/run/secrets/nekopost_kek`（属主 65532、0400），不放在 `data/` 卷中；生成用 `docker run --rm ghcr.io/imdrinkcat/nekopost keys gen > ./secrets/nekopost_kek`。宿主机上是明文文件，建议启用磁盘加密。

**启动校验**：加载 KEK → 计算 `kek_id` → 与 `data_keys` 比较并尝试解开最新一把 DEK；不一致时拒绝启动：「主密钥与数据库不匹配（数据库为 3fa2…，当前为 91bc…）」。`nekopost check` 提前发现这类问题。

### 5.4 轮换

- **DEK**：自动，每个 UTC 日首次写入时生成，过保留期后加密粉碎。
- **KEK（计划内，建议每年）**：`sudo nekopost keys rotate` 把 credential 改写为新旧两行 → 重启时用新 KEK 重新包裹全部 `data_keys`（通常 ≤ 9 行）→ `keys rotate --finish` 只保留新 KEK。设置页显示「主密钥已使用 N 天」，超过 365 天时提示「建议轮换」。
- **KEK 怀疑泄露**：轮换只保护未来；更彻底的是 `keys shred-all`（销毁全部 DEK，元数据保留）后再 `keys rotate`。**KEK 丢失**：`keys reset --i-understand` 丢弃全部 bodies 和 DEK 并生成新 KEK，最多丢失保留期内的正文。

### 5.5 检索：先过滤元数据，再逐行解密

数据量很小（7 天约几千条，90 天上限也只有几万条），所以采用泄露最少的方案：

1. SQL 先按时间区间、来源、类型、类别、状态、已读、`has_code` 过滤（都有索引），按 `received_at DESC` 每批 200 行流式读取。
2. 逐行解密，对 title、body、meta 做 **NFKC + 大小写折叠**后子串匹配。多词 AND；`"…"` 短语；`-词` 排除。中文不分词。
3. **预算**：单次最多扫描 5000 行或 300 ms；未扫完返回 `{items, next_cursor, scanned, exhausted:false}`，界面显示「继续搜索更早的消息」。
4. 每个会话 / 设备同时只允许 1 个搜索；前端防抖 250 ms。查询词只在请求体里，审计只记录「执行了搜索」。
5. 成本：7 天约 3500 条，1 vCPU 上约 30–80 ms（M2 基准测试确认，目标 p95 < 150 ms）。

放弃的方案：SQLite FTS5（影子表里是明文）；HMAC 盲索引（泄露词频，CJK 膨胀）；只在客户端搜索（Web 没有本地缓存）；内存 FTS5 缓存（超过 10 万条或 p95 > 500 ms 时再考虑）。

### 5.6 安全删除

1. **行删除**：继续 `secure_delete=ON` 和每小时 `wal_checkpoint(TRUNCATE)`。
2. **加密粉碎**：某天的 DEK 被销毁后，SSD 残留、快照或旧备份中那天的正文都解不开（前提是去重指纹与幂等键不比正文活得久，§2.2）。残余风险：旧备份里仍有被包裹的 DEK，攻击者同时拿到 KEK 仍可解密——所以**备份与 KEK 分开存放、备份本身也要有保留期**。
3. **单条删除**：同一天的 DEK 仍在用，只靠 `secure_delete`；文档如实说明。
4. **内存**：DEK 只缓存在保留期内；`LimitCORE=0`；建议关闭未加密的 swap。
5. **日志**：测试拦截 slog 输出，断言不出现正文哨兵字符串；DPoP 证明、OAuth code、FCM token 只记录指纹前缀。

## 6. FCM 推送

### 6.1 服务端鉴权（HTTP v1）

- 服务账号只授予 **Firebase Cloud Messaging API Admin**。
- 令牌：`google.CredentialsFromJSONWithType(ctx, saJSON, google.ServiceAccount, "https://www.googleapis.com/auth/firebase.messaging")` + `oauth2.ReuseTokenSource`（不用已弃用的 `CredentialsFromJSON`），发往 `POST https://fcm.googleapis.com/v1/projects/{project_id}/messages:send`。
- 独立 `http.Client`：超时 15 秒，响应体 ≤ 64 KB。服务账号 JSON 经 `ImportCredential` 导入（`nekopost-fcm-sa`）或 Docker secret，不进 env 文件。
- 未配置时 FCM 通道关闭，`push` 组件显示「未配置」，App 退回「打开 App 时同步」。`nekopost check` 实际换取一次 access token 并发一次 `validate_only:true` 请求。
- 不使用 firebase-admin-go（依赖树太大），实现约 300 行。

### 6.2 Token 注册生命周期

- 配对前（含 M1 仅转发模式）`firebase_messaging_auto_init_enabled=false`，不向 Google 注册。配对成功后开启 auto init → `getToken()` → `PUT /v1/devices/self/push`，服务端存 token、`push_state=ok`。
- `onNewToken` 立即 PUT（失败交给 WorkManager 重试）；WorkManager 每 30 天重新 PUT；服务端**超过 45 天未刷新**标为 `stale` 并停止推送，界面提示「推送可能失效，请打开 App」。
- `UNREGISTERED` 或 token 相关 `INVALID_ARGUMENT` → 清空 token、`push_state=invalid`，App 下次打开时重新注册。吊销 / 解除配对时 App `deleteToken()`，服务端先尽力推 `revoked` 再清空。

### 6.3 载荷：只有 ID，只用 data 消息

```json
{"message": {"token": "<device token>",
  "data": {"v": "1", "t": "msg", "id": "m_01JCX…", "seq": "18234"},
  "android": {"priority": "HIGH", "ttl": "86400s"}}}
```

类型（`t`）：`msg` 新消息（消息优先级 `low` → `NORMAL`，其余 `HIGH`；TTL 86400s；**不设 collapse_key**，否则丢 ID）· `sync` 批量变化（`NORMAL`，3600s，collapse_key `sync`）· `approve` 有待确认的登录 / 重新认证 / 配对（`HIGH`，300s）· `revoked` 设备已吊销（`HIGH`，86400s）· `ping` 测试推送（`HIGH`，300s）。

- **不使用 `notification` 字段**，通知完全由 App 本地生成。Google 只能看到时间、ID、设备 token 和频率。
- **每条 `HIGH` 都必须让用户看到通知**（否则 FCM 会降级）；离线后才收到的旧消息合并为一条「离线期间 N 条新消息」。
- 不采用 `android_v2`（预览，可能由 GMS 代发兜底通知）。可选 `NEKOPOST_FCM_INCLUDE_ID=false`：只发 `sync`。

### 6.4 与 Telegram 并存的投递语义

- **router**：每条非过滤消息生成 `telegram/default`（V1 不变）；另外对每台满足条件的设备生成 `fcm/<device_id>`，`aux=1`：设备未吊销、有 `msg:read`、`push_state=ok`；`push_prefs` 匹配；设备不是消息来源（防回环）；被过滤的消息不推送，找回后照常推送。
- **`aux=1` 的投递不参与 `AggregateStatus`**。V1 的 `status`、`?wait=8`、「已送达 Telegram」语义完全不变，iPhone 快捷指令不受影响。
- FCM 的「已投递」= FCM 已接受；端到端证据是设备 ack（界面分开显示「推送：已交给 FCM」→「✓✓ 已在 Fold8 取回」）。
- **Worker 按通道拆分**：每个 channel 一个协程，共享 `DueJobs(ctx, now, channel, limit)`；Telegram 的 1.1 秒节流只作用于 Telegram。FCM 最长重试 `NEKOPOST_FCM_MAX_AGE=1h`。
- 默认两边都发；M6 增加 `NEKOPOST_TELEGRAM_TYPES`。App 按消息 ID 去重（通知 tag = 消息 ID）。
- **0.10.0 起 App 推送可作主通道**（用户要求「不用 Telegram 也应该能收到消息」）：`NEKOPOST_TELEGRAM_TYPES` 不包含的类型（`none` = 全部不发）没有 Telegram 投递，FCM 投递不再是 aux，由它决定状态（「已推送到 App」），最长重试 min(`MaxAge`, 24 h)；Telegram 主通道时 FCM 仍是 aux（1 h）。设备推送偏好在入库时就判定，排除的设备记 `skipped`（终态，不计入聚合）；来源设备在 App 主通道时也记 `skipped`。V1 模式下推送过 App 的正文保留到 `exp`，以便设备取回。Bot 仍用于管理与审批。

### 6.5 失败处理（映射到现有 `Outcome`）

- 200 → `Delivered`。404 `UNREGISTERED`、指向 token 的 400 `INVALID_ARGUMENT` → `Permanent`，清 token、`push_state=invalid`。其他 400、403 `SENDER_ID_MISMATCH` → `Permanent`，`push` 组件记 `config_error`。
- 401 / 403 认证错误、换 token 失败 → `RetrySafe`，刷新令牌后重试，连续失败记 `config_error` 并发 Telegram 告警（每小时 ≤1 次）。429、503 → `RetrySafe`，遵守 `Retry-After`。500、超时、连接中断 → **`RetrySafe`**（载荷只有 ID，重复推送无害）。

健康组件 `push`（「App 推送」）：最近一次成功 / 失败、连续失败次数、失效与陈旧 token 数；没有设备时显示「无法确认」。

## 7. 网页面板（M3 起）

### 7.1 技术选型

**免构建的原生 ES 模块**：随仓库提交 `web/vendor/htm-preact-standalone.mjs`（htm 3.1.1，单文件内含 Preact 10.x + hooks，约 5 KB gzip）与 `SHA256SUMS`，手写 CSS，`go:embed` 编进二进制。

- 服务器没有 Node，`install.sh` 只依赖 Go，源码就是产物；面板直接消费 `/v1` JSON，与 App 同一套接口。
- Preact 只用 DOM API 创建文本节点；htm 不使用 `eval`，可配合 Trusted Types `'none'`；standalone 版没有裸模块名，不需要 import map（内联脚本会和 CSP 冲突）。
- 供应链只有 1 个第三方文件：CI 校验 `SHA256SUMS` 并与 npm 官方 tarball 比对；运行时不访问 CDN。类型检查用 JSDoc + `tsc --noEmit --checkJs`（只在隔离的 workflow 中）。未压缩总量 ≤ 150 KB。
- 放弃：Svelte/React + Vite（需要 Node 构建链）；htmx + 模板（两套表现层，需要 `innerHTML`）。

### 7.2 目录与提供方式

`web/` 下：`index.html`（启动时替换版本号，不含数据）、`app.js`（hash 路由、会话、SSE、快捷键）、`pages/*.js`、`lib/api.js`（带类型的端点构造函数）、`lib/render-safe.js`（Bidi / 零宽可视化、高亮分段）、`css/tokens.css`（生成）与 `css/app.css`、`icons/sprite.svg`、`fonts/jbmono-digits.woff2`、`vendor/`（含 `SHA256SUMS`、`LICENSES`）、`embed.go`。

- 静态资源路径 `/assets/<VERSION>/…`，`immutable`；升级后自然失效。hash 路由（`/#/m/<id>`），Telegram「在面板中打开」按钮链接到这里。
- **URL 中只放筛选条件**，不放正文、搜索词或凭证。不使用 Service Worker；提供 `manifest.webmanifest`。
- 面板只在配置了至少一个登录提供方且 `NEKOPOST_WEB_ALLOW` 非空时启用。

### 7.3 页面列表

| 路由 | 页面 | 内容 |
|---|---|---|
| `#/login` | 登录 | 「使用 GitHub 登录」「使用 Google 登录」（普通 `<a href>`）；被拒时显示自己的身份串和复制按钮 |
| `#/login/wait` | 等待确认 | 大号 2 位数字，「请在 App、Telegram 或服务器上**选出这个数字**」；每 2 秒轮询；被拒或暂停时显示原因 |
| `#/inbox`（首页） | 消息 | 宽屏三栏（导航 \| 列表 \| 详情）；未读圆点；验证码芯片；筛选；搜索（扫描进度 +「继续搜索更早的」）；`aria-live` |
| `#/m/{id}` | 详情 | 正文（`pre-wrap`）、验证码大卡、链接不可点（§7.5）、meta、投递时间线（接收 → 分类 → Telegram ✓ → Fold8 ✓✓）、保留期倒计时、找回 / 重新投递 / 销毁正文 |
| `#/filtered` | 已过滤 | 按类别分组；「找回并转发」；批量（≤20，超过 5 条需重新认证）；拖到「消息」导航即找回（5 秒撤销条） |
| `#/status` · `#/stats` | 总览 · 统计 | 组件网格（新增 push、crypto、web）、7 天堆叠柱图（手写 SVG）、来源明细；读不到的数值显示「无法读取」 |
| `#/sources` | 来源 | 列表、启停、改名图标、新建 / 轮换（凭证 + 登记二维码只显示一次） |
| `#/devices` | 设备 | 权限、最后在线、推送状态（✓✓ 往返时间）、App 版本、锁定状态；「测试推送」；「配对新设备」（二维码 + 手动码，提示「请在 Telegram 或服务器上选出新手机显示的数字」）；吊销 |
| `#/security` | 安全 | 会话与可信浏览器、`mga_` 管理、审计日志（链校验状态）、「退出其他所有会话」 |
| `#/settings` | 设置（只读） | 脱敏配置、保留期、推送、加密状态、确认渠道、版本；说明「修改请编辑 /etc/nekopost/nekopost.env」 |
| 全局 | 重新认证对话框 | 收到 `403 reauth_required` 时弹出：「此操作需要重新验证身份」，两个按钮「重新用 GitHub / Google 登录」与「在 App / Telegram / 服务器上确认」（显示 2 位数字）；完成后自动重试原请求，5 分钟内的后续高危操作不再询问 |
| 全局 | 命令面板与 401 | `Ctrl/⌘+K`、`/`、`j`/`k`、`Enter`、`c`、`e`、`r`、`g i`/`g f`/`g s`、`?`，与 App 外接键盘相同；任何 401 立即停止轮询与 SSE，跳转 `#/login` |

**响应式断点**（与 App 的 `WindowSizeClass` 一致）：< 600 单栏；600–839 列表 / 详情切换；840–1199 双栏；≥ 1200 三栏。**Fold8 上的 Chrome** 使用 Viewport Segments API：`horizontal-viewport-segments: 2` 时列表和详情沿铰链分开，`vertical-viewport-segments: 2`（桌面姿态）时上详情下列表。View Transitions 在 `prefers-reduced-motion` 时取消；WCAG AA；axe-core 在 CI 中扫描。

### 7.4 安全响应头与 CSP（面板路由）

```http
Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:;
  font-src 'self'; connect-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'self';
  frame-ancestors 'none'; require-trusted-types-for 'script'; trusted-types 'none'; upgrade-insecure-requests
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Embedder-Policy: require-corp
Cross-Origin-Resource-Policy: same-origin
Referrer-Policy: no-referrer
Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=(), serial=(), bluetooth=(), clipboard-write=(self)
X-Frame-Options: DENY
X-Content-Type-Options: nosniff
Cache-Control: no-store                         （index.html 与 API）
Strict-Transport-Security: max-age=63072000; includeSubDomains   （现有）
```

不允许任何内联脚本或样式，因此不需要 nonce（M3 中用 Playwright 验证 **CSP 违规数为 0**）。头像不从 GitHub / Google 热链。

### 7.5 渲染不可信内容

- 正文、标题、meta、来源名、设备名**一律作为文本节点**。CI grep 门禁禁止 `innerHTML`、`outerHTML`、`insertAdjacentHTML`、`document.write`、`eval`、`new Function`、`setTimeout("…")`。
- **链接不自动变成可点击**：显示为纯文本 +「复制链接」；含 punycode 或混合书写系统的域名同时显示原样和 punycode 并加 ⚠️；`spam`/`marketing` 类别中完全不识别链接。
- Bidi 控制字符和零宽字符显示为可见标记 `⟨U+202E⟩`；容器 `unicode-bidi: isolate; dir=auto`。
- 每条消息醒目显示**服务器判定的来源**（「📱 Fold8 · 短信」），帮助识别被盗 `mgs_` 伪造的「银行短信」。
- 复制验证码：`navigator.clipboard.writeText`，页面可见时 60 秒后尽力清除。

## 8. 安卓 App

### 8.1 技术栈

| 层 | 选型（版本见 §0.4） | 说明 |
|---|---|---|
| 构建 | Gradle wrapper（`distributionSha256Sum`）+ 版本目录 `gradle/libs.versions.toml`；AGP 9.5.1、Kotlin 2.4.21；JDK 21（仅 CI）；依赖锁 + `verification-metadata.xml`（M1 之后补齐） | **本机不构建**，一切在 GitHub Actions 中完成（§12.2） |
| SDK | `minSdk 30`、`compileSdk 37`（工具链不支持时用 36）、**`targetSdk 36`** | 见 §0.3 #1；lint 的「targetSdk 过旧」警告注明原因后屏蔽 |
| 标识 | `applicationId = com.drinkcat.nekopost`；启动器名称 `Nekopost`；界面中文（简体） | debug 构建加 `.debug` 后缀，可与正式版共存 |
| UI | Compose BOM 2026.09.00、Material 3、`material3-adaptive` 1.3.0（`ListDetailPaneScaffold`、`SupportingPaneScaffold`、`NavigationSuiteScaffold`、`HingePolicy`）、`androidx.window` 1.5.1 | 官方自适应方案 |
| 导航 | Navigation 3 + `ListDetailSceneStrategy` | 回退栈跨折叠保持 |
| 网络 | OkHttp 5 + okhttp-sse + kotlinx.serialization（手写薄客户端，不用 Retrofit） | |
| DPoP（M5） | Nimbus JOSE+JWT + Keystore `Signature("SHA256withECDSA")` | 私钥不离开 Keystore |
| 存储 | Room（outbox、消息缓存）、DataStore（设置）、**Tink** 1.24.0（Keystore 包裹的 AEAD keyset） | `security-crypto` 已弃用，不用 |
| 后台 | WorkManager（expedited + 网络约束 + 周期任务） | 不用前台服务 |
| 生物识别 | `androidx.biometric` | 应用锁；`k_reauth` 的 CryptoObject |
| 推送（M5） | `firebase-messaging`；**不用 google-services 插件**，`FirebaseOptions` 手动初始化（值来自 BuildConfig） | 不引入 Analytics |
| 扫码 | `play-services-code-scanner` | **不需要 CAMERA 权限**；无 GMS 时退回手动输入 |
| 测试 | JUnit、Robolectric 4.18、Roborazzi 1.79.0、Compose UI Test、`window-testing`、`work-testing`、MockWebServer | §10.9、§13 |
| 不使用 | Crashlytics、Analytics、任何广告 / 统计 SDK、WebView、Hilt、Glance | 数据外流和攻击面最小 |

### 8.2 模块结构

- `app/`：Activity、导航、手写 AppContainer、全部界面（登记、向导、转发记录，M5 起收件箱与管理页、值守台、分拣台）、主题。
- `core/`：model（DTO，与服务端共享 JSON fixture）、network（OkHttp、DPoP、证书锁定、错误映射、时钟偏移）、security（Keystore、Tink、应用锁、剪贴板）、data（Room、仓库、同步）、ui（Token、`FoldState`、HingeAware 布局）、enroll（登记链接解析，§3.7.1）。
- `forwarder/`：SmsReceiver、SmsReconciler、NotifListener、规则、去重、UploadWorker——不依赖 `app` 和任何 Compose，转发路径最小、可单测。

### 8.3 安全基线（Manifest 与运行时）

- `android:allowBackup="false"`；`dataExtractionRules` 排除云备份和设备迁移的全部内容。
- 导出组件（以**合并后**的 Manifest 为准，CI 断言导出组件集合等于白名单）：
  - Launcher Activity `exported=true`，**忽略所有 Intent extras 与 data**，永远只打开首页；**不注册任何 deep link**（`nekopost://` 只在 App 内扫码解析，不是 intent filter）；
  - `SmsReceiver`：`android:permission="android.permission.BROADCAST_SMS"`；
  - `NotificationListenerService`：`android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"`；
  - `BootReceiver`（`BOOT_COMPLETED` / `USER_UNLOCKED`）：校验 action，只调度一次补漏扫描；
  - 库自带的接收器（WorkManager、M5 起的 `FirebaseInstanceIdReceiver`）逐个列入白名单并注明来源；其余组件 `exported=false`。
- 通知点击：`PendingIntent`（`FLAG_IMMUTABLE`）只指向未导出的内部 Activity，只携带消息 ID，打开前仍需应用锁。**确认界面只能从 App 内「待确认」列表进入**，任何 Intent 都不能直接打开某个确认。
- `network_security_config`：release 禁止明文、只信任系统 CA；debug 构建只对 `10.0.2.2` 与 `localhost` 允许明文。
- **证书锁定**：服务器主机来自登记二维码，NSC 无法预先写死域名，所以在 OkHttp 中为已登记的主机运行时构建 `CertificatePinner`，锁定 ISRG Root X1、X2 与 Let's Encrypt 已公布的下一代根的 SPKI SHA-256（实现时核对），随 App 版本设 1 年过期（过期后失效开放）；锁定失败时显示可诊断的错误页。
- 敏感界面（详情、验证码、登记、配对、设置）默认 `FLAG_SECURE`，设置中可关闭。确认、配对、轮换、吊销等按钮设置 `filterTouchesWhenObscured`。
- 不申请 `ACCESS_LOCAL_NETWORK`、`READ_CONTACTS`、`QUERY_ALL_PACKAGES`。

### 8.4 短信接收

**接收流程**

1. Manifest 静态注册 `SMS_RECEIVED`；权限 `RECEIVE_SMS`（实时）、`READ_SMS`（补漏）、可选 `READ_PHONE_STATE`（SIM 名称）。
2. `onReceive`：`goAsync()` → `getMessagesFromIntent()` → 按 `originatingAddress + subscriptionId` 合并长短信分段 → 本地规则（发件人 / 关键词的允许或拒绝名单，默认全部转发）→ Tink 加密写 Room outbox → `enqueueUniqueWork("upload", APPEND_OR_REPLACE, expedited)` → `finish()`。全程 < 1 秒。
3. 载荷：`{type:"sms", title:<originatingAddress 原样>, body, priority:"normal", occurred_at:<SMSC 时间，RFC 3339>, meta:{sim:"SIM 1 · docomo"}}`。**不上传通讯录名**；服务端发给 DeepSeek 时把手机号换成 `[手机号]`、短号换成 `[短号]`，字母发件人 ID 保留。
4. **幂等键**：`"sms:" + hex(HMAC-SHA256(device_secret, subId‖addr‖tsMillis‖body))[:32]`，放在 `Idempotency-Key` 头。`device_secret` 是首次启动生成的 32 字节随机数（Tink 加密保存）。**绝不用不带密钥的哈希**。

**补漏扫描（SmsReconciler）**

- 触发：`BOOT_COMPLETED` / `USER_UNLOCKED`、App 打开、WorkManager 每 1 小时（需要网络）。按 `Telephony.Sms.Inbox` 中 `_id > last_seen_id` 查询，用**相同的幂等键**补交，服务端去重。
- **起点必须明确，绝不从 0 开始**：完成登记（或授予短信权限）的那一刻，把 `last_seen_id` 设为当时的 `MAX(_id)` 并记录 `enabled_at`。否则第一次扫描会把几年的短信全部上传。
- 每次补漏加硬性回看上限：`date >= max(now − 72h, enabled_at)`。单次发现超过 50 条时暂停，通知用户确认「补交 N 条」或「全部跳过」。
- 覆盖：首次解锁前收到的短信、App 被强行停止或被 Samsung 深度休眠时的短信、**被 Android 17 延迟 3 小时的 WebOTP 格式短信**。

**Android 17 OTP 保护：检测与标注**

- 服务端（M2 起）比较 `received_at − occurred_at`：> 10 分钟标 `late='late'`；在 2 小时 50 分到 3 小时 15 分之间且 `has_code` 标 `late='otp_hold'`（「系统延迟（OTP 保护）」）。
- **「OTP 短信是否被系统延迟？」状态卡**（转发页常驻）：统计近 7 天的验证码短信「实时广播到达」与「仅由补漏扫描发现」的数量。全部实时 → ✅「未发现系统延迟」；否则 ⚠️ 并解释原因。
- **自测**：向导中说明从另一台手机发 `【测试】您的验证码是 123456`，App 记录到达时间并给出结论。M1 第一项验收就是这一步。
- **不实现** CompanionDeviceManager「伴侣保护」开关；默认短信应用路线也不在 V2 范围内。

**RCS**：RCS 聊天（日本的 +メッセージ）不经过 `SMS_RECEIVED`，也没有公开 API。设置页说明可以把短信应用加入通知转发作为补充；同一内容 2 分钟内已作为短信捕获的，通知路径跳过。

### 8.5 通知监听

- **默认不转发任何应用**，用户逐个勾选。应用列表通过 `<queries>` 声明 `MAIN/LAUNCHER` 获取。
- **禁止选择 Telegram 系应用**和本 App（会形成回环）。
- 过滤：跳过 ongoing、前台服务、group summary、`category ∈ {progress, transport, call, service}`、媒体通知。内容取 `EXTRA_TITLE`、`EXTRA_BIG_TEXT` / `EXTRA_TEXT`；`MessagingStyle` 只取最新一条。
- 去重：`(sbn.key, hash(title, text))` 10 分钟内只转发一次；每应用 30 条 / 分钟，超出合并为一条。
- 载荷：`type:"notification"`、`title:"<应用名> · <通知标题>"`、`meta:{pkg, channel}`；`Idempotency-Key` 为写入 outbox 时生成的随机 UUID。
- **系统打码（Android 15+）**：识别到占位文字或空文本时，默认转发 `meta.redacted=true` 的消息（正文「系统隐藏了验证码内容」）；可改为「跳过并计数」。**短信验证码走短信通道，不依赖通知通道。**
- 每应用规则：全部 / 只含关键词 / 排除关键词。监听断开时 `requestRebind`，诊断页显示连接状态。

### 8.6 首次启动向导（侧载限制）

每一步在 `onResume` 时实时检测并打 ✅ / ⚠️；「转发健康度」卡片常驻首页。

0. **安装前**（写在 Release 说明中）：允许浏览器或「我的文件」安装未知应用；暂时关闭 Samsung **Auto Blocker**；未验证开发者提示的处理见 §12.4。
1. **登记**：扫描服务器上 `nekopost source add <id> --qr` 打印的二维码（§3.7.1），在确认页核对主机名后开始转发。M5 起增加「配对」入口。
2. **允许受限设置**：侧载应用的通知使用权和短信权限属于受限设置。跳到「应用信息」，指引「⋮ → 允许受限设置」。
3. **短信权限**：`RECEIVE_SMS`、`READ_SMS`。
4. **通知使用权**：`ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` 直达本 App 开关。
5. **通知权限**：`POST_NOTIFICATIONS`。
6. **电池**：`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；指引 One UI 把本 App 加入「从不休眠的应用」，关闭「暂停未使用应用的活动」。
7. **One UI 显示设置**：「在封面屏上继续使用应用」中为本 App 选「始终」；Labs 中关闭本 App 的 Flex 模式面板。
8. **（可选，高级）读取被打码的通知验证码**：`adb shell cmd appops set --user 0 com.drinkcat.nekopost RECEIVE_SENSITIVE_NOTIFICATIONS allow`。
9. **OTP 自测**（§8.4）。

### 8.7 离线队列与重试

**outbox（Room）**：`id, created_at, idem_key, kind, payload_ct (Tink AEAD, AAD=id), state(pending|failed_permanent), attempts, next_at, last_error, last_http`。**先持久化，再联网**。

| 结果 | 处理 |
|---|---|
| 202 / 200（重复） | **删除整行**；`idem_key` 摘要保留 7 天用于本地去重 |
| 401 `use_dpop_nonce`（M5） | 带 nonce 重签一次 |
| 401 `invalid_dpop_proof`（M5） | 校正时钟偏移后重试一次；再失败则暂停并提示 |
| 401 `unauthorized` / `device_revoked` | 停止转发、保留队列（**不丢数据**），提示重新登记或配对 |
| 403 `source_disabled` | 暂停转发并提示 |
| 413 / 415 / 422 | `failed_permanent`，保留 7 天供查看或手动删除（415 说明是 App 的 bug） |
| 429（含 `too_many_auth_failures`）/ 503 | 遵守 `Retry-After` |
| 网络错误 / 其他 5xx | `Result.retry()`，指数退避 30 秒起，上限 30 分钟 |

- 网络恢复和 App 启动都会触发 flush；expedited 配额不足时退化为普通任务。
- 容量：5000 条或 30 天，超出丢弃最旧并告警；有条目滞留超过 30 分钟时发一条低优先级通知「有 N 条消息未能转发」。
- 「转发记录」页显示最近 50 条的状态（只显示元数据）。

### 8.8 接收与通知（M5 起）

- `onMessageReceived`：`t:"msg"` → 用 `k_dev` `GET /v1/inbox/{id}`，Tink 加密写入缓存，弹通知；超时或离线则先发通用通知「Nekopost：N 条新消息」，打开 App 后拉取并原地更新同一条通知。
- 通知渠道：`验证码`（高，有声）、`消息`、`系统告警`、`确认`（高）、`安全`（高）。**没有前台服务渠道**。
- **通知文本默认只显示「发件人 · 类别」**，不显示 preview 和正文（其他通知监听器、手表镜像都能读到本地通知）；可按类别开启「通知显示摘要」（经过 §4.4 打码）。所有通知 `setLocalOnly(true)`。
- 锁屏：`VISIBILITY_PRIVATE` + `publicVersion`（「Nekopost · 1 条验证码」）。
- 通知操作：「复制验证码」（`setAuthenticationRequired(true)`）、「标为已读」。其他端标已读后收到 `t:"sync"`，取消对应通知。
- 前台连接 `/v1/events`，进入后台 5 秒后断开。

**电量**：没有常驻前台服务、不轮询；短信靠系统广播，通知监听由系统绑定，接收靠 FCM，上传和补漏靠 WorkManager；低优先级消息用 `NORMAL` FCM；值守台常亮只在 Tabletop + 前台 + 充电（或显式开启）时生效，电量 < 20% 自动关闭。

### 8.9 安全存储与应用锁

| 资产 | Keystore 密钥 | 参数 | 理由 |
|---|---|---|---|
| `mgs_` / `mgd_` 凭证字符串、`device_secret`、outbox、**消息缓存**（标题、preview、正文、meta、otp） | `ks_main`（AES-256-GCM，包裹 Tink AEAD keyset） | 无需用户验证；`setUnlockedDeviceRequired(false)`；优先 StrongBox | 锁屏状态下也必须能转发短信、写入推送来的消息 |
| DPoP 签名（M5） | `k_dev`（EC P-256） | 同上 | 每个 API 请求（§3.6） |
| 重新认证签名（M5） | `k_reauth`（EC P-256） | `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`、`setInvalidatedByBiometricEnrollment(true)` | 高危操作与 App 内确认，TEE 强制生物识别 |

- **Room 中只有这些字段是明文**：`id`、`seq`、时间、类别、来源 ID、状态、`has_code`、`body_expires_at`。其余内容一律经 Tink AEAD 加密（AAD = 行 ID）。
- **保护边界（如实说明）**：Keystore 密钥不可导出，拿到 Room 文件的人（备份、文件提取）解不开；但 root 或代码注入后可以在设备上直接调用 `ks_main` 解密。应用锁只挡「拿到已解锁手机的人」。
- **应用锁**：冷启动、无交互超过 N 分钟（默认 1 分钟，可选「立即」）后，用 `BiometricPrompt(BIOMETRIC_STRONG | DEVICE_CREDENTIAL)` 解锁界面；按**无交互**计时（常亮的值守台也会上锁）；折叠时可选「合上即锁」（§10）。
- **本地保留期自己执行**：启动时、每日任务、每次显示前都检查 `body_expires_at` 与 `code_retention_hours`（来自 `/v1/session`），到期即删；`sync` 中 `body_state` 变为 `expired`/`deleted` 时也立即删除。Room 执行 `PRAGMA secure_delete=ON`。
- **离线兜底**：超过 72 小时没有成功 sync，清空全部正文缓存，只保留元数据。
- 剪贴板：复制验证码时设置 `ClipDescription.EXTRA_IS_SENSITIVE`；60 秒后如果仍是我们复制的内容就清除；优先引导拖放（§10）。
- 解除配对或确认被吊销时：删除数据库 + 删除所有 Keystore 别名 + 清空 DataStore。

## 9. 视觉设计系统

### 9.1 品牌个性

**「靠谱的猫邮差」**：认真、准时，偶尔调皮；界面气质像一间安静的夜班邮局，可爱只用来点缀。启动器名称为「Nekopost」，界面内可以用「猫邮局」作为中文品牌名。文案示例：「已送达 Telegram」（平实，直接用 `model.Status.Label()`）；「猫邮差在打盹。新消息一到，它会第一个跳起来。」（空收件箱，轻松）；「邮路暂时中断。消息仍在手机上排队，恢复后自动补寄。」（平静，给事实和结果）；「吊销后，『旧 iPhone』上的凭证会立即失效，无法撤销。」（严肃）。错误、安全、危险操作的文案**一律不卖萌、不用 emoji**；「结果不确定」必须显示为不确定。

### 9.2 Token 流水线

M0 / M1 的 App 主题先在 Kotlin 中手写（取 §9.3 的值）。M3 引入 Web 后建立单一来源 `design/tokens.json`（种子色、语义色、圆角、间距、字号、动效、断点），由 `go run ./tools/tokens` 生成 `web/css/tokens.css` 与 `android/core/.../ui/token/NekoTokens.kt`；生成文件提交到仓库，CI 重新生成并 `git diff --exit-code`，同时校验对比度（正文 ≥ 4.5:1，大字和图标 ≥ 3:1）。

### 9.3 颜色

品牌种子色：Primary「橘猫橙」`#E07A2E`；Secondary「邮政墨蓝」`#2F4A6B`；Tertiary「猫薄荷绿」`#3E8E6A`；Neutral「信纸」`#8A7B72`。色调表由 material-color-utilities（HCT，`SchemeTonalSpot`）生成。

| 角色 Token | Light | Dark（「夜班邮局」） |
|---|---|---|
| `primary` / `onPrimary` | `#9C4511` / `#FFFFFF` | `#FFB68B` / `#552000` |
| `primaryContainer` / `on…` | `#FFDBC8` / `#341000` | `#783200` / `#FFDBC8` |
| `secondary` / `secondaryContainer` | `#3A5F8C` / `#D3E4FF` | `#A3C9FF` / `#20476F` |
| `tertiary` / `tertiaryContainer` | `#23694C` / `#A9F2C9` | `#8DD5AE` / `#005236` |
| `error` / `errorContainer` | `#BA1A1A` / `#FFDAD6` | `#FFB4AB` / `#93000A` |
| `surface`（信纸） | `#FFF8F5` | `#17120F` |
| `surfaceContainerLow / … / Highest` | `#FCF1EB` / `#F6ECE6` / `#F0E6E0` / `#EAE0DA` | `#1F1915` / `#241D19` / `#2F2723` / `#3A322D` |
| `onSurface` / `onSurfaceVariant` | `#221A15` / `#52443C` | `#EFE0D8` / `#D7C2B8` |
| `outline` / `outlineVariant` | `#85736A` / `#D7C2B8` | `#A08D83` / `#52443C` |

- 默认跟随系统，深色下主色只用于按钮、选中指示和验证码。可选「纯黑」（OLED）。**动态取色默认关闭**。

**状态语义色**（固定；每个状态都同时有图标 + 文字 + 颜色）：`delivered` 猫薄荷绿（`#1E6E43`/`#B6F1CB`，深 `#7FD9A3`/`#00522F`，爪印对勾）；`queued`/`sending`/`classifying` 墨蓝（secondary 系，信封）；`retrying`/`partial` 琥珀（`#7A5900`/`#FFDF9E`，深 `#F5BF48`/`#5C4200`，循环箭头）；`uncertain` 薰衣草（`#6B4EA2`/`#EBDDFF`，深 `#D3BBFF`/`#523787`，问号信封）；`failed` 错误红（断开的信封）；`filtered`/`not_routed` 中性（纸篓 / 横线）。

**类别标签**：`code` 验证码 = **实心 primary**（全应用最醒目）；`finance` = secondaryContainer；`logistics` = tertiaryContainer；`personal` = 玫瑰（`#FFD9E2` / 深 `#7E2949`）；`service` = surfaceContainerHigh；`roaming` = 靛蓝（`#DFE0FF` / 深 `#3A3F8F`）；`marketing` = 描边；`spam` = errorContainer + 警示图标。

### 9.4 字体排印（CJK 优先）

- 中文正文与界面：Android 用系统默认（尊重 One UI 用户字体）；Web 用 `system-ui, "PingFang SC", "HarmonyOS Sans SC", "Noto Sans CJK SC", "Hiragino Sans", "Microsoft YaHei", sans-serif`。不内置完整 CJK 字体。
- 数字、验证码、ID：JetBrains Mono 子集（约 20 KB，OFL），两端共用；统计数字用等宽数字（`tnum`）。
- 字号 / 行高 / 字重：验证码大字 36/44 Mono 500（字间距 +0.12em，每 3 位分组）；headlineSmall 24/32 500；列表发件人 16/24 600；正文 16/26 400；摘要 14/22 400（最多 2 行）；标签与时间 12/16 500。中文不加字间距、不用斜体。全部 `sp`，**200% 字号下不截断关键操作**。

### 9.5 形状、动效、图标、猫元素

- **形状**：列表项 12dp、卡片 16dp、对话框和 Sheet 28dp、主按钮和 FAB 胶囊。来源头像放在 secondaryContainer 底色的圆形容器里。
- **动效**：空间运动用 `MotionScheme.expressive()` 的弹簧；新消息从顶部「投递口」滑入（约 350 ms）。系统「移除动画」或 `prefers-reduced-motion` 时全部改为 100 ms 淡入淡出，猫动画换成静态图。
- **图标**：Material Symbols Rounded 子集 + 一套自绘猫图标（猫邮差、爪印对勾、纸篓猫、睡猫、邮筒、守门猫等）。应用图标：橘猫侧脸叼着一封信，提供单色版。
- **猫元素（有节制）**：收件箱为空时睡猫趴在邮筒上；下拉刷新时猫爪伸下来；当前查看的消息投递成功时盖爪印章；离线时睡猫 +「邮路中断 · 已在本机排队 N 条」；「关于」页连点猫 7 次打开折叠屏调试面板。**数据表、统计图、安全与危险对话框中不出现猫。**

### 9.6 无障碍

触控目标 ≥ 48dp（外屏复制按钮 ≥ 64dp）；TalkBack 读「已送达 Telegram，3 分钟前」，验证码逐位朗读；**所有拖放都有等价的自定义无障碍操作和可见按钮**；键盘快捷键 Web 与 App 相同；颜色从不单独表达含义。

## 10. 折叠屏（Galaxy Z Fold8）交互设计

### 10.1 设备事实（以真机校准为准）

目标机型是 **Galaxy Z Fold8（2026 年的新宽机型）**，Android 17 / One UI 9：外屏 5.5″（10:16），内屏 7.6″（4:3），展开后**横向是自然方向**，正常握持时铰链竖直。

按约 2.625 的默认密度（420 dpi）估算（「屏幕缩放」会改变数值）：

| 显示状态 | dp（约） | 宽度级别 | 用途 |
|---|---|---|---|
| 外屏竖持 | 475×751 | Compact | 单栏 + 底部导航 |
| 外屏横持 | 751×475 | Medium（高度 Compact） | 导航栏 + 双栏 |
| 内屏自然方向（横） | 933×704 | **Expanded** | 导航栏 + 列表 + 详情（+ 浮动第三栏） |
| 内屏旋转 90°（竖） | 704×933 | Medium | 双栏；**竖持半折时即桌面姿态** |

- 内屏最小宽度 ≥ 600dp，属于「大屏」：Android 16/17 会忽略方向锁和宽高比限制。Nekopost **从第一天起完全可缩放，不锁方向**。
- **代码中绝不出现设备型号或像素尺寸**：一切由 `WindowSizeClass` 和 `FoldingFeature` 推导，因此在其他折叠屏（包括竖长内屏机型）、平板和普通手机上自动走通用路径（§10.8）。

### 10.2 姿态模型：`FoldState`，作为参数注入

```kotlin
// core/ui/fold/FoldState.kt —— 唯一的姿态来源（WindowInfoTracker + currentWindowAdaptiveInfoV2，防抖 150 ms）
data class FoldState(val posture: Posture, val hinge: Rect? = null, val hingeAngle: Float? = null, val isSeparating: Boolean = false)
enum class Posture { Cover, Flat, Tabletop, Book, Window }
// Tabletop = HALF_OPENED && HORIZONTAL；Book = HALF_OPENED && VERTICAL；Flat = FLAT
// Cover = 无 FoldingFeature && 折叠屏设备（supportedPostures 非空）&& 宽度 < Expanded；Window = 其他
@Composable fun InboxScreen(state: InboxUiState, fold: FoldState, actions: InboxActions) { … }
```

- **所有屏幕都把 `FoldState` 当作普通参数**，由顶层 `rememberFoldState()` 计算后向下传；预览和 Roborazzi 可以注入任意姿态。
- **铰链安全规则**：`isSeparating == true` 时，任何可点控件和文本都不跨越铰链，铰链 ±24dp 内不放可点元素（`HingeAwareSplit` 组件强制执行）；完全展开时内容可跨中线，但**验证码大卡和二维码仍避开中线**（折痕反光）。
- 窗口尺寸优先于屏幕尺寸：分屏时可能只有约 466dp 宽，按 Compact 处理。

### 10.3 各姿态的布局

**A. 外屏（Cover）——「一瞥」**

```
┌───────────────────────┐  ~475×751dp
│ 🟢 正常 · 队列 0     ⌕ │  ← 状态胶囊 + 搜索
├───────────────────────┤
│ ┌───────────────────┐ │
│ │ 招商银行   10:20    │ │  ← 5 分钟内的验证码置顶为「验证码卡」
│ │   123  456         │ │     应用锁未解开时只显示「1 条验证码 · 解锁查看」
│ │ [ 复制 ]  4:12 后过期│ │
│ └───────────────────┘ │
│ 📱 顺丰 · 快递      ●  │  ← 列表项 72dp，摘要 1 行
├───────────────────────┤
│ 💌  🗑³  📊  📨  ⚙️     │  ← NavigationSuiteScaffold 自动选择底部导航
└───────────────────────┘
```

M1 的转发器在外屏显示：转发健康度、OTP 状态卡、最近转发记录、待发队列数。

**B. 内屏展开（Flat）——「工作台」**：导航栏（收起）+ 列表 40% ┊ 可拖动分栏手柄 ┊ 详情 60%。`paneExpansionDragHandle` 锚点 30% / 50% / 70% /「列表收起」；第三栏「投递详情」在 ≥ 1200dp 时固定显示，在 Fold8 内屏上点「ⓘ」以 `AdaptStrategy.Levitate` 浮层打开；内屏竖持（Medium）保留双栏。

**C. 桌面姿态（Tabletop，铰链水平）——上面看，下面按**。Fold8 要把内屏转成竖向再半折才会进入此姿态。

| 当前页面 | 上半屏（只看不点） | 下半屏（操作区） |
|---|---|---|
| 消息 | 选中消息大字详情；没选中时显示实时消息流 | 紧凑列表、上一条 / 下一条、复制、搜索 |
| 状态 | **值守台**（§10.4 ④） | 时间范围、暂停、常亮开关、今日数字、「翻牌」 |
| 已过滤 | **分拣台**大卡片（⑤） | 找回 / 确实是广告 / 下一条 |
| 配对 | 配对二维码（⑬，按住才显示） | 「按住显示」按钮、倒计时、3 个候选数字、取消 |

**D. 书本姿态（Book，铰链竖直）**：与 Flat 双栏相同，但分栏手柄**吸附到铰链并锁定**（⑨）；每页主操作放在该页靠外侧下方。

### 10.4 交互清单

优先级：P0 首版必做；P1 第二批；P2 有余力再做。每一项都有非折叠设备上的等价路径。

| # | 名称 | 姿态 | 交互 | 实现要点 | 优先级 |
|---|---|---|---|---|---|
| ① | **展开即达** | Cover → Flat | 外屏上打开的消息，展开后**零点击**出现在详情栏；列表滚动到该条并高亮呼吸 400 ms | `focusedMessageId: StateFlow` 在 ViewModel；按 key `animateScrollToItem` | P0 |
| ② | **外屏验证码卡一触复制** | Cover | 验证码卡置顶 5 分钟或直到被复制；点任意处复制 + 触感，卡片翻面「已复制 ✓ · 60 秒后清除」 | `EXTRA_IS_SENSITIVE`；只在设备已解锁**且应用锁已解开**时显示明文 | P0 |
| ③ | **铰链安全布局 + 三栏** | Flat / Book / Tabletop | 见 §10.2、§10.3 | `HingePolicy.AvoidSeparating`；`extraPane` + Levitate | P0 |
| ④ | **桌面值守台** | Tabletop | 上半屏：健康环 + 实时消息传送带 +「投递心电图」（每次投递成功跳一个脉冲，失败显示红色平线）；**验证码到达时只翻出「验证码已到达 · 招商银行」信封牌，不显示数字**，在下半屏轻触「翻牌」并通过生物识别后显示 30 秒再自动翻回。下半屏：时间范围、暂停、「保持常亮」、今日数字。**受限模式**：离开值守台、打开详情、搜索或执行任何操作都要先通过生物识别 | `keepScreenOn` 仅 Tabletop + 前台 + 充电或显式开启；常亮不阻止应用锁（按无交互计时）；`graphicsLayer { rotationY }` | P1 |
| ⑤ | **分拣台**（已过滤审阅） | Tabletop / Book | 一张大卡片（发件人、类别、原文、过滤理由）+ 三个 ≥ 72dp 的大按钮：`↩ 找回并转发` / `✓ 确实是广告` / `→ 下一条`；右滑找回、左滑确认 | `AnchoredDraggable`；找回走 `admin:write`（非高危） | P1 |
| ⑥ | **合上即遮挡** | Flat → Cover | 在内屏看内容时合上：外屏的详情和列表都被「信纸帘」遮住（模糊 + 守门猫 +「轻点显示」），轻点需生物识别；可按**来源或消息类型**豁免（不按 AI 类别）；也可设为「合上即锁 App」；未完成的危险确认自动取消 | Flat→Cover 时 `privacyCurtain=true`；`setRecentsScreenshotEnabled(false)` | P1 |
| ⑦ | **拖放找回** | Flat / Book | 已过滤页把收件箱放在窄栏，拖动时变成「投递口」放置区；松手后 **5 秒撤销条**，到时才调用接口 | 应用内拖放，自定义 MIME，**不加** `DRAG_FLAG_GLOBAL`；每张卡片同时有按钮和无障碍操作 | P1 |
| ⑧ | **把验证码拖进另一个 App** | 分屏 | 长按验证码「拿起」一个只含数字的胶囊，拖到对方输入框，不经过剪贴板 | `DRAG_FLAG_GLOBAL` + `EXTRA_IS_SENSITIVE`；**只能拖出验证码**；应用锁解开时才可拖 | P1 |
| ⑨ | **铰链吸附分栏手柄** | Flat → Book | 进入 Book 时手柄吸附到铰链并锁定；双击在 40/60 与 60/40 间切换 | `PaneExpansionAnchor.Offset(hinge)` | P1 |
| ⑩ | **同屏姿态过渡** | Flat ↔ Tabletop | 选中的消息卡从详情栏「升」到上半屏，列表「沉」到下半屏 | `SharedTransitionLayout` + `sharedBounds` | P1 |
| ⑪ | **在旁边打开** | Flat / 分屏 | 详情页「在新窗口打开」，左右对比两条消息 | `FLAG_ACTIVITY_LAUNCH_ADJACENT` | P1 |
| ⑫ | **开信封** | 展开过程 | 有未读时慢慢打开手机，铰链角度驱动信封翻盖，信纸滑出最新未读标题 | `TYPE_HINGE_ANGLE`；无传感器时播一次固定动画；可关闭 | P2 |
| ⑬ | **桌面配对台** | Tabletop | 用 Fold8 给旧手机配对：上半屏显示大号二维码，**只在按住下半屏「显示」按钮时出现，30 秒换码**；旧手机领取后，下半屏出现 3 个候选数字，点出旧手机上显示的那个并通过生物识别（§3.7.2） | 临时最高亮度，离开恢复；`FLAG_SECURE` | P2 |
| ⑭ | **拖入发送（发给自己）** | 任意 | 把其他 App 的文字拖到「发给自己」区域，生成 `type:"note"` 消息，经 Telegram 和推送到其他设备 | 只接受 `text/plain`，≤ 12000 字，按不可信数据处理；**默认关闭** | P2 |
| ⑮ | **书本翻页** | Book | 右页边缘左滑下一条、右滑上一条 | `HorizontalPager`；减弱动效时无动画 | P2 |
| ⑯ | **姿态切换触感** | 进入 Tabletop / Book | 一次轻触感确认布局已切换 | `SEGMENT_TICK` | P2 |

### 10.5 键盘、鼠标与命令面板

`Ctrl/⌘+K` 命令面板；`/` 或 `Ctrl+F` 搜索；`j`/`k` 下一条 / 上一条；`Enter`/`Esc`；`c` 复制验证码；`e` 标为已读；`r` 找回（出现撤销条）；`g i`/`g f`/`g s` 跳转；`?` 帮助。与 Web 相同；App 在 `onProvideKeyboardShortcuts` 中注册；鼠标悬停显示行内操作，右键弹出上下文菜单。

### 10.6 连续性的实现约定

- 主 Activity 声明 `configChanges="screenSize|smallestScreenSize|screenLayout|orientation|density|keyboard|keyboardHidden|uiMode"`，折叠 / 展开 / 旋转时不重建，Compose 原地重组。测试中仍覆盖 Activity 被重建的情况。
- 必须跨姿态保留：选中消息 ID、各栏滚动位置（以 key 定位）、搜索词、筛选、`PaneExpansionState`、回退栈、未提交的输入。进程被杀后 `SavedStateHandle` **只保存 ID，不保存正文**。
- One UI 默认合上时关闭应用；需要在「在封面屏上继续使用应用」中选「始终」（向导第 7 步）。

### 10.7 Samsung / One UI 专项与调试面板

- **Flex 模式面板**：我们原生支持桌面姿态，向导提示为 Nekopost 关闭它。
- **多任务**：分屏和弹出窗口一律按窗口尺寸布局；所有布局处理 `WindowInsets.safeDrawing`（任务栏占底部 insets）。
- **后置屏 / 双屏（WindowAreaController）**：Galaxy 上能力未确认，V2 不做依赖它的功能。
- **折叠屏调试面板**（关于页连点猫 7 次；仅 debug 构建或开启开发者选项时）：实时显示 `WindowMetrics`、density、`WindowSizeClass`、`FoldingFeature`、`supportedPostures`、推导出的 `Posture`、铰链角度；「复制为 spec 字符串」用于校准 §10.9 的截图矩阵（不含任何秘密）。

### 10.8 降级与不做的想法

**降级**（没有 `FoldingFeature` 时折叠专属入口自动隐藏或转为普通路径）：① 在旋转或调整窗口时同样保持；④ 值守台在普通手机 / 平板上是状态页的「全屏值守台」入口；⑤ 分拣台变为全屏卡片分拣（桌面窗口支持 `←` `→`）；⑥ 在普通手机上变为「切后台返回时遮挡」（可选）；⑦ 拖放找回在普通手机上是长按菜单；⑨ 铰链吸附在平板上只有比例锚点。

**不做**：半折作为「确认危险操作」的手势（不可发现、易误触、CI 测不了；危险操作统一为「Sheet 写明后果 → 长按 1.5 秒 → BiometricPrompt」）；半开偷看验证码；外屏出示配对码（WindowAreaController 在 Galaxy 上未确认，改为 ⑬）；书本模式自动分页；拖到来源上静音；品牌手写字体；**Glance 小组件**（外屏本身能运行完整 App）。

### 10.9 没有真机时如何验证

**设备矩阵**（Compose 预览与 Roborazzi 共用，dp 待真机校准）：

`Fold8Cover`（`w475dp-h751dp-port-420dpi`，Cover）· `Fold8CoverLand`（`w751dp-h475dp-land-420dpi`，Cover）· `Fold8InnerFlat`（`w933dp-h704dp-land-420dpi`，Flat）· `Fold8InnerPortrait`（`w704dp-h933dp-port-420dpi`，Flat）· `Fold8Book`（`w933dp-h704dp-land-420dpi`，Book：竖直铰链 HALF_OPENED）· `Fold8Tabletop`（`w704dp-h933dp-port-420dpi`，Tabletop：水平铰链 HALF_OPENED）· `SplitHalf`（`w466dp-h704dp`）· `Phone`（`w411dp-h891dp`）· `Tablet`（`w1280dp-h800dp`），后三者为 Window。

- **主力方案**：Robolectric 设置尺寸和密度，把 `FoldState` 作为参数注入，`captureRoboImage`。矩阵 = 上述设备 × {浅色, 深色} × {1.0, 2.0 字号} × 关键页面，zh-rCN。M0 / M1 只覆盖已有页面（外壳、登记、向导、转发记录）。
- **推导逻辑**：`window-testing` 的 `FoldingFeature(...)` + `WindowLayoutInfoPublisherRule`，断言 `rememberFoldState()` 推出正确姿态；该 Rule 在 Robolectric 下不生效就移到模拟器测试。
- **交互测试（JVM）**：连续性（`StateRestorationTester` + 切换注入的 `FoldState`）；拖放（customActions 等价、5 秒撤销）；遮挡（Flat → Cover 时 `privacyCurtain`）；铰链避让（遍历语义树，断言没有可点节点与铰链 ±24dp 相交）。
- **模拟器（CI 夜间，M5 起）**：可折叠 AVD（API 36 与 37），`adb emu fold/unfold/posture` 切换姿态。
- **真机验收清单**（每个 App 版本约 10 分钟）：① 外屏收到验证码点卡片 → 已复制 + 触感，60 秒后剪贴板被清；② 外屏打开消息后展开 → 内屏直接显示同一条详情并定位高亮；③ 内屏看消息时合上 → 外屏显示遮挡帘，轻点需生物识别；④ 内屏转竖向半折立在桌上 → 询问进入值守台，铰链附近没有按钮，验证码只显示信封牌、翻牌需生物识别；⑤ 自然方向半折 → 分栏手柄吸附铰链；⑥ 已过滤页拖一条到投递口 → 5 秒撤销条，撤销后不转发；⑦ 分屏把验证码拖进浏览器 → 输入框里只有数字；⑧ 最大字号 + 深色、TalkBack 下完成一次找回 → 无截断、可通过自定义操作完成；⑨ 调试面板「复制 spec」→ 贴回后更新截图矩阵。

## 11. 安全威胁模型与防护

### 11.1 资产与攻击者

**资产**：最高——短信与通知正文（能读正文就能接管其他账号）；高——设备私钥、KEK、Bot Token、FCM 服务账号、OAuth client secret、APK 签名密钥、管理能力；中——元数据、审计日志；另外是可用性（转发不中断）。

**攻击者**：A1 互联网匿名者（扫描、猜测、DoS）· A2 **任何能给你发短信或推送通知的人**（内容完全可控：XSS、钓鱼链接、Bidi、超长内容、提示词注入）· A3 持有某个 `mgs_` 的人 · A4 拿到你手机的人（锁定 / 已解锁 / 胁迫）· A5 浏览器侧（恶意网站、扩展、偷 Cookie 的木马）· A6 GitHub / Google 账号被盗 · A7 网络中间人 · A8 服务器侧（非 root / root 被攻破、备份泄露）· A9 供应链 · A10 诚实但好奇的第三方（Google、GitHub、Telegram、DeepSeek）· A11 手机上的恶意 App · A12 GitHub 账号或 CI 被攻破 · A13 持有已吊销或旧凭证的人 · A14 自己配错。

### 11.2 攻击与缓解

| # | 攻击 | 攻击者 | 缓解 |
|---|---|---|---|
| 1 | **恶意短信内容 XSS** | A2 | Web 只创建文本节点；CI 门禁禁止 `innerHTML` 类 API；CSP `script-src 'self'`、无内联；**Trusted Types `'none'`**；链接不自动可点；App 用 Compose `Text`，没有 WebView，禁止 `Html.fromHtml`；Telegram 渲染保持 V1 的转义。约 200 条 XSS 语料作为短信提交，Playwright 断言 DOM 无新元素、无 CSP 违规 |
| 2 | 来源名、设备名、配对上报字段中的存储型 XSS | A1、A3 | 同上；配对字段 ≤64 字符并去控制字符 |
| 3 | 钓鱼链接 / Bidi / 同形字 | A2 | 链接只能复制，显示 punycode 并加 ⚠️；控制字符可视化；醒目显示服务器判定的来源 |
| 4 | 提示词注入 / 漏识别的验证码 | A2 | AI 只输出枚举类别；**所有安全控制都不依赖 AI 类别**；验证码本地识别并在发给 AI 前遮盖；发件人号码换成类型标记 |
| 5 | 超大内容 / 资源耗尽 | A1–A3 | V1 的大小限制；搜索预算与并发 1；SSE 上限；§4.6 限流 |
| 6 | CSRF / 客户端路径穿越 | A2、A5 | `SameSite=Strict`；`CrossOriginProtection`；CSRF 头；只接受 JSON；不开 CORS；带类型的端点构造函数 + 严格 ID 正则；服务端拒绝 `%2F` / `..`（§3.5） |
| 7 | 点击劫持 | A5、A11 | `frame-ancestors 'none'`；App 关键按钮 `filterTouchesWhenObscured` |
| 8 | OAuth 攻击（回调篡改、授权码注入、mix-up、开放重定向、email 冒充） | A1、A6 | 精确回调 + 关闭通配；每 IdP 独立回调；PKCE + state（+ nonce）；flow 一次性；只认数字 `id` / `sub`；空白名单拒绝一切；不保存第三方 token |
| 9 | **会话窃取 + 高危操作** | A5 | `__Host-`、HttpOnly、Secure；登录与激活时换 ID；高危操作要求 5 分钟内重新认证（重新 OAuth 需要攻击者同时控制 IdP 登录态；号码匹配需要确认端配合）；每个高危操作执行后发 Telegram 提醒；会话列表逐个吊销。**精简版残余风险**：偷到 Cookie 的人若同时控制 Telegram，可以自行完成重新认证并创建新凭证。缓解：`NEKOPOST_APPROVAL_CHANNELS=app,cli`；凭证创建的提醒发到所有渠道；紧急锁定 |
| 10 | **IdP 账号被盗 / 确认疲劳** | A6 | 新浏览器必须确认，号码匹配，选错即拒绝并冷却；1 小时内 2 次拒绝自动暂停该身份并告警；「信任此浏览器」由确认方决定；可信浏览器登录也发提醒；pending 会话权限极小 |
| 11 | **实时中继钓鱼** | A6 + 钓鱼 | **确认防不住**（如实说明）。缓解：只从书签进入；确认消息显示请求方 IP 与浏览器族名。Passkey 可在 V2 之后加入 |
| 12 | 暴力破解 | A1 | 无密码登录；配对码 128 位 / 5 分钟 / 一次性；号码匹配猜错即作废；FailGuard（先验凭证、按 IP 前缀 + 类别计数）+ 限流 |
| 13 | **设备被盗（锁定）** | A4 | 锁屏只显示 `publicVersion`；应用锁；高危操作需要 `k_reauth` 生物识别；远程吊销后 App 联网确认即自擦除；离线 72 小时清空正文缓存 |
| 14 | **设备被 root / App 被注入** | A4、A11 | **精简版不防**（如实说明）：`k_dev` 和 `ks_main` 不需要用户验证，可以读到该设备 scopes 内的正文与本地缓存；但私钥仍不可导出，攻击必须在设备上实时进行；高危操作仍需 TEE 强制的生物识别。补救：Bot「🚨 紧急锁定」把所有设备降为只发送 |
| 15 | 设备已解锁被拿走 / 胁迫 | A4 | 应用锁按无交互计时；值守台为受限模式；合上即遮挡；新录入指纹使 `k_reauth` 失效，重登记需 Telegram / CLI 号码匹配并告警 |
| 16 | 凭证重放 | A7、A13 | 设备凭证 DPoP 绑定（`htm`/`htu`/`iat`/`ath`/`jti`）；吊销实时；已吊销凭证被用时告警。`mgs_` 仍是静态 bearer → 只能发送；建议每台设备独立来源、定期轮换 |
| 17 | 中间人 / 误签证书 | A7 | TLS 1.2+、HSTS；App 锁定 ISRG 根 + 备份根（1 年过期）；不信任用户 CA |
| 18 | **钓鱼登记 / 配对二维码；偷拍抢先领取** | A1 | 登记确认页醒目显示目标主机，用户确认后才保存；App 严格校验链接格式（§3.7.1）；配对需 Telegram / CLI 号码匹配；**争用检测**锁死被抢领的配对并告警；桌面配对台按住才显示、30 秒换码 |
| 19 | 恶意 App 伪造短信广播 / Intent | A11 | 接收器要求 `BROADCAST_SMS`；补漏以系统短信库为准；不注册 deep link；Launcher 忽略 extras；确认界面只能从 App 内进入；CI 断言导出组件白名单 |
| 20 | 剪贴板窃取 / 其他通知监听器 | A11 | `EXTRA_IS_SENSITIVE` + 60 秒清除 + 优先拖放；通知默认只显示「发件人 · 类别」、`setLocalOnly(true)` |
| 21 | 备份 / 快照 / 卷泄露 | A8 | 数据库中只有密文；KEK 不在数据目录；去重密钥迁出、指纹与幂等键随正文清空；凭证只存哈希；按天加密粉碎；迁移前备份 24 小时后删除。**用旧备份回滚数据库无法被自动发现**（精简版没有审计锚点） |
| 22 | 服务器 root 被攻破 | A8 | **不在防护范围内**：root 可读内存和 KEK。缓解：保留期短 + 加密粉碎；审计 HMAC 链使中间篡改可被发现；关键事件同时发到 Telegram |
| 23 | 供应链：Go / Web | A9 | 新增 Go 依赖只有 4 个；`go.sum`、`-mod=readonly`、`govulncheck`、Dependabot；只有 1 个 vendored JS 文件并比对官方 tarball；Node 只在隔离的 `web-checks.yml` 中运行（`permissions: {}`、无缓存、`npm ci --ignore-scripts`） |
| 24 | 供应链：Android / Actions / **缓存投毒** | A9 | Gradle wrapper 校验（`distributionSha256Sum` + wrapper jar 校验）；依赖锁与校验元数据；所有 Action 固定到 commit SHA；默认 `permissions: contents: read`；不用 `pull_request_target`；**发布作业禁用一切缓存** |
| 25 | CI 或签名密钥泄露 | A12 | Free 计划没有 Environment，因此：签名 secrets 只在 `app-v*` tag 触发的单个作业中引用；密钥解码到 `$RUNNER_TEMP` 并在结束时删除；`apksigner verify --print-certs` 断言指纹等于仓库变量；Release 先建为草稿；仓库只有所有者有写权限，GitHub 账号开启 2FA；离线备份；v3 签名保留轮换能力 |
| 26 | 测试后门进入正式版 | 自己 | 假 IdP、假 FCM、假 Telegram 只在 `-tags e2e` 构建中；CI 用 `go tool nm` 断言正式二进制不含 e2e 符号；App 的 http 例外只在 debug 构建 |
| 27 | 第三方平台可见的数据 | A10 | FCM 只有 ID 与 seq；GitHub token 立即撤销；Google 只申请 `openid email`；DeepSeek 只收到遮盖后的文本 |
| 28 | 日志泄露 / 时序攻击 | 全部 | 不记录正文、搜索词、凭证、Cookie、OAuth code、DPoP 证明；哨兵字符串测试；令牌哈希后查库，比较用 `subtle.ConstantTimeCompare` |
| 29 | 自己配错 | A14 | 默认拒绝：空白名单关闭面板、KEK 缺失或不匹配拒绝启动；`nekopost check` 覆盖全部新配置 |
| 30 | 匿名者占满状态表 / 刷 FailGuard 封掉所有者 / pending 会话挤占 | A1、A6 | OAuth 状态在 Cookie 中；FailGuard 先验凭证；确认名额分池；SSE 与限流按会话或设备计数 |
| 31 | 泄露的 `mga_` 读到正文或侦察信息 | A3 类 | authn 层路由白名单（5 个 GET），测试遍历全部路由；settings 精简视图 |
| 32 | 首次补漏把整个短信库上传 | 自己 | `last_seen_id` 初值 = 登记时的 `MAX(_id)`；硬性回看 72 小时；超过 50 条暂停确认 |

### 11.3 审计日志与安全提醒

- **事件**：`login.*`（ok / denied / approval_* / trusted_browser_used / suspended）、`reauth.ok|denied`（附方式 oauth / approval / device）、`session.revoke*`、`browser.revoke`、`lockdown[.lift]`、`device.*`（pair / revoke / unpair / dpop_fail / reauth_key / suspend / resume / prefs_change）、`pairing.contested`、`source.*`、`admin_key.*`、`message.*`（view 按会话 / 设备每分钟合并、rescue、redeliver、body_delete、delete、export）、`search.run`、`keys.*`、`token.revoked_use`、`push.config_error`。
- **Telegram 提醒**：除 `message.view`、`message.rescue|redeliver`、`search.run` 外的事件都发；被拒登录每小时 ≤3 条、配置错误类限频；身份被暂停、重认证密钥重登记、配对与吊销为高优先级并附 [吊销此设备] / [吊销此浏览器]。

- 不记录正文、搜索词、完整凭证。actor 精确到会话或设备（`web:github:123/s:<前 8 位>`、`device:d_x`），事后能区分所有者的会话和被盗会话。
- 每行 `mac = HMAC(K_audit, prev.mac ‖ 规范化内容)`；`sudo nekopost audit verify` 手动校验整条链；Web「安全」页显示 `chain_ok`。精简版**不做** journal 锚点和定时校验，因此尾部截断与整库回滚无法被自动发现（§0.2）。
- 应用代码只追加，除过期清理外没有更新或删除审计行的路径。安全提醒同时推送给有 `admin:read` 的设备。

## 12. 构建与发布

### 12.1 仓库布局

单仓库：根目录 Go；`web/`（面板源码，即产物）；`design/tokens.json` 与 `tools/tokens/`（M3）；`android/`（Gradle 工程）；`.github/workflows/`。`.dockerignore` 排除 `android/`。

### 12.2 GitHub Actions（Free 计划，私有仓库）

本机内存紧张且没有 JDK / Android SDK，**禁止在本机安装它们**（会危及生产服务）。所有 Kotlin / Android 编译与测试只在 Actions 中进行，开发时推送分支后用 `gh run list --branch <b>`、`gh run watch <id> --exit-status`、`gh run view <id> --log-failed` 迭代。

| Workflow | 触发 | 内容 |
|---|---|---|
| `ci.yml`（从现有 `docker.yml` 拆出 test job） | push / PR | gofmt、`go vet`、`go test -race`、govulncheck；登记链接正反例向量；（M2 起）迁移测试、假 GitHub / OIDC / FCM / `faketg` 集成测试、哨兵字符串日志测试、`-tags e2e` 构建与 `go tool nm` 检查；（M3 起）tokens 漂移、vendored JS 校验、innerHTML 与 fetch 路径拼接 grep 门禁。**不运行任何 npm** |
| `android.yml`（M0） | push / PR（`android/**` 或本文件变化） | setup-java（Temurin 21）→ `gradle/actions/setup-gradle`（含 wrapper 校验；**Gradle wrapper 必须提交 `distributionSha256Sum`**，wrapper jar 从 services.gradle.org 下载并按官方校验和核对后提交）→ `./gradlew lint testDebugUnitTest verifyRoborazziDebug assembleDebug`；断言合并后 Manifest 的导出组件等于白名单；debug APK 与 Roborazzi 差异图作为 artifact。此作业可以使用 Gradle 缓存，但**不引用任何签名 secret** |
| `android-release.yml`（M0） | **只由 tag `app-v*` 触发** | 单个作业，`permissions: contents: write`；**禁用一切缓存**（`setup-gradle` 设 `cache-disabled: true`，不用 `actions/cache`），从干净状态构建；仓库级 secrets `ANDROID_KEYSTORE_B64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` **只在这一个作业中引用**，解码到 `$RUNNER_TEMP` → `assembleRelease`（v2 + v3 签名）→ `apksigner verify --print-certs` 并断言证书 SHA-256 等于仓库变量 `ANDROID_CERT_SHA256`（变量存在时）→ 生成 `SHA256SUMS` → 创建**草稿** Release → 删除密钥文件 |
| `docker.yml`（现有，需修改） | main / `v*` tag | 镜像构建不变（静态资源已 embed）；**去掉 `cache-from/cache-to: type=gha`**，推送 GHCR 的作业从干净状态构建；冒烟测试增加 KEK、CSP 头、`/v1/meta` 检查（M2 / M3 起） |
| `web-checks.yml`（M3，隔离 Node） | 只在 PR 与夜间，不在 main push 上 | `permissions: {}`；不用缓存；`npm ci --ignore-scripts`；按 digest 固定的 Playwright 镜像；`tsc --checkJs`、E2E、CSP 违规监听、XSS 语料、axe-core、多断点截图（含 CDP 模拟 Fold8 铰链） |
| `android-emulator.yml`（M5） | 夜间 | 可折叠 AVD × API 36 / 37：`adb emu sms send` → 断言 `nekopost-e2e` 收到（模拟器内经 `10.0.2.2`，debug 构建允许 http）；`adb emu fold/unfold/posture` 切换姿态 |
| Dependabot | 每周 | gomod、github-actions、gradle、（M3 起）npm（设最短发布年龄 7 天） |

通用：所有 Action 固定 commit SHA；`permissions` 最小化；`concurrency` 防止并发发布。**Free 计划的限制**：没有 Environment、必需审批人，私有仓库也没有分支保护和 tag 规则，所以「签名前人工批准」这道闸不存在；补偿措施是 secrets 只在 tag 触发的发布作业中引用、Release 只建草稿由你手动发布、仓库只有你有写权限。私有仓库用不了 artifact attestation，cosign keyless 会把仓库名写进公开日志，因此不启用，改为把镜像 digest 与 APK SHA-256 写入 Release。

### 12.3 签名密钥

- 由用户在**自己的电脑上**用 `keytool` 生成 PKCS#12 keystore（RSA 4096 或 EC P-256，有效期 30 年）。**不在服务器、不在 CI 中生成，不在聊天中传递。**
- 仓库级 secrets：`ANDROID_KEYSTORE_B64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；仓库变量 `ANDROID_CERT_SHA256`（公开信息）。M5 起 Firebase 配置值（`projectId`、`applicationId`、`apiKey`、`gcmSenderId`）也存为仓库变量，构建时写入 BuildConfig。
- **离线备份两份**。丢失 = 无法覆盖升级，只能卸载重装并重新登记 / 配对（服务端数据不受影响）。
- 包名与签名密钥从第一个版本起保持不变（FCM 和开发者验证都与它们绑定）。

### 12.4 分发与 Android 开发者验证

- 安装：手机浏览器登录 GitHub → 私有仓库 Release 下载 APK → 核对 SHA-256 → 安装。App「关于」页显示自身签名证书指纹。
- **开发者验证**：2026-09-30 起部分国家的认证设备只能安装已验证开发者的应用，2027 年计划扩展到全球；日本目前未强制。ADB 安装或「高级流程」仍可安装未登记的应用。推荐 2027 年前注册免费的 **Limited Distribution 账号**，登记包名和签名证书；备选是 ADB 安装。
- 更新：App 检测 `/v1/meta` 的 `min_app_version` 变化时提示，并链接 Release 页面。

### 12.5 版本号与部署影响

- **版本**：服务端 `VERSION` 0.5.0 → 0.5.1（M1，登记二维码）→ 0.6.0 → … → **1.0.0**，tag `vX.Y.Z` 触发 GHCR 镜像；App 用 `android/VERSION`（semver），`versionCode = major*1_000_000 + minor*1_000 + patch`，tag `app-vX.Y.Z` 触发发布；API 只做增量，服务端始终兼容前一个 App 版本。
- **systemd**：`install.sh` **仍然只需要 Go**；M2 起创建 `/etc/credstore.encrypted`（0700）、KEK 不存在时在升级前执行 `nekopost keys init`、最后执行 `nekopost check`；unit 增加 `ImportCredential=nekopost-*`、`LimitCORE=0`、`GOMEMLIMIT=96MiB`、`MemoryMax=192M`，其余加固项不变。
- **Docker**：Dockerfile 不变；compose `secrets:` + `*_FILE` 环境变量、`mem_limit: 192m`；release notes 写明「先 `keys gen` 并挂载 secret，再升级镜像」。反向代理部署需设置 `NEKOPOST_PUBLIC_URL`，SSE 关闭缓冲、超时 ≥ 1 小时。
- **新 CLI**：`source add|rotate <id> --qr [--url https://…]`（M1）、`keys init|status|rotate|shred-all|reset|gen|export`、`device list|pair|revoke|resume`、`web sessions|revoke-all|unlock`、`approve`、`lockdown [--revoke-devices] [--keep <id>] [--lift]`、`inbox list|show|search`、`audit verify`（Docker 下用 `docker compose exec nekopost nekopost …`）。

## 13. 分阶段交付计划

M3 首发按用户决定只接 Google。浏览器使用服务端渲染的只读页面与同源脚本；面向设备的收件箱 API、同步与事件接口留到后续里程碑。

每个里程碑都能**单独上线并带来可用价值**。本机没有 Android 设备、模拟器和 SDK：Android 的正确性依靠可测试的架构（`FoldState` 作为参数、IO 在接口后面）+ CI 中的 Robolectric / Roborazzi（M5 起加夜间模拟器）+ 每个里程碑的真机清单。服务器部分在本机部署验证，**开发期间不动线上服务**，上线由用户决定。

| 里程碑 | 交付内容 | 验证方式 | 验收标准 |
|---|---|---|---|
| **M0 准备**（进行中；服务端行为不变） | `android/` Gradle 工程：wrapper（`distributionSha256Sum`）+ `gradle/libs.versions.toml`、`app` / `core` / `forwarder` 三模块、Compose 外壳、手写主题、`FoldState` 与自适应导航；`android.yml`（lint、单测、Roborazzi、debug APK）；`android-release.yml`（`app-v*` tag、仓库级 secrets、无缓存、草稿 Release）；`docker.yml` 去掉 gha 缓存 | CI 全绿；现有 Go 测试全部通过 | debug APK 能在 Fold8 上安装并显示外屏 / 内屏两种布局；推送 `app-v0.0.x` 测试 tag 能产出签名正确的草稿 Release |
| **M1 App 0.1「转发器」**（进行中；服务端 0.5.1：`internal/enroll` + `source add\|rotate --qr`） | **第一项：Android 17 OTP 实测**（targetSdk 36）；登记二维码（§3.7.1，严格校验 + 主机确认页）；SmsReceiver + 补漏扫描（起点 = 登记时 `MAX(_id)`，回看 ≤72 小时，>50 条需确认）+ HMAC 幂等键；通知监听（应用选择、去重、打码标记、禁选 Telegram）；outbox 与重试（§8.7 全部状态码）；Tink + Keystore 加密存储；权限向导；OTP 状态卡；证书锁定；转发记录页；外屏 / 内屏布局 | Go：登记链接构造与正反例向量；Robolectric：链接解析正反例（错误 scheme / host / v / kind、非 https、debug 例外、token 格式）、构造 PDU 发 `SMS_RECEIVED`（单段、多段、双卡）断言 outbox 与幂等键、补漏首次运行不上传历史短信、`StatusBarNotification` 提取与去重、`work-testing` 覆盖 202 / 200 / 401 / 413 / 415 / 422 / 429 / 5xx / 离线；Roborazzi：登记与向导各步 × {外屏, 内屏} × {浅, 深} | 真机：测试验证码短信 10 秒内到达 Telegram；OTP 状态卡为 ✅；飞行模式排队、恢复后补发；重启后补漏；**连续 3 天零漏转**（服务端与手机短信库对账一致） |
| **M2 服务端 0.6.0「加密底座」** | v3 迁移（含备份）；`sealbox`；`bodies`、`seq`、墓碑、`late` 标注；去重密钥迁出、指纹随窗口清空；扩充验证码规则与预览打码；FailGuard 新语义；保留期（验证码 24 小时）；按通道 worker；`events`；Bot 已过滤页解密读取；`keys *`、`inbox *` CLI；`crypto` 组件；`install.sh` 自动 `keys init`；抽出 `internal/service` | 单元：AEAD 往返、AAD 篡改被拒、KEK 错误拒绝启动、DEK 粉碎后不可解、`seq` 单调；验证码语料；被封 IP 携带有效 `mgs_` 仍可提交；V1 fixture 迁移测试；搜索 fuzz；1 万条基准（p95 < 150 ms）；`strings nekopost.db \| grep <哨兵>` 为空 | 生产稳定 72 小时，RSS ≤ 35 MB；iPhone 快捷指令与 M1 App 照常可用（V1 API 快照不变） |
| **M3 服务端 0.7.0「Web 面板（只读）」** | v4 迁移；GitHub / Google 登录（无状态 flow Cookie）、白名单、会话、可信浏览器、**号码匹配的新浏览器确认**（Telegram + CLI）、冷却与暂停；Telegram 安全模板；CSRF 与 CSPT 防护；CSP + Trusted Types；面板只读页面；SSE；审计 + HMAC 链 + 安全提醒；tokens 流水线；`web-checks.yml` | 模拟 OIDC 覆盖 state、PKCE、nonce、iss、aud、白名单外、mix-up、开放重定向、flow Cookie 篡改 / 重放；选错号码拒绝并冷却；pending 权限；Bot 模板无 `url` 实体；路径穿越与 CSRF 被拒；响应头快照；Playwright + CSP 零违规 + XSS 语料 + axe | 用户在 PC 与 Fold8 Chrome 上各完成一次 Google 登录；Telegram 确认可用；白名单外账号被拒 |
| **M4 服务端 0.8.0「Web 管理 + 设备后端」** | v5 迁移；管理写操作（找回、重新投递、来源与 `mga_` 管理、导出）；**5 分钟重新认证**（重新 OAuth / 号码匹配 / 设备签名）；`mga_` 路由白名单；配对（Web 与 CLI 二维码 + Telegram / CLI 号码匹配 + 争用检测）；DPoP 校验；`/v1/inbox*`、`/v1/sync`、`/v1/devices/self*`；Bot「📱 设备」「🚨 紧急锁定」；`lockdown` | Go **模拟设备**（同样的 DPoP 协议）跑全流程：配对 → 提交 → 读取 → 吊销后 401；DPoP 测试向量（篡改 htu、iat 超窗、jti 重放、jkt / ath 不符、nonce）；scope 越权被拒；超过 5 分钟未重新认证的高危操作被拒；`k_reauth` 签名错误 / 挑战重放被拒；锁定后设备读接口 403、发送照常；`mga_` 只能访问 5 个 GET；与 Kotlin 共享的 JSON fixture | 用户在 Web 上重新认证后轮换一个来源凭证（只显示一次）；Go 模拟设备完成配对（Telegram 确认）；紧急锁定后模拟设备只能发送 |
| **M5 App 0.2 + 服务端 0.9.0「接收」** | 服务端 FCM 通道（aux）、`push` 组件、acks；App：配对（`kind=device` 二维码、`k_dev` + `k_reauth`，含从 `mgs_` 升级）、DPoP 客户端、收件箱 / 详情 / 搜索 / 前台 SSE、本地通知、✓✓ ack、Tink 加密缓存（本地保留期、72 小时离线清空）、应用锁、App 内确认（待确认列表 + 号码匹配 + 生物识别）、重新认证、测试推送；夜间模拟器 workflow | 假 FCM 覆盖 §6.5 每一行；跳过来源设备；**aux 不影响聚合状态**；Robolectric 构造 `RemoteMessage` → 拉取 → 通知（锁屏公共版本）、按 ID 去重；Keystore 抽象为接口后替换为假实现 | Fold8 收到脚本消息的通知 3 秒内（p50），锁屏只显示公共版本；Web 显示 ✓✓；Web 标已读后手机通知消失；吊销设备后 App 自擦除；推送接收率 ≥ 99% |
| **M6 App 0.3「管理对等 + 折叠屏 P0/P1」** | App 管理页全部；用 Fold8 给其他设备配对；§10.4 ①–⑪；键盘与命令面板；`NEKOPOST_TELEGRAM_TYPES` 与每设备推送偏好 | `window-testing` 姿态推导；Roborazzi 矩阵（§10.9）；连续性、拖放、遮挡、铰链避让测试；夜间模拟器姿态切换 | **附录 C 对等清单全部 ✓**；用户在 Fold8 上走完 §10.9 真机清单 |
| **M7 1.0「加固」** | 折叠屏 P2（⑫–⑯，⑭ 默认关闭）；证书锁定到期策略复核；文档（`security.md`、`api.md`、新增 `encryption.md`、`web.md`、`android.md`、`foldables.md`）；威胁模型复查与依赖审计 | OWASP ASVS L2 适用条目自查；恢复演练：丢失所有设备 → CLI 配对；手机被盗 → Bot 紧急锁定 → CLI 解除；新录入指纹 → `k_reauth` 重登记；KEK 轮换；从备份恢复 + KEK；`audit verify` | 用户签字验收 |

## 14. 需要用户完成的操作 & 已决问题

### 14.1 需要用户完成的操作

> 所有秘密都由你自己写入服务器或 GitHub，**不要发到聊天里**，开发过程也不会读取 `/etc/nekopost/nekopost.env`。下文 `read -rs` 让秘密只从键盘进入管道，不回显、不进 shell 历史。

1. **〔M0〕Android 签名密钥**（在**自己的电脑**上）：`keytool -genkeypair -v -storetype PKCS12 -keystore nekopost-release.p12 -alias nekopost -keyalg RSA -keysize 4096 -validity 10950`，离线备份两份。导入**仓库级** secrets（Free 计划没有 Environment）：`base64 -w0 nekopost-release.p12 | gh secret set ANDROID_KEYSTORE_B64`；`gh secret set ANDROID_KEYSTORE_PASSWORD`、`gh secret set ANDROID_KEY_ALIAS`（值 `nekopost`）、`gh secret set ANDROID_KEY_PASSWORD` 会交互式提示输入。证书指纹（公开信息）：`keytool -list -v -keystore nekopost-release.p12 -alias nekopost | grep SHA256` → `gh variable set ANDROID_CERT_SHA256 --body <去掉冒号的 64 位十六进制>`。
2. **〔M0〕账号安全**：GitHub、Google、Telegram 都开启 2FA（推荐 Passkey 或硬件密钥）；不要给仓库添加其他有写权限的协作者。
3. **〔M1，Fold8〕** 允许浏览器安装未知应用；暂时关闭 Samsung Auto Blocker；安装 APK → 应用信息「⋮ → 允许受限设置」→ 授予短信、通知使用权、通知权限 → 电池「不受限制」并加入「从不休眠的应用」→「在封面屏上继续使用应用」选「始终」→ 关闭 Labs 中本 App 的 Flex 模式面板。
4. **〔M1〕** 在服务器上执行 `sudo nekopost source add fold8 --qr`（已有来源则 `sudo nekopost source rotate fold8 --qr`），用 App 扫码，在确认页核对主机名为 `osaka.drinkcat.com`；扫完执行 `clear`。
5. **〔M1〕** 用另一台手机给 Fold8 发测试验证码短信，确认 OTP 状态卡为 ✅；把调试面板「复制 spec」的结果贴给我（不含秘密）。
6. **〔M1，可选〕** `adb shell cmd appops set --user 0 com.drinkcat.nekopost RECEIVE_SENSITIVE_NOTIFICATIONS allow`。
7. **〔M2〕** 升级时 `install.sh` 会自动执行 `nekopost keys init`。推荐把 KEK 离线备份：`sudo nekopost keys export | <密码管理器 CLI 的写入命令>`，与数据库备份分开存放。
8. **〔M2〕** 执行 `swapon --show`：如有未加密的 swap，考虑关闭或改为加密 swap。
9. **〔将来启用 GitHub 登录时〕GitHub OAuth App**：Settings → Developer settings → OAuth Apps → New。Homepage `https://osaka.drinkcat.com`；Authorization callback URL **精确为** `https://osaka.drinkcat.com/auth/github/callback`；不勾选 Device Flow；如有通配回调选项则关闭。env 中写 `NEKOPOST_GITHUB_CLIENT_ID=<Client ID>`；secret 存为加密凭证：
   `read -rs S && printf %s "$S" | sudo systemd-creds encrypt --name=nekopost-github-client-secret - /etc/credstore.encrypted/nekopost-github-client-secret; unset S`
10. **〔M3 前〕Google OAuth Client**：Google Auth Platform → Audience：**External + Testing**，Test users 只加你自己；Data access 只加 `openid`、`email`；Clients → Web application，Authorized redirect URI **精确为** `https://osaka.drinkcat.com/auth/google/callback`。env 写 `NEKOPOST_GOOGLE_CLIENT_ID`；secret 同上存为 `nekopost-google-client-secret`。
11. **〔M3 前〕白名单**：GitHub 数字 ID 见 `https://api.github.com/users/<用户名>` 的 `id`；Google `sub` 在第一次登录被拒时页面上会显示。env 写 `NEKOPOST_WEB_ALLOW=github:<id>,google:<sub>`，然后 `sudo systemctl restart nekopost && sudo nekopost check`。
12. **〔M3〕** 第一次网页登录：浏览器显示 2 位数字后，在 Telegram 中点选，或在服务器上 `sudo nekopost approve` 输入该数字并选择「信任此浏览器 30 天」。
13. **〔M5 前〕Firebase 项目**：添加项目（**不启用** Google Analytics）→ 添加 Android 应用，包名 `com.drinkcat.nekopost`。`google-services.json` **不提交到仓库**，只从中抄 4 个值存为仓库变量：`FIREBASE_PROJECT_ID`、`FIREBASE_APP_ID`、`FIREBASE_API_KEY`、`FIREBASE_SENDER_ID`。
14. **〔M5 前〕限制 Firebase API key**：Application restrictions = Android apps（包名 + 签名证书 SHA-1）；API restrictions 只勾 FCM 客户端注册所需的 API（实现时核对）。
15. **〔M5 前〕FCM 服务账号**：新建 `nekopost-fcm`，只授予 Firebase Cloud Messaging API Admin，创建 JSON key，用 `scp` 传到服务器后：
    `sudo systemd-creds encrypt --name=nekopost-fcm-sa ./sa.json /etc/credstore.encrypted/nekopost-fcm-sa && shred -u ./sa.json && sudo systemctl restart nekopost && sudo nekopost check`
16. **〔M5〕配对 Fold8**：`sudo nekopost device pair --name Fold8 --role full`，用 App 扫终端二维码，在终端（或 Telegram）选出 App 上显示的数字。完成后在 Web「来源」页吊销 M1 时的 `mgs_`。之后可设 `NEKOPOST_APPROVAL_CHANNELS=app,cli` 去掉 Telegram 确认。
17. **〔Docker 备用环境〕** `docker run --rm ghcr.io/imdrinkcat/nekopost keys gen > ./secrets/nekopost_kek && chmod 0400 ./secrets/nekopost_kek`；FCM JSON 与 OAuth secret 也放 `./secrets/` 并通过 `*_FILE` 指向 `/run/secrets/…`；`secrets/` 不放进 `data/` 卷。
18. **〔2027 年前〕** 注册 Android Developer Console 的免费 Limited Distribution 账号，登记包名与签名证书（或接受只用 ADB 安装）。

### 14.2 已决问题

| # | 问题 | 结论 |
|---|---|---|
| 1 | 机型 | Galaxy Z Fold8（宽机型，内屏 4:3 横向自然方向）；不做其他机型专属分支，通用路径覆盖其他设备 |
| 2 | 包名与 App 名 | `com.drinkcat.nekopost`；启动器名称「Nekopost」；界面中文 |
| 3 | 安全方案 | 精简版（§0.2） |
| 4 | Passkey | **V2 不做**；以后可作为可选因素加入（`go-webauthn`，1 个依赖 + 1 张表），届时可用于网页登录、重新认证与 `k_reauth` 重登记，是唯一能防实时中继钓鱼的手段 |
| 5 | GitHub 计划 | Free：无 Environment，仓库级 secrets 只在 `app-v*` tag 的发布作业中引用，发布作业不用缓存（§12.2） |
| 6 | CDM「伴侣保护」 | **不实现**；只有 M1 实测发现 WebOTP 格式短信对你有实际影响时再评估 |
| 7 | `NEKOPOST_CODE_RETENTION_HOURS` | 默认 **24** |
| 8 | 「设备未 ack 才发 Telegram」 | **不实现**；保持两边都发 + `NEKOPOST_TELEGRAM_TYPES` |
| 9 | ⑭ 拖入发送 | **实现，默认关闭**（P2） |
| 10 | Glance 小组件 | **不做** |
| 11 | TLS 证书 | **继续使用 Let's Encrypt**；改用其他 CA 前必须先发布更新证书锁定的 App |

其余此前讨论过的选项（锁屏不显示验证码、`FLAG_SECURE` 默认开、被系统打码的通知转发占位、正文保留期上限 90 天且不做星标、暂不启用 FTS5、面板不拆子域名、值守台明文大号牌默认关闭）已按推荐定为默认值，可通过设置或配置调整。

## 附录 A：新增 / 变更的配置项（`/etc/nekopost/nekopost.env`）

| 变量 | 默认 | 说明 |
|---|---|---|
| `NEKOPOST_PUBLIC_URL`（M3 新增） | `https://$NEKOPOST_DOMAIN` | 对外地址；OAuth 回调、面板链接与登记二维码都用它（反向代理部署时必须设置） |
| `NEKOPOST_KEK_FILE` | `$CREDENTIALS_DIRECTORY/nekopost-kek` | 主密钥文件（M2 起**必需**） |
| `NEKOPOST_BODY_RETENTION_DAYS` | `7` | 0–90；0 = V1 行为 |
| `NEKOPOST_CODE_RETENTION_HOURS` | `24` | 验证码类正文保留时间（按本地规则的 `has_code`）；0 = 同正文 |
| `NEKOPOST_DEDUPE_WINDOW`（已有） | `60s` | V2 起 `content_hash` 只保留 `max(该值, 1h)` |
| `NEKOPOST_KEEP_MIGRATION_BACKUP` / `NEKOPOST_AUDIT_RETENTION_DAYS` | `false` / `180` | 是否保留迁移前备份 / 审计保留天数；`NEKOPOST_FILTER_KEEP_DAYS` **弃用**，作为正文保留期的兼容别名 |
| `NEKOPOST_WEB_ALLOW` | 空（面板关闭） | `github:<id>,google:<sub>` |
| `NEKOPOST_{GITHUB,GOOGLE}_CLIENT_ID` / `_SECRET_FILE` | 空 / `$CREDENTIALS_DIRECTORY/nekopost-{github,google}-client-secret` | |
| `NEKOPOST_WEB_SESSION_IDLE` / `_MAX` / `NEKOPOST_TRUSTED_BROWSER_TTL` | `12h` / `168h` / `720h` | |
| `NEKOPOST_WEB_NEW_BROWSER_APPROVAL` | `on` | 关闭时启动日志与健康报告警告 |
| `NEKOPOST_APPROVAL_CHANNELS` | `app,telegram,cli` | 确认渠道（登录、重新认证、配对、重登记共用） |
| `NEKOPOST_REAUTH_WINDOW` | `5m` | 高危操作要求的重新认证时效（最大 `15m`） |
| `NEKOPOST_WEB_LOGIN_COOLDOWN` | `1h` | 选错号码或被拒后该身份的登录冷却时间 |
| `NEKOPOST_FCM_CREDENTIALS_FILE` / `_PROJECT_ID` | `$CREDENTIALS_DIRECTORY/nekopost-fcm-sa` / 从 JSON 读取 | 文件不存在则推送关闭 |
| `NEKOPOST_FCM_MAX_AGE` / `NEKOPOST_FCM_TTL` | `1h` / `24h` | FCM 最长重试时间 / `msg` 的 `android.ttl` |
| `NEKOPOST_FCM_INCLUDE_ID` | `true` | `false` 时只发 `sync` |
| `NEKOPOST_TELEGRAM_TYPES` | `*` | 哪些消息类型发到 Telegram（`*` 全部；`none`/`-` 都不发，只推 App；或逗号分隔的类型）。0.10.0 实现 |
| `NEKOPOST_MIN_APP_VERSION` | 空 | `/v1/meta` 返回 |

## 附录 B：新增 Go 依赖

`rsc.io/qr` v0.2.0（M1，终端 ANSI / SVG 二维码）；`golang.org/x/oauth2` v0.38.0（授权码 + PKCE；FCM 服务账号令牌）；`github.com/coreos/go-oidc/v3` v3.21.0（Google ID Token、发现、JWKS）；`github.com/go-jose/go-jose/v4`（随 go-oidc；DPoP JWS、RFC 7638 指纹、`k_reauth` 签名校验）。
加密只用标准库：`crypto/aes`、`crypto/cipher`、`crypto/hkdf`、`crypto/hmac`、`crypto/rand`、`crypto/ecdsa`。明确**不引入**：firebase-admin-go、gorilla/csrf、scs、任何 ORM、任何前端构建链、Redis、go-webauthn（V2 内）。

## 附录 C：功能对等清单（M6 验收门槛）

| 功能 | Telegram Bot | Web | App |
|---|---|---|---|
| 系统总览、组件状态、服务器与 Bot 状态、统计 | ✓ | ✓（实时、图表） | ✓（实时、图表；值守台） |
| 来源：查看、启用、停用 | ✓ | ✓（启停需重新认证） | ✓（启停需生物识别） |
| 来源：新建、轮换、删除；`mga_` 管理 | ✗（CLI） | ✓（重新认证） | ✓（生物识别） |
| 已过滤：查看、找回 | ✓ | ✓（批量、拖放） | ✓（分拣台、拖放） |
| 正文查看与搜索 | ✗（保持 V1：Bot 不显示正文） | ✓ | ✓（本地加密缓存，需应用锁解开） |
| 重新投递、销毁正文、导出 | ✗ | ✓ | ✓ |
| 设备：查看、吊销 | ✓ | ✓ | ✓ |
| 配对新设备 | 确认（号码匹配） | ✓（创建需重新认证） | ✓（桌面配对台，可在本机确认） |
| 会话与审计 | 只收提醒 | ✓ | ✓ |
| 确认（登录、重新认证、配对、重登记） | ✓（号码匹配；登录恒为仅本次） | — | ✓（生物识别 + 号码匹配，可选信任浏览器） |
| 紧急锁定 | ✓ | — | —（CLI 也可；解除只能 CLI） |
| 设置视图（只读） | ✓ | ✓ | ✓ |
| 测试推送 | ✗ | ✓ | ✓ |

## 附录 D：参考资料

- Android：17 行为变更 https://developer.android.com/about/versions/17/behavior-changes-17 · 15 通知 OTP 打码 https://www.androidauthority.com/android-15-two-factor-authentication-codes-3492585/ · 受限设置 https://www.androidauthority.com/android-15-restricted-settings-sideloading-3481098/ · 开发者验证 https://developer.android.com/blog/posts/android-developer-verification-rolling-out-to-all-developers-on-play-console-and-android-developer-console · androidx.security 弃用 https://developer.android.com/jetpack/androidx/releases/security
- 折叠屏：Galaxy Z Fold8 https://www.samsung.com/ae/mobile-phone-buying-guide/galaxy-z-fold8-features-specs/ · Samsung 应用连续性 https://developer.samsung.com/one-ui/foldable/app-continuity.html · WindowManager https://developer.android.com/jetpack/androidx/releases/window · Material 3 Adaptive https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive · Viewport Segments https://developer.chrome.com/blog/viewport-segments-api-shipped · Roborazzi https://github.com/takahirom/roborazzi/
- 构建：Gradle wrapper 校验 https://docs.gradle.org/current/userguide/gradle_wrapper.html#wrapper_checksum_verification
- 推送：FCM HTTP v1 https://firebase.google.com/docs/cloud-messaging/send/v1-api · 优先级 https://firebase.google.com/docs/cloud-messaging/android/message-priority · Token 管理 https://firebase.google.com/docs/cloud-messaging/manage-tokens
- 认证：GitHub OAuth PKCE https://github.blog/changelog/2025-07-14-pkce-support-for-oauth-and-github-app-authentication/ · go-oidc https://pkg.go.dev/github.com/coreos/go-oidc/v3/oidc · `http.CrossOriginProtection` https://pkg.go.dev/net/http#CrossOriginProtection · OWASP 会话管理 https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html · DPoP RFC 9449 · JWK 指纹 RFC 7638
