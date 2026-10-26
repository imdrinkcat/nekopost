# 消息来源扩展指南

新增一个消息来源**不需要修改任何代码**：只要在服务器上创建来源、拿到凭证，然后让设备或程序发一个 HTTPS 请求即可。

## 1. 创建来源

在服务器上用 root 执行（CLI 会自动切换到 `msggw` 用户操作数据库）：

```bash
msggw source add linux-web --name "Web 服务器" --icon 🖥
```

输出示例：

```
已创建来源 linux-web（🖥 Web 服务器）

凭证（只显示这一次，请立即保存；服务器只保存其哈希）:

  mgs_2xY...（共 47 个字符）

提交地址: https://osaka.drinkcat.com/v1/messages
请求头:   Authorization: Bearer <上面的凭证>
```

- 来源 ID 只能使用小写字母、数字、`-` 和 `_`，以字母或数字开头，最长 32 个字符。ID 会出现在统计和日志中，显示名称和图标则用在 Telegram 消息里。
- **每个设备或程序单独建一个来源**。这样一个凭证泄露时，只需吊销这一个，不影响其他来源。
- 新来源立即生效，无需重启服务。

常用的管理命令：

```bash
msggw source list                       # 列出来源、状态、最近请求时间
msggw source show linux-web             # 详情与凭证记录（只显示前缀）
msggw source set linux-web --name "Web-1" --icon 🌐
msggw source disable linux-web          # 暂停：新消息返回 403，已在队列中的消息仍会投递
msggw source enable linux-web
msggw source rotate linux-web           # 生成新凭证，旧凭证立即失效
msggw source remove linux-web --yes     # 永久删除来源和凭证（历史统计保留到过期）
```

在 Telegram Bot 的「📨 消息来源」中也可以停用或启用来源，操作需要二次确认。

如果凭证轮换后旧凭证仍在被使用，Bot 和 `/v1/admin/status` 中的 `sources` 组件会提示：“XX 在 N 分钟前使用了已吊销的凭证”。

## 2. 如何选择字段

| 场景 | `type` | `priority` | 幂等键 |
|---|---|---|---|
| 短信 / 验证码 | `sms` | `normal` | 不需要（60 秒内相同内容会自动去重） |
| 定时任务结果 | `task` | 成功用 `low`（静默），失败用 `high` | `任务名-运行时间`，避免重试时重复通知 |
| 监控告警 | `alert` | `high` | `告警ID-触发时间` |
| 普通通知 | `notification` 或省略 | `normal` | 可选 |
| 日志摘要 | `log` | `low` | 可选 |

- 幂等键用于“客户端可能会重发同一条消息”的场景，例如网络超时后重试。同一个键在保留期（默认 30 天）内只会转发一次。
- 如果两条消息内容可能完全相同，但都需要收到（例如两次“备份完成”），请给它们不同的幂等键。否则在没有幂等键的情况下，60 秒内的第二条会被当作重复消息忽略。
- 客户端应根据响应来判断结果：
  - HTTP 2xx 表示服务器已接受。
  - 想确认已送达，可以加 `?wait=N`，然后检查 `delivered == true`。
  - 遇到 429 或 503 时，按 `Retry-After` 稍后重试。
  - 遇到 401、403、422 时不要重试，应先修正配置或请求内容。

## 3. 各平台示例

以下示例中，`TOKEN` 是来源凭证，`URL` 为 `https://osaka.drinkcat.com/v1/messages`。

### Linux / macOS：curl

```bash
#!/bin/sh
# notify.sh "标题" "正文" [type] [priority]
URL=https://osaka.drinkcat.com/v1/messages
TOKEN=$(cat ~/.config/msggw/token)        # 文件权限设为 600
python3 -c 'import json,sys; print(json.dumps({"title":sys.argv[1],"body":sys.argv[2],"type":sys.argv[3],"priority":sys.argv[4]}))' \
    "$1" "$2" "${3:-notification}" "${4:-normal}" |
curl -sS --max-time 30 -X POST "$URL?wait=8" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" --data-binary @-
```

示例中用 `python3` 生成 JSON，以正确转义中文、引号和换行。如果机器上没有 Python，可以改用纯文本方式提交：

```bash
printf '%s\n' "多行内容" "第二行" | curl -sS -X POST "$URL?title=Backup&type=task" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: text/plain" --data-binary @-
```

### 定时任务包装器（cron）

```bash
#!/bin/sh
# run-and-notify.sh <任务名> <命令...>：执行命令，把结果和输出的最后 30 行发到 Telegram
NAME=$1; shift
OUT=$("$@" 2>&1); RC=$?
if [ $RC -eq 0 ]; then PRIO=low; T="✅ $NAME 成功"; else PRIO=high; T="❌ $NAME 失败（退出码 $RC）"; fi
KEY="$NAME-$(date +%Y%m%d%H%M)"
printf '%s\n' "$OUT" | tail -n 30 | curl -sS --max-time 30 -X POST \
  "https://osaka.drinkcat.com/v1/messages?type=task&priority=$PRIO&title=$(python3 -c 'import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))' "$T")" \
  -H "Authorization: Bearer $(cat /etc/msggw-client/token)" -H "Content-Type: text/plain" \
  -H "Idempotency-Key: $KEY" --data-binary @- >/dev/null
exit $RC
```

