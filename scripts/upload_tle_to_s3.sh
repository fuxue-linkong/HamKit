#!/usr/bin/env bash
# 下载 CelesTrak 四个 TLE 源并上传到 hamkit-tle S3 bucket（tle.hamkit.click 源站）。
# 客户端（SatelliteDataSource.kt）的 URL 常量与这里的 S3 key 一一对应。
#
# 运行环境：需配置 AWS CLI 凭证（有 hamkit-tle bucket 写权限）。
# 容错：单源失败跳过，不删除已有对象。

set -uo pipefail

BUCKET="hamkit-tle"
CELESTRAK_BASE="https://celestrak.org/NORAD/elements/gp.php"

# 源定义：名称 | CelesTrak query | S3 key | 是否核心源
SOURCES=(
  "satnogs|GROUP=satnogs&FORMAT=3le|tle/satnogs.3le|1"
  "amateur|GROUP=amateur&FORMAT=3le|tle/amateur.3le|1"
  "active|GROUP=active&FORMAT=csv|tle/active.csv|0"
  "iss|CATNR=25544&FORMAT=3le|tle/iss.3le|1"
)

# 频率控制：距上次成功上传不足间隔则跳过（被高频调用时生效）
STAMP_FILE="${TLE_STAMP_DIR:-/tmp}/tle_s3_last_refresh"
MIN_INTERVAL_SECONDS="${MIN_INTERVAL_SECONDS:-7200}"  # 2 小时

mkdir -p "$(dirname "$STAMP_FILE")" 2>/dev/null || true
if [ -f "$STAMP_FILE" ]; then
  last=$(stat -c %Y "$STAMP_FILE" 2>/dev/null || echo 0)
  now=$(date +%s)
  if [ $((now - last)) -lt "$MIN_INTERVAL_SECONDS" ]; then
    echo "距上次上传不足 ${MIN_INTERVAL_SECONDS}s，跳过"
    exit 0
  fi
fi

fetch_and_upload() {
  local name="$1" query="$2" key="$3"
  local url="${CELESTRAK_BASE}?${query}"
  local tmp="/tmp/tle_upload_$$.tmp"

  echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] ${name} ← ${url}"

  if curl --fail --retry 3 --retry-delay 5 -m 60 -sS \
        -A "HamKit-TLE-Mirror/1.0" -o "$tmp" "$url"; then
    if [ -s "$tmp" ]; then
      # 上传到 S3：text/plain 类型 + 1 小时缓存（与 TLE 每小时更新对齐）
      if aws s3 cp "$tmp" "s3://${BUCKET}/${key}" \
           --content-type "text/plain" \
           --cache-control "public, max-age=3600" \
           --only-show-errors 2>&1; then
        echo "  → 上传成功 s3://${BUCKET}/${key} ($(wc -c < "$tmp") 字节)"
        rm -f "$tmp"
        return 0
      else
        echo "  → 上传失败"
        rm -f "$tmp"
        return 1
      fi
    else
      echo "  → 失败：响应为空"
      rm -f "$tmp"
      return 1
    fi
  else
    echo "  → 失败：curl 退出码 $?"
    rm -f "$tmp"
    return 1
  fi
}

core_ok=0
fail=0
for entry in "${SOURCES[@]}"; do
  IFS='|' read -r name query key is_core <<< "$entry"
  if fetch_and_upload "$name" "$query" "$key"; then
    if [ "$is_core" = "1" ]; then
      core_ok=1
    fi
  else
    fail=1
  fi
done

if [ "$core_ok" -eq 1 ]; then
  touch "$STAMP_FILE"
  if [ "$fail" -eq 1 ]; then
    echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] 核心源成功，可选源部分失败，已更新时间戳"
  fi
else
  echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] 全部核心源失败，不更新时间戳"
fi
exit 0