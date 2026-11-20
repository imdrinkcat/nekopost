# Linux 部署指南

本文介绍 **单个 Go 程序 + systemd** 的部署方式，这也是当前生产环境使用的方式。已经在用 Docker 的服务器可以改用容器镜像，见 [docker.md](docker.md)。

> **为什么主环境不用 Docker？** 原需求写的是 Docker 部署。评估时发现：目标服务器只有 1 vCPU、约 1 GB 内存，Docker 守护进程（dockerd + containerd）常驻就要占用几十到上百 MB，比网关本身还多；容器中读取宿主机指标、处理 IPv6 网络也会增加复杂度。而 systemd 已经提供了自动重启、开机自启、日志、资源限制和沙箱隔离。经确认，主环境改为 systemd 部署。后来为了部署到其他服务器，又增加了 Docker 镜像。

当前环境：Ubuntu 24.04，域名 `osaka.drinkcat.com`（A 记录指向本机），Go 1.27。

> **0.6.0 起新增了主密钥（KEK）。** 标题、正文和 meta 加密保存，主密钥在数据库之外。没有主密钥服务不会启动，主密钥丢了，保留期内的正文就解不开。**备份和迁移时数据库和主密钥必须分开处理**，见第 6 节和 [encryption.md](encryption.md)。

---

## 1. 系统要求

| 项目 | 要求 |
|---|---|
| 系统 | 任意使用 systemd 的 Linux（已在 Ubuntu 24.04 上验证）。主密钥用 `systemd-creds` 加密保存，需要较新的 systemd |
| 资源 | 1 vCPU、512 MB 内存即可；服务自身限制在 192 MB 以内（`MemoryMax=192M`，`GOMEMLIMIT=96MiB`，实测空闲约 14 MB，见 [testing.md](testing.md)） |
| 网络 | 公网可访问的 **80** 和 **443** 端口；服务器能访问 `api.telegram.org` |
| 域名 | 一个 A（IPv4）或 AAAA（IPv6）记录指向本机的域名 |
| 编译 | Go 1.27+（只在编译时需要） |

### IPv4 / IPv6

- 服务同时监听 IPv4 和 IPv6（`:443`）。
- **纯 IPv6 服务器**：给域名添加 AAAA 记录即可，Let's Encrypt 支持 IPv6 验证。但只有 IPv4 的网络（部分 Wi-Fi）将无法访问纯 IPv6 服务器，需要在前面加一个双栈代理。另外服务器本身需要能访问 `api.telegram.org`（Telegram 支持 IPv6）。
- 本机目前**只有 IPv4**（66.245.221.76），iPhone 在任何网络下都能访问。

---

## 2. 首次安装

```bash
# 1. 获取代码（本机代码位于 /root/message_server，生产跟踪 v2 分支）
cd /root/message_server

# 2. 安装（编译、创建 nekopost 系统用户、生成主密钥、安装 systemd 服务）
sudo ./deploy/install.sh

# 3. 填写配置
sudo nano /etc/nekopost/nekopost.env
#   NEKOPOST_DOMAIN=osaka.drinkcat.com
#   NEKOPOST_TELEGRAM_BOT_TOKEN=...      （见 telegram-bot.md）
#   NEKOPOST_TELEGRAM_ADMIN_IDS=...

# 4. 开放防火墙
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
#   如果云服务商还有「安全组」，同样需要放行 80/443

# 5. 启动并检查
sudo systemctl restart nekopost
sudo nekopost check --send-test
sudo nekopost keys status

# 6. 把主密钥离线备份（在自己的终端执行，不要贴进聊天或工单）
sudo nekopost keys export | <密码管理器的写入命令>

# 7. 创建第一个消息来源
sudo nekopost source add iphone --name iPhone --icon 📱
```

首次启动时，服务会自动向 Let's Encrypt 申请证书（通常十几秒内完成）。之后在证书到期前 30 天自动续期，**无需任何定时任务**。

第 2 步的 `install.sh` 在没有 `/etc/credstore.encrypted/nekopost-kek` 时会执行 `nekopost keys init`，生成 32 字节随机主密钥并用 `systemd-creds` 加密保存。`keys export` 和 `keys gen` 在标准输出是终端时会拒绝输出，防止密钥显示在屏幕上。

Android 配对、网页面板、FCM 推送都是可选功能，分别见 [android.md](android.md)、[web.md](web.md)。新增设备不需要 SSH，可以在 Telegram Bot 里完成。

