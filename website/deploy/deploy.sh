#!/usr/bin/env bash
# ============================================================
# HamKit 官网上线脚本（在本机 Git Bash / WSL / Linux 上运行）
#
# 用法：
#   bash deploy.sh <服务器IP> <域名> [pem路径]
#
# 例：
#   bash deploy.sh 43.212.x.x hamkit.click /c/Users/Admin/Downloads/123.pem
#
# 做这些事（幂等，可重复执行）：
#   1) 校验 SSH 连通性与 .pem 权限
#   2) 打包项目（排除 test/tools/data/node_modules/.env）
#   3) 上传到 /srv/hamkit/ 并解包
#   4) 首次运行时执行 setup-server.sh（装 Node/nginx/certbot）
#   5) 安装 systemd 单元与 nginx 站点配置
#   6) 启动服务并本机自检
#
# 不负责：DNS 解析（需 AWS Route53）、签发证书（需域名已解析到位，用 issue-ssl.sh）
# ============================================================
set -euo pipefail

SERVER_IP="${1:?用法: bash deploy.sh <服务器IP> <域名> [pem路径]}"
DOMAIN="${2:?用法: bash deploy.sh <服务器IP> <域名> [pem路径]}"
PEM_PATH="${3:-/c/Users/Admin/Downloads/123.pem}"
SSH_USER="${SSH_USER:-ubuntu}"
APP_DIR="/srv/hamkit/website"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

log()  { echo -e "\n\033[1;34m==> $*\033[0m"; }
warn() { echo -e "\033[1;33m[警告] $*\033[0m"; }
die()  { echo -e "\033[1;31m[失败] $*\033[0m"; exit 1; }

SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 -o ServerAliveInterval=30)
SCP_OPTS=(-o StrictHostKeyChecking=accept-new -o ConnectTimeout=15)

# ---------- 1) 校验 .pem ----------
log "1/6 校验 SSH 密钥"
[[ -f "$PEM_PATH" ]] || die "找不到密钥文件：$PEM_PATH"
chmod 600 "$PEM_PATH" 2>/dev/null || true

# ---------- 2) 打包 ----------
log "2/6 打包项目"
PACKAGE="$(mktemp -t hamkit-site-XXXXXX.tar.gz)"
# Git Bash 下 tar 需要 Windows 风格路径才能正确处理，故用 -C 切到父目录
PROJECT_PARENT="$(dirname "$PROJECT_DIR")"
PROJECT_NAME="$(basename "$PROJECT_DIR")"
tar -czf "$PACKAGE" -C "$PROJECT_PARENT" \
  --exclude="$PROJECT_NAME/test" \
  --exclude="$PROJECT_NAME/tools" \
  --exclude="$PROJECT_NAME/data" \
  --exclude="$PROJECT_NAME/node_modules" \
  --exclude="$PROJECT_NAME/.env" \
  --exclude="$PROJECT_NAME/tmp-*" \
  --exclude="$PROJECT_NAME/.git" \
  "$PROJECT_NAME"
echo "包大小：$(du -h "$PACKAGE" | cut -f1)"

# ---------- 3) 测试 SSH ----------
log "3/6 测试 SSH 连接 ($SSH_USER@$SERVER_IP)"
ssh_ok() { ssh "${SSH_OPTS[@]}" -i "$PEM_PATH" "$1@$SERVER_IP" 'echo SSH_OK' >/dev/null 2>&1; }

if ! ssh_ok "$SSH_USER"; then
  # 常见情况：AMI 默认用户不是 ubuntu，按候选逐个试
  resolved=""
  for candidate in ubuntu ec2-user admin debian fedora; do
    if ssh_ok "$candidate"; then resolved="$candidate"; break; fi
  done
  if [[ -n "$resolved" ]]; then
    SSH_USER="$resolved"
    warn "默认用户不是 ubuntu，已改用：$SSH_USER"
  else
    die "SSH 登录失败。请确认：安全组放行 22 端口 / 密钥与该实例匹配 / 用户名正确（已尝试 ubuntu, ec2-user, admin, debian, fedora）"
  fi
fi
echo "SSH 可用（用户 $SSH_USER）"

# ---------- 4) 上传 ----------
log "4/6 上传代码到 $APP_DIR"
scp "${SCP_OPTS[@]}" -i "$PEM_PATH" "$PACKAGE" "$SSH_USER@$SERVER_IP:/tmp/hamkit-site.tar.gz"
ssh "${SSH_OPTS[@]}" -i "$PEM_PATH" "$SSH_USER@$SERVER_IP" bash -s <<'REMOTE_UPLOAD'
set -euo pipefail
sudo mkdir -p /srv/hamkit
sudo chown -R "$USER":"$USER" /srv/hamkit
# 解包到 /srv/hamkit/，包内顶层目录名即 website
tar -xzf /tmp/hamkit-site.tar.gz -C /srv/hamkit
rm -f /tmp/hamkit-site.tar.gz
mkdir -p /srv/hamkit/website/data
echo "解包完成："
ls -la /srv/hamkit/website | head -20
REMOTE_UPLOAD

