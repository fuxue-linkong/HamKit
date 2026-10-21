/**
 * 请求管线与应用装配。
 *
 * 顺序：安全响应头 → 限流 → 请求体解析 → 身份解析 → 路由 → 兜底静态/404。
 * `assembleApp` 返回纯函数 `handle(req, res)`，不绑定端口，方便测试直接注入。
 */

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

import { createStore } from '../store/store.mjs';
import {
  createVersionRepo,
  createContentRepo,
  createAnnouncementRepo,
  createUserRepo,
  createAuditRepo,
  createStatsRepo,
} from '../store/repositories.mjs';
import { createOidcClient } from '../core/oidc.mjs';
import { createSessionManager, createSessionRegistry, parseCookies } from '../core/session.mjs';
import { createAuthService, HttpError } from '../core/auth.mjs';
import { createRouter, RouteError } from './router.mjs';
import {
  appendCookies,
  createReply,
  contentTypeFor,
  resolveStaticPath,
  serveFile,
} from './respond.mjs';
import { registerApiRoutes } from '../routes/api.mjs';
import { registerAuthRoutes } from '../routes/auth.mjs';

const MAX_BODY_BYTES = 1024 * 1024; // 1MB：官网数据都是小 JSON，超过即视为异常

/** 命中这些前缀的路径永不作为静态文件下发（源码 / 密钥 / 数据）。 */
export const STATIC_DENY = [
  '/.env',
  '/.session-secret',
  '/data/',
  '/server/',
  '/test/',
  '/build/',
  '/node_modules/',
  '/package.json',
  '/package-lock.json',
  '/.git',
  '/.gitignore',
  '/README.md',
];

/** 静态资源里允许公开的文件名（根目录只放前端资源，其余一律 404）。 */
const PUBLIC_ROOT_FILES = new Set([
  'index.html',
  'style.css',
  'script.js',
  'site.css',
  'site.js',
  'markdown.js',
  'login.html',
  'account.html',
  'privacy.html',
  '404.html',
  'offline.html',
  'robots.txt',
  'favicon.ico',
  'update.json',
  'version.json',
  'home.js',
  'auth.js',
  'admin.js',
]);

export function securityHeaders(config, urlPath = '/') {
  const headers = {
    'x-content-type-options': 'nosniff',
    'referrer-policy': 'strict-origin-when-cross-origin',
    'x-frame-options': 'DENY',
    'permissions-policy': 'geolocation=(), microphone=(), camera=()',
    'cross-origin-opener-policy': 'same-origin',
  };
  if (config.security.hsts) {
    headers['strict-transport-security'] = 'max-age=31536000; includeSubDomains';
  }
  const isAdmin = urlPath.startsWith('/admin') || urlPath.startsWith('/account');
  const csp = [
    "default-src 'self'",
    // 现有站点脚本是内联 IIFE，故允许内联脚本；后台页面暂无内联脚本需求
    "script-src 'self' 'unsafe-inline'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: https:",
    "font-src 'self' data:",
    `connect-src 'self'${config.env !== 'production' ? ' ws: http://localhost:*' : ''}`,
    "form-action 'self'",
    "frame-ancestors 'none'",
    "base-uri 'self'",
    "object-src 'none'",
  ];
  if (isAdmin) csp.push("upgrade-insecure-requests");
  headers['content-security-policy'] = csp.join('; ');
  return headers;
}