### 安装后的文件位置

| 路径 | 内容 | 权限 |
|---|---|---|
| `/usr/local/bin/nekopost` | 程序 | root 755 |
| `/etc/nekopost/nekopost.env` | 配置（含 Bot Token） | root 600 |
| `/etc/credstore.encrypted/nekopost-kek` | 主密钥（systemd 加密凭证，绑定本机） | root |
| `/etc/credstore.encrypted/nekopost-google-client-secret` | 网页面板的 Google Client Secret（启用面板时） | root |
| `/etc/credstore.encrypted/nekopost-fcm-sa` | FCM 服务账号（启用 Android 推送时） | root |
| `/etc/systemd/system/nekopost.service` | systemd 服务 | root 644 |
| `/var/lib/nekopost/nekopost.db` | SQLite 数据库 | nekopost 600 |
| `/var/lib/nekopost/acme/` | 证书与 ACME 账号密钥 | nekopost 700 |

`nekopost.service` 里的 `ImportCredential=nekopost-*` 会把 `/etc/credstore.encrypted/` 下所有 `nekopost-` 开头的凭证交给服务，所以新增凭证不需要改 unit。

---

## 3. 配置项

所有配置都在 `/etc/nekopost/nekopost.env`，修改后执行 `sudo systemctl restart nekopost`。完整的带注释清单见 `deploy/nekopost.env.example`。

> **0.11.0 起默认不自动删除**（`NEKOPOST_AUTO_DELETE=false`）。消息和正文（加密保存）一直保留，只能手动删除，数据会随时间增长；下表中的保留期变量在关闭时被忽略，启动日志会警告。想要「正文 7 天、验证码 24 小时、元数据 30 天」的旧行为，设置 `NEKOPOST_AUTO_DELETE=true`。详见 [v2/upgrade-0.11.0.md](v2/upgrade-0.11.0.md)。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `NEKOPOST_DOMAIN` | — | 必填（autocert 模式）。证书只为这个域名签发 |
| `NEKOPOST_TELEGRAM_BOT_TOKEN` | — | 必填。BotFather 给出的 Token |
| `NEKOPOST_TELEGRAM_ADMIN_IDS` | — | 必填。管理员数字 ID，逗号分隔 |
| `NEKOPOST_TELEGRAM_CHAT_ID` | 第一个管理员 | 消息投递到的聊天 |
| `NEKOPOST_TELEGRAM_TYPES` | `*` | 哪些消息类型发到 Telegram：`*` 全部，`none` 都不发（只推送到 App），或逗号分隔的类型。见 [android.md](android.md) |
| `NEKOPOST_TLS_MODE` | `autocert` | `autocert`：自动 HTTPS；`off`：纯 HTTP（仅在反向代理之后或测试时使用） |
| `NEKOPOST_TRUSTED_PROXIES` | 空 | 反向代理的 IP 或 CIDR，逗号分隔。只有来自这些地址的连接，才读取 `X-Forwarded-For` 作为客户端 IP。直接面向公网时留空 |
| `NEKOPOST_HTTPS_ADDR` / `NEKOPOST_HTTP_ADDR` | `:443` / `:80` | 监听地址。`off` 模式只用 `HTTP_ADDR` |
| `NEKOPOST_ACME_EMAIL` | 空 | Let's Encrypt 联系邮箱（可选） |
| `NEKOPOST_DATA_DIR` | `/var/lib/nekopost` | 数据目录 |
| `NEKOPOST_TIMEZONE` | `Asia/Tokyo` | 显示用的时区 |
| `NEKOPOST_AUTO_DELETE` | `false` | 自动删除。关闭时消息和正文（加密保存）一直保留，只能手动删除；开启后下面的保留期生效 |
| `NEKOPOST_RETENTION_DAYS` | `30` | 消息**元数据**保留天数，不能小于正文保留天数（仅 `NEKOPOST_AUTO_DELETE=true` 时生效） |
| `NEKOPOST_BODY_RETENTION_DAYS` | `7` | 普通正文保留天数（0–90）。`0` 表示投递结束后立即删除正文（0.5 的行为）（仅 `NEKOPOST_AUTO_DELETE=true` 时生效） |
| `NEKOPOST_CODE_RETENTION_HOURS` | `24` | 验证码类正文保留小时数。`0` 表示与普通正文相同（仅 `NEKOPOST_AUTO_DELETE=true` 时生效） |
| `NEKOPOST_KEK_FILE` | 空 | 主密钥文件。留空时使用 systemd 凭证 `nekopost-kek` 或 `/run/secrets/nekopost_kek` |
| `NEKOPOST_KEEP_MIGRATION_BACKUP` | `false` | 保留升级时自动生成的迁移前数据库备份。默认 24 小时后删除，备份里可能有旧的明文 |
| `NEKOPOST_DELIVERY_MAX_AGE` | `24h` | 投递失败后最长重试多久 |
| `NEKOPOST_DEDUPE_WINDOW` | `60s` | 无幂等键时，同一来源相同内容在此时间内视为重复；`0s` 关闭 |
| `NEKOPOST_MAX_BODY_CHARS` | `12000` | 正文最大字符数 |
| `NEKOPOST_SOURCE_RATE_PER_MIN` | `60` | 每个来源每分钟最多提交条数 |
| `NEKOPOST_LOG_LEVEL` | `info` | `debug` / `info` / `warn` / `error` |
| `NEKOPOST_DEEPSEEK_API_KEY` | 空 | 设置后启用短信过滤（DeepSeek），详见 [sms-filter.md](sms-filter.md) |
| `NEKOPOST_DEEPSEEK_MODEL` / `NEKOPOST_DEEPSEEK_API_BASE` | `deepseek-flash` / `https://api.deepseek.com` | DeepSeek 模型与 API 地址 |
| `NEKOPOST_FILTER_TYPES` | `sms` | 需要分类的消息类型 |
| `NEKOPOST_FILTER_DROP` | `roaming,marketing,spam` | 不推送的类别 |
| `NEKOPOST_FILTER_KEEP_DAYS` | `7` | 被过滤的短信可以找回的天数（仅 `NEKOPOST_AUTO_DELETE=true` 时生效）。显式写在环境变量里并且比正文保留期更长时，才会抬高正文保留期，并在启动日志里警告 |
| `NEKOPOST_PUBLIC_URL`、`NEKOPOST_GOOGLE_CLIENT_ID`、`NEKOPOST_WEB_ALLOW` | 空 | 网页面板，见 [web.md](web.md)。`WEB_ALLOW` 为空时面板关闭 |
| `NEKOPOST_FCM_CREDENTIALS_FILE`、`NEKOPOST_FCM_PROJECT_ID` | 空 | Android 推送，见 [android.md](android.md)。服务账号默认取 systemd 凭证 `nekopost-fcm-sa` |

