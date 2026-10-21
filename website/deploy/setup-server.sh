#!/usr/bin/env bash
# ============================================================
# HamKit 官网服务器初始化（Debian/Ubuntu，ARM64 或 x86_64）
# 幂等：可重复执行。
#
# 用法（在服务器上以 root 运行）：
#   bash setup-server.sh
#
# 做这些事：
#   1) 装 Node.js 22（NodeSource）+ nginx + certbot + 基础工具
#   2) 建 hamkit 系统用户与 /srv/hamkit 目录
#   3) 放行防火墙（若启用 ufw）
# 不负责：上传代码、写 .env、签发证书（由部署脚本分步执行）
# ============================================================
set -euo pipefail

log() { echo -e "\n\033[1;34m==> $*\033[0m"; }

export DEBIAN_FRONTEND=noninteractive

log "系统信息"
uname -m
. /etc/os-release && echo "$PRETTY_NAME"

log "1/5 更新软件包索引"
apt-get update -y

log "2/5 安装基础工具"
apt-get install -y --no-install-recommends \
  ca-certificates curl gnupg git rsync unzip jq \
  nginx certbot python3-certbot-nginx ufw

log "3/5 安装 Node.js 22（NodeSource）"
if ! command -v node >/dev/null 2>&1 || [[ "$(node -v | sed 's/^v//' | cut -d. -f1)" -lt 20 ]]; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
else
  echo "已安装：$(node -v)"
fi
node -v
npm -v

log "4/5 创建 hamkit 用户与目录"
if ! id -u hamkit >/dev/null 2>&1; then
  useradd --system --create-home --shell /usr/sbin/nologin hamkit
  echo "已创建系统用户 hamkit"
fi
mkdir -p /srv/hamkit/website/data /var/www/certbot
chown -R hamkit:hamkit /srv/hamkit
chmod 755 /var/www/certbot

log "5/5 防火墙（若 ufw 启用则放行 22/80/443）"
if command -v ufw >/dev/null 2>&1; then
  ufw allow 22/tcp  >/dev/null 2>&1 || true
  ufw allow 80/tcp  >/dev/null 2>&1 || true
  ufw allow 443/tcp >/dev/null 2>&1 || true
  ufw status | head -5 || true
fi

log "完成"
echo "node:  $(node -v)"
echo "nginx: $(nginx -v 2>&1)"
echo "certbot: $(certbot --version 2>&1)"
echo
echo "下一步：上传代码到 /srv/hamkit/website，写好 .env，然后启用 systemd 服务。"
