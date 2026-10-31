#!/usr/bin/env bash
# 本机导出**未签名**真机 IPA（不需要 Apple Developer 账号）。
#
# 产物：iosApp/HamKit-unsigned.ipa（标准 Payload/HamKit.app 结构）+ .sha256
# 完整构建日志：仓库根目录 ios-device-build.log
#
# 用法（在任意目录执行均可）：
#   ./iosApp/scripts/export-unsigned-ipa.sh              # Release（默认）
#   ./iosApp/scripts/export-unsigned-ipa.sh --debug      # Debug 配置
#   ./iosApp/scripts/export-unsigned-ipa.sh --no-generate # 已有 .xcodeproj，跳过 xcodegen
#
# 注意：未签名 IPA 不能双击安装，需要 Sideloadly / AltStore 用自己的 Apple ID 重签。
set -euo pipefail

cd "$(dirname "$0")/.."   # → iosApp/

CONFIG=Release
GENERATE=1
for arg in "$@"; do
  case "$arg" in
    --debug)       CONFIG=Debug ;;
    --release)     CONFIG=Release ;;
    --no-generate) GENERATE=0 ;;
    -h|--help)     sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "未知参数: $arg（支持 --debug / --release / --no-generate）" >&2; exit 2 ;;
  esac
done

command -v xcodebuild >/dev/null 2>&1 || {
  echo "找不到 xcodebuild：请在 macOS + Xcode 的终端里运行" >&2
  exit 1
}

if [ "$GENERATE" -eq 1 ]; then
  command -v xcodegen >/dev/null 2>&1 || {
    echo "缺少 xcodegen：brew install xcodegen（或加 --no-generate 用现有工程）" >&2
    exit 1
  }
  echo "==> xcodegen generate"
  xcodegen generate
fi

# Kotlin/Native framework 由工程里的 Run Script（preBuildScripts）自动编译，
# 这里只需要保证在仓库根目录能找到 gradlew —— 工程脚本自己会 cd ..。
DERIVED="build-device"
LOG="../ios-device-build.log"

echo "==> 构建 ${CONFIG}（iphoneos / arm64，已禁用签名）"
set +e
xcodebuild \
  -project HamKit.xcodeproj \
  -scheme HamKit \
  -configuration "$CONFIG" \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$DERIVED" \
  ARCHS=arm64 \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  build 2>&1 | tee "$LOG" | tail -n 40
BUILD_STATUS=${PIPESTATUS[0]}
set -e
if [ "$BUILD_STATUS" -ne 0 ]; then
  echo "构建失败（xcodebuild 退出码 ${BUILD_STATUS}），完整日志：$(cd .. && pwd)/ios-device-build.log" >&2
  exit "$BUILD_STATUS"
fi

APP="$DERIVED/Build/Products/${CONFIG}-iphoneos/HamKit.app"
if [ ! -d "$APP" ]; then
  echo "构建产物不存在：$(pwd)/$APP" >&2
  echo "完整日志：$(cd .. && pwd)/ios-device-build.log" >&2
  exit 1
fi

echo "==> 打包 IPA"
rm -rf Payload HamKit-unsigned.ipa
mkdir -p Payload
cp -R "$APP" Payload/
zip -qry HamKit-unsigned.ipa Payload
rm -rf Payload

shasum -a 256 HamKit-unsigned.ipa | tee HamKit-unsigned.ipa.sha256
ls -lh HamKit-unsigned.ipa
echo
echo "完成：$(pwd)/HamKit-unsigned.ipa"
echo "安装：用 Sideloadly / AltStore 以自己的 Apple ID 重签后安装（免费账号 7 天有效）。"
