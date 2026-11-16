# 升级到 0.6.0

在 2026-11-16 用生产库的副本演练过。副本里有 54 条消息元数据，明文正文已经按旧规则删光，所以迁移后 `bodies` 和 `data_keys` 都是 0。`user_version` 从 2 升到 3，`payloads` 表被删掉。迁移本身花了 **18 毫秒**。

预计停机是进程重启的时间，不是迁移的时间。上次停机约 3 分钟，是因为停掉服务之后才编译。这次先编译好再重启，停机应在几秒内。生产还没有升级。

## 升级

在仓库的 `v2` 分支、已经包含 0.6.0 的代码上执行：

```bash
cd /root/message_server
sudo ./deploy/install.sh
```

`install.sh` 的顺序是：

1. 先编译。这段时间服务继续跑。
2. 换上新二进制。
3. 如果 `/etc/credstore.encrypted/nekopost-kek` 还不存在，执行 `nekopost keys init`。失败则停止，不重启。
4. 安装 unit（`GOMEMLIMIT=96MiB`、`MemoryMax=192M`、`LimitCORE=0`、`ImportCredential=nekopost-*`）。
5. 最后才 `systemctl restart`，然后 `nekopost check`。

重启时进程会先把 `/var/lib/nekopost/nekopost.db` 备份成 `nekopost.db.pre-v3-<UTC时间>.bak`，再迁移。这份备份含有升级前的明文，默认在新版本成功启动 24 小时后删除。

升级后确认：

```bash
nekopost version
sudo nekopost check
sudo nekopost keys status
```

版本应是 `0.6.0`。`keys status` 只打印主密钥标识的前几位，不打印密钥。

把主密钥另存到密码管理器，不要和数据库备份放在一起：

```bash
sudo nekopost keys export | <密码管理器的写入命令>
```

标准输出是终端时，这条命令会拒绝写出密钥。

## 回滚

新二进制打不开旧库，旧二进制也打不开已经迁到 v3 的库。回滚要同时换回二进制和数据库：

```bash
sudo systemctl stop nekopost
sudo cp -a /usr/local/bin/nekopost /usr/local/bin/nekopost.0.6.0
# 用升级前自己复制的那份 /var/lib/nekopost 备份覆盖现库
# 再装回升级前的二进制
sudo systemctl start nekopost
```

动手前先自己复制一份 `/var/lib/nekopost`，不要只靠迁移程序生成的 `pre-v3` 备份。那份备份会在 24 小时后被删掉。

## 这次一起上去的删除按钮

每条 Telegram 推送下面多一行「🗑 删除」。点下去变成「确认删除 / 取消」，5 分钟内再点一次才执行。确认后会删掉这条消息的全部分段，以及服务器上的正文、元数据和投递记录，统计里也不再计入。

Bot 只能删除 48 小时内发出的 Telegram 消息。更早的会改成「此消息已删除」并去掉按钮。升级前发出的推送没有保存 `message_id`，只有被点到的那一条能处理，同一条消息的其他分段删不掉。