crontab 示例：`0 3 * * * /usr/local/bin/run-and-notify.sh 每日备份 /usr/local/bin/backup.sh`

### Python

```python
import json, urllib.request

def notify(body, title="", type_="notification", priority="normal", key=None, token=open("/path/token").read().strip()):
    req = urllib.request.Request(
        "https://osaka.drinkcat.com/v1/messages?wait=8",
        data=json.dumps({"title": title, "body": body, "type": type_, "priority": priority}).encode(),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json",
                 **({"Idempotency-Key": key} if key else {})},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=35) as r:
            return json.load(r)          # r["delivered"] / r["status"] / r["message"]
    except urllib.error.HTTPError as e:
        return json.load(e)              # 统一错误格式：e["error"]["code"]
```

### Windows：PowerShell

```powershell
$token = Get-Content "$env:USERPROFILE\.msggw-token"
$body  = @{ title = "Windows 任务"; body = "导出完成`n共 120 个文件"; type = "task" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "https://osaka.drinkcat.com/v1/messages?wait=8" `
  -Headers @{ Authorization = "Bearer $token" } `
  -ContentType "application/json; charset=utf-8" `
  -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
```

Windows PowerShell 5.1 默认不以 UTF-8 发送请求体，所以要像上面这样用 `UTF8.GetBytes` 转换，否则中文会变成乱码。可以配合「任务计划程序」定时运行。

### macOS

macOS 可以直接使用上面的 curl 脚本，也可以用 launchd 定时运行。macOS 的「快捷指令」App 与 iPhone 的用法相同，见 [iphone-shortcuts.md](iphone-shortcuts.md)。

### Android（Tasker / HTTP Shortcuts 等）

在任意能发 HTTP 请求的自动化 App 中这样配置：

- 方法：`POST`
- URL：`https://osaka.drinkcat.com/v1/messages?wait=8`
- 请求头：`Authorization: Bearer mgs_...`、`Content-Type: application/json`
- 请求体：`{"title":"%evtprm2","body":"%evtprm3","type":"notification"}`，其中的变量换成该 App 提供的通知标题和内容变量。

注意：如果变量内容可能包含引号或换行，必须使用 App 提供的 JSON 编码功能；或者改用 `Content-Type: text/plain`，直接把正文作为请求体，标题放在查询参数 `?title=` 中。

App 收到响应后，显示响应中的 `message` 字段即可。

## 4. 开发者：新增输出通道

输出通道通过 `delivery.Channel` 接口接入（`internal/delivery/channel.go`）：

```go
type Channel interface {
    Name() string // 路由中使用的通道名，例如 "telegram"
    // 从 job.Delivery.PartsSent 之后继续投递；每确认一段就调用一次 progress
    Deliver(ctx context.Context, job store.Job, progress func(total, sent int)) Result
}
```

实现要点：

1. **对结果如实分类**，这是“至少一次”语义的基础：
   - 确认送达：`Delivered`。
   - 确定没有发出去（连接失败、限流）：`RetrySafe`，可以在 `RetryAfter` 中给出建议的重试间隔。
   - 可能已经发出、但没有拿到确认：`RetryUncertain`。
   - 对方拒绝且重试也无用：`Permanent`。

   同时设置 `FailKind`（`FailNetwork` / `FailConfig` / `FailOther`），健康报告会据此区分“不可达”和“配置错误”。
2. `Err` 只写简短的描述，**不能包含消息正文或任何凭证**。
3. 需要分段时，自己负责渲染和拆分，并通过 `progress` 报告进度，以支持断点续传。可以复用 `render.Render`，也可以按目标平台的限制自己实现。
4. 在 `internal/app/app.go` 的 `worker.Channels` 中注册：

   ```go
   Channels: map[string]delivery.Channel{
       "telegram": &delivery.TelegramChannel{...},
       "bark":     &mychannel.Bark{...},
   },
   ```

5. 为它编写测试，可以参考 `internal/delivery/worker_test.go` 的写法：用假服务端覆盖成功、限流、超时和拒绝几种情况。

Worker、存储、重试、统计和状态展示都不需要修改。

## 5. 开发者：路由规则

路由集中在 `router.Router`（`internal/router/router.go`）：

```go
type Router interface {
    Route(src model.Source, msg *model.Message) []router.Target
}
type Target struct{ Channel, Target string; Silent bool }
```

- 返回多个 `Target`：同一条消息发往多个目标，每个目标独立重试，消息状态会聚合为 `delivered` 或 `partial` 等。
- 返回空列表：消息记为 `not_routed`，不会保存正文。可以用来对某个来源关闭通知。
- 可以按 `src.ID`、`msg.Kind`、`msg.Priority` 编写规则，例如：

  ```go
  type Rules struct{ Default router.Default }

  func (r Rules) Route(src model.Source, m *model.Message) []router.Target {
      if src.ID == "noisy-script" && m.Priority != model.PriorityHigh {
          return nil // 只转发重要消息
      }
      t := r.Default.Route(src, m)
      if m.Kind == "alert" {
          t = append(t, router.Target{Channel: "telegram", Target: "ops-group"})
      }
      return t
  }
  ```

- 新增的目标名（如上例中的 `ops-group`）需要在对应通道的配置中定义。对 `TelegramChannel` 来说，就是在 `Targets` 映射中加入 `"ops-group": <chat id>`。
- 最后在 `app.go` 中把 `router.Default{...}` 替换为新的路由实现。
