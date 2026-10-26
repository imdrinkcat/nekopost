# msggw · 个人消息网关

一个轻量的个人消息网关：统一接收来自 iPhone、服务器、脚本等来源的消息，转发到 Telegram；同时可以在 Telegram Bot 中查看网关、Bot 和服务器的运行状态。

```
iPhone 快捷指令 ─┐
Linux / cron  ───┼─HTTPS─▶  msggw  ──▶ Telegram
其他程序 / 设备 ──┘        （认证·去重·持久化·重试）
                               ▲
              Telegram Bot 管理菜单（状态 / 来源 / 统计）
```

- **单个 Go 程序 + SQLite + systemd**，无外部依赖；空闲时进程内存约 19 MB、CPU 约 0.01%（实测见 [docs/testing.md](docs/testing.md)）。
- 每个来源独立凭证，可单独停用 / 轮换；来源身份由凭证决定，无法伪造。
- 消息先持久化再确认，Telegram 故障时自动重试（至少一次，最长 24 小时）；明确区分「已接收」「已送达」「失败」「结果不确定」。
- 正文在投递结束后立即删除，日志中不记录正文和凭证。
- 自动申请和续期 Let's Encrypt 证书。
- 中文 Telegram 管理菜单：系统总览、机器人状态、服务器状态、消息来源、消息统计、系统设置。
- 可选的短信过滤：用 DeepSeek 识别广告和垃圾短信，不推送但可在 Bot 里找回；验证码不发给 AI，AI 故障时直接转发。

## 快速开始

```bash
sudo ./deploy/install.sh                 # 编译并安装 systemd 服务
sudo nano /etc/msggw/msggw.env           # 填写 Bot Token 与管理员 ID
sudo systemctl restart msggw
sudo msggw check --send-test             # 自检并发送测试消息
sudo msggw source add iphone --name iPhone --icon 📱   # 创建来源，记下凭证
```

发送一条消息：

```bash
curl https://osaka.drinkcat.com/v1/messages?wait=8 \
  -H "Authorization: Bearer mgs_..." -H "Content-Type: application/json" \
  -d '{"title":"你好","body":"第一条消息 👋"}'
```

## 文档

| 文档 | 内容 |
|---|---|
| [docs/deployment.md](docs/deployment.md) | Linux 部署指南（安装、配置项、更新、备份、卸载） |
| [docs/telegram-bot.md](docs/telegram-bot.md) | Telegram Bot 配置教程、菜单及交互说明 |
| [docs/iphone-shortcuts.md](docs/iphone-shortcuts.md) | iPhone 快捷指令配置教程（手动发送、短信自动化、iOS 限制） |
| [docs/api.md](docs/api.md) | 对外接口说明 |
| [docs/message-model.md](docs/message-model.md) | 统一消息模型说明 |
| [docs/sms-filter.md](docs/sms-filter.md) | 短信过滤（DeepSeek）：流程、隐私、配置、找回 |
| [docs/adding-sources.md](docs/adding-sources.md) | 消息来源扩展指南（含其他系统示例、新增输出通道） |
| [docs/architecture.md](docs/architecture.md) | 项目架构说明 |
| [docs/operations.md](docs/operations.md) | 服务监控与故障排查 |
| [docs/security.md](docs/security.md) | 安全配置指南与隐私风险 |
| [docs/limitations.md](docs/limitations.md) | 已知限制与未来扩展建议 |
| [docs/testing.md](docs/testing.md) | 自动化测试与部署验证结果 |

## 开发

```bash
make test        # 单元测试 + 集成测试
make race        # 带竞态检测运行全部测试
make vet         # gofmt + go vet
make build       # 编译到 bin/msggw
sudo make install
```

目录结构：

```
cmd/msggw/            程序入口与命令行（serve / check / source / admin-key）
internal/config       配置加载与校验
internal/model        统一消息模型与状态
internal/auth         凭证生成、哈希、内容指纹
internal/store        SQLite 持久化（来源、消息、投递、统计）
internal/httpapi      HTTP 接口、认证、限流、HTTPS 证书
internal/router       路由：消息 → 投递目标
internal/filter       短信过滤：验证码识别、遮盖、DeepSeek 分类
internal/delivery     持久化发件箱、重试、Telegram 输出通道
internal/render       消息 → Telegram 文本（转义、拆分）
internal/telegram     精简的 Telegram Bot API 客户端
internal/bot          管理 Bot（菜单、权限、视图）
internal/health       运行状态采集与组件状态评估
internal/status       状态 / 统计查询服务
internal/sysinfo      服务器指标（/proc、statfs）
internal/ratelimit    限流与认证失败封禁
internal/sdnotify     systemd 就绪通知与看门狗
internal/app          组件装配与生命周期
deploy/               systemd 服务、配置模板、安装脚本
```