# ---------- 5) 首次初始化 ----------
log "5/6 检查并安装运行环境"
NEED_SETUP=$(ssh "${SSH_OPTS[@]}" -i "$PEM_PATH" "$SSH_USER@$SERVER_IP" \
  'command -v node >/dev/null 2>&1 && command -v nginx >/dev/null 2>&1 && echo "READY" || echo "NEED_SETUP"')
if [[ "$NEED_SETUP" == "NEED_SETUP" ]]; then
  echo "环境未就绪，执行 setup-server.sh …"
  scp "${SCP_OPTS[@]}" -i "$PEM_PATH" "$SCRIPT_DIR/setup-server.sh" "$SSH_USER@$SERVER_IP:/tmp/setup-server.sh"
  ssh "${SSH_OPTS[@]}" -i "$PEM_PATH" "$SSH_USER@$SERVER_IP" 'sudo bash /tmp/setup-server.sh'
else
  echo "Node 与 nginx 已安装，跳过初始化"
fi
# 确保骨架目录存在（setup-server 会建，重复执行也安全）
ssh "${SSH_OPTS[@]}" -i "$PEM_PATH" "$SSH_USER@$SERVER_IP" \
  'sudo mkdir -p /var/www/certbot /srv/hamkit/website/data && sudo chown -R hamkit:hamkit /srv/hamkit && sudo chmod 755 /var/www/certbot'

# ---------- 6) 安装服务与站点配置 ----------
log "6/6 安装 systemd 单元与 nginx 配置"
scp "${SCP_OPTS[@]}" -i "$PEM_PATH" \
  "$SCRIPT_DIR/hamkit.service" \
  "$SCRIPT_DIR/nginx-hamkit.conf" \
  "$SCRIPT_DIR/nginx-oauth.conf" \
  "$SSH_USER@$SERVER_IP:/tmp/"

ssh "${SSH_OPTS[@]}" -i "$PEM_PATH" "$SSH_USER@$SERVER_IP" bash -s <<REMOTE_INSTALL
set -euo pipefail

# .env 不存在时用模板生成（占位值，需人工补 OIDC 配置）
if [ ! -f /srv/hamkit/website/.env ]; then
  sudo cp /srv/hamkit/website/.env.example /srv/hamkit/website/.env
  sudo sed -i "s|^BASE_URL=.*|BASE_URL=https://${DOMAIN}|" /srv/hamkit/website/.env
  sudo sed -i "s|^PORT=.*|PORT=8787|" /srv/hamkit/website/.env
  sudo sed -i "s|^HOST=.*|HOST=127.0.0.1|" /srv/hamkit/website/.env
  sudo sed -i "s|^NODE_ENV=.*|NODE_ENV=production|" /srv/hamkit/website/.env
  sudo sed -i "s|^TRUST_PROXY=.*|TRUST_PROXY=1|" /srv/hamkit/website/.env
  echo "已从模板生成 .env（仍需补 OIDC_ISSUER / OIDC_CLIENT_ID / OIDC_CLIENT_SECRET）"
else
  echo ".env 已存在，保持不变"
fi
sudo chown hamkit:hamkit /srv/hamkit/website/.env
sudo chmod 600 /srv/hamkit/website/.env

# systemd
sudo cp /tmp/hamkit.service /etc/systemd/system/hamkit.service
sudo systemctl daemon-reload
sudo systemctl enable hamkit >/dev/null 2>&1 || true
sudo systemctl restart hamkit

# nginx：先只装主站，oauth 站点等 Casdoor 起来后再启用
sudo cp /tmp/nginx-hamkit.conf /etc/nginx/sites-available/hamkit.conf
sudo ln -sf /etc/nginx/sites-available/hamkit.conf /etc/nginx/sites-enabled/hamkit.conf
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx

sleep 2
echo "--- hamkit 服务状态 ---"
sudo systemctl is-active hamkit
echo "--- 本机自检 ---"
for p in / /login /admin/ /assets/site.css /api/health /update.json; do
  code=\$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:8787\$p" || echo ERR)
  printf '  %-20s %s\n' "\$p" "\$code"
done
REMOTE_INSTALL

log "部署完成"
cat <<EOF

下一步：
  1) 域名解析到 $SERVER_IP 后，签发证书：
       bash $(basename "$SCRIPT_DIR")/issue-ssl.sh $DOMAIN
  2) 补全 .env 里的 OIDC 配置（Casdoor 就绪后）
  3) Casdoor 部署见 casdoor-lab/README.production.md

EOF
rm -f "$PACKAGE"
