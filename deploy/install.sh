#!/usr/bin/env bash
# Build and install (or upgrade) nekopost as a systemd service.
# Usage: sudo ./deploy/install.sh
set -euo pipefail

cd "$(dirname "$0")/.."
[[ $EUID -eq 0 ]] || { echo "请用 root 运行（sudo ./deploy/install.sh）" >&2; exit 1; }
command -v go >/dev/null || { echo "未找到 go，请先安装 Go 1.27+" >&2; exit 1; }

VERSION="$(cat VERSION)"
echo "==> 编译 nekopost ${VERSION}"
CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o bin/nekopost ./cmd/nekopost

echo "==> 创建系统用户与目录"
if ! id nekopost >/dev/null 2>&1; then
  useradd --system --home-dir /var/lib/nekopost --no-create-home --shell /usr/sbin/nologin nekopost
fi
install -d -o nekopost -g nekopost -m 0700 /var/lib/nekopost
install -d -o root -g root -m 0755 /etc/nekopost
if [[ ! -f /etc/nekopost/nekopost.env ]]; then
  install -o root -g root -m 0600 deploy/nekopost.env.example /etc/nekopost/nekopost.env
  echo "    已生成 /etc/nekopost/nekopost.env，请填写 Bot Token 和管理员 ID"
fi
chmod 0600 /etc/nekopost/nekopost.env

echo "==> 安装程序与 systemd 服务"
install -o root -g root -m 0755 bin/nekopost /usr/local/bin/nekopost.new
mv -f /usr/local/bin/nekopost.new /usr/local/bin/nekopost
install -o root -g root -m 0644 deploy/nekopost.service /etc/systemd/system/nekopost.service
systemctl daemon-reload
systemctl enable nekopost.service >/dev/null

if grep -Eq '^NEKOPOST_TELEGRAM_BOT_TOKEN=.+' /etc/nekopost/nekopost.env && grep -Eq '^NEKOPOST_TELEGRAM_ADMIN_IDS=.+' /etc/nekopost/nekopost.env; then
  echo "==> 重启服务"
  systemctl restart nekopost.service
  sleep 2
  systemctl --no-pager --lines=0 status nekopost.service || true
  echo
  /usr/local/bin/nekopost check || true
else
  echo
  echo "配置尚未完成：编辑 /etc/nekopost/nekopost.env 后执行 systemctl restart nekopost"
fi
