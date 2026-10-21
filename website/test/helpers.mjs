/**
 * 测试脚手架：真实 HTTP 服务器 + 内置 Mock IdP + 会话/请求辅助。
 * 不依赖任何外部服务，`node --test` 可直接跑。
 */

import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

import { loadConfig } from '../server/config.mjs';
import { assembleApp, startServer } from '../server/http/app.mjs';
import { createMockOidcProvider } from '../server/dev/mock-oidc.mjs';
import { parseCookies } from '../server/core/session.mjs';

export const quietLogger = {
  debug() {},
  info() {},
  warn() {},
  error() {},
  log() {},
};

/** 创建临时数据目录。 */
export async function tempDataDir(prefix = 'hamkit-test-') {
  return fs.mkdtemp(path.join(os.tmpdir(), prefix));
}

/**
 * 启动一套完整环境。
 * @param {object} [options]
 * @param {object} [options.env]     覆盖环境变量
 * @param {boolean} [options.mockOidc=true]
 * @param {boolean} [options.devLogin=true]
 * @param {(config: object) => void} [options.tweakConfig]
 */
export async function startTestEnv(options = {}) {
  const {
    env = {},
    mockOidc = true,
    devLogin = true,
    tweakConfig = null,
    logger = quietLogger,
    dataDir: providedDataDir,
  } = options;

  const dataDir = providedDataDir || (await tempDataDir());
  const mock = mockOidc ? await createMockOidcProvider({ quiet: true }) : null;

  const baseEnv = {
    NODE_ENV: 'test',
    PORT: '0',
    HOST: '127.0.0.1',
    DATA_DIR: dataDir,
    SESSION_SECRET: 'test-secret-'.padEnd(48, 'x'),
    DEV_LOGIN: devLogin ? 'true' : 'false',
    OIDC_ROLE_MAP: 'hamkit-admin:admin,hamkit-editor:editor,hamkit-viewer:viewer',
    // 测试里“第一个用户自动成为管理员”会掩盖角色映射问题，默认关闭
    FIRST_USER_IS_ADMIN: 'false',
    ...(mock
      ? {
          OIDC_ISSUER: mock.issuer,
          OIDC_CLIENT_ID: 'hamkit-website',
          OIDC_CLIENT_SECRET: 'mock-secret',
        }
      : {}),
    ...env,
  };

  let config = loadConfig(baseEnv);
  if (tweakConfig) {
    tweakConfig(config);
    // 允许 tweak 改动影响后续构造（例如 secure cookie 需要 https baseUrl）
  }

  const running = await startServer(config, { logger });
  // 端口是运行时分配的（PORT=0），把真实地址回填进配置，
  // 保证 redirect_uri / post_logout_redirect_uri 与实际监听一致。
  const realBaseUrl = `http://127.0.0.1:${running.port}`;
  config.baseUrl = realBaseUrl;

  return {
    dataDir,
    config,
    app: running.app,
    server: running.server,
    url: realBaseUrl,
    port: running.port,
    mock,
    repos: running.app.repos,
    async close() {
      await running.close();
      await mock?.close();
      if (!providedDataDir) await fs.rm(dataDir, { recursive: true, force: true });
    },
  };
}

