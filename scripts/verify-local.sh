#!/usr/bin/env bash
# 本地编译与测试一键脚本（步骤对齐 .github/workflows/test.yml 与 ios.yml，便于本地复现 CI）
#
# 用法：
#   ./scripts/verify-local.sh               # 全量：shared JVM 单测 + Android 单测 + iOS 模拟器构建
#   ./scripts/verify-local.sh --fast        # 只跑与本次改动相关的：ZoneResolverTest + shared JVM 单测
#   ./scripts/verify-local.sh --android     # 只跑 Android 侧（app 全量单测，顺带编译）
#   ./scripts/verify-local.sh --shared      # 只跑共享内核 JVM 单测（commonTest 走 jvm 目标）
#   ./scripts/verify-local.sh --ios         # 只跑 iOS：链接 Shared.framework + xcodebuild 模拟器构建
#   ./scripts/verify-local.sh --apk         # 额外编译 Debug APK（:app:assembleDebug）
#   ./scripts/verify-local.sh --list        # 只列出将要执行的步骤，不真正执行
#
# 说明：
#   - 默认**不中断**：任一步失败继续跑后面的步骤，最后统一汇总并返回非 0；
#   - 每步完整日志写到 build/verify/*.log，失败的步骤在汇总里给出日志路径；
#   - iOS 构建不需要签名与开发者账号；iOS 26 的 Liquid Glass 需要 iOS 26+ SDK，
#     低于该版本仍能编译（Swift 侧有 #if compiler 保护），只是产物退回半透明材质。
#
# 前置条件：
#   - JDK 21（KMP 的 Kotlin/Native 与 Gradle 都需要；脚本会用 /usr/libexec/java_home -v 21 自动找）
#   - iOS 构建：macOS + Xcode + XcodeGen（brew install xcodegen）
#
# 产物位置：
#   app/build/reports/tests/testDebugUnitTest/index.html   Android 单测报告
#   shared/build/reports/tests/jvmTest/index.html          shared 单测报告
#   iosApp/build/Build/Products/Debug-iphonesimulator/HamKit.app   iOS 模拟器产物

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1

LOG_DIR="$ROOT/build/verify"
mkdir -p "$LOG_DIR"

GRADLE="./gradlew"
[ -x "$GRADLE" ] || chmod +x "$GRADLE" 2>/dev/null

# 区域测试类（本次改动新增的三组用例都在这里）
ZONE_TEST_CLASS="com.example.hamkit.data.zone.ZoneResolverTest"

# ── 参数解析 ────────────────────────────────────────────────────────────────
DO_SHARED=0
DO_ANDROID=0
DO_IOS=0
DO_APK=0
LIST_ONLY=0
EXPLICIT=0
NO_COLOR="${NO_COLOR:-}"

usage() {
  sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --fast)     EXPLICIT=1; DO_SHARED=1; DO_ANDROID=1; FAST=1 ;;
    --android)  EXPLICIT=1; DO_ANDROID=1 ;;
    --shared)   EXPLICIT=1; DO_SHARED=1 ;;
    --ios)      EXPLICIT=1; DO_IOS=1 ;;
    --apk)      EXPLICIT=1; DO_APK=1 ;;
    --all)      EXPLICIT=1; DO_SHARED=1; DO_ANDROID=1; DO_IOS=1; DO_APK=1 ;;
    --list)     LIST_ONLY=1 ;;
    -h|--help)  usage; exit 0 ;;
    *) echo "未知参数：$1（--help 查看用法）" >&2; exit 2 ;;
  esac
  shift
done
FAST="${FAST:-0}"

if [ "$EXPLICIT" -eq 0 ]; then
  DO_SHARED=1; DO_ANDROID=1; DO_IOS=1
fi

# ── 输出辅助 ────────────────────────────────────────────────────────────────
if [ -t 1 ] && [ -z "$NO_COLOR" ]; then
  BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GREEN=$'\033[32m'
  YELLOW=$'\033[33m'; RESET=$'\033[0m'
else
  BOLD=""; DIM=""; RED=""; GREEN=""; YELLOW=""; RESET=""
fi

