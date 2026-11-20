# Docker 部署

Nekopost 也提供容器镜像，适合部署到已经在用 Docker 的服务器。功能和 systemd 部署完全一样。内存很小的 VPS 仍然推荐 systemd 部署（见 [deployment.md](deployment.md)），因为 Docker 守护进程本身就要占用几十 MB 内存。

> ⚠️ **只有包含 0.6.0 及之后代码的镜像才有正文加密、Android 配对和网页面板，并且必须提供主密钥才能启动。** 镜像只在 `main` 分支和 `v*` 标签上构建。`v2` 已经合并进 `main`，之后构建的 `latest` 都包含这些功能；但 **`0.5.0` / `0.5` 这类旧版本号标签不含加密，不要拿旧镜像去打开 0.6.0 以后的数据库**，旧版本打不开已经迁移过的库。

| 项目 | 说明 |
|---|---|
| 镜像 | `ghcr.io/imdrinkcat/nekopost` |
| 标签 | `latest`（main 分支最新版）、`<版本号>`（推送 `v*` 标签时生成，如 `0.5.0`）、`sha-xxxxxxx`（某次提交） |
| 架构 | `linux/amd64`、`linux/arm64` |
| 基础镜像 | `distroless/static`：没有 shell，以 uid/gid `65532` 运行，镜像约 15 MB |
| 数据 | 容器内的 `/data`：数据库 `nekopost.db` 和证书目录 `acme/` |
| 主密钥 | 容器内的 `/run/secrets/nekopost_kek`（Docker secret），**不要放进数据卷** |
| 端口 | 容器内 `8080`（HTTP）和 `8443`（HTTPS） |

镜像由 GitHub Actions 构建（`.github/workflows/docker.yml`）：推送到 main 分支或推送 `v*` 标签后，构建镜像并启动容器做冒烟测试，通过后才发布。冒烟测试包含「没有主密钥必须拒绝启动」这一项。全部单元测试在另一个工作流（`ci.yml`）里，每次推送都会运行，Docker 工作流不再重复运行测试。

---

## 1. 准备

1. 安装 Docker（含 compose 插件），见 <https://docs.docker.com/engine/install/>。
2. 登录 GHCR。仓库是私有的，镜像也是私有的，所以需要登录：
   - 在 GitHub → Settings → Developer settings → Personal access tokens (classic) 新建一个 Token，只勾选 `read:packages`。GHCR 只支持 classic Token。
   - 在服务器上执行下面的命令，粘贴 Token。登录信息保存在 `~/.docker/config.json`，以后更新不用再登录。

   ```bash
   docker login ghcr.io -u imdrinkcat
   ```

3. 准备部署目录。只需要两个文件：仓库里的 `deploy/docker/compose.yaml`（或 `compose.proxy.yaml`）和 `deploy/nekopost.env.example`。

   ```bash
   sudo mkdir -p /opt/nekopost && cd /opt/nekopost
   # 把 compose.yaml 和 nekopost.env.example 复制到这里（scp、gh 或直接粘贴）
   sudo install -d -o 65532 -g 65532 -m 700 data   # 数据目录，容器以 uid 65532 运行
   sudo cp nekopost.env.example nekopost.env && sudo chmod 600 nekopost.env
   sudo nano nekopost.env                          # 填写域名、Bot Token、管理员 ID
   ```

4. 生成主密钥。compose 会把 `secrets/nekopost_kek` 挂到容器的 `/run/secrets/nekopost_kek`：

   ```bash
   sudo install -d -m 700 secrets
   docker run --rm ghcr.io/imdrinkcat/nekopost keys gen | sudo tee secrets/nekopost_kek >/dev/null
   sudo chown 65532:65532 secrets/nekopost_kek && sudo chmod 400 secrets/nekopost_kek
   ```

   `keys gen` 在标准输出是终端时会拒绝输出，所以必须用管道或重定向。之后马上把主密钥备份到密码管理器（在自己的终端执行，不要贴进聊天）：

   ```bash
   sudo cat secrets/nekopost_kek | base64 | <密码管理器的写入命令>
   ```

   备份的内容是 base64 文本，恢复时服务可以直接读取。主密钥不要和数据库备份放在一起，详见 [encryption.md](encryption.md)。

   `NEKOPOST_DATA_DIR`、`NEKOPOST_HTTP_ADDR`、`NEKOPOST_HTTPS_ADDR` 已经由 compose 固定，在 `nekopost.env` 里设置也不会生效。