/** 构造一个带 Cookie 罐与 CSRF 的请求客户端。 */
export function createClient(baseUrl) {
  const jar = new Map();

  function cookieHeader() {
    if (!jar.size) return undefined;
    return [...jar.entries()].map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('; ');
  }

  function absorb(res) {
    const raw = res.headers.getSetCookie?.() ?? [];
    for (const line of raw) {
      const [pair] = line.split(';');
      const idx = pair.indexOf('=');
      if (idx < 0) continue;
      const name = pair.slice(0, idx).trim();
      const value = pair.slice(idx + 1).trim();
      if (!value) {
        jar.delete(name);
      } else {
        // 用原始值存，不回填 decode：Cookie 值本身是 base64url / 不透明串
        jar.set(name, value);
      }
    }
  }

  const client = {
    jar,
    get cookieValue() {
      return (name) => jar.get(name);
    },
    setCookie(name, value) {
      jar.set(name, value);
    },
    clearCookies() {
      jar.clear();
    },
    raw: async (method, target, { body, headers = {}, redirect = 'manual' } = {}) => {
      const url = target.startsWith('http') ? target : `${baseUrl}${target}`;
      const finalHeaders = { ...headers };
      const cookies = cookieHeader();
      if (cookies) finalHeaders.cookie = cookies;
      let payload;
      if (body !== undefined && body !== null) {
        if (typeof body === 'string' || Buffer.isBuffer(body)) {
          payload = body;
        } else if (body instanceof URLSearchParams) {
          payload = body.toString();
          finalHeaders['content-type'] = 'application/x-www-form-urlencoded';
        } else {
          payload = JSON.stringify(body);
          finalHeaders['content-type'] = 'application/json';
        }
      }
      const res = await fetch(url, {
        method,
        headers: finalHeaders,
        body: payload,
        redirect,
      });
      absorb(res);
      return res;
    },
    json: async (method, target, options = {}) => {
      const headers = { accept: 'application/json', ...(options.headers || {}) };
      const csrf = jar.get('hamkit_csrf');
      if (csrf && !['GET', 'HEAD'].includes(method.toUpperCase())) {
        headers['x-csrf-token'] = csrf;
      }
      const res = await client.raw(method, target, { ...options, headers });
      const text = await res.text();
      let parsed = null;
      try {
        parsed = text ? JSON.parse(text) : null;
      } catch {
        parsed = null;
      }
      return { status: res.status, headers: res.headers, body: parsed, text };
    },
    get: (target, options) => client.json('GET', target, options),
    post: (target, body, options) => client.json('POST', target, { body, ...options }),
    patch: (target, body, options) => client.json('PATCH', target, { body, ...options }),
    put: (target, body, options) => client.json('PUT', target, { body, ...options }),
    del: (target, options) => client.json('DELETE', target, options),
    followRedirect: async (target) => {
      const res = await client.raw('GET', target, { redirect: 'manual' });
      return res;
    },
  };
  return client;
}

/** 用开发直登把客户端变成某个角色。 */
export async function loginAs(client, role = 'admin', extra = '') {
  const res = await client.raw('GET', `/auth/dev-login?role=${encodeURIComponent(role)}${extra}`, {
    redirect: 'manual',
  });
  return {
    status: res.status,
    location: res.headers.get('location'),
    csrf: client.jar.get('hamkit_csrf') || null,
  };
}

/**
 * 走完整 OIDC 授权码流程（浏览器会在 Mock IdP 页面点选身份）。
 * @returns {Promise<{status:number, location:string|null}>}
 */
export async function oidcLogin(client, { as = 'admin', returnTo = '/admin/' } = {}) {
  // 1) 起始登录
  const start = await client.raw('GET', `/auth/login?returnTo=${encodeURIComponent(returnTo)}`, {
    redirect: 'manual',
  });
  if (start.status !== 302) throw new Error(`/auth/login 期望 302，实际 ${start.status}`);
  const authorizeUrl = start.headers.get('location');
  if (!authorizeUrl) throw new Error('/auth/login 未返回 location');

  // 2) IdP 授权页（login_hint 让 Mock 直接放行，模拟已登录 IdP 会话）
  const authorize = new URL(authorizeUrl);
  authorize.searchParams.set('login_hint', as);
  const idpRes = await client.raw('GET', authorize.toString(), { redirect: 'manual' });
  if (idpRes.status !== 302) {
    throw new Error(`IdP /authorize 期望 302，实际 ${idpRes.status}`);
  }

  // 3) 回到本站回调
  const callbackUrl = idpRes.headers.get('location');
  const cb = new URL(callbackUrl);
  const callback = await client.raw(
    'GET',
    `${cb.pathname}${cb.search}`,
    { redirect: 'manual' },
  );
  return { status: callback.status, location: callback.headers.get('location'), raw: idpRes };
}

/** 从 cookie 罐里取会话 cookie（用于断言 HttpOnly/属性）。 */
export function sessionFromResponse(res) {
  const cookies = res.headers.getSetCookie?.() ?? [];
  return cookies.map((line) => ({ line, parsed: parseCookies(line.split(';')[0]) }));
}
