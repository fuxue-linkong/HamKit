#!/usr/bin/env bash
# ============================================================
# hamkit-tle-sync —— 从 celestrak-mirror 同步 TLE 数据到 S3
#
# 流程：
#   1) 读仓库的 LAST_REFRESH；若与上次同步的标记相同则直接退出（省流量）
#   2) 下载 tle/ 下全部 *.tle
#   3) 上传到 s3://hamkit-tle-tpe/tle/（桶在台北 ap-east-2，经 VPC Gateway Endpoint 走内网）
#
# 由 systemd timer 每 30 分钟触发（hamkit-tle-sync.timer）。
# 手动运行：
#   sudo /usr/local/bin/hamkit-tle-sync.sh            # 正常同步
#   sudo /usr/local/bin/hamkit-tle-sync.sh --force    # 忽略 LAST_REFRESH，强制同步
#   sudo /usr/local/bin/hamkit-tle-sync.sh --prune    # 额外删除 S3 上仓库已不存在的文件
#   sudo /usr/local/bin/hamkit-tle-sync.sh --dry-run  # 只显示会做什么
# ============================================================
set -uo pipefail

# ---------- 配置 ----------
REPO_RAW="https://raw.githubusercontent.com/astrion-tech/celestrak-mirror/main"
REPO_API="https://api.github.com/repos/astrion-tech/celestrak-mirror/contents/tle"
# CelesTrak 官方 GP API（用于 App 需要的分组；镜像仓库没有这几个文件）
CELESTRAK_API="https://celestrak.org/NORAD/elements/gp.php"
BUCKET="hamkit-tle-tpe"
PREFIX="tle"
REGION="ap-east-2"          # 桶所在区域（台北）
WORK_DIR="/var/lib/hamkit-tle"
STATE_FILE="${WORK_DIR}/last-refresh.state"
LOG_FILE="/var/log/hamkit-tle-sync.log"
CRED_FILE="/etc/hamkit-tle/credentials"
LOCK_FILE="/run/hamkit-tle-sync.lock"

# App 端写死的四个分组（app/.../SatelliteDataSource.kt 的 TleSourceUrls.Cdn）。
# 这些文件必须存在，否则 App 卫星列表会退化；镜像仓库不提供，故直连 CelesTrak。
#
# ⚠️ CelesTrak 的下发窗口机制（实测）：
#   同一 GROUP 在数据未更新前重复请求会返回 403，正文形如
#   "GP data has not updated since your last successful download of GROUP=xxx …"
#   数据每 2 小时更新一次，且**节流按 GROUP 计**（换 FORMAT 也一样被拦）。
#   因此这里对每个分组做 2 小时缓存，只有窗口过了才再次请求，避免无谓的 403。
#   403 一律视为"暂无新数据"，保留 S3 上的旧文件，绝不中断。
#
# 格式说明：
#   TLE（3le 文本）—— App 默认流程使用（parseThreeLineTLEs）
#   CSV（含 OBJECT_NAME 表头）—— App 在自定义 URL 场景下用 parseActiveCsv 解析
# 两种都产出，互不影响。
CELESTRAK_MIN_INTERVAL_SEC=7200     # 2 小时，与 CelesTrak 更新周期一致
# 收到 403（窗口未开）时的**原地快速重试**。
# 只用于兜住"窗口刚好在这一轮开启"的边界情况，因此刻意保持很短
# （3 × 45s ≈ 2.5 分钟），远小于 service 的 TimeoutStartSec=600，
# 避免 systemd 掐断。真正的兜底是定时器每 30 分钟再跑一轮。
CELESTRAK_RETRY_TIMES=3
CELESTRAK_RETRY_SLEEP=45

# 分组清单：GROUP|输出文件名|格式
APP_GROUPS="\
satnogs|satnogs.3le|tle
amateur|amateur.3le|tle
active|active.3le|tle
active|active.csv|csv
"
# ISS 单独文件从 stations 组抽取
ISS_FROM_STATIONS=1

