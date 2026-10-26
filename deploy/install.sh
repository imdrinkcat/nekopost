#!/usr/bin/env bash
# Build and install (or upgrade) msggw as a systemd service.
# Usage: sudo ./deploy/install.sh
set -euo pipefail

cd "$(dirname "$0")/.."
[[ $EUID -eq 0 ]] || { echo "请用 root 运行（sudo ./deploy/install.sh）" >&2; exit 1; }
command -v go >/dev/null || { echo "未找到 go，请先安装 Go 1.27+" >&2; exit 1; }

VERSION="$(cat VERSION)"
echo "==> 编译 msggw ${VERSION}"
CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o bin/msggw ./cmd/msggw

echo "==> 创建系统用户与目录"
if ! id msggw >/dev/null 2>&1; then
  useradd --system --home-dir /var/lib/msggw --no-create-home --shell /usr/sbin/nologin msggw
fi
install -d -o msggw -g msggw -m 0700 /var/lib/msggw
install -d -o root -g root -m 0755 /etc/msggw
if [[ ! -f /etc/msggw/msggw.env ]]; then
  install -o root -g root -m 0600 deploy/msggw.env.example /etc/msggw/msggw.env
  echo "    已生成 /etc/msggw/msggw.env，请填写 Bot Token 和管理员 ID"
fi
chmod 0600 /etc/msggw/msggw.env

echo "==> 安装程序与 systemd 服务"
install -o root -g root -m 0755 bin/msggw /usr/local/bin/msggw.new
mv -f /usr/local/bin/msggw.new /usr/local/bin/msggw
install -o root -g root -m 0644 deploy/msggw.service /etc/systemd/system/msggw.service
systemctl daemon-reload
systemctl enable msggw.service >/dev/null

if grep -Eq '^MSGGW_TELEGRAM_BOT_TOKEN=.+' /etc/msggw/msggw.env && grep -Eq '^MSGGW_TELEGRAM_ADMIN_IDS=.+' /etc/msggw/msggw.env; then
  echo "==> 重启服务"
  systemctl restart msggw.service
  sleep 2
  systemctl --no-pager --lines=0 status msggw.service || true
  echo
  /usr/local/bin/msggw check || true
else
  echo
  echo "配置尚未完成：编辑 /etc/msggw/msggw.env 后执行 systemctl restart msggw"
fi