> ⚠️ 同一个 Telegram Bot 只能由一个实例运行。新实例要么用新的 Bot Token，要么先停掉旧实例（见第 5 节）。两个实例同时轮询同一个 Bot 会互相冲突。

---

## 2. 方式一：内置 HTTPS（端口 80/443 空闲时）

和 systemd 部署一样，由程序自己申请 Let's Encrypt 证书。要求域名已经解析到这台服务器，且 80、443 端口没有被占用。

```bash
cd /opt/nekopost
docker compose up -d
docker compose ps                                # STATUS 应在约 30 秒后变为 healthy
docker compose exec nekopost nekopost check --send-test
docker compose exec nekopost nekopost source add iphone --name iPhone --icon 📱
```

证书保存在 `data/acme/`。重建容器不会重新申请证书。

---

## 3. 方式二：放在已有的反向代理后面

服务器上已经有 Caddy、nginx、Traefik 等占用了 80/443 时，使用 `compose.proxy.yaml`：

- 证书由反向代理负责，容器内关闭 TLS（`NEKOPOST_TLS_MODE=off`）。
- 容器只监听本机的 `127.0.0.1:8080`，反向代理把请求转发到这里。

```bash
cd /opt/nekopost
docker compose -f compose.proxy.yaml up -d
```

之后的命令都要加上 `-f compose.proxy.yaml`。也可以把它改名为 `compose.yaml`，就不用每次加参数。

**Caddy**：

```caddyfile
your.domain {
    reverse_proxy 127.0.0.1:8080
}
```

**nginx**：

```nginx
server {
    listen 443 ssl;
    server_name your.domain;
    # ssl_certificate ... 按你现有的证书配置
    client_max_body_size 256k;
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 60s;   # ?wait= 最长等待 25 秒
    }
}
```

**客户端 IP**：限流和认证失败封禁按客户端 IP 计算。经过反向代理后，连接来自代理，所以 Nekopost 只在对方属于 `NEKOPOST_TRUSTED_PROXIES` 时才读取 `X-Forwarded-For`。读取时从右往左，取第一个不属于可信代理的地址，所以客户端伪造的头部不起作用。compose.proxy.yaml 默认信任 Docker 常用的私有网段。如果 Docker 网络用的是其他网段，请相应修改。

反向代理如果在另一个容器里，也可以去掉 `ports`，把两个容器放进同一个 Docker 网络，然后转发到 `nekopost:8080`。

---

## 4. 日常运维

```bash
docker compose logs -f --since 1h                 # 查看日志
docker compose restart                            # 重启
docker compose exec nekopost nekopost source list # 所有命令行操作都用 exec 执行
docker compose exec nekopost nekopost check
```

**更新**：

```bash
docker compose pull && docker compose up -d
```

需要固定版本时，把 compose 里的 `:latest` 改成某个版本号或 `sha-xxxxxxx`。回滚时改回旧版本号即可。数据库结构只会向前升级，所以回滚前请先备份。

**修改配置**：编辑 `nekopost.env` 后执行 `docker compose up -d`。只执行 `restart` 不会重新读取 env 文件。

**备份**：

```bash
cd /opt/nekopost
docker compose stop
sudo tar czf nekopost-backup-$(date +%F).tgz data nekopost.env compose*.yaml
docker compose start
```

这份备份**不含** `secrets/nekopost_kek`。主密钥单独保存（见第 1 节第 4 步），不要和数据库备份放在一起。恢复时先把主密钥放回 `secrets/nekopost_kek`，再启动容器。

---

## 5. 从 systemd 实例迁移

迁移后数据、来源、凭证和证书都会保留，iPhone 快捷指令不需要任何修改。

