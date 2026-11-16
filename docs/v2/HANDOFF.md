# Nekopost V2 交接文档

> 写于 2026-11-16（UTC 约 11:40）。上一位 agent 在这里交接，之后由新 agent 接手。
> 本文只记录**代码和 git 历史里看不出来的东西**：现状、用户已经做出的决定、约束和下一步。技术设计以 [`design.md`](design.md) 为准。

---

## 1. 用户与协作方式（必须遵守）

- **用中文回复**。用户是项目所有者，也是唯一用户。
- **较大的改动先给评估和方案，确认后再实现。** 有取舍时给出推荐选项；用户回答过的问题不要再问（见第 4 节）。
- **秘密绝不进入聊天**：Bot Token、DeepSeek Key、来源凭证（`mgs_`/`mga_`）、OAuth secret、签名密钥、KEK。
  - **不要读取** `/etc/nekopost/nekopost.env`。
  - 需要用户填写秘密时，给出让他在自己终端执行的命令（例如 `read -rs` + `systemd-creds encrypt`、`gh secret set`）。
  - 会打印凭证的命令（如 `nekopost source add … --qr`）让用户在**自己的 SSH 终端**里执行，不要由 agent 执行，也不要建议用 `!` 前缀执行（输出会进入对话）。
- **生产环境的操作**（重启、升级、迁移）要先征得同意。
  - 升级时**先编译、再停服务**。上次 `install.sh` 在停服务之后才编译，导致停机约 3 分钟。
  - 动手前先备份 `/var/lib/nekopost`。
- **提交信息**结尾加上会话要求的署名行（Co-Authored-By 等，以新会话的系统提示为准）。
- **只在 `v2` 分支工作**，不要直接推 `main`，不要 force-push。提交时写明具体路径，不要 `git add -A`：`.claude/` 是 agent 的工作目录，已经写进 `.gitignore`。

## 2. 项目与环境

| 项目 | 现状 |
|---|---|
| 名称 | Nekopost（猫邮局），个人消息网关：接收短信、通知和脚本输出，通过 Telegram 推送 |
| 仓库 | `/root/message_server`，GitHub `imdrinkcat/nekopost`（**私有**，因为文档里有域名和 IP） |
| 生产环境 | **就是本机**：`osaka.drinkcat.com`（66.245.221.76），Ubuntu 24.04，**1 vCPU，955 MB 内存（可用约 360 MB）**，Go 1.27.3 |
| 服务 | systemd `nekopost.service`，用户 `nekopost`，数据在 `/var/lib/nekopost`，配置在 `/etc/nekopost/nekopost.env`，内置 Let's Encrypt |
| Telegram | Bot @DrinkCatBot（ID 7983572752），管理员 ID 8583412753 |
| 当前线上版本 | **`v2` 分支的 `898f9fe`**，显示版本号 0.5.0。2026-11-16 11:15 UTC 部署，用于支持 `source add --qr`。线上已有 iPhone 来源；用户已经用 `--qr` 配对了 Fold8 |
| 备份 | `/root/nekopost-backup-before-v2-20261116111226`（升级到 v2 前）；`/root/msggw-migration-backup-20261026061418`（更名前，可删） |
| 本机限制 | **没有 JDK、Gradle、Android SDK、Node，也不要安装**（内存不足会拖垮生产服务）。安卓和前端只在 GitHub Actions 里构建 |
| Docker | 镜像 `ghcr.io/imdrinkcat/nekopost`（私有，`docker.yml` 只在 main 和 `v*` tag 上构建）。本机没有安装 Docker |

**GitHub 账号是 Free 计划**，没有 Environments。APK 签名用的 secrets 放在仓库级，只在 `app-v*` tag 触发的 release 作业里引用。

## 3. V2 进度

| 里程碑 | 状态 |
|---|---|
| 设计 | ✅ `docs/v2/design.md`（精简版，约 1300 行）。安卓接口约定见 `android/CONTRACT.md` |
| M0 安卓骨架 + CI | ✅ |
| M1 转发器 App 0.1 + 服务端 `--qr` | ✅ **用户已在 Fold8 真机验收**，回复「没有任何问题」 |
| **M2 服务端 0.6.0「加密底座」** | ⏳ **尚未开始**。上一个 workflow 在写计划阶段被停掉，没有留下任何文件或未提交改动 |
| 删除按钮（插队需求） | ⏳ M2 完成后做，见第 5 节 |
| M3–M7 | 未开始（Web 只读面板 → Web 管理和设备配对 → App 接收（FCM）→ 管理功能对等和折叠屏交互 → 1.0 加固） |

