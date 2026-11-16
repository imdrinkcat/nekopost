# Android 收件箱和设备配对

现有 Android 转发设置可继续使用。安装新版后，在「收件箱」解锁并配对；只接收的设备可以从设置向导跳到收件箱。

## 配对

在自己的 SSH 终端创建一个五分钟配对：

```bash
sudo nekopost device pair --name Fold8 --role full --source fold8
```

需要发送权限的配对应绑定正确的来源 ID。同一服务器、同一来源的既有转发配置在配对后可使用设备签名认证。扫码或手动填写地址及配对码，核对服务器域名，再在 Telegram 或服务器终端选出手机显示的两位数字。不要把二维码、配对码、凭证或消息正文发到聊天里。

「仅接收」角色使用 `--role receiver`。「仅转发」使用 `--role sender`。Nekopost 使用 Android Keystore 中的 P-256 设备密钥。另一把需要强生物识别的密钥用于管理操作，因此配对前请先在设备设置系统屏幕锁和强生物识别。

## FCM

按 [V2 设计](v2/design.md) §14.1 创建 Firebase 项目、正式 Android 应用和服务账号。正式包名是 `com.drinkcat.nekopost`。备份正式签名密钥，并在仓库配置下列 **variables**：

- `FIREBASE_PROJECT_ID`
- `FIREBASE_APP_ID`
- `FIREBASE_API_KEY`
- `FIREBASE_SENDER_ID`

服务器使用 `nekopost-fcm-sa` systemd 加密凭证，或 `NEKOPOST_FCM_CREDENTIALS_FILE` 所指定的服务账号文件。把内容放进系统凭证，保持服务账号 JSON 与 APK 签名私钥在仓库之外。

未配置 FCM 时可以手动刷新和在前台通过事件流接收更新。配对后才启用 Firebase Messaging。FCM 数据载荷只包含消息 ID、序号及协议类型。Android 发出的本地通知在锁屏中显示通用提示；正文经设备验证后在收件箱读取。

## 真机验收

1. 保留旧 App 数据安装同签名的新 APK。确认原短信及通知转发工作，然后完成设备配对。
2. 在电脑和手机上确认新设备可读消息。测试系统锁屏、应用闲置锁、搜索、到期正文和前台事件更新。
3. 发送测试推送，再从另一个来源提交普通消息。检查锁屏只显示通用提示，解锁后打开详情，并核对网页投递回执。
4. 在掉线后恢复网络，检查收件箱和通知。操作自己的设备前，用测试设备演练暂停读取、吊销和重新配对。
5. 走完[折叠屏验收](foldables.md)。

正式 APK 由 `app-v*` 标签构建，需要事先配置 Android 签名 secrets。除非另有显式授权，工作分支的 CI 仅生成验证产物。
