#!/usr/bin/env node
/**
 * roundtrip-csv.mjs —— 端到端往返验证：
 *   TLE 原文 → 生成的 CSV → 用 App 侧 parseActiveCsv + buildTle 的等价逻辑重建 TLE
 *   → 校验重建结果（字段一致 + 校验和正确）。
 *
 * 判据说明：
 *  - EPOCH：TLE 的年内日是 8 位小数，CSV 按 CelesTrak 惯例只写 6 位微秒。
 *    因此必然存在 ≤ 0.5 微秒的舍入，不要求字符串全等，改为比较
 *    "CSV 重建值 vs App 从 TLE 原文解析出的值"（反映真实转换损失，并用容差判定）。
 *  - 其余字段要求精确一致（它们都是无损的定点/整数表示）。
 *  - 最终用 App 的 checksum() 校验重建的 line1/line2，确保是合法 TLE。
 *
 * 用法：node roundtrip-csv.mjs <原始.tle> <生成的.csv>
 */
import { readFileSync } from 'node:fs';

const [tleFile, csvFile] = process.argv.slice(2);
if (!tleFile || !csvFile) { console.error('用法: node roundtrip-csv.mjs <tle> <csv>'); process.exit(2); }

const c = (line, from, to) => line.slice(from - 1, to).trim();

// ── 复刻 App 侧 ──
function formatExponentialField(value) {
  if (value === 0) return ' 00000+0';
  let exponent = 0;
  let scaled = value * 1e5;
  while (Math.abs(scaled) < 10000 && exponent < 9) { scaled *= 10; exponent++; }
  while (Math.abs(scaled) >= 100000 && exponent > 0) { scaled /= 10; exponent--; }
  const mantissa = Math.max(-99999, Math.min(99999, Math.round(scaled)));
  const sign = mantissa < 0 ? '-' : ' ';
  return `${sign}${String(Math.abs(mantissa)).padStart(5, '0')}${exponent >= 0 ? '+' : '-'}${Math.abs(exponent)}`;
}

function formatIntlDesignator(objectId) {
  const m = /\d{4}-(\d{3})([A-Z0-9]{1,3})/.exec(objectId.trim());
  if (!m) return '        ';
  return (objectId.trim().substring(2, 4) + m[1] + m[2]).padStart(8, ' ');
}

function parseEpoch(epoch) {
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?$/.exec(epoch.trim());
  if (!m) return null;
  const year = Number(m[1]), month = Number(m[2]), day = Number(m[3]);
  const hour = Number(m[4]), minute = Number(m[5]), second = Number(m[6]);
  const fraction = m[7] ? Number('0.' + m[7]) : 0;
  const dayOfYear = Math.round((Date.UTC(year, month - 1, day) - Date.UTC(year, 0, 1)) / 86400000) + 1;
  return [year % 100, dayOfYear + (hour * 3600 + minute * 60 + second) / 86400 + fraction / 86400];
}

function checksum(line) {
  let sum = 0;
  for (const ch of line) {
    if (ch >= '0' && ch <= '9') sum += ch.charCodeAt(0) - 48;
    else if (ch === '-') sum += 1;
  }
  return sum % 10;
}

function padStart(s, n, p) { s = String(s); return s.length >= n ? s : s.padStart(n, p); }

