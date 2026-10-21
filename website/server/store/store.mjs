/**
 * 数据层 —— 文件系统 JSON + JSONL，零外部数据库。
 *
 * 为什么不是 SQLite：官网数据量极小（版本、公告、用户、日志），
 * 单文件 JSON 便于备份/审计/迁移（直接 scp data/ 即可）。
 * 写入走「临时文件 + rename」保证原子性，进程内串行队列避免丢更新。
 */

import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';

const JSON_EXT = '.json';

/** 进程内串行化：同一 key 的读改写不会交错。 */
function createKeyedQueue() {
  const tails = new Map();
  return function enqueue(key, fn) {
    const prev = tails.get(key) || Promise.resolve();
    const next = prev.then(fn, fn);
    // 无论成功失败都要把链条接下去，且不留下未处理的 rejection
    tails.set(
      key,
      next.then(
        () => {},
        () => {},
      ),
    );
    return next;
  };
}

export function createStore({ dataDir, logger = console }) {
  const enqueue = createKeyedQueue();
  const ready = fs.mkdir(dataDir, { recursive: true }).catch((err) => {
    logger.error?.(`[store] 创建数据目录失败：${err.message}`);
    throw err;
  });

  const fileFor = (key) => path.join(dataDir, `${key}${JSON_EXT}`);
  const jsonlFor = (key, shard) =>
    path.join(dataDir, shard ? `${key}-${shard}.jsonl` : `${key}.jsonl`);

  let writeCount = 0;

  /** 原子写：先写 tmp 再 rename，避免读到半截文件。 */
  async function writeAtomic(file, contents) {
    await ready;
    const tmp = `${file}.${process.pid}.${crypto.randomBytes(4).toString('hex')}.tmp`;
    await fs.writeFile(tmp, contents, 'utf8');
    await fs.rename(tmp, file);
    writeCount++;
  }

  async function readRaw(key, fallback) {
    await ready;
    try {
      const text = await fs.readFile(fileFor(key), 'utf8');
      if (!text.trim()) return fallback;
      return JSON.parse(text);
    } catch (err) {
      if (err.code === 'ENOENT') return fallback;
      if (err instanceof SyntaxError) {
        // 损坏时备份现场再回退到默认值，避免静默丢数据
        const broken = `${fileFor(key)}.broken-${Date.now()}`;
        await fs.rename(fileFor(key), broken).catch(() => {});
        logger.error?.(`[store] ${key}.json 解析失败，已备份到 ${path.basename(broken)}`);
        return fallback;
      }
      throw err;
    }
  }

  return {
    dataDir,
    ready,

    async read(key, fallback) {
      const value = await readRaw(key, fallback);
      return value === undefined ? fallback : value;
    },

    async write(key, value) {
      return enqueue(key, () => writeAtomic(fileFor(key), JSON.stringify(value, null, 2)));
    },

    async update(key, fallback, mutator) {
      return enqueue(key, async () => {
        const current = await readRaw(key, fallback);
        const next = await mutator(current === undefined ? fallback : current);
        if (next === undefined) return current;
        await writeAtomic(fileFor(key), JSON.stringify(next, null, 2));
        return next;
      });
    },

    async remove(key) {
      return enqueue(key, async () => {
        await fs.rm(fileFor(key), { force: true });
      });
    },

    /** 追加一行 JSONL（日志/统计），天然适合顺序追加。 */
    async append(key, record, { shard } = {}) {
      await ready;
      const file = jsonlFor(key, shard);
      await fs.appendFile(file, `${JSON.stringify(record)}\n`, 'utf8');
    },

    async readJsonl(key, { shard, limit } = {}) {
      await ready;
      try {
        const text = await fs.readFile(jsonlFor(key, shard), 'utf8');
        const lines = text.split('\n').filter(Boolean);
        const slice = limit ? lines.slice(-limit) : lines;
        const out = [];
        for (const line of slice) {
          try {
            out.push(JSON.parse(line));
          } catch {
            /* 跳过损坏行，不让一行坏数据毁掉整份日志 */
          }
        }
        return out;
      } catch (err) {
        if (err.code === 'ENOENT') return [];
        throw err;
      }
    },

    async listFiles(prefix = '') {
      await ready;
      const entries = await fs.readdir(dataDir, { withFileTypes: true });
      return entries
        .filter((e) => e.isFile() && e.name.startsWith(prefix))
        .map((e) => e.name);
    },

    async stat(key) {
      await ready;
      try {
        const s = await fs.stat(fileFor(key));
        return { size: s.size, mtime: s.mtimeMs };
      } catch {
        return null;
      }
    },

    /** 整体导出（备份用）。 */
    async exportAll() {
      await ready;
      const files = await fs.readdir(dataDir, { withFileTypes: true });
      const out = {};
      for (const entry of files) {
        if (!entry.isFile()) continue;
        if (!entry.name.endsWith(JSON_EXT)) continue;
        const key = entry.name.slice(0, -JSON_EXT.length);
        out[key] = await readRaw(key, null);
      }
      return out;
    },

    get stats() {
      return { writes: writeCount, dataDir };
    },
  };
}

/** 生成短 ID（可读前缀 + 时间有序随机段）。 */
export function makeId(prefix = 'id') {
  const ts = Date.now().toString(36);
  const rand = crypto.randomBytes(4).toString('hex');
  return `${prefix}_${ts}${rand}`;
}

export function nowIso() {
  return new Date().toISOString();
}
