#!/usr/bin/env bash
# 把 SatNOGS 的全量转发器表（频率数据）镜像到 hamkit-tle S3 bucket，
# 供 iOS「转发器/上下行频率」功能兜底。
#
# 背景：db.satnogs.org 在国内经常超时或被 DNS 污染，Android 侧靠 Room 缓存兜底，
# iOS 侧（`SatnogsTransmitters.kt`）改造前没有任何兜底 —— 拉不到就永远显示
# 「暂无转发器 / 数据不可用」。现在客户端按下面的顺序尝试：
#   1. https://tle.hamkit.click/tle/transmitters.json   ← 本脚本负责生成这个对象（主源）
#   2. https://db.satnogs.org/api/transmitters/?format=json（备源）
# 与 TLE 一致：主 = 自建 CDN，次 = 上游官方源。
#
# 运行环境：需配置 AWS CLI 凭证（有 hamkit-tle bucket 写权限）。
# 建议 cron：每 24 小时一次（SatNOGS 转发器数据变动很慢）。
#
# 安全阀：解析出的记录数少于 MIN_RECORDS 时拒绝上传，避免空响应/半截响应
# 把 CDN 上的好数据覆盖掉。

set -uo pipefail

BUCKET="hamkit-tle-tpe"          # 与 website/deploy/hamkit-tle-sync.sh 的生产桶一致
REGION="ap-east-2"
SRC_URL="https://db.satnogs.org/api/transmitters/?format=json"
S3_KEY="tle/transmitters.json"   # 与 satnogs.3le 等同目录，客户端 URL 即 tle.hamkit.click/tle/transmitters.json
TMP="/tmp/satnogs_transmitters_$$.json"
MIN_RECORDS="${MIN_RECORDS:-3000}"

echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] 拉取 ${SRC_URL}"

if ! curl --fail --retry 3 --retry-delay 5 -m 120 -sS \
      -A "HamKit-TLE-Mirror/1.0" -o "$TMP" "$SRC_URL"; then
  echo "  → 失败：curl 退出码 $?"
  rm -f "$TMP"
  exit 1
fi

if [ ! -s "$TMP" ]; then
  echo "  → 失败：响应为空"
  rm -f "$TMP"
  exit 1
fi

# 记录数用 'norad_cat_id' 出现次数近似（不依赖 jq）
count=$(grep -o '"norad_cat_id"' "$TMP" | wc -l | tr -d ' ')
echo "  → 收到 $(wc -c < "$TMP") 字节，约 ${count} 条记录"

if [ "$count" -lt "$MIN_RECORDS" ]; then
  echo "  → 拒绝上传：记录数 ${count} < ${MIN_RECORDS}（可能是半截响应）"
  rm -f "$TMP"
  exit 1
fi

if aws --region "$REGION" s3 cp "$TMP" "s3://${BUCKET}/${S3_KEY}" \
     --content-type "application/json" \
     --cache-control "public, max-age=21600" \
     --only-show-errors; then
  echo "  → 上传成功 s3://${BUCKET}/${S3_KEY}"
  rm -f "$TMP"
  exit 0
else
  echo "  → 上传失败"
  rm -f "$TMP"
  exit 1
fi
