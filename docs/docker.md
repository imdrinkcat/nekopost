# Docker 部署

Nekopost 也提供容器镜像，适合部署到已经在用 Docker 的服务器。功能和 systemd 部署完全一样。内存很小的 VPS 仍然推荐 systemd 部署（见 [deployment.md](deployment.md)），因为 Docker 守护进程本身就要占用几十 MB 内存。

| 项目 | 说明 |
|---|---|
| 镜像 | `ghcr.io/imdrinkcat/nekopost` |
| 标签 | `latest`（main 分支最新版）、`0.5.0` / `0.5`（版本号）、`sha-xxxxxxx`（某次提交） |
| 架构 | `linux/amd64`、`linux/arm64` |
| 基础镜像 | `distroless/static`：没有 shell，以 uid/gid `65532` 运行，镜像约 15 MB |
| 数据 | 容器内的 `/data`：数据库 `nekopost.db` 和证书目录 `acme/` |
| 端口 | 容器内 `8080`（HTTP）和 `8443`（HTTPS） |

镜像由 GitHub Actions 构建（`.github/workflows/docker.yml`）：推送到 main 分支或推送 `v*` 标签后，先运行全部测试，再启动容器做冒烟测试，通过后才发布。

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

需要固定版本时，把 compose 里的 `:latest` 改成 `:0.5.0` 这样的版本号。回滚时改回旧版本号即可。数据库结构只会向前升级，所以回滚前请先备份。

**修改配置**：编辑 `nekopost.env` 后执行 `docker compose up -d`。只执行 `restart` 不会重新读取 env 文件。

**备份**：

```bash
cd /opt/nekopost
docker compose stop
sudo tar czf nekopost-backup-$(date +%F).tgz data nekopost.env compose*.yaml
docker compose start
```

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

然后：

1. 把域名的 DNS 记录改到新服务器的 IP，等待生效。
2. 在新服务器上执行 `docker compose up -d`，再执行 `docker compose exec nekopost nekopost check`。
3. 确认正常后，在旧服务器上执行 `sudo systemctl disable nekopost`，防止旧服务器重启后又把 Bot 抢回去。

迁移期间投递失败的消息会在新实例启动后继续重试。

---

## 6. 与 systemd 部署的区别

| 方面 | systemd | Docker |
|---|---|---|
| 进程卡死 | systemd 看门狗在 5 分钟内重启 | 程序自己检测：数据库或投递循环连续 5 分钟不正常就主动退出，由 `restart: unless-stopped` 重新启动 |
| 健康检查 | `/healthz` | 同样用 `/healthz`，由镜像里的 `nekopost healthcheck` 通过本机 HTTP 端口检查。内置 HTTPS 模式下，80 端口也会响应 `/healthz` |
| 日志 | journald | `docker compose logs`，每个文件最大 10 MB，保留 3 个 |
| 资源限制 | `MemoryMax=160M` 等 | `mem_limit: 160m`、`pids_limit: 64` |
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

用本地镜像部署时，把 compose 里的 `image:` 改为 `nekopost:latest`。
