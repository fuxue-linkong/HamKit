#!/usr/bin/env node
/**
 * trim-transmitters.mjs —— 把 SatNOGS 全量转发器 JSON 裁剪成 App 真正用得到的字段。
 *
 * 背景：`db.satnogs.org/api/transmitters/?format=json` 每条记录有 30+ 个字段
 * （uuid / alive / type / baud / sat_id / telemetry_rate / updated / deleted …），
 * 全量十几 MB。App 端只用到其中 7 个，镜像到 CDN 时按需裁剪能省 3～5 倍带宽，
 * 也让国内用户首次拉取快很多。
 *
 * **裁剪规则完全照抄 Android `TransmitterDataSource.parseTransmitterItem`**，
 * 保证「CDN 镜像」与「直连 SatNOGS」两条路的解析结果逐条一致：
 *   - `norad_cat_id` 必须 > 0，否则丢弃；
 *   - `uplink_low` / `downlink_low` 至少一个 > 0，否则丢弃；
 *   - `mode`：uplink_mode → downlink_mode → mode；
 *   - `name`：name → description → `<mode> <频率 MHz>`；
 *   - `description`：保留（Android `RadioInfo.displayName` 优先用它作标题）；
 *   - `status`：缺省 `unknown`；字面量 "null" 视为缺失；
 *   - `inverted`：缺省 false（上游有的版本叫 `invert`，两个都认）。
 *
 * 输出**仍是 JSON 数组**，字段名保持 SatNOGS 原名，App 侧解析器无需改动。
 *
 * 用法：
 *   node trim-transmitters.mjs <输入.json> <输出.json>
 *
 * 退出码：0 成功；1 输入不是数组；2 参数错误。
 */

import { readFileSync, writeFileSync } from 'node:fs';

const [inPath, outPath] = process.argv.slice(2);
if (!inPath || !outPath) {
  console.error('用法: node trim-transmitters.mjs <输入.json> <输出.json>');
  process.exit(2);
}

const raw = readFileSync(inPath, 'utf8');
let input;
try {
  input = JSON.parse(raw);
} catch (e) {
  console.error(`trim-transmitters: 输入不是合法 JSON: ${e.message}`);
  process.exit(1);
}
if (!Array.isArray(input)) {
  console.error('trim-transmitters: 输入顶层不是数组（SatNOGS 期望返回 JSON 数组）');
  process.exit(1);
}

/** 空串 / 空白 / 字面量 "null" 视为缺失；剔除中括号（"[Beacon]" → "Beacon"）。 */
function cleanField(value) {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (!trimmed || trimmed.toLowerCase() === 'null') return null;
  const stripped = trimmed.replace(/[[\]]/g, '').trim();
  return stripped || null;
}

/** org.json 的 optLong 语义：数字或数字字符串都接受，非法值当 0。 */
function optNumber(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value.trim());
    return Number.isFinite(parsed) ? parsed : 0;
  }
  return 0;
}

/** org.json 的 optBoolean 语义：布尔或 "true"/"false" 字符串。 */
function optBool(value) {
  if (typeof value === 'boolean') return value;
  if (typeof value === 'string') return value.trim().toLowerCase() === 'true';
  return false;
}

function formatMhz(hz) {
  return `${(hz / 1_000_000).toFixed(3)} MHz`;
}

/** 与 Android parseTransmitterItem 一一对应；返回 null 表示该条会被丢弃。 */
function trimItem(item) {
  if (!item || typeof item !== 'object') return null;

  const norad = Math.trunc(optNumber(item.norad_cat_id));
  if (!(norad > 0)) return null;

  const uplink = Math.trunc(optNumber(item.uplink_low));
  const downlink = Math.trunc(optNumber(item.downlink_low));
  if (uplink <= 0 && downlink <= 0) return null;

  const mode =
    cleanField(item.uplink_mode) ??
    cleanField(item.downlink_mode) ??
    cleanField(item.mode) ??
    '';

  const rawName = cleanField(item.name);
  const description = cleanField(item.description);
  const name =
    rawName ??
    description ??
    (() => {
      const freq = downlink > 0 ? downlink : uplink;
      const text = formatMhz(freq);
      return mode ? `${mode} ${text}` : text;
    })();

  const status = (() => {
    const s = typeof item.status === 'string' ? item.status.trim().toLowerCase() : '';
    return s && s !== 'null' ? s : 'unknown';
  })();

  const inverted = optBool(item.inverted ?? item.invert);

  // 只写有值的字段：JSON.stringify 会跳过 undefined，体积再小一点。
  const out = {
    norad_cat_id: norad,
    name,
    uplink_low: uplink > 0 ? uplink : undefined,
    downlink_low: downlink > 0 ? downlink : undefined,
    uplink_mode: cleanField(item.uplink_mode) ?? undefined,
    downlink_mode: cleanField(item.downlink_mode) ?? undefined,
    mode: mode || undefined,
    inverted: inverted ? true : undefined,
    status,
    // Android 端 displayName 优先用 description 作标题（Look4Sat 同款规则），
    // 不保留会让升级后转发器名称从 description 退化成 name。
    description: description ?? undefined,
  };
  return out;
}

const trimmed = [];
for (const item of input) {
  const out = trimItem(item);
  if (out) trimmed.push(out);
}

if (trimmed.length === 0) {
  console.error(`trim-transmitters: 输入 ${input.length} 条，裁剪后为 0 条，拒绝输出`);
  process.exit(1);
}

const json = JSON.stringify(trimmed);
writeFileSync(outPath, json);

console.log(
  `trim-transmitters: ${input.length} 条 → ${trimmed.length} 条，` +
    `${raw.length} B → ${json.length} B（${(json.length / raw.length * 100).toFixed(0)}%）`
);