### M1 交付要点

- **安卓工程**在 `android/`，分三个模块：`:core`（主题、`FoldState`、`ForwarderController` 接口、登记链接解析）、`:forwarder`（短信、通知、加密 outbox、上传、证书锁定）、`:app`（界面和向导）。
- **CI 是 `.github/workflows/android.yml`**，任何分支只要改了 `android/**` 就会触发：单元测试、Robolectric、Roborazzi 截图对比（134 张参考图）、lint、debug 构建、导出组件白名单检查。`app-v*` tag 才会签名发布。
- **截图变化后要重录参考图**：
  1. 推送后 CI 会失败，从产物 `screenshots/build/outputs/roborazzi/*_actual.png` 里取新图。
  2. 先人工看图确认没问题。
  3. 覆盖 `android/app/src/test/screenshots/<名字>.png`，再推送。
- **调试版**：包名 `com.drinkcat.nekopost.debug`，名称「Nekopost Debug」，用仓库里的 `android/keystore/debug.p12` 签名（每次构建签名一致）。
- **正式版**：包名 `com.drinkcat.nekopost`，名称「Nekopost」。**签名密钥用户还没有创建**，做法见 design.md §14.1。
- **APK 有 42 MB，超过 SendUserFile 的 30 MB 上限。** 用 `zip -9` 压缩后约 14 MB，用户用「我的文件」解压后安装。
- **登记二维码的格式**由 Go（`internal/enroll`）和 Kotlin 共用同一份测试数据，见 design.md §3.7.1。

## 4. 用户已经做出的决定（不要再问）

- **V2 的范围**：
  - 安卓 App 要做到：转发短信、转发选定 App 的通知、在 App 里接收消息（FCM，推送里只带 ID）、和网页面板功能对等的管理功能。
  - 另做一个网页管理面板。
- **网页登录**：GitHub / Google OAuth，只认白名单里的不可变 ID（`github:<数字id>`、`google:<sub>`）。
- **正文**：加密保存，默认保留 7 天（验证码类 24 小时），网页和 App 都能查看、搜索。
- **安全方案是「精简版」**（与完整草案的区别见 design.md §0.2）：
  - 保留：OAuth + 白名单 + 新浏览器确认；DPoP 设备凭证（每台设备一把 `k_dev`）；正文加密；CSP / XSS 防护；限流；审计（HMAC 链）。
  - 高危操作只要求「5 分钟内重新认证过」。
  - 配对用二维码 + 一次性码 + Telegram 或 CLI 号码匹配确认。
  - **已确认保留第二把 Keystore 密钥 `k_reauth`**：每次签名都要生物识别，只用于重新认证和审批。
- **机型**：Galaxy Z Fold8，宽机型（外屏约 475×751dp，内屏约 933×704dp，横向是自然方向）。
- **包名**：`com.drinkcat.nekopost`，桌面名称「Nekopost」，界面中文。
- **targetSdk 36（不是 37）**：Android 17 会把 targetSdk 37 的应用收到的含验证码短信扣留 3 小时。
- **其他已定事项**：
  - 不做 Passkey（以后可以加）。
  - 不做 CDM 伴侣保护。
  - 不做「设备 ack 后才发 Telegram」：两边都发，按类型路由。
  - 拖入发送 ⑭ 是 P2，默认关闭。
  - 不做 Glance 小组件。
  - 证书继续用 Let's Encrypt。
- **生产升级**：用户同意过一次升级，即部署 M1 时那次。**M2 涉及数据库迁移和 KEK，部署前要单独征得同意。**

## 5. 下一步

### 5.1 M2「加密底座」（服务端 0.6.0）

范围、测试和验收标准见 design.md §13 的 M2 行，以及 §2.1–2.2、§2.5、§4.6、§5、§8.4（late / otp_hold 标注）、§12.6、附录 A。原计划这样做：

