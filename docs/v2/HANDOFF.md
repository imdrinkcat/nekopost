# Nekopost V2 交接文档

> 写于 2026-11-16（UTC 约 12:40）。上一份交接写于同日 11:40，当时 M2 还没开始。
> 本文只记录**代码和 git 历史里看不出来的东西**。技术设计以 [`design.md`](design.md) 为准，M2 的实现取舍以 [`m2-plan.md`](m2-plan.md) 为准，升级步骤以 [`upgrade-0.6.0.md`](upgrade-0.6.0.md) 为准。

---

## 当前工作区

0.7.0 的 M3 工作区新增只读网页面板、Google 登录、Telegram／CLI 号码确认与 v4 迁移。用户已创建 Google OAuth 客户端，并决定暂缓 GitHub 登录。部署配置与 PC／Fold8 验收步骤见 [网页面板](../web.md)。0.7.0 已于 2026-11-16 上线，SQLite 已迁到 v4，`healthcheck`、`check` 和 HTTPS Google 授权跳转通过。升级前的 0.6.0 二进制和已校验的 v3 数据备份在 `/root/nekopost-backup-before-0.7.0-20261116T133124Z-vG5QiM`。下文 0.6.0 状态为升级前的历史记录。

Google 白名单已写入正式配置并生效。曾因重复 `google:` 前缀启动失败，服务用临时 systemd 配置恢复，正式配置核对一致后已撤掉临时配置。健康检查通过。用户在收到电脑与 Fold8 验收指引后回复「ok了」。服务端独立核实了一次 Google 登录通过数字确认、五次消息详情读取。现有审计记录无法独立确认电脑和 Fold8 分别完成登录。Chromium 自动验收走 `web-checks.yml`，尚未运行。

## 1. 用户与协作方式（必须遵守）

- **用中文回复**。用户是项目所有者，也是唯一用户。
- **较大的改动先给评估和方案，确认后再实现。** 有取舍时给出推荐选项；用户回答过的问题不要再问（见第 4 节）。
- **秘密绝不进入聊天**：Bot Token、DeepSeek Key、来源凭证（`mgs_`/`mga_`）、OAuth secret、签名密钥、KEK。
  - **不要读取** `/etc/nekopost/nekopost.env`。
  - **不要读取** `/etc/credstore.encrypted/nekopost-kek`，也不要运行 `nekopost keys export` 或 `keys gen`（标准输出就是密钥）。
  - 需要用户填写秘密时，给出让他在自己终端执行的命令（例如 `read -rs` + `systemd-creds encrypt`、`gh secret set`）。
  - 会打印凭证的命令（如 `nekopost source add … --qr`、`keys export`）让用户在**自己的 SSH 终端**里执行，不要由 agent 执行，也不要建议用 `!` 前缀执行（输出会进入对话）。
- **生产环境的操作**（重启、升级、迁移）要先征得同意。
  - 升级时**先编译、再停服务**。`deploy/install.sh` 已经是这个顺序。
  - 动手前先备份 `/var/lib/nekopost`，并留下当时的 `/usr/local/bin/nekopost`。
- **提交信息**结尾加上会话要求的署名行（Co-Authored-By 等，以新会话的系统提示为准）。用户没明确说提交时，不要自行提交。
- **只在 `v2` 分支工作**，不要直接推 `main`，不要 force-push。提交时写明具体路径，不要 `git add -A`：`.claude/` 是 agent 的工作目录，已经写进 `.gitignore`。

## 2. 项目与环境

| 项目 | 现状 |
|---|---|
| 名称 | Nekopost（猫邮局），个人消息网关：接收短信、通知和脚本输出，通过 Telegram 推送 |
| 仓库 | `/root/message_server`，GitHub `imdrinkcat/nekopost`（**私有**，因为文档里有域名和 IP） |
| 生产环境 | **就是本机**：`osaka.drinkcat.com`（66.245.221.76），Ubuntu 24.04，**1 vCPU，955 MB 内存**，Go 1.27.3 |
| 服务 | systemd `nekopost.service`，用户 `nekopost`，数据在 `/var/lib/nekopost`，配置在 `/etc/nekopost/nekopost.env`，内置 Let's Encrypt |
| Telegram | Bot @DrinkCatBot（ID 7983572752），管理员 ID 8583412753 |
| 当前线上版本 | **0.6.0**，2026-11-16 12:32:21 UTC 启动。二进制是用**当时的未提交工作区**编译的，不是某个 commit |
| Git | 分支 `v2`，跟踪 `origin/v2`。远程最新提交仍是 `fe68be1`（上一份交接文档）。0.6.0 的全部代码都在工作区里，**还没提交** |
| 主密钥 | 已由 `nekopost keys init` 写到 `/etc/credstore.encrypted/nekopost-kek`。`keys status` 能解开。**用户还没有离线备份** |
| 数据库 | `user_version = 3`。升级时 54 条消息元数据，正文按旧规则已经删光，所以 `bodies` 和 `data_keys` 都是 0 |
| 备份 | `/root/nekopost-backup-before-0.6.0-20261116122912`（二进制 + 升级前的数据目录，回滚用这个）；`/var/lib/nekopost/nekopost.db.pre-v3-20261116T123221Z.bak`（迁移程序自动生成，含升级前的库，成功启动 24 小时后由维护任务删除）；`/root/nekopost-backup-before-v2-20261116111226`（更早）；`/root/msggw-migration-backup-20261026061418`（更名前，可删） |
| 本机限制 | **没有 JDK、Gradle、Android SDK、Node，也不要安装**。安卓和前端只在 GitHub Actions 里构建 |
| Docker | 镜像 `ghcr.io/imdrinkcat/nekopost`（私有，`docker.yml` 只在 main 和 `v*` tag 上构建）。本机没有安装 Docker |

