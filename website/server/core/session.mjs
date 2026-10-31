/**
 * 会话与 CSRF —— 无状态签名 Cookie + 服务端撤销表。
 *
 * 设计取舍：
 *   - 会话令牌本身是 HS256 签名的 JWT（无状态、可水平扩展），只放 uid / 角色 / sid。
 *   - 同时在内存+磁盘维护“会话登记表”，支持强制下线（踢人）与过期清理；
 *     校验时两边都要过 —— 无状态签名的即时性由登记表补上。
 *   - CSRF：写操作要求 X-CSRF-Token 头与 Cookie 中的令牌双提交一致（SameSite=Lax 之外的第二道锁）。
 */

import crypto from 'node:crypto';
import { signJwt, verifyJwt, timingSafeEqualString } from './jwt.mjs';

export const SESSION_COOKIE = 'hamkit_session';
export const CSRF_COOKIE = 'hamkit_csrf';
export const OAUTH_COOKIE = 'hamkit_oauth';

export const ROLES = ['admin', 'editor', 'viewer'];

/** 角色权限矩阵：单一事实来源，路由与前端都从这里派生。 */
export const PERMISSIONS = {
  'version:read': ['admin', 'editor', 'viewer'],
  'version:write': ['admin', 'editor'],
  'version:delete': ['admin'],
  'content:read': ['admin', 'editor', 'viewer'],
  'content:write': ['admin', 'editor'],
  'announcement:read': ['admin', 'editor', 'viewer'],
  'announcement:write': ['admin', 'editor'],
  'stats:read': ['admin', 'editor', 'viewer'],
  'audit:read': ['admin', 'editor', 'viewer'],
  'user:read': ['admin', 'editor'],
  'user:write': ['admin'],
  'system:read': ['admin'],
};

export function roleHas(role, permission) {
  const allowed = PERMISSIONS[permission];
  if (!allowed) return false;
  return allowed.includes(role);
}

/** 按角色汇总权限，前端据此渲染菜单与按钮。 */
export function permissionsForRole(role) {
  const out = {};
  for (const [perm, roles] of Object.entries(PERMISSIONS)) out[perm] = roles.includes(role);
  return out;
}

/**
 * 会话管理器。
 * @param {object} options
 * @param {string} options.secret      签名密钥
 * @param {number} [options.ttlSec]    会话有效期（默认 8 小时）
 * @param {object} [options.registry]  会话登记表 { isActive, register, revoke, touch }
 */
export function createSessionManager({ secret, ttlSec = 8 * 3600, registry = null, now = () => Date.now() }) {
  if (!secret) throw new Error('createSessionManager 需要 secret');

  /** 创建会话：签发令牌并登记。 */
  function create(user, { userAgent = '', ip = '' } = {}) {
    const sid = crypto.randomBytes(16).toString('base64url');
    const issuedAt = Math.floor(now() / 1000);
    const expiresAt = issuedAt + ttlSec;
    const token = signJwt(
      {
        sub: user.id,
        sid,
        role: user.role,
        name: user.name || user.email || user.id,
        iat: issuedAt,
        exp: expiresAt,
      },
      { alg: 'HS256', secret },
    );
    registry?.register?.({
      sid,
      userId: user.id,
      role: user.role,
      createdAt: now(),
      lastSeenAt: now(),
      expiresAt: expiresAt * 1000,
      userAgent,
      ip,
    });
    return { token, sid, expiresAt };
  }

  /**
   * 校验会话令牌。返回 { ok, payload, reason }。
   * reason: missing | malformed | invalid | expired | revoked
   */
  async function verify(token) {
    if (!token) return { ok: false, reason: 'missing' };
    try {
      const payload = await verifyJwt({
        token,
        secret,
        allowedAlgs: ['HS256'],
        clockToleranceSec: 0,
        now: now(),
      });
      if (!payload.sid || !payload.sub) return { ok: false, reason: 'invalid' };
      if (registry?.isActive && !(await registry.isActive(payload.sid))) {
        return { ok: false, reason: 'revoked', payload };
      }
      registry?.touch?.(payload.sid, now());
      return { ok: true, payload };
    } catch (err) {
      if (err?.code === 'expired') return { ok: false, reason: 'expired' };
      return { ok: false, reason: err?.code === 'malformed' ? 'malformed' : 'invalid' };
    }
  }

  return { create, verify, ttlSec };
}

