/**
 * 响应与静态文件 —— 把处理器返回值落成真正的 HTTP 响应。
 *
 * 处理器可返回：
 *   { status?, json?, body?, html?, redirect?, headers?, cookies? }
 * 或调用 reply.send(...)（流式/自定义场景）。
 */

import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.txt': 'text/plain; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json',
  '.xml': 'application/xml; charset=utf-8',
  '.apk': 'application/vnd.android.package-archive',
};

export function contentTypeFor(filePath) {
  return MIME[path.extname(filePath).toLowerCase()] || 'application/octet-stream';
}

export function createReply(res, { method = 'GET' } = {}) {
  let sent = false;
  const extraHeaders = [];

  const reply = {
    get sent() {
      return sent;
    },
    header(name, value) {
      if (value === undefined || value === null) return reply;
      extraHeaders.push([name, value]);
      return reply;
    },
    send(status, body, headers = {}) {
      if (sent) return reply;
      sent = true;
      const buffer = Buffer.isBuffer(body) ? body : Buffer.from(body ?? '', 'utf8');
      for (const [k, v] of extraHeaders) res.setHeader(k, v);
      for (const [k, v] of Object.entries(headers)) {
        if (v !== undefined && v !== null) res.setHeader(k, v);
      }
      res.statusCode = status;
      if (method === 'HEAD' || status === 204 || status === 304) {
        res.end();
        return reply;
      }
      res.setHeader('content-length', buffer.length);
      res.end(buffer);
      return reply;
    },
    json(status, value, headers = {}) {
      return reply.send(status, JSON.stringify(value), {
        'content-type': 'application/json; charset=utf-8',
        'cache-control': 'no-store',
        ...headers,
      });
    },
    redirect(location, status = 302) {
      return reply.send(status, '', { location });
    },
    text(status, value, headers = {}) {
      return reply.send(status, value, {
        'content-type': 'text/plain; charset=utf-8',
        ...headers,
      });
    },
  };
  return reply;
}

/** Cookie 数组 → 多个 Set-Cookie 头。 */
export function appendCookies(res, cookies) {
  const list = (Array.isArray(cookies) ? cookies : [cookies]).filter(Boolean);
  if (!list.length) return;
  const existing = res.getHeader('set-cookie');
  const merged = existing ? (Array.isArray(existing) ? [...existing, ...list] : [existing, ...list]) : list;
  res.setHeader('set-cookie', merged);
}

/**
 * 统一处理中文 JSON 输出（不转义非 ASCII，便于人工阅读与排查）。
 */
export function jsonStringify(value) {
  return JSON.stringify(value, null, 2);
}

/** 计算 ETag（弱校验，够用且便宜）。 */
export function etagFor(buffer, mtimeMs) {
  const hash = crypto.createHash('sha1').update(buffer).digest('base64url').slice(0, 27);
  return `W/"${buffer.length.toString(16)}-${hash}"${mtimeMs ? '' : ''}`;
}

export function isFresh(req, etag, mtimeMs) {
  const inm = req.headers['if-none-match'];
  if (inm && inm.split(',').some((tag) => tag.trim() === etag)) return true;
  const ims = req.headers['if-modified-since'];
  if (ims && mtimeMs) {
    const since = Date.parse(ims);
    if (Number.isFinite(since) && Math.floor(mtimeMs / 1000) * 1000 <= since) return true;
  }
  return false;
}

/**
 * 安全地解析静态文件路径：
 *   1) 百分号解码；2) 归一化；3) 必须落在 root 内；4) 拒绝 null 字节。
 */
export function resolveStaticPath(root, urlPath) {
  let decoded;
  try {
    decoded = decodeURIComponent(urlPath);
  } catch {
    return null;
  }
  if (decoded.includes('\0')) return null;
  const clean = decoded.split('?')[0].split('#')[0];
  const resolved = path.resolve(root, `.${path.posix.normalize(clean)}`);
  const rootWithSep = root.endsWith(path.sep) ? root : root + path.sep;
  if (resolved !== root && !resolved.startsWith(rootWithSep)) return null;
  return resolved;
}

/** 读取文件并生成响应（支持 ETag / 304 / Range 简化版）。 */
export async function serveFile(req, reply, filePath, { maxAge = 0, immutable = false, download = null } = {}) {
  let stat;
  try {
    stat = await fs.stat(filePath);
  } catch {
    return false;
  }
  if (stat.isDirectory()) return false;

  const cacheControl = immutable && maxAge > 0
    ? `public, max-age=${maxAge}, immutable`
    : maxAge > 0
      ? `public, max-age=${maxAge}`
      : 'no-cache';

  // 小文件直接读；大文件（APK 等）走流式
  if (stat.size > 4 * 1024 * 1024) {
    reply.header('content-type', contentTypeFor(filePath));
    reply.header('cache-control', cacheControl);
    if (download) reply.header('content-disposition', `attachment; filename="${download}"`);
    reply.header('content-length', stat.size);
    reply.header('accept-ranges', 'none');
    // 交给 reply.send 之外的流式通道：用 res 直接管道
    return { stream: filePath, size: stat.size };
  }

  const buffer = await fs.readFile(filePath);
  const etag = etagFor(buffer, stat.mtimeMs);
  if (isFresh(req, etag, stat.mtimeMs)) {
    reply.send(304, '', { etag, 'cache-control': cacheControl });
    return true;
  }
  const headers = {
    etag,
    'cache-control': cacheControl,
    'content-type': contentTypeFor(filePath),
    'last-modified': new Date(stat.mtimeMs).toUTCString(),
  };
  if (download) headers['content-disposition'] = `attachment; filename="${download}"`;
  reply.send(200, buffer, headers);
  return true;
}
