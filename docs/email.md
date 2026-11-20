# 邮件通道

0.11.0 起，每条消息可以再发一封邮件。邮件是**辅助通道**，和 App 推送的「副本」一样：Telegram（或 App）仍然决定消息的状态，邮件失败不会让消息变成「失败」。

默认关闭。设置了 `NEKOPOST_SMTP_HOST` 才启用。

## 先想清楚：内容会离开服务器

邮件一旦发出，正文就留在你的邮件服务商和收件箱里。本服务的 7 天 / 24 小时删除、手动删除按钮，都**不会**删掉已经发出的邮件。验证码类短信尤其如此。

用 `NEKOPOST_EMAIL_PRIVACY` 控制发什么：

| 值 | 邮件里有什么 |
|---|---|
| `full`（默认） | 标题、正文、来源、类型、时间、消息 ID |
| `masked` | 同上，但数字和链接被隐藏（和发给 AI 分类前的处理相同） |
| `ping` | 只有「某来源收到了一条新消息」和时间、消息 ID，没有内容 |

只想要一个提醒的话用 `ping` 或 `masked`。

## 配置

```bash
# /etc/nekopost/nekopost.env
NEKOPOST_SMTP_HOST=smtp.example.com
NEKOPOST_SMTP_SECURITY=starttls        # 或 tls
NEKOPOST_SMTP_USERNAME=neko@example.com
NEKOPOST_EMAIL_FROM=nekopost@example.com
NEKOPOST_EMAIL_TO=me@example.org
```

密码不放在环境文件里，放到凭证文件。**在你自己的终端执行，不要把密码贴进聊天**：

```bash
# systemd
read -rs -p "SMTP 密码: " P; echo
printf '%s' "$P" | sudo systemd-creds encrypt --name=nekopost-smtp-password - /etc/credstore.encrypted/nekopost-smtp-password
unset P
```

`install.sh` 写的 unit 已经带 `ImportCredential=nekopost-*`，所以服务会自动读到 `nekopost-smtp-password`。也可以用 `NEKOPOST_SMTP_PASSWORD_FILE` 指向任意一个只有服务用户能读的文件。

Docker：在 `compose.yaml` 里加一个 secret，挂载到 `/run/secrets/nekopost_smtp_password`（和 `nekopost_kek` 的写法一样）。

| 变量 | 默认 | 说明 |
|---|---|---|
| `NEKOPOST_SMTP_HOST` | 空 | 设置后启用邮件 |
| `NEKOPOST_SMTP_PORT` | `587`（tls 为 `465`） | |
| `NEKOPOST_SMTP_SECURITY` | `starttls` | `starttls`、`tls`，或只用于本机中继的 `none` |
| `NEKOPOST_SMTP_USERNAME` | 空 | 留空则不认证（只用于本机中继） |
| `NEKOPOST_SMTP_PASSWORD_FILE` | 见上 | 密码文件 |
| `NEKOPOST_EMAIL_FROM` | 必填 | 发件人地址（不含显示名） |
| `NEKOPOST_EMAIL_TO` | 必填 | 收件人，逗号分隔，最多 5 个 |
| `NEKOPOST_EMAIL_TYPES` | `*` | 发邮件的消息类型，写法同 `NEKOPOST_TELEGRAM_TYPES` |
| `NEKOPOST_EMAIL_PRIVACY` | `full` | `full`、`masked`、`ping` |

配置有错时，邮件会被关掉，其他功能照常运行，原因写在启动日志里（`email disabled`）。

改完执行 `sudo nekopost check --send-test`，它会发一封测试邮件，并把服务器、认证或收件人的问题直接打印出来。

## 安全上的取舍

- **只走加密连接。** `starttls` 要求服务器支持 STARTTLS，不支持就拒绝发送，不会退回明文；证书必须通过校验。登录凭据只会在加密之后发送。
- `none` 只允许 `localhost`、`127.0.0.1`、`::1`，用于同机的邮件中继。
- 邮件头里只有配置好的发件人和收件人，来自短信的标题和发件人不会被写进任何头部（换行会被去掉，主题经过编码）。
- 同一条消息每次发送用同一个 `Message-ID`，重发造成的重复邮件在邮件客户端里能看出是重复。
- 收件人和发件人只能在服务器上改配置，Telegram Bot 里不能增删，避免 Bot 被拿下后多出一条外发通道。

## 行为

- **只发辅助副本。** 邮件失败不影响消息状态，也不计入「Telegram 转发」健康项。最终失败的邮件会出现在 Bot 的「最近错误」里（组件「邮件」），也能在消息的投递详情里看到原因。
- **重试最长 1 小时**（辅助通道的统一规则），之后放弃。
- **至少一次。** 如果邮件已发出但没收到服务器的确认（比如连接中途断开），会重发，可能收到两封，投递记录会标成「结果不确定」。
- **被短信过滤判定为广告的消息不发邮件**，和不发 Telegram 一样。
- Telegram 不接收的类型（`NEKOPOST_TELEGRAM_TYPES`）可以只发邮件，但邮件是辅助通道，消息状态会是「未转发」，除非同时有 App 推送作为主通道。
- 邮件只含文本，不支持附件。

## 常见问题

| 现象 | 原因 |
|---|---|
| `check` 报「邮件服务器不支持 STARTTLS」 | 换成 `NEKOPOST_SMTP_SECURITY=tls` 和 465 端口，或确认端口是 587 |
| 「无法验证邮件服务器的证书」 | 服务器地址和证书不一致，或证书不受信任。用证书上的域名作 `NEKOPOST_SMTP_HOST` |
| 「SMTP 认证失败」 | 用户名或密码错误；Gmail、Outlook 等需要应用专用密码 |
| 「邮件服务器拒绝（550 …）」 | 发件地址没有被服务器允许，或收件地址不存在 |
| 收不到 | 看垃圾邮件箱；自建服务器发邮件通常需要 SPF / DKIM，建议改用服务商的 SMTP |
