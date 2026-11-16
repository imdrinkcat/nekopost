# 正文加密

服务端 0.6.0 起，标题、正文和 meta 不再以明文写入数据库。主密钥（KEK）在进程外面，数据库里只有按 UTC 日包裹的数据密钥（DEK）和密文。

## 主密钥放在哪里

服务进程按这个顺序查找，找到就用，都没有就拒绝启动：

1. `NEKOPOST_KEK_FILE`
2. `$CREDENTIALS_DIRECTORY/nekopost-kek`（systemd 用 `ImportCredential=nekopost-*` 注入）
3. `/run/secrets/nekopost_kek`（Docker secret）

`nekopost check` 和 `keys status` 在服务没运行时，还会用 `systemd-creds decrypt` 读取 `/etc/credstore.encrypted/nekopost-kek`。解密结果只留在内存里。

没有 TPM 时，systemd 用主机密钥加密。只拷走数据目录或加密凭证文件的人解不开。拿到 root 或整盘镜像的人仍然拿得到主密钥。

## 生成和备份

systemd 主机上，`deploy/install.sh` 在重启前发现没有主密钥时会执行 `nekopost keys init`。也可以自己执行：

```bash
sudo nekopost keys init
```

把主密钥放进密码管理器，并且不要和数据库备份放在一起：

```bash
sudo nekopost keys export | <密码管理器的写入命令>
```

标准输出是终端时，`keys export` 和 `keys gen` 会拒绝写出密钥。

Docker：

```bash
mkdir -p secrets
docker run --rm ghcr.io/imdrinkcat/nekopost keys gen > secrets/nekopost_kek
chmod 0400 secrets/nekopost_kek
```

`secrets/nekopost_kek` 挂到 `/run/secrets/nekopost_kek`，不要放进数据卷。

## 轮换

```bash
sudo nekopost keys rotate
sudo systemctl restart nekopost
sudo nekopost keys rotate --finish
```

`rotate` 同时保留新旧两把主密钥。重启时用新密钥重新包裹每一天的数据密钥，`--finish` 再去掉旧的。主密钥使用超过 365 天时，健康报告会提示轮换。

怀疑主密钥已经泄露时，轮换只保护之后的新正文。`sudo nekopost keys shred-all` 会销毁全部数据密钥，已保存的正文都解不开，元数据还在。主密钥丢了则 `sudo nekopost keys reset --i-understand`，同样丢掉保留期内的正文并生成新密钥。

## 保留多久

| 数据 | 默认 |
|---|---|
| 普通正文 | 7 天（`NEKOPOST_BODY_RETENTION_DAYS`，0–90；0 表示投递结束就删） |
| 验证码类正文 | 24 小时（`NEKOPOST_CODE_RETENTION_HOURS`；0 表示与普通正文相同） |
| 内容指纹 | `max(去重窗口, 1 小时)`，之后清空 |
| 幂等键 | 正文到期或删除时清空 |
| 元数据 | `NEKOPOST_RETENTION_DAYS`（默认 30，不能小于正文保留天数） |

某一天已经没有正文、并且过了保留期，那天的数据密钥会被清掉。之后备份和磁盘残留里的密文也解不开。单条删除只靠 SQLite 的 `secure_delete`，同一天的其他正文仍用同一把数据密钥。

升级到 0.6.0 时会先复制一份数据库，文件名类似 `nekopost.db.pre-v3-<时间>.bak`。这份备份含有升级前的明文，默认在新版本成功启动 24 小时后删除。需要留下时设置 `NEKOPOST_KEEP_MIGRATION_BACKUP=true`。

## 幂等键

`Idempotency-Key` 必须是随机值，或者带密钥的 HMAC。不要用正文的普通哈希。服务端保存的是 `HMAC(主密钥派生的去重密钥, 原始键)` 的前 32 个十六进制字符。正文到期后，同一个键会被当成一条新消息。
