#!/usr/bin/env bash
#
# 从 HamKit 的签名 keystore 生成手环快应用所需的 pem 证书/私钥。
#
# 为什么必须这么做：
#   手环快应用与手机 App 通过 system.interconnect 通信时，系统会校验两端签名。
#   rpk 的签名证书必须与本 App 的签名证书完全一致，否则连接会被直接拒绝
#   （官方 FAQ：「手表和手机通信前会检查应用的签名，如果签名不正确通信会被拒绝」）。
#
# 用法（环境变量与打 release 包时保持一致）：
#   KEYSTORE_PATH=signing/release.keystore \
#   KEYSTORE_PASSWORD=xxx KEY_ALIAS=xxx KEY_PASSWORD=xxx \
#   ./scripts/prepare-wearable-sign.sh
#
# 输出：wearable/sign/release/{private.pem,certificate.pem}
# 这两个文件已被 .gitignore 忽略，不要提交。

set -euo pipefail

KEYSTORE_PATH="${KEYSTORE_PATH:-signing/release.keystore}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-}"
KEY_ALIAS="${KEY_ALIAS:-}"
KEY_PASSWORD="${KEY_PASSWORD:-}"
OUT_DIR="${OUT_DIR:-wearable/sign/release}"

fail() { echo "错误：$*" >&2; exit 1; }

[ -f "$KEYSTORE_PATH" ] || fail "找不到 keystore：$KEYSTORE_PATH"
[ -n "$KEYSTORE_PASSWORD" ] || fail "缺少 KEYSTORE_PASSWORD"
command -v keytool >/dev/null || fail "缺少 keytool（请安装 JDK）"
command -v openssl >/dev/null || fail "缺少 openssl"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "==> jks -> p12"
keytool -importkeystore \
  -srckeystore "$KEYSTORE_PATH" \
  -srcstorepass "$KEYSTORE_PASSWORD" \
  -destkeystore "$work/keystore.p12" \
  -deststoretype PKCS12 \
  -deststorepass "$KEYSTORE_PASSWORD" \
  -srcalias "$KEY_ALIAS" \
  -destalias "$KEY_ALIAS" \
  -srckeypass "$KEY_PASSWORD" \
  -destkeypass "$KEYSTORE_PASSWORD" \
  -noprompt

echo "==> p12 -> pem"
openssl pkcs12 -nodes \
  -in "$work/keystore.p12" \
  -passin "pass:$KEYSTORE_PASSWORD" \
  -out "$work/keystore.pem"

mkdir -p "$OUT_DIR"

# 私钥：第一个 PRIVATE KEY 块（PKCS8）
awk '/-----BEGIN PRIVATE KEY-----/{f=1} f{print} /-----END PRIVATE KEY-----/{f=0}' \
  "$work/keystore.pem" > "$OUT_DIR/private.pem"

# 证书：第一个 CERTIFICATE 块
awk '/-----BEGIN CERTIFICATE-----/{f=1} f{print} /-----END CERTIFICATE-----/{f=0}' \
  "$work/keystore.pem" > "$OUT_DIR/certificate.pem"

[ -s "$OUT_DIR/private.pem" ] || fail "未能从 keystore 提取私钥（可能不是 PKCS8 格式）"
[ -s "$OUT_DIR/certificate.pem" ] || fail "未能从 keystore 提取证书"

echo "==> 完成，输出到 $OUT_DIR"
echo "    $(grep -c . "$OUT_DIR/private.pem") 行 private.pem / $(grep -c . "$OUT_DIR/certificate.pem") 行 certificate.pem"
echo
echo "注意：debug 包请改用 ~/.android/debug.keystore（别名 androiddebugkey，口令 android），"
echo "     并在 OUT_DIR=wearable/sign/debug 下重新生成一次，否则 debug 版 App 连不上手环。"