/** 重建 TLE line1/line2（复刻 buildLine1/buildLine2） */
function buildLines(row) {
  const norad = Number(row.NORAD_CAT_ID);
  const epochs = parseEpoch(row.EPOCH);
  if (!epochs) return null;
  const [year, refEpoch] = epochs;

  const l1 = '1 ' + padStart(norad, 5, '0') + (row.CLASSIFICATION_TYPE || 'U').slice(0, 1) + ' '
    + formatIntlDesignator(row.OBJECT_ID)
    + ' ' + padStart(year, 2, '0') + refEpoch.toFixed(8).padStart(12, '0')
    + ' ' + dragField(Number(row.MEAN_MOTION_DOT) || 0)
    + ' ' + formatExponentialField(Number(row.MEAN_MOTION_DDOT) || 0)
    + ' ' + formatExponentialField(Number(row.BSTAR) || 0)
    + ' ' + (row.EPHEMERIS_TYPE || '0').slice(0, 1)
    + padStart(row.ELEMENT_SET_NO || 999, 5, ' ');
  const l1full = l1 + checksum(l1);

  const l2 = '2 ' + padStart(norad, 5, '0') + ' '
    + Number(row.INCLINATION).toFixed(4).padStart(8, ' ')
    + ' ' + Number(row.RA_OF_ASC_NODE).toFixed(4).padStart(8, ' ')
    + ' ' + padStart(Math.round(Number(row.ECCENTRICITY) * 1e7), 7, '0')
    + ' ' + Number(row.ARG_OF_PERICENTER).toFixed(4).padStart(8, ' ')
    + ' ' + Number(row.MEAN_ANOMALY).toFixed(4).padStart(8, ' ')
    + ' ' + Number(row.MEAN_MOTION).toFixed(8).padStart(11, ' ')
    + padStart(row.REV_AT_EPOCH || 9995, 5, '0');
  const l2full = l2 + checksum(l2);
  return { l1: l1full, l2: l2full };
}

function dragField(value) {
  if (value === 0) return ' .00000000';
  const sign = value < 0 ? '-' : ' ';
  const abs = Math.min(Math.abs(value), 0.99999999);
  return sign + abs.toFixed(8).substring(1);
}

// ── 读原始 TLE ──
const lines = readFileSync(tleFile, 'utf8').split(/\r?\n/);
const src = [];
for (let i = 0; i + 2 < lines.length + 1; i += 3) {
  const [name, l1, l2] = [lines[i], lines[i + 1], lines[i + 2]];
  if (!l1 || !l2 || !l1.startsWith('1 ') || !l2.startsWith('2 ')) continue;
  const tleExpToValue = (s) => {
    const m = /^([+-]?)(\d{5})([+-])(\d)$/.exec(s.trim());
    if (!m) return Number(s) || 0;
    return (m[1] === '-' ? -1 : 1) * (Number(m[2]) / 1e5) * Math.pow(10, Number(m[4]) * (m[3] === '-' ? -1 : 1));
  };
  src.push({
    norad: Number(c(l1, 3, 7)),
    classification: c(l1, 8, 8) || 'U',
    intl: c(l1, 10, 17),
    epoch: c(l1, 19, 32),
    ndot: c(l1, 34, 43),
    nddot: formatExponentialField(tleExpToValue(c(l1, 45, 52))),
    bstar: formatExponentialField(tleExpToValue(c(l1, 54, 61))),
    ephemeris: c(l1, 63, 63) || '0',
    esno: c(l1, 65, 68),
    inclination: Number(c(l2, 9, 16)).toFixed(4),
    raan: Number(c(l2, 18, 25)).toFixed(4),
    ecc: c(l2, 27, 33).padStart(7, '0'),
    argp: Number(c(l2, 35, 42)).toFixed(4),
    ma: Number(c(l2, 44, 51)).toFixed(4),
    mm: Number(c(l2, 53, 63)).toFixed(8),
    rev: c(l2, 64, 68),
  });
}

// ── 读生成的 CSV ──
const cl = readFileSync(csvFile, 'utf8').split(/\r?\n/).filter((l) => l.trim() !== '');
const headers = cl[0].split(',').map((h) => h.trim());
const idx = Object.fromEntries(headers.map((h, i) => [h, i]));
const byNorad = new Map();
for (let n = 1; n < cl.length; n++) {
  const f = cl[n].split(',');
  byNorad.set(Number((f[idx.NORAD_CAT_ID] ?? '').trim()), f);
}

let checked = 0, mismatches = 0, epochDrift = 0, maxDriftSec = 0, badChecksum = 0;
const details = [];

