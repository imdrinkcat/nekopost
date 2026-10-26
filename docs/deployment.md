# Linux 部署指南

本项目以 **单个 Go 程序 + systemd** 的方式部署，不使用 Docker。

> **为什么不用 Docker？** 原需求写的是 Docker 部署。评估时发现：目标服务器只有 1 vCPU、约 1 GB 内存，Docker 守护进程（dockerd + containerd）常驻就要占用几十到上百 MB，比网关本身还多；容器中读取宿主机指标、处理 IPv6 网络也会增加复杂度。而 systemd 已经提供了自动重启、开机自启、日志、资源限制和沙箱隔离。经确认，改为 systemd 部署。

当前环境：Ubuntu 24.04，域名 `osaka.drinkcat.com`（A 记录指向本机），Go 1.27。

---

## 1. 系统要求

| 项目 | 要求 |
|---|---|
| 系统 | 任意使用 systemd 的 Linux（已在 Ubuntu 24.04 上验证） |
| 资源 | 1 vCPU、512 MB 内存即可；服务自身限制在 160 MB 以内（实测空闲约 19 MB，见 [testing.md](testing.md)） |
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
# 1. 获取代码（本机代码位于 /root/message_server）
cd /root/message_server

# 2. 安装（编译、创建 msggw 系统用户、安装 systemd 服务）
sudo ./deploy/install.sh

# 3. 填写配置
sudo nano /etc/msggw/msggw.env
#   MSGGW_DOMAIN=osaka.drinkcat.com
#   MSGGW_TELEGRAM_BOT_TOKEN=...      （见 telegram-bot.md）
#   MSGGW_TELEGRAM_ADMIN_IDS=...

# 4. 开放防火墙
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
#   如果云服务商还有「安全组」，同样需要放行 80/443

# 5. 启动并检查
sudo systemctl restart msggw
sudo msggw check --send-test

# 6. 创建第一个消息来源
sudo msggw source add iphone --name iPhone --icon 📱
```

首次启动时，服务会自动向 Let's Encrypt 申请证书（通常十几秒内完成）。之后在证书到期前 30 天自动续期，**无需任何定时任务**。

### 安装后的文件位置

| 路径 | 内容 | 权限 |
|---|---|---|
| `/usr/local/bin/msggw` | 程序 | root 755 |
| `/etc/msggw/msggw.env` | 配置（含 Bot Token） | root 600 |
| `/etc/systemd/system/msggw.service` | systemd 服务 | root 644 |
| `/var/lib/msggw/msggw.db` | SQLite 数据库 | msggw 600 |
| `/var/lib/msggw/acme/` | 证书与 ACME 账号密钥 | msggw 700 |

---

## 3. 配置项

所有配置都在 `/etc/msggw/msggw.env`，修改后执行 `sudo systemctl restart msggw`。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `MSGGW_DOMAIN` | — | 必填（autocert 模式）。证书只为这个域名签发 |
| `MSGGW_TELEGRAM_BOT_TOKEN` | — | 必填。BotFather 给出的 Token |
| `MSGGW_TELEGRAM_ADMIN_IDS` | — | 必填。管理员数字 ID，逗号分隔 |
| `MSGGW_TELEGRAM_CHAT_ID` | 第一个管理员 | 消息投递到的聊天 |
| `MSGGW_TLS_MODE` | `autocert` | `autocert`：自动 HTTPS；`off`：纯 HTTP（仅在反向代理之后或测试时使用） |
| `MSGGW_HTTPS_ADDR` / `MSGGW_HTTP_ADDR` | `:443` / `:80` | 监听地址。`off` 模式只用 `HTTP_ADDR` |
| `MSGGW_ACME_EMAIL` | 空 | Let's Encrypt 联系邮箱（可选） |
| `MSGGW_DATA_DIR` | `/var/lib/msggw` | 数据目录 |
| `MSGGW_TIMEZONE` | `Asia/Tokyo` | 显示用的时区 |
| `MSGGW_RETENTION_DAYS` | `30` | 元数据保留天数（正文投递结束后立即删除，不受此项影响） |
| `MSGGW_DELIVERY_MAX_AGE` | `24h` | 投递失败后最长重试多久 |
| `MSGGW_DEDUPE_WINDOW` | `60s` | 无幂等键时，同一来源相同内容在此时间内视为重复；`0s` 关闭 |
| `MSGGW_MAX_BODY_CHARS` | `12000` | 正文最大字符数 |
| `MSGGW_SOURCE_RATE_PER_MIN` | `60` | 每个来源每分钟最多提交条数 |
| `MSGGW_LOG_LEVEL` | `info` | `debug` / `info` / `warn` / `error` |
| `MSGGW_DEEPSEEK_API_KEY` | 空 | 设置后启用短信过滤（DeepSeek），详见 [sms-filter.md](sms-filter.md) |
| `MSGGW_DEEPSEEK_MODEL` / `MSGGW_DEEPSEEK_API_BASE` | `deepseek-flash` / `https://api.deepseek.com` | DeepSeek 模型与 API 地址 |
| `MSGGW_FILTER_TYPES` | `sms` | 需要分类的消息类型 |
| `MSGGW_FILTER_DROP` | `roaming,marketing,spam` | 不推送的类别 |
| `MSGGW_FILTER_KEEP_DAYS` | `7` | 被过滤的短信可以找回的天数 |

