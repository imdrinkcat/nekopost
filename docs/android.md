# Android 收件箱和设备配对

现有 Android 转发设置可继续使用。安装新版后，在「收件箱」解锁并配对；只接收的设备可以从设置向导跳到收件箱。

## 配对

新增设备不需要 SSH，三种方式任选其一，都会生成同样的五分钟配对码，审核也都是号码匹配：

1. **Telegram**：在 Bot 里打开「📱 设备」→「➕ 添加设备」（或发送 `/adddevice`），选择权限（完整 / 仅接收 / 仅转发），需要转发权限时再选来源（已有来源或「🆕 新来源」）。Bot 会发来一张二维码图片，图片说明里有服务器地址、手动配对码和完整的 `nekopost://enroll?...kind=device` 配对链接，三者都在可轻点复制的代码块里。新手机扫码即可；**如果要配对的就是正在看 Telegram 的这台手机**，复制链接或配对码，在猫邮局里点「粘贴配对链接」。配对码被使用、作废（「作废此配对码」按钮）或过期后，这条消息会自动删除。新手机申请后，Bot 会另发一条号码匹配消息，选出手机上显示的两位数字。只有管理员能用，10 分钟内最多生成 3 个配对码；创建和作废都写入审计日志（操作者 `bot:tg:<用户 ID>`），其他管理员会收到提醒。
2. **已配对的完整权限手机**：设置 › 设备 ›「添加设备」，使用 `POST /v1/admin/pairings`，需要生物识别重新认证（`k_reauth`）。
3. **服务器终端**（仍然可用）：

```bash
sudo nekopost device pair --name Fold8 --role full --source fold8
```

需要发送权限的配对应绑定正确的来源 ID。同一服务器、同一来源的既有转发配置在配对后可使用设备签名认证。扫码、粘贴配对链接，或手动填写地址及配对码，核对服务器域名，再在 Telegram、管理手机或服务器终端选出手机显示的两位数字。不要把配对码转发给别人，也不要把凭证或消息正文发到聊天里。Bot 发出的配对消息禁止转发，并在用过或过期后删除。

「仅接收」角色使用 `--role receiver`。「仅转发」使用 `--role sender`。Nekopost 使用 Android Keystore 中的 P-256 设备密钥。另一把需要强生物识别的密钥用于管理操作，因此配对前请先在设备设置系统屏幕锁和强生物识别。

## 只用 App 接收（不用 Telegram）

`NEKOPOST_TELEGRAM_TYPES` 决定哪些消息类型发到 Telegram（`*` 为全部，默认；`none` 为都不发；或用逗号分隔的类型，如 `sms`）。Telegram 不接收的类型由 App 推送作为主通道：推送被 FCM 接受即算送达（`已推送到 App`），失败会按退避重试最长 24 小时。设备自己的推送设置（类型、最低优先级、类别）排除的消息记为 `skipped`，不算失败；转发这条短信的手机本身默认不重复推送。都没有可推送设备时状态是 `not_routed`，但只要服务器保留正文（`NEKOPOST_BODY_RETENTION_DAYS` > 0），消息仍可在 App 收件箱里看到。Bot 不受影响，继续用于管理和审批。`GET /v1/devices/self` 的 `telegram_types`（`null` = 全部）告诉 App 哪些类型只通过 App 接收。

## FCM

按 [V2 设计](v2/design.md) §14.1 创建 Firebase 项目、正式 Android 应用和服务账号。正式包名是 `com.drinkcat.nekopost`。备份正式签名密钥，并在仓库配置下列 **variables**：

- `FIREBASE_PROJECT_ID`
- `FIREBASE_APP_ID`
- `FIREBASE_API_KEY`
- `FIREBASE_SENDER_ID`

服务器使用 `nekopost-fcm-sa` systemd 加密凭证，或 `NEKOPOST_FCM_CREDENTIALS_FILE` 所指定的服务账号文件。把内容放进系统凭证，保持服务账号 JSON 与 APK 签名私钥在仓库之外。

未配置 FCM 时可以手动刷新和在前台通过事件流接收更新。配对后才启用 Firebase Messaging。FCM 数据载荷只包含消息 ID、序号及协议类型，从不包含正文：

| `t` | 其他字段 | 含义 |
|---|---|---|
| `msg` | `id`、`seq` | 新消息。App 用 `GET /v1/inbox/{id}?via=push&ack=1` 取详情并确认收到（审计为 `message.push_fetch`，不算查看） |
| `read` | `ids`（逗号分隔，最多 50 个） | 消息已在其他设备标为已读，清除对应通知（NORMAL 优先级，ttl 1 小时） |
| `gone` | `ids` | 消息已删除或正文已销毁，清除对应通知 |
| `ping` | — | 测试推送 |
| `revoked` | — | 设备已吊销 |

Android 发出的本地通知在锁屏中只显示公共版本；正文经设备验证后在收件箱读取。

收件箱列表、搜索和同步条目另有：`sender`（未打码的标题：短信号码或「应用名 · 标题」，最多 64 字；正文过期时为 `null`；只遮盖紧跟验证码关键词的验证码）、`app_only`、`deliveries[].aux`。`GET /v1/inbox` 返回 `unread_count`，并支持 `?unread=1`、`?has_code=1` 过滤。`title`、`preview` 仍然打码，保持兼容。

## 真机验收

1. 保留旧 App 数据安装同签名的新 APK。确认原短信及通知转发工作，然后完成设备配对。
2. 在电脑和手机上确认新设备可读消息。测试系统锁屏、应用闲置锁、搜索、到期正文和前台事件更新。
3. 发送测试推送，再从另一个来源提交普通消息。检查锁屏只显示通用提示，解锁后打开详情，并核对网页投递回执。
4. 在掉线后恢复网络，检查收件箱和通知。操作自己的设备前，用测试设备演练暂停读取、吊销和重新配对。
5. 走完[折叠屏验收](foldables.md)。

正式 APK 由 `app-v*` 标签构建，需要事先配置 Android 签名 secrets。除非另有显式授权，工作分支的 CI 仅生成验证产物。