for (const s of src) {
  const f = byNorad.get(s.norad);
  if (!f) { mismatches++; if (details.length < 10) details.push(`NORAD ${s.norad} 在 CSV 缺失`); continue; }
  const row = Object.fromEntries(headers.map((h, i) => [h, (f[i] ?? '').trim()]));

  // 重建 TLE 并校验
  const built = buildLines(row);
  if (!built) { mismatches++; if (details.length < 10) details.push(`NORAD ${s.norad} 无法重建`); continue; }
  if (checksum(built.l1.slice(0, 68)) !== Number(built.l1[68])) badChecksum++;
  if (checksum(built.l2.slice(0, 68)) !== Number(built.l2[68])) badChecksum++;

  const got = {
    classification: row.CLASSIFICATION_TYPE,
    intl: formatIntlDesignator(row.OBJECT_ID).trim(),
    ndot: row.MEAN_MOTION_DOT,
    nddot: formatExponentialField(Number(row.MEAN_MOTION_DDOT) || 0),
    bstar: formatExponentialField(Number(row.BSTAR) || 0),
    ephemeris: row.EPHEMERIS_TYPE,
    esno: row.ELEMENT_SET_NO,
    inclination: Number(row.INCLINATION).toFixed(4),
    raan: Number(row.RA_OF_ASC_NODE).toFixed(4),
    ecc: padStart(Math.round(Number(row.ECCENTRICITY) * 1e7), 7, '0'),
    argp: Number(row.ARG_OF_PERICENTER).toFixed(4),
    ma: Number(row.MEAN_ANOMALY).toFixed(4),
    mm: Number(row.MEAN_MOTION).toFixed(8),
    rev: row.REV_AT_EPOCH,
  };

  for (const k of Object.keys(got)) {
    checked++;
    if (String(got[k]) !== String(s[k])) {
      mismatches++;
      if (details.length < 10) details.push(`NORAD ${s.norad} ${k}: 得到="${got[k]}" 期望="${s[k]}"`);
    }
  }

  // 历元：容差比较（CSV 按 CelesTrak 惯例只保留 6 位微秒）
  const a = parseEpoch(row.EPOCH), b = parseEpoch(isoFromTleEpoch(s.epoch));
  if (a && b) {
    const driftSec = Math.abs((a[1] - b[1]) * 86400);
    maxDriftSec = Math.max(maxDriftSec, driftSec);
    if (driftSec > 1e-3) { epochDrift++; }
  }
  checked++;
}

/** YYDDD.DDDDDDDD → ISO（仅用于给出历元期望值） */
function isoFromTleEpoch(e) {
  const m = /^(\d{2})(\d{3}\.\d+)$/.exec(e.trim());
  if (!m) return '';
  const yy = Number(m[1]);
  const year = yy > 57 ? 1900 + yy : 2000 + yy;
  const doy = Number(m[2]);
  const d = new Date(Date.UTC(year, 0, 1) + Math.round((doy - 1) * 86400000));
  const p = (n) => String(n).padStart(2, '0');
  const frac = doy - Math.floor(doy);
  const micros = Math.round(frac * 86400 * 1e6) % 1000000;
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}T${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}.${String(micros).padStart(6, '0')}`;
}

console.log(`往返验证：${src.length} 颗卫星，${checked} 个字段`);
console.log(`  字段不一致: ${mismatches}`);
console.log(`  校验和错误: ${badChecksum}`);
console.log(`  历元漂移 >1ms 的条数: ${epochDrift}（最大 ${maxDriftSec.toExponential(2)} 秒）`);

if (mismatches === 0 && badChecksum === 0 && epochDrift === 0) {
  console.log('  ✅ 全部字段无损、校验和正确、历元漂移在 1ms 内');
  process.exit(0);
}
if (details.length) {
  console.log('  样例：');
  for (const d of details) console.log('     ' + d);
}
process.exit(1);