/** 客户端 IP：按 trustProxy 决定是否信任 X-Forwarded-For。 */
export function clientIp(req, config) {
  const hops = config.trustProxy || 0;
  if (hops > 0) {
    const xff = String(req.headers['x-forwarded-for'] || '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
    if (xff.length) {
      return xff[Math.max(0, xff.length - hops)] || xff[0];
    }
    const real = req.headers['x-real-ip'];
    if (real) return String(real);
  }
  return req.socket?.remoteAddress || '';
}

/** URLSearchParams → 普通对象；重复键收成数组。 */
export function queryToObject(searchParams) {
  const out = {};
  for (const [key, value] of searchParams.entries()) {
    if (out[key] === undefined) out[key] = value;
    else if (Array.isArray(out[key])) out[key].push(value);
    else out[key] = [out[key], value];
  }
  return out;
}

/** 简单滑动窗口限流（内存态，按 IP + 通道）。 */
export function createRateLimiter({ windowSec = 60, max = 240, logger = console } = {}) {
  const buckets = new Map();
  let lastSweep = Date.now();

  function sweep(now) {
    if (now - lastSweep < windowSec * 1000) return;
    lastSweep = now;
    for (const [key, bucket] of buckets) {
      if (now - bucket.start > windowSec * 1000 * 2) buckets.delete(key);
    }
  }

  return function check(key, overrideMax) {
    const now = Date.now();
    sweep(now);
    const limit = overrideMax || max;
    const windowMs = windowSec * 1000;
    let bucket = buckets.get(key);
    if (!bucket || now - bucket.start >= windowMs) {
      bucket = { start: now, count: 0 };
      buckets.set(key, bucket);
    }
    bucket.count += 1;
    const remaining = Math.max(0, limit - bucket.count);
    const resetSec = Math.ceil((bucket.start + windowMs - now) / 1000);
    return { allowed: bucket.count <= limit, remaining, resetSec, limit };
  };
}

/** 读取并解析请求体（JSON / 表单），带大小上限。 */
export async function readBody(req, { maxBytes = MAX_BODY_BYTES } = {}) {
  const method = req.method || 'GET';
  if (['GET', 'HEAD', 'OPTIONS'].includes(method)) return null;

  const type = String(req.headers['content-type'] || '').split(';')[0].trim().toLowerCase();
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > maxBytes) {
      // 必须继续把剩余字节读干净：否则客户端仍在写、拿不到响应，会一直挂住
      for await (const _discard of req) void _discard;
      throw new HttpError(413, `请求体过大（上限 ${Math.round(maxBytes / 1024)}KB）`, 'payload_too_large');
    }
    chunks.push(chunk);
  }
  if (!size) return null;
  const text = Buffer.concat(chunks).toString('utf8');

  if (type === 'application/json' || type === '') {
    try {
      return JSON.parse(text);
    } catch {
      throw new HttpError(400, '请求体不是合法 JSON', 'invalid_json');
    }
  }
  if (type === 'application/x-www-form-urlencoded') {
    const params = new URLSearchParams(text);
    return Object.fromEntries(params.entries());
  }
  throw new HttpError(415, `不支持的 Content-Type：${type}`, 'unsupported_media_type');
}

function wantsJson(req, urlPath) {
  if (urlPath.startsWith('/api/') || urlPath.startsWith('/auth/')) return true;
  if (urlPath.endsWith('.json')) return true;
  return String(req.headers.accept || '').includes('application/json');
}

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

function errorPage(error) {
  const status = error.status || 500;
  const title = status === 404 ? '页面不存在' : status === 403 ? '没有权限' : '出错了';
  return `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${status} · ${title} · HamKit</title>
<link rel="stylesheet" href="/site.css"></head>
<body class="page-error">
<main class="err">
  <div class="err__code">${status}</div>
  <h1 class="err__title">${escapeHtml(title)}</h1>
  <p class="err__text">${escapeHtml(error.message || '服务器内部错误')}</p>
  ${error.code ? `<code class="err__code-tag">${escapeHtml(error.code)}</code>` : ''}
  <div class="err__actions">
    <a class="btn btn--primary" href="/">返回首页</a>
    <a class="btn btn--ghost" href="/admin/">进入后台</a>
  </div>
</main>
</body></html>`;
}

/**
 * 装配应用。
 * @param {object} config
 * @param {object} [overrides] 测试可注入 store / oidc / logger / now
 */