declare -a NAMES=() STATUS=() LOGS=() DURATIONS=()
FAIL_COUNT=0

run_step() {
  local name="$1"; shift
  local slug log start elapsed rc
  slug=$(printf '%s' "$name" | tr ' /:' '___')
  log="$LOG_DIR/${slug}.log"
  printf '\n%s▶ %s%s\n' "$BOLD" "$name" "$RESET"
  start=$SECONDS
  "$@" 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
  elapsed=$((SECONDS - start))

  NAMES+=("$name"); LOGS+=("$log"); DURATIONS+=("${elapsed}s")
  if [ "$rc" -eq 0 ]; then
    STATUS+=("OK")
    printf '%s✔ %s（%ss）%s\n' "$GREEN" "$name" "$elapsed" "$RESET"
  else
    STATUS+=("FAIL")
    FAIL_COUNT=$((FAIL_COUNT + 1))
    printf '%s✘ %s（%ss，退出码 %s）%s\n' "$RED" "$name" "$elapsed" "$rc" "$RESET"
  fi
}

# ── 环境检查 ────────────────────────────────────────────────────────────────
check_env() {
  if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    [ -n "$JAVA_HOME" ] && export JAVA_HOME
  fi

  local java_bin="java"
  [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] && java_bin="$JAVA_HOME/bin/java"

  if ! command -v "$java_bin" >/dev/null 2>&1; then
    printf '%s✘ 找不到 java，请先安装 JDK 21（brew install --cask temurin@21）%s\n' "$RED" "$RESET"
    exit 2
  fi

  local java_major
  java_major=$("$java_bin" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
  if [ -n "$java_major" ] && [ "$java_major" -lt 21 ] 2>/dev/null; then
    printf '%s! 当前 JDK 主版本 %s，本项目需要 21（版本不符时 Gradle 会报 toolchain 错误）%s\n' \
      "$YELLOW" "$java_major" "$RESET"
  fi
  printf '%s  JDK: %s（JAVA_HOME=%s）%s\n' "$DIM" "$("$java_bin" -version 2>&1 | head -1)" "${JAVA_HOME:-未设置}" "$RESET"
}

gradle() {
  "$GRADLE" "$@" --console=plain --stacktrace --no-daemon
}

# ── iOS 前置检查 ────────────────────────────────────────────────────────────
ios_preflight() {
  if [ "$(uname -s)" != "Darwin" ]; then
    printf '%s✘ iOS 构建只能在 macOS 上进行%s\n' "$RED" "$RESET"; exit 2
  fi
  if ! command -v xcodebuild >/dev/null 2>&1; then
    printf '%s✘ 找不到 xcodebuild，请安装 Xcode 并在 xcode-select 中指向它%s\n' "$RED" "$RESET"; exit 2
  fi
  if ! command -v xcodegen >/dev/null 2>&1; then
    printf '%s✘ 找不到 xcodegen，请先执行：brew install xcodegen%s\n' "$RED" "$RESET"; exit 2
  fi
  local sdk
  sdk=$(xcrun --sdk iphonesimulator --show-sdk-version 2>/dev/null || echo "0")
  printf '%s  Xcode: %s；模拟器 SDK: iOS %s%s\n' \
    "$DIM" "$(xcodebuild -version | head -1)" "$sdk" "$RESET"
  if [ "${sdk%%.*}" -lt 26 ] 2>/dev/null; then
    printf '%s! SDK < 26：能编译，但 Liquid Glass 会退回 ultraThinMaterial（无原生玻璃效果）%s\n' \
      "$YELLOW" "$RESET"
  fi
}

# ── 列出步骤 ────────────────────────────────────────────────────────────────
if [ "$LIST_ONLY" -eq 1 ]; then
  echo "将执行以下步骤："
  n=0
  if [ "$DO_SHARED" -eq 1 ]; then
    n=$((n + 1)); echo "  $n) shared JVM 单测        : :shared:jvmTest"
  fi
  if [ "$DO_ANDROID" -eq 1 ]; then
    n=$((n + 1))
    if [ "$FAST" -eq 1 ]; then
      echo "  $n) 区域修正单测（快速）  : :app:testDebugUnitTest --tests $ZONE_TEST_CLASS"
    else
      echo "  $n) Android 全量单测      : :app:testDebugUnitTest"
    fi
  fi
  if [ "$DO_APK" -eq 1 ]; then
    n=$((n + 1)); echo "  $n) Debug APK 编译        : :app:assembleDebug"
  fi
  if [ "$DO_IOS" -eq 1 ]; then
    n=$((n + 1)); echo "  $n) iOS 模拟器构建        : :shared:linkDebugFrameworkIosSimulatorArm64 + xcodebuild"
  fi
  echo "日志目录：$LOG_DIR"
  exit 0
fi

check_env
[ "$DO_IOS" -eq 1 ] && ios_preflight

# ── 1. shared 内核单测（commonTest 通过 jvm 目标执行）────────────────────────
if [ "$DO_SHARED" -eq 1 ]; then
  run_step "shared 内核 JVM 单测" \
    gradle :shared:jvmTest
fi

# ── 2. Android 单测 ─────────────────────────────────────────────────────────
if [ "$DO_ANDROID" -eq 1 ]; then
  if [ "$FAST" -eq 1 ]; then
    run_step "区域修正单测（ZoneResolverTest）" \
      gradle :app:testDebugUnitTest --tests "$ZONE_TEST_CLASS" --continue
  else
    run_step "Android 全量单测" \
      gradle :app:testDebugUnitTest --continue
  fi
fi

# ── 3. Debug APK 编译 ───────────────────────────────────────────────────────
if [ "$DO_APK" -eq 1 ]; then
  run_step "Debug APK 编译" \
    gradle :app:assembleDebug
fi

# ── 4. iOS：链接 framework + 生成工程 + 模拟器构建 ──────────────────────────
if [ "$DO_IOS" -eq 1 ]; then
  run_step "链接 Shared.framework（iOS 模拟器 arm64）" \
    gradle :shared:linkDebugFrameworkIosSimulatorArm64

  run_step "生成 Xcode 工程（xcodegen）" \
    bash -c "cd '$ROOT/iosApp' && xcodegen generate"

  run_step "iOS 模拟器构建（xcodebuild，免签名）" \
    bash -c "cd '$ROOT/iosApp' && xcodebuild \
      -project HamKit.xcodeproj \
      -scheme HamKit \
      -configuration Debug \
      -sdk iphonesimulator \
      -destination 'generic/platform=iOS Simulator' \
      -derivedDataPath build \
      ARCHS=arm64 \
      AMAP_WEB_KEY=\"\${AMAP_WEB_KEY:-}\" \
      CODE_SIGNING_ALLOWED=NO \
      CODE_SIGNING_REQUIRED=NO \
      build"
fi

# ── 汇总 ────────────────────────────────────────────────────────────────────
printf '\n%s════════ 汇总 ════════%s\n' "$BOLD" "$RESET"
for i in "${!NAMES[@]}"; do
  if [ "${STATUS[$i]}" = "OK" ]; then
    printf '%s✔ %-40s %s%s\n' "$GREEN" "${NAMES[$i]}" "${DURATIONS[$i]}" "$RESET"
  else
    printf '%s✘ %-40s %s  日志：%s%s\n' "$RED" "${NAMES[$i]}" "${DURATIONS[$i]}" "${LOGS[$i]}" "$RESET"
  fi
done

if [ "$FAIL_COUNT" -gt 0 ]; then
  printf '\n%s%d 个步骤失败%s\n' "$RED" "$FAIL_COUNT" "$RESET"
  exit 1
fi

printf '\n%s全部通过%s\n' "$GREEN" "$RESET"
[ "$DO_ANDROID" -eq 1 ] && printf '%s  Android 报告：app/build/reports/tests/testDebugUnitTest/index.html%s\n' "$DIM" "$RESET"
[ "$DO_SHARED" -eq 1 ] && printf '%s  shared 报告：shared/build/reports/tests/jvmTest/index.html%s\n' "$DIM" "$RESET"
[ "$DO_IOS" -eq 1 ] && printf '%s  iOS 产物：iosApp/build/Build/Products/Debug-iphonesimulator/HamKit.app%s\n' "$DIM" "$RESET"
exit 0
