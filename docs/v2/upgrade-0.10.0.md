# 升级到 0.10.0

2026-11-17 01:28:35 UTC 从 0.9.0 升级到 0.10.0，用户同意的选项是「全部上线」。同时上线的有新版网页面板，以及 App 推送成为正式投递通道、Telegram「➕ 添加设备」。App 0.3.0 用 `app-v0.3.0` 打包。

- **演练**：把数据目录复制到临时目录，执行 `NEKOPOST_DATA_DIR=<副本> nekopost check`。没有数据库迁移（仍是 v5）。配置、Telegram、DeepSeek、`keys status`、`web audit verify` 都通过。
- **备份**：`/root/nekopost-backup-before-0.10.0-20261117T012830Z`，内含 `data/` 和 `nekopost-0.9.0`。
- **升级**：执行 `sudo ./deploy/install.sh`。先编译，最后才重启，停机约 1 秒。之后 `healthcheck` 正常，公网 `/healthz` 和面板返回 200。

## 回滚

没有迁移，所以只换二进制即可。如果要连数据一起回退，就用备份里的 `data/`：

```bash
sudo install -m 0755 /root/nekopost-backup-before-0.10.0-20261117T012830Z/nekopost-0.9.0 /usr/local/bin/nekopost
sudo systemctl restart nekopost && sudo nekopost check
```