/** CSRF 令牌：随机值，双提交校验。 */
export function createCsrfToken() {
  return crypto.randomBytes(24).toString('base64url');
}

export function csrfMatches(cookieValue, headerValue) {
  return timingSafeEqualString(cookieValue || '', headerValue || '');
}

/**
 * 会话登记表：内存为主 + 落盘（重启后仍能踢掉旧会话）。
 */
export function createSessionRegistry({ store, now = () => Date.now() } = {}) {
  const sessions = new Map();
  let loaded = false;
  let persistTimer = null;

  async function load() {
    if (loaded) return;
    loaded = true;
    if (!store) return;
    const data = await store.read('sessions', { sessions: [] });
    const t = now();
    for (const s of data.sessions || []) {
      if (s?.sid && s.expiresAt > t) sessions.set(s.sid, s);
    }
  }

  function schedulePersist() {
    if (!store || persistTimer) return;
    persistTimer = setTimeout(() => {
      persistTimer = null;
      const t = now();
      const alive = [...sessions.values()].filter((s) => s.expiresAt > t);
      store.write('sessions', { sessions: alive }).catch(() => {});
    }, 500);
    persistTimer.unref?.();
  }

  return {
    load,
    register(session) {
      sessions.set(session.sid, session);
      schedulePersist();
    },
    async isActive(sid) {
      await load();
      const s = sessions.get(sid);
      if (!s) return false;
      if (s.expiresAt <= now()) {
        sessions.delete(sid);
        schedulePersist();
        return false;
      }
      return true;
    },
    touch(sid, ts) {
      const s = sessions.get(sid);
      if (s) {
        s.lastSeenAt = ts;
        schedulePersist();
      }
    },
    revoke(sid) {
      const had = sessions.delete(sid);
      schedulePersist();
      return had;
    },
    /** 用于“强制该用户全部下线” */
    revokeByUser(userId) {
      let count = 0;
      for (const [sid, s] of sessions) {
        if (s.userId === userId) {
          sessions.delete(sid);
          count++;
        }
      }
      schedulePersist();
      return count;
    },
    list() {
      const t = now();
      return [...sessions.values()]
        .filter((s) => s.expiresAt > t)
        .sort((a, b) => (b.lastSeenAt || 0) - (a.lastSeenAt || 0));
    },
    async flush() {
      if (!store) return;
      const t = now();
      const alive = [...sessions.values()].filter((s) => s.expiresAt > t);
      await store.write('sessions', { sessions: alive });
    },
  };
}

/** 解析 Cookie 头。 */
export function parseCookies(header = '') {
  const out = {};
  for (const part of String(header).split(';')) {
    const idx = part.indexOf('=');
    if (idx < 0) continue;
    const key = part.slice(0, idx).trim();
    if (!key) continue;
    const raw = part.slice(idx + 1).trim();
    try {
      out[key] = decodeURIComponent(raw);
    } catch {
      out[key] = raw;
    }
  }
  return out;
}

/** 生成 Set-Cookie 值。 */
export function serializeCookie(name, value, options = {}) {
  const parts = [`${name}=${encodeURIComponent(value)}`];
  if (options.maxAge !== undefined) parts.push(`Max-Age=${Math.floor(options.maxAge)}`);
  if (options.path) parts.push(`Path=${options.path}`);
  if (options.domain) parts.push(`Domain=${options.domain}`);
  if (options.httpOnly) parts.push('HttpOnly');
  if (options.secure) parts.push('Secure');
  if (options.sameSite) parts.push(`SameSite=${options.sameSite}`);
  return parts.join('; ');
}