FORCE=0
PRUNE=0
DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --force)   FORCE=1 ;;
    --prune)   PRUNE=1 ;;
    --dry-run) DRY_RUN=1 ;;
    *) echo "未知参数: $arg" >&2; exit 2 ;;
  esac
done

# ---------- 日志 ----------
mkdir -p "$WORK_DIR"
log() {
  local line="[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] $*"
  echo "$line"
  # 日志超过 5MB 时轮转一次
  if [ -f "$LOG_FILE" ] && [ "$(stat -c%s "$LOG_FILE" 2>/dev/null || echo 0)" -gt 5242880 ]; then
    mv -f "$LOG_FILE" "${LOG_FILE}.1"
  fi
  echo "$line" >> "$LOG_FILE"
}

# ---------- 单实例锁（避免上一轮未跑完就叠加） ----------
exec 9>"$LOCK_FILE" || { echo "无法创建锁文件 $LOCK_FILE" >&2; exit 1; }
if ! flock -n 9; then
  log "上一轮同步仍在运行，跳过本次"
  exit 0
fi

# ---------- 依赖检查 ----------
for cmd in curl aws flock; do
  command -v "$cmd" >/dev/null 2>&1 || { log "缺少依赖: $cmd"; exit 1; }
done

# ---------- 凭证 ----------
if [ -f "$CRED_FILE" ]; then
  # 只取出这两个变量，不 source 整个文件以避免副作用
  AWS_ACCESS_KEY_ID="$(grep -E '^AWS_ACCESS_KEY_ID=' "$CRED_FILE" | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'" | xargs)"
  AWS_SECRET_ACCESS_KEY="$(grep -E '^AWS_SECRET_ACCESS_KEY=' "$CRED_FILE" | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'" | xargs)"
  export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
fi
if [ -z "${AWS_ACCESS_KEY_ID:-}" ] || [ -z "${AWS_SECRET_ACCESS_KEY:-}" ]; then
  # 也允许通过实例角色取凭证（若以后改成 IAM Role，凭据文件可删）
  if ! curl -s --max-time 3 -o /dev/null http://169.254.169.254/latest/meta-data/iam/security-credentials/ 2>/dev/null; then
    log "错误：未找到 AWS 凭证（$CRED_FILE 不存在且无实例角色）"
    exit 1
  fi
fi
export AWS_DEFAULT_REGION="$REGION"
AWS="aws --region $REGION"

# ---------- 1) 检查新鲜度 ----------
REMOTE_REFRESH="$(curl -fsS --max-time 20 -H 'User-Agent: hamkit-tle-sync' "${REPO_RAW}/LAST_REFRESH" 2>/dev/null | tr -d '\r\n' || true)"
if [ -z "$REMOTE_REFRESH" ]; then
  log "无法读取 LAST_REFRESH（会继续尝试同步，以便网络抖动后自愈）"
else
  log "远端最后刷新: $REMOTE_REFRESH"
fi

LAST_SYNCED=""
[ -f "$STATE_FILE" ] && LAST_SYNCED="$(cat "$STATE_FILE" 2>/dev/null | tr -d '\r\n')"

# 仓库线是否需要跑（注意：**这里不能 exit**，App 分组线要独立判断）
# 两条线的更新周期不同：仓库每 30 分钟刷新，CelesTrak 每 2 小时更新。
# 若在此处直接 exit，仓库没变时 App 分组就永远不会被刷新。
REPO_CHANGED=1
if [ "$FORCE" -eq 0 ] && [ -n "$REMOTE_REFRESH" ] && [ "$REMOTE_REFRESH" = "$LAST_SYNCED" ]; then
  REPO_CHANGED=0
fi

DL_DIR="${WORK_DIR}/tle"
mkdir -p "$DL_DIR"