1. **写实现计划** `docs/v2/m2-plan.md`，内容包括：
   - v3 迁移 DDL，以及现有 payload 的迁移方式；
   - 包的划分（`internal/sealbox`、`internal/service`、`internal/events`）；
   - KEK 的加载顺序（`NEKOPOST_KEK_FILE` → `$CREDENTIALS_DIRECTORY/nekopost-kek` → `/run/secrets/nekopost_kek`），以及 `keys init|export|rotate|status|gen`；
   - 按 UTC 日轮换 DEK，到期加密粉碎；
   - 去重密钥迁出数据库，`content_hash` 到期清空，幂等键改存 HMAC；
   - 扩充验证码规则和 Mask；
   - FailGuard 新语义；
   - `inbox` CLI；
   - `crypto` 健康组件；
   - install.sh、unit、Dockerfile、compose 的改动；
   - 新增 `ci.yml`，`docker.yml` 去掉 `type=gha` 缓存；
   - 文档（新增 `docs/encryption.md`；英文 README 里不能有中文字符）。
2. **分三段依次实现**，每段都保持 `gofmt`、`go vet` 和 `go test -race ./...` 全部通过：
   1. 加密底座：sealbox、KEK、keys CLI、迁移、健康组件和安装相关改动；
   2. 数据流：ingest、delivery、filter、bot、保留期和加密粉碎、去重、late 标注、FailGuard、events、`inbox` CLI、`internal/service`；
   3. 验证、CI 和文档。
3. **两个方向独立审查**（加密和安全；正确性、迁移和运维），然后修复。
4. **用生产数据库的副本演练升级**：
   - 放在临时目录里，用临时 KEK，不打印任何消息内容，结束后删除副本。
   - 产出升级手册：先编译再停服务、备份、`systemd-creds` 生成 KEK、验证、回滚。
   - 必要时改造 `install.sh`，让它先编译、最后才替换二进制并重启。
5. **把手册和预计停机时间给用户，获得同意后再升级生产。** 提醒用户把 KEK 离线备份，并且和数据库备份分开存放（design.md §14.1）。

兼容性要求：
- V1 HTTP API 的行为和 JSON 结构不能变（iPhone 快捷指令和 M1 App 都依赖它）。
- 现有凭证必须继续可用。
- Telegram 的渲染不变。
- 投递语义不变：至少一次。

### 5.2 插队需求：推送消息上的「删除」按钮（在 M2 之后做）

用户的需求是：「给消息推送里加入马上删除的功能」。已经确定：

- **在每条推送的 Telegram 消息下方加「🗑 删除」按钮**。现有按钮是一行 `[来源] [类型 · 类别]`，回调前缀是 `i:`，见 `internal/render`。
- **两步确认**：点「删除」后，按钮变成「确认删除 / 取消」，再点一次才执行。确认要有过期时间，过期后恢复原按钮。可以参考现有「停用来源」的确认逻辑。只响应管理员。
- **范围是「全部抹掉」**：
  - 删除这条消息在 Telegram 里的所有分段；
  - 删除服务器上的正文（M2 之后是加密正文）、元数据和投递记录；
  - 之后统计里也不再计入这条消息。
  - 需要确认统计是实时从消息表计算的，还是另有计数器。
- **Telegram 的限制**：Bot 只能删除 48 小时内发出的消息。超过 48 小时的，改为把这条消息编辑成不含任何内容的「此消息已删除」，并移除按钮。
- **前提条件**：需要知道每条投递对应的 Telegram `message_id`，确认 deliveries 表里有没有存。M5 加入 App 推送之后，删除也要同步到设备（用 tombstone 或 seq 机制）。

### 5.3 之后的里程碑

M3–M7 的内容按 design.md §13。

- **M3 前**，用户要先创建 GitHub OAuth App 和 Google OAuth Client，回调地址的精确值见 design.md §14.1。
- **M5 前**，用户要先创建 Firebase 项目和服务账号。

## 6. 经验与坑

- **跑测试**：`go test -race ./...` 在本机要 2–3 分钟，用后台运行等它结束。不要同时跑多个重型构建。
- **调试安卓**：
  - 只能推送后看 CI，常用命令：
    - `gh run list --branch v2`
    - `gh run watch <id> --exit-status`
    - `gh run view <id> --log-failed`
  - 每轮大约 4 分钟。
- **会话用量上限**：多 agent 的 workflow 曾因此中断。workflow 可以用 `resumeFromRunId` 续跑，但续跑前要检查：
  - 被中断的 agent 有没有留下没提交的改动，或者占着某个分支的 worktree；
  - 续跑时，`git checkout -B <分支> origin/v2` 会把已有的工作冲掉。
- **`go.mod` 没有执行过完整的 `go mod tidy`**。这是有意的，执行的话会改动大约 30 条无关的 go.sum 记录。
- **记忆目录**：`/root/.claude/projects/-root-message-server/memory/` 里有用户偏好和 V2 决定，新会话会自动加载。
