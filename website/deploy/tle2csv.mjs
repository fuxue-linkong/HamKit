#!/usr/bin/env node
/**
 * tle2csv.mjs —— 把 CelesTrak 的 3le 文本转换为 CelesTrak gp.php 的 CSV 格式。
 *
 * 为什么需要：CelesTrak 的 GP 接口有"同一 GROUP 2 小时内只下发一次"的窗口节流
 * （窗口未开返回 403）。而 active 组已有新鲜的 3le 数据，且两者的字段是 1:1 对应的
 * （见 app 的 TleParser.kt 注释），因此本地转换即可得到等价 CSV，无需等待窗口。
 *
 * 字段映射（全部来自 TLE 固定列位，无损）：
 *   OBJECT_NAME        ← 名称行（去掉首尾空白）
 *   OBJECT_ID          ← line1[10..17]  YYNNNX  →  YYYY-NNNX
 *   EPOCH              ← line1[19..32]  YYDDD.DDDDDDDD  →  YYYY-MM-DDTHH:MM:SS.ffffff
 *   MEAN_MOTION        ← line2[53..63]
 *   ECCENTRICITY       ← "0." + line2[27..33]
 *   INCLINATION        ← line2[9..16]
 *   RA_OF_ASC_NODE     ← line2[18..25]
 *   ARG_OF_PERICENTER  ← line2[35..42]
 *   MEAN_ANOMALY       ← line2[44..51]
 *   EPHEMERIS_TYPE     ← line1[63]
 *   CLASSIFICATION_TYPE← line1[8]
 *   NORAD_CAT_ID       ← line1[3..7]
 *   ELEMENT_SET_NO     ← line1[65..68]
 *   REV_AT_EPOCH       ← line2[64..68]
 *   BSTAR              ← line1[54..61]（保留隐式指数记法，与 CelesTrak 输出一致）
 *   MEAN_MOTION_DOT    ← line1[34..43]（保留隐式小数点记法）
 *   MEAN_MOTION_DDOT   ← line1[45..52]
 *
 * 用法：node tle2csv.mjs <输入.tle> <输出.csv>
 */
import { readFileSync, writeFileSync } from 'node:fs';

const [input, output] = process.argv.slice(2);
if (!input || !output) {
  console.error('用法: node tle2csv.mjs <输入.tle> <输出.csv>');
  process.exit(2);
}

const HEADER = [
  'OBJECT_NAME', 'OBJECT_ID', 'EPOCH', 'MEAN_MOTION', 'ECCENTRICITY',
  'INCLINATION', 'RA_OF_ASC_NODE', 'ARG_OF_PERICENTER', 'MEAN_ANOMALY',
  'EPHEMERIS_TYPE', 'CLASSIFICATION_TYPE', 'NORAD_CAT_ID', 'ELEMENT_SET_NO',
  'REV_AT_EPOCH', 'BSTAR', 'MEAN_MOTION_DOT', 'MEAN_MOTION_DDOT',
].join(',');

/**
 * TLE 隐式指数记法 → 显式 E 记法（供 CSV 使用）。
 *
 * ⚠️ 这里**不能照抄 TLE 原文**：TLE 字段形如 "±NNNNN±E"，尾数的小数点在**最前面**，
 * 且指数位不带符号零。直接抄进 CSV 会让 App 的 toDoubleOrNull() 解析失败并静默变成 0。
 *
 * 正确解读（已用真实 CelesTrak CSV 反证）：
 *   "37201857-3" = 0.37201857 × 10⁻³ = 3.72e-4   → CSV 里是 ".37201857E-3"
 *   "90072738-3" = 0.90072738 × 10⁻³ = 9.01e-4   → CSV 里是 ".90072738E-3"
 *   "76931-4"    = 0.76931    × 10⁻⁴ = 7.69e-5   → CSV 里是 ".76931E-4"
 *
 * ⚠️ 注意这不是标准 TLE 的 "±NNNNN±E"（那套小数点在第 2 位，值要 ×1e-5）；
 *    CelesTrak 的 GP 接口用的是小数点在最前的那一套，两者差 5 个数量级。
 *
 * 输出形如 ".76931E-4"：Java 可解析，且经 App 的 formatExponentialField 能精确还原。
 */
function tleExpToCsv(raw) {
  const s = raw.trim();
  if (s === '') return '0';
  const m = /^([+-]?)(\d{5})([+-])(\d)$/.exec(s);
  if (!m) {
    const v = Number(s);
    return Number.isFinite(v) ? String(v) : '0';
  }
  const [, sign, digits, expSign, expDigit] = m;
  if (Number(digits) === 0) return '0';
  const exp = expDigit === '0' ? 0 : Number(expDigit) * (expSign === '-' ? -1 : 1);
  return `${sign === '-' ? '-' : ''}.${digits}E${exp}`;
}