if [ "$REPO_CHANGED" -eq 1 ]; then
  log "仓库数据有更新（$REMOTE_REFRESH），开始同步仓库文件"

  # ---------- 2) 取文件清单 ----------
  # 优先用 API 拿真实文件列表；API 不可用（限流）时退回内置清单
  FILES=""
  LIST_JSON="$(curl -fsS --max-time 25 -H 'User-Agent: hamkit-tle-sync' -H 'Accept: application/vnd.github+json' "$REPO_API" 2>/dev/null || true)"
  if [ -n "$LIST_JSON" ]; then
    FILES="$(printf '%s' "$LIST_JSON" | grep -o '"name": *"[^"]*\.tle"' | sed 's/.*"name": *"//; s/"$//' | sort -u)"
  fi
  if [ -z "$FILES" ]; then
    log "GitHub API 不可用，使用内置文件清单"
    FILES="$(printf '%s\n' geo.tle military.tle oneweb.tle resource.tle science.tle starlink.tle stations.tle weather.tle)"
  fi
  FILE_COUNT="$(printf '%s\n' "$FILES" | grep -c . )"
  log "待同步文件数: $FILE_COUNT"

  # ---------- 3) 下载 ----------
  OK=0
  FAIL=0
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    tmp="${DL_DIR}/.${f}.part"
    if curl -fsS --max-time 120 --retry 3 --retry-delay 3 \
         -H 'User-Agent: hamkit-tle-sync' \
         -o "$tmp" "${REPO_RAW}/tle/${f}"; then
      size="$(stat -c%s "$tmp")"
      if [ "$size" -gt 0 ]; then
        mv -f "$tmp" "${DL_DIR}/${f}"
        OK=$((OK+1))
      else
        log "  ✗ ${f} 下载为空"
        rm -f "$tmp"; FAIL=$((FAIL+1))
      fi
    else
      log "  ✗ ${f} 下载失败"
      rm -f "$tmp"; FAIL=$((FAIL+1))
    fi
  done <<< "$FILES"
  log "仓库文件下载完成：成功 ${OK}，失败 ${FAIL}"

  if [ "$OK" -eq 0 ]; then
    log "仓库文件一个都没下载成功，本轮不更新状态标记（App 分组仍会继续尝试）"
  else
    if [ -n "$REMOTE_REFRESH" ]; then
      printf '%s' "$REMOTE_REFRESH" > "$STATE_FILE"
      log "已记录仓库同步标记: $REMOTE_REFRESH"
    fi
  fi
else
  log "仓库数据未变化（$LAST_SYNCED），仓库线跳过（App 分组线仍会检查）"
fi

# ---------- 4) 额外拉取 App 需要的分组（镜像仓库不提供）----------
# App 从 tle.hamkit.click/tle/ 读取 satnogs.3le / amateur.3le / active.3le / active.csv / iss.3le，
# 这些在 celestrak-mirror 里不存在，直连 CelesTrak GP API 获取。
# 注意：本段**独立于仓库线**执行，因为它按 CelesTrak 的 2 小时周期走。
GROUP_STATE_DIR="${WORK_DIR}/group-state"
mkdir -p "$GROUP_STATE_DIR"

# 返回 0 表示"可以请求"，1 表示"还在 2 小时窗口内"
group_due() {
  local group="$1"
  local stamp_file="${GROUP_STATE_DIR}/${group}.ts"
  [ "$FORCE" -eq 1 ] && return 0
  [ -f "$stamp_file" ] || return 0
  local last now
  last="$(cat "$stamp_file" 2>/dev/null || echo 0)"
  now="$(date +%s)"
  [ $(( now - last )) -ge "$CELESTRAK_MIN_INTERVAL_SEC" ]
}

group_mark() {
  date +%s > "${GROUP_STATE_DIR}/$1.ts"
}

log "拉取 App 所需分组（CelesTrak GP API）"
APP_OK=0
APP_SKIP=0
# 同一分组可能对应多个产物（如 active 的 tle 与 csv），用去重后的分组列表判断窗口
for group in $(printf '%s\n' "$APP_GROUPS" | cut -d'|' -f1 | sort -u); do
  if ! group_due "$group"; then
    log "  - GROUP=${group} 在 2 小时窗口内，跳过（避免无谓 403）"
    APP_SKIP=$((APP_SKIP+1))
    continue
  fi

  # 该分组要产出的所有 文件名|格式
  while IFS='|' read -r g outname fmt; do
    [ "$g" = "$group" ] || continue
    tmp="${DL_DIR}/.${outname}.part"

    # 403（窗口未开）时原地重试 —— 但仅当"上次成功请求"已接近 2 小时，
    # 也就是窗口真的快要开的时候才有意义。
    # 若上次请求就在刚刚（比如手工 --force 连跑两次），重试纯属浪费，
    # 因此这里按已流逝时间决定是否重试。
    stamp_file="${GROUP_STATE_DIR}/${group}.ts"
    elapsed=999999
    [ -f "$stamp_file" ] && elapsed=$(( $(date +%s) - $(cat "$stamp_file" 2>/dev/null || echo 0) ))
    allow_retry=0
    if [ "$elapsed" -ge $(( CELESTRAK_MIN_INTERVAL_SEC - 600 )) ]; then
      allow_retry=1   # 距窗口开启不足 10 分钟，值得原地等
    fi

    attempt=0
    while : ; do
      attempt=$((attempt+1))
      code=$(curl -s -o "$tmp" -w '%{http_code}' --max-time 180 --retry 2 --retry-delay 5 \
               -A 'hamkit-tle-sync/1.0 (+https://hamkit.click)' \
               "${CELESTRAK_API}?GROUP=${g}&FORMAT=${fmt}" 2>/dev/null || echo 000)
      size="$(stat -c%s "$tmp" 2>/dev/null || echo 0)"

      if [ "$code" = "403" ] && [ "$allow_retry" -eq 1 ] && [ "$attempt" -le "$CELESTRAK_RETRY_TIMES" ]; then
        log "  … ${outname} 窗口未开（403），${CELESTRAK_RETRY_SLEEP}s 后重试（第 ${attempt}/${CELESTRAK_RETRY_TIMES} 次）"
        sleep "$CELESTRAK_RETRY_SLEEP"
        continue
      fi
      break
    done

    if [ "$code" = "200" ] && [ "$size" -gt 200 ]; then
      mv -f "$tmp" "${DL_DIR}/${outname}"
      log "  ✓ ${outname} (GROUP=${g}, FORMAT=${fmt}, ${size} B)"
      APP_OK=$((APP_OK+1))
    elif [ "$code" = "403" ]; then
      # 窗口未开 —— 保留旧文件，由本地生成兜底（见下一步），并等下一轮
      log "  · ${outname} 窗口未开（403，尝试 ${attempt} 次），保留现有文件"
      rm -f "$tmp"
    else
      log "  ✗ ${outname} 获取失败（HTTP ${code}, ${size} B），保留现有文件"
      rm -f "$tmp"
    fi
  done <<< "$APP_GROUPS"

  group_mark "$group"
done

# ISS 单独文件：从 stations 组里抽取 ISS（ZARYA）那一条
if [ "$ISS_FROM_STATIONS" -eq 1 ]; then
  if group_due stations; then
    tmp="${DL_DIR}/.iss_src.part"
    code=$(curl -s -o "$tmp" -w '%{http_code}' --max-time 60 --retry 2 --retry-delay 5 \
             -A 'hamkit-tle-sync/1.0 (+https://hamkit.click)' \
             "${CELESTRAK_API}?GROUP=stations&FORMAT=tle" 2>/dev/null || echo 000)
    if [ "$code" = "200" ] && [ -s "$tmp" ]; then
      # stations 是「名称行/TLE1/TLE2」三行一组；抽 ISS 时排除碎片（ISS DEB）
      if awk 'NR%3==1{n=$0} NR%3==2{l1=$0} NR%3==0{l2=$0; if (n ~ /ISS/ && n !~ /DEB/) printf "%s\n%s\n%s\n", n, l1, l2}' "$tmp" > "${DL_DIR}/iss.3le.new" \
         && [ -s "${DL_DIR}/iss.3le.new" ] && [ "$(grep -c '^1 ' "${DL_DIR}/iss.3le.new")" -ge 1 ]; then
        mv -f "${DL_DIR}/iss.3le.new" "${DL_DIR}/iss.3le"
        log "  ✓ iss.3le（从 stations 组抽取，$(stat -c%s "${DL_DIR}/iss.3le") B）"
        APP_OK=$((APP_OK+1))
      else
        log "  ✗ iss.3le 抽取失败，保留 S3 现有文件"
        rm -f "${DL_DIR}/iss.3le.new"
      fi
    else
      log "  · iss.3le 源数据未更新或失败（HTTP ${code}），保留 S3 现有文件"
    fi
    rm -f "$tmp"
    group_mark stations
  else
    log "  - GROUP=stations 在 2 小时窗口内，跳过"
    APP_SKIP=$((APP_SKIP+1))
  fi
fi
log "App 分组完成：成功 ${APP_OK}，窗口内跳过 ${APP_SKIP}"

# ---------- 4b) CSV 兜底：CelesTrak 窗口关闭时，由 3le 本地生成 ----------
# CelesTrak 的 GP 接口有「同一 GROUP 2 小时内只下发一次」的窗口节流（未开返回 403），
# 因此 CSV 有时拿不到新数据。而 CSV 与 3le 的字段是 1:1 对应的（见 app 的 TleParser.kt），
# 所以这里用刚取到的 active.3le 本地生成 active.csv，保证该文件始终与 TLE 同步新鲜。
#
# 优先用 CelesTrak 官方 CSV；仅在官方 CSV 本轮没更新时才生成，避免覆盖权威数据。
CSV_SRC="${DL_DIR}/active.3le"
CSV_OUT="${DL_DIR}/active.csv"
if [ -s "$CSV_SRC" ] && [ -x /usr/local/bin/tle2csv.mjs ]; then
  CSV_FRESH=0
  if [ -f "$CSV_OUT" ]; then
    # "本轮是否刚生成过"：生成发生在 group_mark 之后，
    # 所以 CSV 比 group-state 时间戳新 ⇒ 它是本轮生成的，别再被官方值覆盖。
    if [ -f "${GROUP_STATE_DIR}/active.ts" ] && [ "$CSV_OUT" -nt "${GROUP_STATE_DIR}/active.ts" ]; then
      CSV_FRESH=1
    fi
  fi

  if [ "$CSV_FRESH" -eq 1 ]; then
    log "active.csv 来自 CelesTrak 官方且比 3le 新，保留官方版本"
  else
    log "由 active.3le 本地生成 active.csv（CelesTrak 窗口未开时的兜底）"
    if node /usr/local/bin/tle2csv.mjs "$CSV_SRC" "${CSV_OUT}.new" 2>&1 | sed 's/^/    /'; then
      # 生成后自检：必填列齐全且行数合理，通过才替换
      if [ -x /usr/local/bin/validate-csv.mjs ] \
         && node /usr/local/bin/validate-csv.mjs "${CSV_OUT}.new" >/dev/null 2>&1; then
        # 内容没变就不替换（保持时间戳，避免 s3 sync 每轮白传 2.4MB）
        if [ -f "$CSV_OUT" ] && cmp -s "${CSV_OUT}.new" "$CSV_OUT"; then
          log "  · active.csv 内容未变化，跳过替换（省一次上传）"
          rm -f "${CSV_OUT}.new"
        else
          mv -f "${CSV_OUT}.new" "$CSV_OUT"
          log "  ✓ active.csv 生成并自检通过（$(wc -c < "$CSV_OUT") B, $(($(wc -l < "$CSV_OUT") - 1)) 条）"
        fi
      else
        log "  ✗ 生成的 active.csv 未通过自检，保留 S3 上的现有文件"
        rm -f "${CSV_OUT}.new"
      fi
    else
      log "  ✗ 生成失败，保留 S3 上的现有文件"
      rm -f "${CSV_OUT}.new"
    fi
  fi
else
  [ -s "$CSV_SRC" ] || log "无 active.3le，跳过 CSV 生成"
fi

# ---------- 4c) SatNOGS 转发器快照（App「转发器 / 上下行频率」的主源）----------
# 客户端取数顺序（shared/.../SatnogsTransmitters.kt）：
#   主 = tle.hamkit.click/tle/transmitters.json
#   备 = db.satnogs.org/api/transmitters/?format=json
# db.satnogs.org 在国内经常超时/被 DNS 污染，所以这里把上游 JSON 原样镜像到
# 同一个 tle/ 前缀下（和 satnogs.3le 等文件并列），国内可直连。
#
# 上游数据变动很慢，按 24 小时窗口刷新；记录数低于阈值时拒绝替换，
# 避免上游返回半截/空响应把 CDN 上的好数据覆盖掉。
SATNOGS_TRANSMITTERS_URL="https://db.satnogs.org/api/transmitters/?format=json"
# 刻意放在 DL_DIR 之外：第 5 步的 `aws s3 sync --delete` 只处理 DL_DIR，
# 这样即使带 --prune 也不可能把 S3 上的 transmitters.json 删掉。
TX_OUT="${WORK_DIR}/transmitters.json"
TX_RAW="${WORK_DIR}/transmitters.raw.json"
TX_STAMP="${WORK_DIR}/transmitters.ts"
# 与 Android `TransmitterDataSource.parseTransmitterItem` 同规则的裁剪脚本
# （只保留 App 用得到的 7 个字段，体积约为原始的 1/3～1/5）
TX_TRIM="/usr/local/bin/trim-transmitters.mjs"
TX_MIN_INTERVAL_SEC=86400     # 24 小时
TX_MIN_RECORDS=3000           # 当前 4000+ 条；低于此值视为异常响应

tx_due() {
  [ "$FORCE" -eq 1 ] && return 0
  [ -f "$TX_STAMP" ] || return 0
  local last now
  last="$(cat "$TX_STAMP" 2>/dev/null || echo 0)"
  now="$(date +%s)"
  [ $(( now - last )) -ge "$TX_MIN_INTERVAL_SEC" ]
}

if tx_due; then
  tx_code=$(curl -s -o "$TX_RAW" -w '%{http_code}' --max-time 240 --retry 2 --retry-delay 5 \
              -A 'hamkit-tle-sync/1.0 (+https://hamkit.click)' \
              "$SATNOGS_TRANSMITTERS_URL" 2>/dev/null || echo 000)
  tx_raw_size="$(stat -c%s "$TX_RAW" 2>/dev/null || echo 0)"
  tx_count=0
  [ "$tx_raw_size" -gt 0 ] && tx_count="$(grep -o '"norad_cat_id"' "$TX_RAW" | wc -l | tr -d ' ')"

  if [ "$tx_code" != "200" ] || [ "$tx_count" -lt "$TX_MIN_RECORDS" ]; then
    log "  ✗ transmitters.json 获取失败/异常（HTTP ${tx_code}, ${tx_raw_size} B, ${tx_count} 条），保留 S3 现有文件"
    rm -f "$TX_RAW"
  else
    # 裁剪（规则照抄 Android）。脚本不存在或失败时退回原始 JSON，功能不受影响。
    tx_new="${TX_OUT}.new"
    if [ -x "$TX_TRIM" ] && node "$TX_TRIM" "$TX_RAW" "$tx_new" 2>&1 | sed 's/^/    /' | tee -a "$LOG_FILE"; then
      :
    elif [ -x "$TX_TRIM" ]; then
      log "  ! 裁剪失败，退回原始 JSON（体积更大但可用）"
      cp -f "$TX_RAW" "$tx_new"
    else
      log "  · 未安装 $TX_TRIM，直接镜像原始 JSON"
      cp -f "$TX_RAW" "$tx_new"
    fi

    if [ ! -s "$tx_new" ]; then
      log "  ✗ 裁剪输出为空，保留 S3 现有文件"
      rm -f "$tx_new"
    elif [ -f "$TX_OUT" ] && cmp -s "$tx_new" "$TX_OUT"; then
      log "  · transmitters.json 内容未变化，跳过替换（省一次上传）"
      rm -f "$tx_new"
    else
      mv -f "$tx_new" "$TX_OUT"
      log "  ✓ transmitters.json（$(stat -c%s "$TX_OUT") B，来自 ${tx_count} 条原始记录）"
    fi
    rm -f "$TX_RAW"
    date +%s > "$TX_STAMP"
  fi
else
  log "  - transmitters.json 在 24 小时窗口内，跳过"
fi

# ---------- 5) 上传 ----------
# 覆盖三类产物：
#   *.tle —— 镜像仓库的分组文件
#   *.3le —— App 需要的 3le 文本（satnogs/amateur/active/iss）
#   *.csv —— App 需要的 active.csv（自定义 URL 场景走 parseActiveCsv）
SYNC_ARGS=(--region "$REGION" --only-show-errors --no-progress
           --exclude '*' --include '*.tle' --include '*.3le' --include '*.csv'
           --content-type 'text/plain; charset=utf-8'
           --cache-control 'public, max-age=900')
[ "$PRUNE" -eq 1 ] && SYNC_ARGS+=(--delete)

if [ "$DRY_RUN" -eq 1 ]; then
  log "[dry-run] 将执行: aws s3 sync $DL_DIR s3://${BUCKET}/${PREFIX}/  (共 $(ls -1 "$DL_DIR" | grep -cE '\.(tle|3le|csv)$') 个文件)"
  ls -la "$DL_DIR" | sed 's/^/    /'
  exit 0
fi

log "上传到 s3://${BUCKET}/${PREFIX}/"
if $AWS s3 sync "$DL_DIR" "s3://${BUCKET}/${PREFIX}/" "${SYNC_ARGS[@]}" 2>&1 | sed 's/^/    /' | tee -a "$LOG_FILE"; then
  :
else
  log "上传失败"
  exit 1
fi

# transmitters.json 单独上传：content-type 用 application/json（sync 那一步
# 给 .tle/.3le/.csv 统一用了 text/plain），缓存 6 小时。
if [ -s "$TX_OUT" ]; then
  if $AWS s3 cp "$TX_OUT" "s3://${BUCKET}/${PREFIX}/transmitters.json" \
       --only-show-errors --no-progress \
       --content-type 'application/json' \
       --cache-control 'public, max-age=21600' 2>&1 | sed 's/^/    /' | tee -a "$LOG_FILE"; then
    log "  ✓ transmitters.json 已上传（$(stat -c%s "$TX_OUT") B）"
  else
    log "  ✗ transmitters.json 上传失败"
  fi
fi

# ---------- 6) 汇总 ----------
TLE_N="$($AWS s3 ls "s3://${BUCKET}/${PREFIX}/" 2>/dev/null | grep -c '\.tle$' || echo '?')"
THREE_N="$($AWS s3 ls "s3://${BUCKET}/${PREFIX}/" 2>/dev/null | grep -c '\.3le$' || echo '?')"
CSV_N="$($AWS s3 ls "s3://${BUCKET}/${PREFIX}/" 2>/dev/null | grep -c '\.csv$' || echo '?')"
TX_N="$($AWS s3 ls "s3://${BUCKET}/${PREFIX}/transmitters.json" 2>/dev/null | wc -l | tr -d ' ')"
log "同步完成。s3://${BUCKET}/${PREFIX}/ 下 .tle=${TLE_N} 个，.3le=${THREE_N} 个，.csv=${CSV_N} 个，transmitters.json=${TX_N}"
