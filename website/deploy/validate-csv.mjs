#!/usr/bin/env node
/**
 * validate-csv.mjs —— 校验生成的 CSV 能否被 App 的 parseActiveCsv 逻辑正确解析。
 * 复刻 TleParser.kt 的必填字段校验与 Java parseDouble/parseInt 的可解析性。
 *
 * 用法：node validate-csv.mjs <csv文件>
 */
import { readFileSync } from 'node:fs';

const file = process.argv[2];
if (!file) { console.error('用法: node validate-csv.mjs <csv>'); process.exit(2); }

const REQUIRED = ['EPOCH', 'NORAD_CAT_ID', 'MEAN_MOTION', 'INCLINATION',
                  'RA_OF_ASC_NODE', 'ARG_OF_PERICENTER', 'MEAN_ANOMALY'];
// Java Double.parseDouble 可接受的格式（含隐式小数点与指数记法）
const NUM = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/;
// Java Integer.parseInt（允许前导零）
const INT = /^[+-]?\d+$/;
// EPOCH_PATTERN: yyyy-MM-dd[ T]HH:mm:ss(.frac)?
const EPOCH = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?$/;
// YYYY-NNNX（OBJECT_ID）
const OBJID = /^\d{4}-\d{3}[A-Z0-9]*$/;

const csv = readFileSync(file, 'utf8');
const lines = csv.split(/\r?\n/).filter((l) => l.trim() !== '');
const headers = lines[0].split(',').map((h) => h.trim());
const idx = Object.fromEntries(headers.map((h, i) => [h, i]));

console.log(`文件: ${file}`);
console.log(`表头列数: ${headers.length}`);
console.log(`数据行数: ${lines.length - 1}`);

const missing = REQUIRED.filter((r) => !(r in idx));
if (missing.length) {
  console.log(`❌ 必填列缺失: ${missing.join(', ')} —— parseActiveCsv 会直接返回空列表`);
  process.exit(1);
}
console.log('✅ 必填列齐全');

const INT_FIELDS = ['NORAD_CAT_ID', 'ELEMENT_SET_NO', 'REV_AT_EPOCH'];
const NUM_FIELDS = ['MEAN_MOTION', 'ECCENTRICITY', 'INCLINATION', 'RA_OF_ASC_NODE',
                    'ARG_OF_PERICENTER', 'MEAN_ANOMALY', 'BSTAR',
                    'MEAN_MOTION_DOT', 'MEAN_MOTION_DDOT'];

let bad = 0;
const badSamples = [];
for (let n = 1; n < lines.length; n++) {
  const f = lines[n].split(',');
  if (f.length < headers.length) {
    bad++; if (badSamples.length < 5) badSamples.push(`第 ${n + 1} 行列数不足 (${f.length}<${headers.length})`);
    continue;
  }
  const get = (k) => (f[idx[k]] ?? '').trim();
  const errs = [];

  if (!INT.test(get('NORAD_CAT_ID')) || Number(get('NORAD_CAT_ID')) <= 0) errs.push('NORAD_CAT_ID');
  if (!EPOCH.test(get('EPOCH'))) errs.push('EPOCH');
  if (!OBJID.test(get('OBJECT_ID'))) errs.push('OBJECT_ID');
  for (const k of NUM_FIELDS) {
    const v = get(k);
    if (v === '') continue;                 // 缺省按 0 处理，可接受
    if (!NUM.test(v)) errs.push(k + '="' + v + '"');
  }
  for (const k of INT_FIELDS) {
    const v = get(k);
    if (v === '') continue;
    if (!INT.test(v)) errs.push(k + '="' + v + '"');
  }

  if (errs.length) {
    bad++;
    if (badSamples.length < 8) badSamples.push(`第 ${n + 1} 行 (${get('OBJECT_NAME')}): ${errs.slice(0, 4).join(', ')}`);
  }
}

console.log(`\n解析校验：`);
if (bad === 0) {
  console.log('  ✅ 所有行的字段均可被 Java parseDouble/parseInt 解析');
} else {
  console.log(`  ❌ ${bad} / ${lines.length - 1} 行存在不可解析字段：`);
  for (const s of badSamples) console.log('     ' + s);
}

// 抽样展示
console.log('\n抽样（前 3 条关键字段）：');
for (let n = 1; n < Math.min(4, lines.length); n++) {
  const f = lines[n].split(',');
  const get = (k) => (f[idx[k]] ?? '').trim();
  console.log(`  ${get('OBJECT_NAME')} | ID=${get('OBJECT_ID')} | NORAD=${get('NORAD_CAT_ID')} | EPOCH=${get('EPOCH')} | ESNo=${get('ELEMENT_SET_NO')} | ecl=${get('ECCENTRICITY')} | bstar=${get('BSTAR')}`);
}

process.exit(bad === 0 ? 0 : 1);
