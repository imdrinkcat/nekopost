# 网页只读面板

Nekopost 0.7.0 的网页面板使用 Google 登录。面板可以浏览消息、搜索保留期内的正文，查看已过滤消息、系统状态、统计、来源、审计和设置。

## 配置

Google 客户端类型为 **Web application**。在 Google Auth Platform 的 **Authorized redirect URIs** 填入站点的 HTTPS 地址加 `/auth/google/callback`。在 **Audience** 保持 **External / Testing**，在测试用户里加入要登录的账号。

对外地址默认取内置 HTTPS 配置的 `NEKOPOST_DOMAIN`。如果由代理终止 TLS，设置 `NEKOPOST_PUBLIC_URL` 为对外地址，例如 `https://osaka.drinkcat.com`。

在服务环境中设置下面两项。允许列表使用账号的 Google `sub`，邮箱只用于检查 Google 是否已验证该账号。

```ini
NEKOPOST_GOOGLE_CLIENT_ID=<Google Client ID>
NEKOPOST_WEB_ALLOW=google:<你的 sub>
```

如果还不知道 `sub`，可先填 `NEKOPOST_WEB_ALLOW=google:0`。启动后用自己的 Google 账号登录一次，被拒绝的页面会显示当前账号的 `google:<sub>`。把这一整串填回允许列表后再加载配置。允许列表为空时面板关闭。

在自己的 SSH 终端把 **Client Secret** 保存为 systemd 加密凭证。粘贴后按回车。终端不会回显输入。

```bash
read -rs -p 'Google Client Secret: ' nekopost_google_secret
printf '\n'
printf '%s' "$nekopost_google_secret" | sudo systemd-creds encrypt \
  --name=nekopost-google-client-secret - /etc/credstore.encrypted/nekopost-google-client-secret
unset nekopost_google_secret
```

也可用 `NEKOPOST_GOOGLE_CLIENT_SECRET_FILE` 指向只有服务用户能读取的文件。容器可挂载 `/run/secrets/nekopost_google_client_secret`。如果读取凭证失败，服务会记录警告并关闭面板，消息接收与转发仍可用。

0.7.0 首次启动会将 SQLite 从 v3 迁移到 v4。升级前保留 0.6.0 的二进制和数据目录的完整备份。需要回滚时同时恢复二进制与升级前的数据目录。

## 登录和检查

- 电脑和 Fold8 Chrome 分别访问站点。点击「使用 Google 登录」，选择允许列表里的账号。
- 浏览器显示两位数字。在 Telegram 的管理员私聊中点相同数字，也可在服务器运行 `sudo nekopost approve`。CLI 可额外选择「信任此浏览器 30 天」。
- 确认窗口为 5 分钟。选错或拒绝会触发冷却。管理员可用 `sudo nekopost web unlock google:<sub>` 解除限制。
- 确认前无法访问详情和搜索。确认后可打开消息详情，检查来源和投递记录，并查看总览与统计。
- 使用白名单外的测试账号时应显示「此账号未获授权」。由于 Google Testing 自身也限制测试账号，若要测试 Nekopost 的允许列表，需要先把第二个测试账号加入 Google 的测试用户，验证完成后再移除。
- 审计链可在面板的「安全」页或通过 `sudo nekopost web audit verify` 检查。