配置错误时服务拒绝启动，并在日志中逐条说明哪一项有问题；`nekopost check` 也会显示。

---

## 4. 日常管理

### 服务

```bash
sudo systemctl status nekopost        # 状态
sudo systemctl restart nekopost       # 重启
sudo journalctl -u nekopost -f        # 实时日志
sudo journalctl -u nekopost --since "1 hour ago"
sudo nekopost check                   # 自检
sudo nekopost keys status             # 主密钥前几位、数据密钥数量、已使用天数（不打印密钥）
```

### 消息来源

```bash
sudo nekopost source list
sudo nekopost source add linux --name "Linux Server" --icon 🐧
sudo nekopost source show iphone
sudo nekopost source set iphone --name "我的 iPhone" --icon 📱
sudo nekopost source disable iphone   # 暂停（拒绝新消息）
sudo nekopost source enable iphone
sudo nekopost source rotate iphone    # 换新凭证，旧凭证立即失效
sudo nekopost source remove iphone --yes
```

来源修改**立即生效，不需要重启服务**。命令以 root 运行时会自动切换为 `nekopost` 用户访问数据库，不会产生权限问题。

`source add`、`source rotate` 会在终端里打印凭证（`--qr` 还会打印二维码），请在自己的终端里执行，输出不要进入聊天、日志或工单。

### 设备、网页登录和主密钥

```bash
sudo nekopost device list                       # 已配对的设备
sudo nekopost device pair --name Fold8 --role full --source fold8   # 生成配对码，见 android.md
sudo nekopost approve                           # 在服务器上确认网页登录的号码匹配
sudo nekopost web unlock google:<sub>           # 解除网页登录冷却
sudo nekopost web audit verify                  # 校验审计链
sudo nekopost inbox search <关键词>              # 搜索保留期内的正文
sudo nekopost keys status                       # 主密钥状态
```