**GitHub 账号是 Free 计划**，没有 Environments。APK 签名用的 secrets 放在仓库级，只在 `app-v*` tag 触发的 release 作业里引用。

**不要把工作区重置到 `origin/v2`。** 那样会丢掉已经在生产上运行的 0.6.0 源码。提交之前，生产二进制和 git 历史对不上。

## 3. V2 进度

| 里程碑 | 状态 |
|---|---|
| 设计 | ✅ `docs/v2/design.md`。安卓接口约定见 `android/CONTRACT.md` |
| M0 安卓骨架 + CI | ✅ |
| M1 转发器 App 0.1 + 服务端 `--qr` | ✅ 用户已在 Fold8 真机验收 |
| **M2 服务端 0.6.0「加密底座」** | ✅ 已上生产。计划 `docs/v2/m2-plan.md`，升级记录 `docs/v2/upgrade-0.6.0.md`，用法 `docs/encryption.md` |
| 删除按钮 | ✅ 已随 0.6.0 上线 |
| M3–M7 | 未开始 |

### 3.1 这次升级

- 停机约 **0.2 秒**（旧进程 12:32:21.633 UTC 退出，新进程 12:32:21.785 写日志）。编译的大约 3 分钟里旧服务一直在跑。
- 升级后 `nekopost version` 是 `0.6.0`，`healthcheck` 返回 ok，`check` 通过（配置、数据库、Telegram、DeepSeek）。内存大约 11 MB，`MemoryMax=192M`。
- 来源仍是 2 个且都启用。iPhone 快捷指令和 M1 App 用的 V1 HTTP JSON 没有改字段。
- 用户同意这次升级的原话是「继续吧」。**下一次重启或升级仍要单独问。**

### 3.2 M2 里和设计原文不完全一样的地方

这些是实现时定下来的，不要再改回去，除非用户要求：

- **历史正文按 `received_at` 的 UTC 日各一把 DEK**，不塞进「今天」这一把。这次生产库没有残留明文，所以没有生成 DEK。
- **幂等键**在迁移时先 HMAC 再保留。正文按新保留期已经到期的，键清掉。因此超过 24 小时、但仍在手机 72 小时补漏窗口里的验证码，若被再次补交，会当成新消息再发一次 Telegram。
- **投递成功后不再删除正文。** 普通正文默认 7 天，验证码类 24 小时。`NEKOPOST_BODY_RETENTION_DAYS=0` 才回到「投递结束就删」。`NEKOPOST_FILTER_KEEP_DAYS` 只有在环境变量里**显式写了**并且比正文保留更长时，才把正文保留期抬高并在启动日志里警告。
- **`internal/events` 只有 `seq`、墓碑和 `ChangesSince`。** 没有 `/v1/events`、`/v1/sync`、`/v1/inbox`。搜索只在 `nekopost inbox search`。
- **`deliveries.tg_message_ids`** 是为删除按钮加的，不在 design §2.2 的 DDL 里。只记录这次升级之后新发出的 Telegram `message_id`。
- **管理命令**在服务没起来、因此没有 `CREDENTIALS_DIRECTORY` 时，可以用 `systemd-creds decrypt` 把加密凭证读进内存。服务进程本身只认三条路径：`NEKOPOST_KEK_FILE`、`$CREDENTIALS_DIRECTORY/nekopost-kek`、`/run/secrets/nekopost_kek`。
- **`keys rotate --finish` 用只读方式核对 `data_keys.kek_id`**，避免先 `setuid` 到 `nekopost` 再写不了 `/etc/credstore.encrypted`。`keys reset` 以 root 写库，写完把 `nekopost.db`、`-wal`、`-shm` 的属主改回数据目录的属主。
- 子密钥 `audit` 和 `oauth-flow` 已经派生，M3 再接上，不要改 info 字符串。
- 没有做 design 里写的「两遍独立审查」。上线前跑过 `go test -race ./...`（删除按钮之前），删除按钮之后跑过 `go test` 的 render、bot、store、delivery。