```bash
# 旧服务器：停止服务并打包
sudo systemctl stop nekopost
sudo tar czf nekopost-move.tgz -C /var/lib/nekopost . -C /etc/nekopost nekopost.env

# 新服务器：解包到部署目录（compose.yaml 已就位）
cd /opt/nekopost
sudo install -d -o 65532 -g 65532 -m 700 data
sudo tar xzf nekopost-move.tgz -C data
sudo mv data/nekopost.env . && sudo chmod 600 nekopost.env
sudo chown -R 65532:65532 data
```

**主密钥不在这个压缩包里**，必须单独带过去，否则新容器打不开旧数据库里的正文。在**新服务器自己的终端**里，把旧服务器 `keys export` 备份到密码管理器里的内容粘贴进去（粘贴后按回车，输入不回显）：

```bash
cd /opt/nekopost
sudo install -d -m 700 secrets
read -rs -p '主密钥: ' kek; printf '\n'
printf '%s\n' "$kek" | sudo tee secrets/nekopost_kek >/dev/null
unset kek
sudo chown 65532:65532 secrets/nekopost_kek && sudo chmod 400 secrets/nekopost_kek
```

`keys export` 输出的是 base64 文本，服务可以直接读取。**不要用 `keys gen` 生成新的**：新密钥打不开旧数据。

然后：

1. 把域名的 DNS 记录改到新服务器的 IP，等待生效。
2. 先在旧服务器上执行 `sudo systemctl disable --now nekopost`，再在新服务器上执行 `docker compose up -d`（同一个 Bot 只能有一个实例轮询，两个同时运行会互相冲突，也能防止旧服务器重启后又把 Bot 抢回去）。
3. 执行 `docker compose exec nekopost nekopost check` 和 `docker compose exec nekopost nekopost keys status`，确认主密钥前几位和旧服务器一致。
4. Google 登录的 Client Secret 和 FCM 服务账号放在 systemd 凭证里，不在压缩包中。需要的话挂载为 `/run/secrets/nekopost_google_client_secret`，或用 `NEKOPOST_FCM_CREDENTIALS_FILE` 指向挂载的文件（见 [web.md](web.md)、[android.md](android.md)）。

迁移期间投递失败的消息会在新实例启动后继续重试。

---

## 6. 与 systemd 部署的区别

| 方面 | systemd | Docker |
|---|---|---|
| 进程卡死 | systemd 看门狗在 5 分钟内重启 | 程序自己检测：数据库或投递循环连续 5 分钟不正常就主动退出，由 `restart: unless-stopped` 重新启动 |
| 健康检查 | `/healthz` | 同样用 `/healthz`，由镜像里的 `nekopost healthcheck` 通过本机 HTTP 端口检查。内置 HTTPS 模式下，80 端口也会响应 `/healthz` |
| 日志 | journald | `docker compose logs`，每个文件最大 10 MB，保留 3 个 |
| 资源限制 | `MemoryMax=192M`、`GOMEMLIMIT=96MiB` | `mem_limit: 192m`、`pids_limit: 64` |
| 沙箱 | `ProtectSystem=strict` 等 | 只读根文件系统、去掉所有 capabilities、`no-new-privileges`、非 root 用户 |
| Bot 中的服务器指标 | 本机 | 内存、负载、运行时长是**宿主机**的数值；磁盘是 `data` 目录所在磁盘 |

**IPv6**：Docker 默认通过用户态代理转发 IPv6 连接，所以 IPv6 客户端在程序里看到的都是同一个 Docker 网关地址，会共用一个限流和认证失败计数。只有在 IPv6 客户端很多、或者遭到攻击时才会有影响。可以在 compose 里给网络开启 IPv6（需要 Docker 27 或更高版本）：

```yaml
services:
  nekopost:
    networks: [default]
networks:
  default:
    enable_ipv6: true
```

---

## 7. 本地构建镜像

```bash
make docker            # 等同于 docker build -t nekopost:<版本> -t nekopost:latest .
```

用本地镜像部署时，把 compose 里的 `image:` 改为 `nekopost:latest`。这样可以在发布镜像之前先验证当前代码。
