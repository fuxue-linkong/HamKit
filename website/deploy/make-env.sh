#!/usr/bin/env bash
# ============================================================
# 在服务器上生成 .env（幂等：已存在则只做必要修补，不覆盖密钥）
# 用法：sudo bash make-env.sh <域名>
# ============================================================
set -euo pipefail
DOMAIN="${1:?用法: sudo bash make-env.sh <域名>}"
ENV_FILE=/srv/hamkit/website/.env

if [[ ! -f "$ENV_FILE" ]]; then
  echo "生成新的 .env（域名 $DOMAIN）"
  SESSION_SECRET="$(openssl rand -base64 48 | tr -d '\n')"
  cat > "$ENV_FILE" <<EOF
# ============ HamKit 官网生产配置 ============
# 由部署脚本生成于 $(date -Is)

NODE_ENV=production
PORT=8787
HOST=127.0.0.1
BASE_URL=https://${DOMAIN}

# 会话密钥（随机生成，勿外泄；多实例部署需所有实例一致）
SESSION_SECRET=${SESSION_SECRET}
SESSION_TTL_HOURS=8

# 反向代理一层（nginx）
TRUST_PROXY=1

# ---------- OIDC（Casdoor）----------
# Casdoor 就绪后填入；issuer 必须与 Casdoor 的 origin 完全一致
OIDC_ISSUER=https://oauth.${DOMAIN}
OIDC_CLIENT_ID=hamkit-website
OIDC_CLIENT_SECRET=
OIDC_CLIENT_AUTH_METHOD=client_secret_post
OIDC_SCOPE=openid profile email
OIDC_ROLE_CLAIM=roles
OIDC_ROLE_MAP=hamkit-admin:admin,hamkit-editor:editor,hamkit-viewer:viewer

# ---------- 授权策略 ----------
# 默认不让首个登录者自动成为管理员；管理员由 OIDC_ROLE_MAP 控制。
# 若 IdP 还不能映射角色，先设置 BOOTSTRAP_ADMINS=you@example.com。
FIRST_USER_IS_ADMIN=false
ALLOW_VIEWER_LOGIN=true
# 在 IdP 配置 MFA 后改为 true，强制 admin/editor 使用 MFA
OIDC_REQUIRE_MFA=false

# ---------- 统计 ----------
STATS_ENABLED=true
STATS_RETENTION_DAYS=180
STATS_STORE_IP=false

# ---------- 限流 ----------
RATE_LIMIT_WINDOW_SEC=60
RATE_LIMIT_MAX=240
LOGIN_RATE_LIMIT_MAX=30

LOG_LEVEL=info
EOF
  echo "已生成，SESSION_SECRET 长度 $(echo -n "$SESSION_SECRET" | wc -c)"
else
  echo ".env 已存在，仅确保 BASE_URL 正确"
  sed -i "s|^BASE_URL=.*|BASE_URL=https://${DOMAIN}|" "$ENV_FILE"
fi

chown hamkit:hamkit "$ENV_FILE"
chmod 600 "$ENV_FILE"
echo "--- 关键项（隐藏密钥）---"
grep -E '^(NODE_ENV|PORT|HOST|BASE_URL|TRUST_PROXY|OIDC_ISSUER|OIDC_CLIENT_ID|OIDC_CLIENT_AUTH_METHOD|OIDC_ROLE_MAP|STATS_ENABLED)=' "$ENV_FILE" | sed 's/^/  /'
if grep -q '^SESSION_SECRET=.\+' "$ENV_FILE"; then echo "  SESSION_SECRET=已设置"; fi