/** CSV 字段转义：含逗号/引号/换行时用双引号包裹，内部引号翻倍。 */
function csvField(s) {
  const v = String(s ?? '');
  return /[",\n\r]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
}

/** YYDDD.DDDDDDDD → YYYY-MM-DDTHH:MM:SS.ffffff */
function epochToIso(yy, doy, frac) {
  // CelesTrak/太空时代惯例：YY > 57 视为 19xx，否则 20xx
  const year = yy > 57 ? 1900 + yy : 2000 + yy;
  const base = Date.UTC(year, 0, 1);
  const ms = Math.round((doy - 1 + frac) * 86400000);
  const d = new Date(base + ms);
  if (Number.isNaN(d.getTime())) return null;

  const p = (n, w = 2) => String(n).padStart(w, '0');
  const iso =
    `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}` +
    `T${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}`;

  // 与 CelesTrak 一致的 6 位小数秒
  const micros = Math.round(frac * 86400 * 1e6) % 1000000;
  return `${iso}.${String(micros).padStart(6, '0')}`;
}

/** line1[10..17] 的 YYNNNX → YYYY-NNNX */
function objectId(intl) {
  const s = intl.trim();
  const m = /^(\d{2})(\d{3})([A-Z0-9]{0,3})$/.exec(s);
  if (!m) return '';
  const [, yy, num, piece] = m;
  const year = Number(yy) > 57 ? 1900 + Number(yy) : 2000 + Number(yy);
  return `${year}-${num}${piece}`;
}

/** 取 TLE 固定列位（1-based，含首含尾）。串太短时返回空串。 */
const col = (line, from, to) => (line.length >= to ? line.slice(from - 1, to).trim() : '');

const raw = readFileSync(input, 'utf8');
const lines = raw.split(/\r?\n/);

const out = [HEADER];
let written = 0;
let skipped = 0;
let badEpoch = 0;

for (let i = 0; i + 2 < lines.length + 1; i++) {
  const nameLine = lines[i];
  const l1 = lines[i + 1];
  const l2 = lines[i + 2];
  if (l1 === undefined || l2 === undefined) break;
  // 严格按「名称 / 1 / 2」三行一组识别，避免错位
  if (!l1.startsWith('1 ') || !l2.startsWith('2 ')) {
    continue;   // 非三行组的起始位置，往后挪一行继续找
  }

  const epoch = col(l1, 19, 32);           // YYDDD.DDDDDDDD
  const epochMatch = /^(\d{2})(\d{3}\.\d+)$/.exec(epoch);
  if (!epochMatch) {
    skipped++;
    i += 2;
    continue;
  }
  const doyFloat = Number(epochMatch[2]);
  const isoFinal = epochToIso(Number(epochMatch[1]), Math.floor(doyFloat), doyFloat - Math.floor(doyFloat));
  if (!isoFinal) {
    badEpoch++;
    i += 2;
    continue;
  }

  const eccRaw = col(l2, 27, 33);
  const row = [
    col(nameLine, 1, nameLine.length) || col(l1, 3, 7),
    objectId(col(l1, 10, 17)),
    isoFinal,
    col(l2, 53, 63),
    eccRaw === '' ? '' : `0.${eccRaw.replace(/^0+/, '').padStart(7, '0')}`,
    col(l2, 9, 16),
    col(l2, 18, 25),
    col(l2, 35, 42),
    col(l2, 44, 51),
    col(l1, 63, 63) || '0',
    col(l1, 8, 8) || 'U',
    col(l1, 3, 7),
    // 第 1 行 63-68 位是 "0  9993"：63=星历类型，64=空格，65-68=星历号
    // （星历号左补空格、最多 4 位；末尾那一位是校验和的一部分，超出部分由 col() 自然截断）
    col(l1, 65, 68) || '999',
    col(l2, 64, 68) || '9995',
    // BSTAR / MEAN_MOTION_DDOT / MEAN_MOTION_DOT：
    // TLE 用隐式记法（"76931-4"、".00000396"），CSV 必须转成显式记法，
    // 否则 App 侧 toDoubleOrNull() 解析失败会静默变成 0。
    tleExpToCsv(col(l1, 54, 61)),
    col(l1, 34, 43) || '0',
    tleExpToCsv(col(l1, 45, 52)),
  ];

  if (!row[11]) { skipped++; i += 2; continue; }  // 无 NORAD 号
  out.push(row.map(csvField).join(','));
  written++;
  i += 2;
}

writeFileSync(output, out.join('\n') + '\n', 'utf8');

console.error(`tle2csv: 输入 ${lines.length} 行 → 写出 ${written} 条`);
if (skipped) console.error(`  跳过（缺字段）: ${skipped}`);
if (badEpoch) console.error(`  跳过（历元非法）: ${badEpoch}`);
console.error(`  输出: ${output}`);