主密钥的生成、备份、轮换见 [encryption.md](encryption.md)。

### 只读管理 API 密钥（可选）

```bash
sudo nekopost admin-key create monitoring
sudo nekopost admin-key list
sudo nekopost admin-key revoke mga_xxxxxx
```

用于 `/v1/admin/*` 接口（见 [api.md](api.md)），不需要时可以不创建。`admin-key create` 会打印密钥，同样请在自己的终端里执行。

---

## 5. 更新

`deploy/install.sh` 的顺序是：**先编译**（这段时间旧服务继续运行）→ 原子替换二进制 → 没有主密钥才生成（失败则中止，不重启）→ 安装 unit → **最后才重启**并执行 `nekopost check`。停机只有重启的时间，约 1 秒。它不会覆盖已有的配置文件、凭证和数据库。

### 升级步骤

```bash
# 1. 拉取代码（生产跟踪 v2 分支）
cd /root/message_server && git pull --ff-only
cat VERSION                                  # 新版本号

# 2. 备份当前数据和旧二进制，路径带版本和时间，回滚要用
B=/root/nekopost-backup-before-$(cat VERSION)-$(date -u +%Y%m%dT%H%M%SZ)
sudo mkdir -p "$B"
sudo cp -a /var/lib/nekopost "$B/data"
sudo cp -a /usr/local/bin/nekopost "$B/nekopost-$(nekopost version | awk '{print $2}')"
#   运行中复制会把 -wal 文件一起复制。需要严格一致的备份时，先 systemctl stop nekopost，复制完再 start。

# 3. 数据库结构有变化时先演练（见下文）

# 4. 升级
sudo ./deploy/install.sh

# 5. 验证
nekopost version
sudo nekopost check
sudo nekopost keys status
curl -s https://osaka.drinkcat.com/healthz
```

重启期间的请求会失败，快捷指令会显示连接错误，重试即可。重启前正在投递的消息会在启动后自动重新投递（可能重复一次，见 [architecture.md](architecture.md)）。

### 演练（有数据库迁移时）

新版本升级时会先把数据库备份成 `nekopost.db.pre-v<N>-<时间>.bak`，再迁移。在升级之前，可以把数据复制到临时目录，用新二进制打开，让迁移先在副本上跑一遍：

```bash
R=$(sudo mktemp -d /var/tmp/np-rehearse.XXXXXX)
sudo cp -a /var/lib/nekopost/. "$R"/ && sudo chown -R nekopost:nekopost "$R" && sudo chmod 700 "$R"
sudo NEKOPOST_DATA_DIR="$R" ./bin/nekopost check
sudo NEKOPOST_DATA_DIR="$R" ./bin/nekopost keys status
sudo NEKOPOST_DATA_DIR="$R" ./bin/nekopost web audit verify
sudo rm -rf "$R"
```

`./bin/nekopost` 需要先编译：`CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=$(cat VERSION)" -o bin/nekopost ./cmd/nekopost`（和 `install.sh` 的编译命令相同），编译期间旧服务照常运行。副本要放在 `nekopost` 用户能访问的位置：以 root 运行时这些命令会切换成 `nekopost` 用户，副本放在 `/root` 下会报 `permission denied`。**不要在副本上运行 `serve`**，否则会和线上 Bot 争抢 `getUpdates`。副本里有解密后的数据，用完立刻删除。各版本的实际升级记录见 [v2/upgrade-0.6.0.md](v2/upgrade-0.6.0.md)、[0.9.0](v2/upgrade-0.9.0.md)、[0.10.0](v2/upgrade-0.10.0.md)。

### 回滚

数据库结构只会向前升级，**旧二进制打不开已经迁移过的库**。

| 这次升级 | 回滚方法 |
|---|---|
| 没有数据库迁移（`user_version` 不变） | 只换回备份里的旧二进制，再 `systemctl restart nekopost` |
| 有数据库迁移 | 停服务，**同时**换回旧二进制和升级前的 `data/` 备份，再启动 |

```bash
sudo systemctl stop nekopost
sudo install -m 0755 "$B/nekopost-<旧版本>" /usr/local/bin/nekopost
sudo rsync -a --delete "$B/data/" /var/lib/nekopost/      # 仅在有数据库迁移时
sudo systemctl start nekopost && sudo nekopost check
```

不要只依赖迁移程序自己生成的 `pre-v<N>` 备份，它默认 24 小时后会被删除，而且升级前要自己备份。回滚会丢掉升级之后产生的消息。