配置错误时服务拒绝启动，并在日志中逐条说明哪一项有问题；`msggw check` 也会显示。

---

## 4. 日常管理

### 服务

```bash
sudo systemctl status msggw        # 状态
sudo systemctl restart msggw       # 重启
sudo journalctl -u msggw -f        # 实时日志
sudo journalctl -u msggw --since "1 hour ago"
sudo msggw check                   # 自检
```

### 消息来源

```bash
sudo msggw source list
sudo msggw source add linux --name "Linux Server" --icon 🐧
sudo msggw source show iphone
sudo msggw source set iphone --name "我的 iPhone" --icon 📱
sudo msggw source disable iphone   # 暂停（拒绝新消息）
sudo msggw source enable iphone
sudo msggw source rotate iphone    # 换新凭证，旧凭证立即失效
sudo msggw source remove iphone --yes
```

来源修改**立即生效，不需要重启服务**。命令以 root 运行时会自动切换为 `msggw` 用户访问数据库，不会产生权限问题。

### 只读管理 API 密钥（可选）

```bash
sudo msggw admin-key create monitoring
sudo msggw admin-key list
sudo msggw admin-key revoke mga_xxxxxx
```

用于 `/v1/admin/*` 接口（见 [api.md](api.md)），不需要时可以不创建。

---

## 5. 更新

```bash
cd /root/message_server
# 更新代码后：
sudo ./deploy/install.sh       # 重新编译、替换程序、重启服务
```

安装脚本是幂等的：不会覆盖已有的配置文件和数据库。数据库结构如有变化会在启动时自动迁移。

重启期间（约 1–2 秒）的请求会失败，快捷指令会显示连接错误，重试即可。重启前正在投递的消息会在启动后自动重新投递（可能重复一次，见 [architecture.md](architecture.md)）。

---

## 6. 备份与迁移

需要备份的只有两个位置：

```bash
sudo systemctl stop msggw
sudo tar czf msggw-backup.tgz /etc/msggw /var/lib/msggw
sudo systemctl start msggw
```

数据库中只有来源配置（凭证以哈希保存）、元数据和尚未投递完成的消息。迁移到新服务器：安装程序 → 恢复这两个目录 → `chown -R msggw:msggw /var/lib/msggw` → 修改 DNS → 启动。

---

## 7. 卸载

```bash
sudo systemctl disable --now msggw
sudo rm /etc/systemd/system/msggw.service /usr/local/bin/msggw
sudo systemctl daemon-reload
sudo rm -rf /etc/msggw /var/lib/msggw      # 会删除所有数据
sudo userdel msggw
sudo ufw delete allow 80/tcp; sudo ufw delete allow 443/tcp
```

---

## 8. systemd 服务说明

`deploy/msggw.service` 的要点：

- `Type=notify` + `WatchdogSec=5min`：程序启动完成后才报告就绪；若数据库或投递循环卡死，程序停止发送心跳，systemd 自动重启。Telegram 不可达**不会**触发重启（重启解决不了网络问题）。
- `Restart=always`：任何异常退出 5 秒后自动重启；系统重启后自动启动。
- 以无特权用户 `msggw` 运行，仅授予绑定 80/443 端口的能力（`CAP_NET_BIND_SERVICE`）。
- 沙箱：文件系统只读（仅数据目录可写）、无法访问 `/home`、私有 `/tmp`、禁止提权、限制系统调用等。
- 资源上限：内存 160 MB（`GOMEMLIMIT=64MiB` 让 Go 尽早回收内存）、最多 64 个线程。
