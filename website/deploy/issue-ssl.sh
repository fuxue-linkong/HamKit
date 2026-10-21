#!/usr/bin/env bash
# ============================================================
# 为已解析到本服务器的域名签发 Let's Encrypt 证书并配置自动续期
#
# 用法：
#   bash issue-ssl.sh <主域名> [额外域名...] [pem路径]
#
# 例：
#   bash issue-ssl.sh hamkit.click www.hamkit.click /c/Users/Admin/Downloads/123.pem
#   bash issue-ssl.sh oauth.hamkit.click /c/Users/Admin/Downloads/123.pem
#
# 前置条件：
#   1) 域名 A 记录已指向本服务器弹性 IP（必须先验证，否则 HTTP-01 校验必然失败）
#   2) nginx 已装好，且 80 端口可从公网访问（安全组放行）
#
# 用 nginx 插件模式：自动改站点配置并加 80→443 跳转；renew 由 certbot.timer 负责。
# ============================================================
set -euo pipefail

[[ $# -ge 2 ]] || { echo "用法: bash issue-ssl.sh <主域名> [额外域名...] [pem路径]"; exit 1; }

PRIMARY="$1"; shift
# 最后一个参数若是存在的文件则视为 pem 路径，其余为额外域名
PEM_PATH="/c/Users/Admin/Downloads/123.pem"
EXTRA=()
for arg in "$@"; do
  if [[ "$arg" == *.pem && -f "$arg" ]]; then PEM_PATH="$arg"; else EXTRA+=("$arg"); fi
done

EMAIL="${CERTBOT_EMAIL:-}"
SSH_USER="${SSH_USER:-ubuntu}"
SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o ConnectTimeout=15)

die()  { echo -e "\033[1;31m[失败] $*\033[0m"; exit 1; }
log()  { echo -e "\n\033[1;34m==> $*\033[0m"; }

[[ -f "$PEM_PATH" ]] || die "找不到密钥：$PEM_PATH"

log "解析前检查：$PRIMARY 的公网 A 记录"
RESOLVED="$(nslookup "$PRIMARY" 8.8.8.8 2>/dev/null | awk '/^Address: /{print $2}' | tail -1 || true)"
echo "  $PRIMARY -> ${RESOLVED:-(解析不到)}"
[[ -n "$RESOLVED" ]] || die "域名 $PRIMARY 解析不到。请先在 Route53 添加 A 记录，等生效后再签证书。"

# 服务器自身公网 IP（用于比对解析是否已指向本机）
SERVER_IP="$(ssh "${SSH_OPTS[@]}" -i "$PEM_PATH" "$SSH_USER@$RESOLVED" 'curl -s --max-time 10 https://checkip.amazonaws.com' 2>/dev/null | tr -d '[:space:]' || true)"
if [[ -n "$SERVER_IP" ]]; then
  echo "  服务器出口 IP：$SERVER_IP"
  if [[ "$RESOLVED" != "$SERVER_IP" ]]; then
    echo -e "\033[1;33m[警告] 解析结果与服务器出口 IP 不一致。若用了 CloudFront/代理属正常，否则证书校验可能失败。\033[0m"
  fi
fi

# 若 Certbot 需要邮箱（首次注册）
if [[ -z "$EMAIL" ]]; then
  echo
  echo "建议提供一个邮箱用于证书过期通知（直接回车则用 --register-unsafely-without-email）"
  read -r -p "邮箱: " EMAIL || true
fi

DOMAINS=(-d "$PRIMARY")
for d in "${EXTRA[@]:-}"; do [[ -n "$d" ]] && DOMAINS+=(-d "$d"); done

log "在服务器上执行 certbot（nginx 模式）"
EMAIL_ARG=(--register-unsafely-without-email)
[[ -n "$EMAIL" ]] && EMAIL_ARG=(--email "$EMAIL")

ssh "${SSH_OPTS[@]}" -i "$PEM_PATH" "$SSH_USER@$RESOLVED" bash -s -- \
  "${EMAIL_ARG[@]}" -- "${DOMAINS[@]}" <<'REMOTE'
set -euo pipefail
EMAIL_ARG=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --) shift; break ;;
    *) EMAIL_ARG+=("$1"); shift ;;
  esac
done
DOMAINS=("$@")

echo "certbot certonly --nginx ${EMAIL_ARG[*]} ${DOMAINS[*]} --agree-tos --non-interactive --redirect"
sudo certbot --nginx "${EMAIL_ARG[@]}" "${DOMAINS[@]}" \
  --agree-tos --non-interactive --redirect --keep-until-expiring

echo
echo "--- 证书列表 ---"
sudo certbot certificates | sed -n '1,40p'
echo "--- 续期定时器 ---"
systemctl list-timers certbot.timer --no-pager 2>/dev/null | head -3 || true
echo "--- nginx 配置校验 ---"
sudo nginx -t
REMOTE

log "完成"
echo "访问 https://$PRIMARY/ 验证证书是否生效。"