export function assembleApp(config, overrides = {}) {
  const logger = overrides.logger || console;
  const now = overrides.now || (() => Date.now());
  const store = overrides.store || createStore({ dataDir: config.dataDir, logger });

  const versions = createVersionRepo(store);
  const content = createContentRepo(store);
  const announcements = createAnnouncementRepo(store);
  const users = createUserRepo(store, { config, logger });
  const audit = createAuditRepo(store);
  const stats = createStatsRepo(store, { config });

  const oidc = overrides.oidc || createOidcClient(config.oidc, { logger, now });
  const sessionRegistry = overrides.registry || createSessionRegistry({ store, now });
  const sessions = createSessionManager({
    secret: config.session.secret,
    ttlSec: config.cookie.maxAgeSec,
    registry: sessionRegistry,
    now,
  });

  const auth = createAuthService({
    config,
    oidc,
    sessions,
    users,
    audit,
    stats,
    registry: sessionRegistry,
    logger,
  });

  const router = createRouter();
  const routeDeps = {
    config,
    logger,
    now,
    store,
    repos: { versions, content, announcements, users, audit, stats, sessions: sessionRegistry },
    auth,
    oidc,
  };
  registerApiRoutes(router, routeDeps);
  registerAuthRoutes(router, routeDeps);

  const rateLimiter = createRateLimiter({
    windowSec: config.security.rateLimitWindowSec,
    max: config.security.rateLimitMax,
    logger,
  });

  const publicRoot = config.websiteRoot;
  /** 公共静态资源目录：映射到 /assets/*（site.css / site.js / markdown.js / admin.css）。 */
  const assetsRoot = config.publicDir || path.join(config.websiteRoot, 'public');

  /** 显式静态路由：'/' → index.html */
  const PAGE_ALIASES = {
    '/': '/index.html',
    '/login': '/login.html',
    '/login/': '/login.html',
    '/account': '/account.html',
    '/account/': '/account.html',
    '/admin': '/admin/index.html',
    '/admin/': '/admin/index.html',
    '/docs': '/docs/index.html',
    '/docs/': '/docs/index.html',
  };

  async function tryStatic(req, reply, urlPath) {
    // /assets/* → website/public/*（设计系统与客户端核心）
    if (urlPath === '/assets' || urlPath.startsWith('/assets/')) {
      const assetPath = resolveStaticPath(assetsRoot, urlPath.slice('/assets'.length) || '/');
      if (!assetPath) return false;
      const ext = path.extname(assetPath).toLowerCase();
      const immutable = ['.woff2', '.woff', '.png', '.jpg', '.jpeg', '.webp', '.svg', '.ico'].includes(ext);
      const result = await serveFile(req, reply, assetPath, {
        maxAge: immutable ? 604800 : 0,
        immutable,
      });
      if (result && typeof result === 'object' && result.stream) return streamFile(res, result.stream);
      return Boolean(result);
    }

    // 拒绝访问内部文件
    const lowered = urlPath.toLowerCase();
    for (const deny of STATIC_DENY) {
      if (lowered === deny || lowered.startsWith(deny.endsWith('/') ? deny : `${deny}/`) || lowered === `${deny}`) {
        return false;
      }
    }
    const alias = PAGE_ALIASES[urlPath] || urlPath;
    // 根目录只放行白名单文件与固定目录，避免源码通过静态通道泄露
    const firstSegment = alias.split('/').filter(Boolean)[0] || '';
    if (firstSegment && !['images', 'admin', 'docs'].includes(firstSegment)) {
      if (!PUBLIC_ROOT_FILES.has(alias.replace(/^\//, ''))) return false;
    }

    const filePath = resolveStaticPath(publicRoot, alias);
    if (!filePath) return false;
    try {
      const stat = await fs.promises.stat(filePath);
      if (stat.isDirectory()) {
        return tryStatic(req, reply, path.posix.join(alias, 'index.html'));
      }
    } catch {
      return false;
    }
    const ext = path.extname(filePath).toLowerCase();
    const immutable = ['.woff2', '.woff', '.png', '.jpg', '.jpeg', '.webp', '.svg', '.ico'].includes(ext);
    const result = await serveFile(req, reply, filePath, {
      maxAge: immutable ? 604800 : 0,
      immutable,
    });
    if (result && typeof result === 'object' && result.stream) return streamFile(res, result.stream);
    return true;
  }

  /** 大文件（>4MB，如 APK 镜像）流式下发。 */
  function streamFile(res, file) {
    const stream = fs.createReadStream(file);
    stream.on('error', () => {
      if (!res.headersSent) res.statusCode = 500;
      res.end();
    });
    stream.pipe(res);
    return true;
  }

  async function handle(req, res) {
    const reply = createReply(res, { method: req.method });
    const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
    const urlPath = url.pathname;

    // 首页浏览量（只统计 HTML 文档请求，跳过静态资源与 API；不阻塞响应）
    if (
      ['GET', 'HEAD'].includes(req.method) &&
      (urlPath === '/' || urlPath.endsWith('.html') || ['/login', '/account', '/admin/'].includes(urlPath))
    ) {
      const ua = req.headers['user-agent'] || '';
      if (!/bot|crawler|spider|preview/i.test(String(ua))) {
        stats
          .record('pageview', {
            path: urlPath,
            ip: clientIp(req, config),
            ua,
            ref: req.headers.referer || null,
          })
          .catch(() => {});
      }
    }

    for (const [k, v] of Object.entries(securityHeaders(config, urlPath))) {
      res.setHeader(k, v);
    }

    const ip = clientIp(req, config);
    const ua = req.headers['user-agent'] || '';
    const channel = urlPath.startsWith('/api/') || urlPath.startsWith('/auth/') ? 'api' : 'site';

    const ctx = {
      req,
      res,
      reply,
      config,
      logger,
      now,
      ip,
      userAgent: ua,
      method: req.method,
      path: urlPath,
      query: queryToObject(url.searchParams),
      url,
      headers: req.headers,
      channel,
      getHeader: (name) => req.headers[String(name).toLowerCase()],
      state: {},
    };

    try {
      // 限流（登录端点更严格）
      const isLoginStart = urlPath === '/auth/login' || urlPath === '/auth/dev-login';
      const limit = rateLimiter(
        `${channel}:${ip}`,
        isLoginStart ? config.security.loginRateLimitMax : undefined,
      );
      res.setHeader('x-ratelimit-limit', limit.limit);
      res.setHeader('x-ratelimit-remaining', limit.remaining);
      if (!limit.allowed) {
        res.setHeader('retry-after', limit.resetSec);
        throw new HttpError(429, `请求过于频繁，请 ${limit.resetSec} 秒后重试`, 'rate_limited');
      }

      ctx.cookies = parseCookies(req.headers.cookie || '');
      ctx.body = await readBody(req);
      ctx.identity = await auth.resolveIdentity(ctx);

      const matched = router.match(req.method, urlPath);
      if (matched?.methodNotAllowed) {
        res.setHeader('allow', 'GET, POST, PUT, PATCH, DELETE');
        throw new HttpError(405, `方法 ${req.method} 不被支持`, 'method_not_allowed');
      }
      if (matched) {
        const { route, params } = matched;
        ctx.params = params;
        const result = await route.handler(ctx, params);
        if (!reply.sent) {
          if (result === null || result === undefined) {
            reply.json(204, null);
          } else if (result.redirect) {
            appendCookies(res, result.cookies);
            reply.redirect(result.redirect, result.status || 302);
          } else if (result.json !== undefined) {
            appendCookies(res, result.cookies);
            reply.json(result.status || 200, result.json, result.headers);
          } else if (result.html !== undefined) {
            appendCookies(res, result.cookies);
            reply.send(result.status || 200, result.html, {
              'content-type': 'text/html; charset=utf-8',
              ...result.headers,
            });
          } else if (result.body !== undefined) {
            appendCookies(res, result.cookies);
            reply.send(result.status || 200, result.body, result.headers);
          } else {
            reply.send(204, '');
          }
        }
        return;
      }

      // 静态资源
      if (['GET', 'HEAD'].includes(req.method)) {
        const staticResult = await tryStatic(req, reply, urlPath);
        if (staticResult) return;
      }

      throw new HttpError(404, `找不到路径 ${urlPath}`, 'not_found');
    } catch (err) {
      const error = normalizeError(err, logger);
      const status = error.status || 500;
      if (status >= 500) {
        logger.error?.(`[http] ${req.method} ${urlPath} → ${status} ${error.message}\n${err?.stack || ''}`);
      } else {
        logger.debug?.(`[http] ${req.method} ${urlPath} → ${status} ${error.message}`);
      }
      if (reply.sent) return;
      const json = wantsJson(req, urlPath);
      const payload = {
        ok: false,
        error: error.message,
        code: error.code || 'error',
        ...(error.details ? { details: error.details } : {}),
      };
      if (json) {
        reply.json(status, payload);
      } else {
        reply.send(status, errorPage(error), { 'content-type': 'text/html; charset=utf-8' });
      }
    }
  }

  return {
    handle,
    router,
    repos: { versions, content, announcements, users, audit, stats, sessions: sessionRegistry },
    store,
    auth,
    oidc,
    rateLimiter,
    async close() {
      await sessionRegistry.flush();
    },
  };
}

function normalizeError(err, logger) {
  if (err instanceof HttpError || err instanceof RouteError) return err;
  if (err?.status) return err;
  if (err?.code === 'ENOENT') return new HttpError(404, '资源不存在', 'not_found');
  return new HttpError(500, '服务器内部错误', 'internal_error', null);
}

/** 创建并启动真实 HTTP 服务。 */
export async function startServer(config, overrides = {}) {
  const app = assembleApp(config, overrides);
  await app.repos.sessions.load();
  const server = http.createServer((req, res) => {
    app.handle(req, res).catch((err) => {
      (overrides.logger || console).error?.('[http] 未捕获异常', err);
      if (!res.headersSent) {
        res.statusCode = 500;
        res.setHeader('content-type', 'application/json; charset=utf-8');
      }
      res.end(JSON.stringify({ ok: false, error: '服务器内部错误', code: 'internal_error' }));
    });
  });
  server.keepAliveTimeout = 65_000;
  server.headersTimeout = 70_000;

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(config.port, config.host, resolve);
  });

  const address = server.address();
  const actualPort = typeof address === 'object' && address ? address.port : config.port;

  return {
    app,
    server,
    port: actualPort,
    url: `http://${config.host === '0.0.0.0' ? 'localhost' : config.host}:${actualPort}`,
    async close() {
      await app.close();
      // keep-alive 连接会阻止 close 回调，先主动断开再关闭
      server.closeAllConnections?.();
      server.closeIdleConnections?.();
      await new Promise((resolve) => server.close(resolve));
    },
  };
}