### 3.3 删除按钮

- 推送键盘是两行：原来的 `[来源] [类型 · 类别]`，下面是 `🗑 删除`。回调前缀 `xd:` / `xc:` / `xx:`，见 `internal/render` 和 `internal/bot/delete.go`。`i:` 仍然只弹详情，不改消息。
- 确认 5 分钟有效，过期恢复原按钮。只响应管理员。
- 确认后删除该条消息在库里的正文、元数据和投递记录，并写 `tombstones`。统计是对 `messages` 实时 `COUNT` / `SUM`，没有单独计数器，删行之后就不再计入。
- Telegram：48 小时内 `deleteMessage`；更早的改成「此消息已删除」并去掉按钮。同一条消息的分段 id 存在 `tg_message_ids`。**升级前发出的推送没有这个 id**，只能处理被点到的那一条，其他分段删不掉。
- M5 的设备同步还没做。墓碑已经会写，SSE / sync 还没有 HTTP。

### 3.4 M1 仍然有效的要点

- 安卓工程在 `android/`，模块 `:core`、`:forwarder`、`:app`。本机不要构建。
- CI 是 `.github/workflows/android.yml`。截图变了要先看 CI 产物，再覆盖 `android/app/src/test/screenshots/`。
- 调试包名 `com.drinkcat.nekopost.debug`。正式包名 `com.drinkcat.nekopost`。**正式签名密钥用户还没有创建**，做法见 design.md §14.1。
- 登记二维码格式见 design.md §3.7.1，Go 和 Kotlin 共用测试向量。

## 4. 用户已经做出的决定（不要再问）

- **V2 的范围**：安卓转发短信和选定 App 的通知、在 App 里接收消息（FCM，推送里只带 ID）、管理功能与网页对等；另做网页管理面板。
- **网页登录**：GitHub / Google OAuth，只认 `github:<数字id>`、`google:<sub>`。
- **正文**：加密保存，默认 7 天，验证码类 24 小时。
- **安全方案是精简版**（design.md §0.2）。高危操作只要 5 分钟内重新认证过。保留 `k_reauth`。
- **机型**：Galaxy Z Fold8。包名 `com.drinkcat.nekopost`，界面中文。**targetSdk 36**。
- 不做 Passkey、CDM 伴侣保护、Glance 小组件，也不做「设备 ack 后才发 Telegram」。拖入发送 ⑭ 是 P2，默认关闭。证书继续用 Let's Encrypt。
- **M2 的实现取舍**见第 3.2 节，用户确认计划时说了「没问题」。
- **0.6.0 已经上生产**，用户说「继续吧」之后执行的。不要再升级或重启，除非他再次同意。

## 5. 下一步

1. **提醒用户离线备份主密钥**，如果他还没做。命令由他自己执行：
   `sudo nekopost keys export | <密码管理器的写入命令>`
   和数据库备份分开存放。agent 不要代跑。
2. **用户要求提交时**，只在 `v2` 上提交当前工作区，路径写明，不要 `git add -A`，不要提交 `.env` 或密钥。提交前看第 2 节：生产二进制来自这份未提交代码。
3. **M3（服务端 0.7.0，Web 只读面板）按 design.md §13。** Google OAuth 客户端已创建。完成工作区验证后，等用户授权升级，再按网页面板文档配置和验收。
4. M5 前用户要先创建 Firebase 项目和服务账号。

M3 不要提前做的事：设备、DPoP、FCM、`/v1/inbox` 的 HTTP。`events.ChangesSince` 留着给以后的 SSE 和 sync。

## 6. 经验与坑

- **跑测试**：`go test -race ./...` 在本机要几分钟，用后台运行等它结束。不要同时跑多个重型构建，也不要和 `install.sh` 的编译并行。
- **升级**：`install.sh` 先编译再重启。回滚必须同时换回旧二进制和升级前的数据目录备份；新二进制打不开 v2 库，旧二进制打不开 v3 库。回滚备份是 `/root/nekopost-backup-before-0.6.0-20261116122912`，不是 24 小时后会删的 `pre-v3` 文件。
- **演练生产库**：可以复制到临时目录，用临时 KEK 打开，只打印行数、`user_version` 和耗时。不要 `strings` 数据库，不要把查询结果里的正文打出来。演练程序不要留在仓库里（这次用过的 `cmd/rehearse` 已经删掉）。
- **调试安卓**：只能推送后看 CI：`gh run list --branch v2`、`gh run watch <id> --exit-status`、`gh run view <id> --log-failed`。每轮大约 4 分钟。
- **`go.mod` 不要跑完整的 `go mod tidy`**。那会改大约 30 条无关的 go.sum。M2 没有新增直接依赖。
- **会话被打断时**：先看工作区有没有未提交改动。`git checkout -B <分支> origin/v2` 会把 0.6.0 的源码冲掉，而生产已经在跑这份源码。