---

## 6. 备份与迁移

**主密钥和数据库不要放在同一份备份里。** 数据库备份里只有密文和被主密钥包裹的数据密钥，单独拿走解不开正文；如果把主密钥放在一起，加密就失去意义。

### 备份

```bash
sudo systemctl stop nekopost
sudo tar czf nekopost-backup.tgz /etc/nekopost/nekopost.env /var/lib/nekopost
sudo systemctl start nekopost
```

这份备份包含数据库、证书和配置，**不含主密钥**。主密钥另存：

```bash
sudo nekopost keys export | <密码管理器的写入命令>
```

`/etc/credstore.encrypted/` 里的文件是用**本机**的 systemd 主机密钥加密的，只拷贝文件到别的机器无法解开。网页面板的 Google Client Secret 和 FCM 服务账号也在这个目录，需要的话在新机器上重新用 `systemd-creds encrypt` 生成（见 [web.md](web.md)、[android.md](android.md)）。

数据库中有来源配置（凭证以哈希保存）、设备公钥、元数据、加密后的正文和尚未投递完成的消息。

### 迁移到新服务器

1. 在新服务器上安装程序：`sudo ./deploy/install.sh`。这时 `nekopost.env` 还没有填，服务不会启动；脚本会生成一把**临时的**主密钥，下一步换掉它。
2. 换成**旧的**主密钥。在新服务器自己的终端里，把密码管理器里的内容粘贴进去，再加密保存（粘贴后按回车，输入不回显）：

   ```bash
   sudo rm /etc/credstore.encrypted/nekopost-kek
   read -rs -p '主密钥: ' kek; printf '\n'
   printf '%s\n' "$kek" | sudo systemd-creds encrypt --name=nekopost-kek - /etc/credstore.encrypted/nekopost-kek
   unset kek
   ```

   `keys export` 输出的是 base64，服务可以直接读取。**必须在服务第一次启动之前完成**：如果用临时主密钥启动过，新数据会用临时密钥加密，旧数据又解不开，两者混在一起。
3. 恢复数据和配置：解包备份，再 `sudo chown -R nekopost:nekopost /var/lib/nekopost`。
4. 修改 DNS，指向新服务器。
5. 先在旧服务器上 `sudo systemctl disable --now nekopost`（同一个 Bot 只能有一个实例轮询，两个同时运行会互相冲突），再在新服务器上 `sudo systemctl restart nekopost`。
6. 执行 `sudo nekopost check` 和 `sudo nekopost keys status`，确认主密钥前几位和旧服务器一致。

迁移到 Docker 见 [docker.md](docker.md#5-从-systemd-实例迁移)。

---

## 7. 卸载

```bash
sudo systemctl disable --now nekopost
sudo rm /etc/systemd/system/nekopost.service /usr/local/bin/nekopost
sudo systemctl daemon-reload
sudo rm -rf /etc/nekopost /var/lib/nekopost      # 会删除所有数据
sudo rm -f /etc/credstore.encrypted/nekopost-*   # 会删除主密钥，数据库中的正文将永远无法解开
sudo userdel nekopost
sudo ufw delete allow 80/tcp; sudo ufw delete allow 443/tcp
```

---

## 8. systemd 服务说明

`deploy/nekopost.service` 的要点：

- `Type=notify` + `WatchdogSec=5min`：程序启动完成后才报告就绪；若数据库或投递循环卡死，程序停止发送心跳，systemd 自动重启。Telegram 不可达**不会**触发重启（重启解决不了网络问题）。
- `Restart=always`：任何异常退出 5 秒后自动重启；系统重启后自动启动。
- 以无特权用户 `nekopost` 运行，仅授予绑定 80/443 端口的能力（`CAP_NET_BIND_SERVICE`）。
- `ImportCredential=nekopost-*`：把 `/etc/credstore.encrypted/` 里的加密凭证（主密钥等）解密后放进服务私有的 `$CREDENTIALS_DIRECTORY`，服务以外的进程读不到。
- 沙箱：文件系统只读（仅数据目录可写）、无法访问 `/home`、私有 `/tmp`、禁止提权、限制系统调用等。`LimitCORE=0` 禁止生成 core dump，避免密钥落盘。
- 资源上限：内存 192 MB（`GOMEMLIMIT=96MiB` 让 Go 尽早回收内存）、最多 64 个线程。
