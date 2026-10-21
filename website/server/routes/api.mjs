/**
 * API 路由表 —— 所有后台/前台接口的唯一入口。
 *
 * 约定：
 *   GET               公开读（无副作用，除下载计数）
 *   POST/PUT/PATCH/DELETE  需要登录 + 对应权限 + X-CSRF-Token
 *   /api/public/*     前台使用，只输出已发布内容
 *   /api/*            后台使用，需要会话
 *   /update.json /version.json  由版本数据派生，保持 App 兼容
 */

import crypto from 'node:crypto';
import { HttpError } from '../core/auth.mjs';
import { roleHas, ROLES } from '../core/session.mjs';
import { validateConfig } from '../config.mjs';
import { jsonStringify, etagFor, isFresh } from '../http/respond.mjs';
import { toVersionFiles } from './compat.mjs';

const VERSION_PATCHABLE = [
  'versionCode',
  'versionName',
  'tag',
  'downloadUrl',
  'changelog',
  'channel',
  'prerelease',
  'published',
  'sizeBytes',
  'sha256',
];

function pick(input, keys) {
  const out = {};
  for (const key of keys) {
    if (input && Object.prototype.hasOwnProperty.call(input, key)) out[key] = input[key];
  }
  return out;
}

function requireObject(value, message = '请求体必须是 JSON 对象') {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new HttpError(400, message, 'invalid_body');
  }
  return value;
}

function notFound(message = '资源不存在') {
  throw new HttpError(404, message, 'not_found');
}

/** 只允许从官方 APK CDN 下载，防止后台被接管后把任意 URL 推给所有用户。 */
const ALLOWED_APK_HOSTS = new Set(['apk.hamkit.click']);

function isSafeSha256(value) {
  return /^[a-f0-9]{64}$/i.test(String(value || '').trim());
}

/**
 * 发布版本的安全校验：
 * - downloadUrl 必须是 https 且 host 在允许列表；
 * - sha256 必须是 64 位十六进制；
 * - 已发布版本必须同时具备 downloadUrl 与 sha256，App 端才能做完整性/签名校验。
 */
function validateVersionFields(payload, { requireArtifact = false } = {}) {
  const hasUrl = Object.prototype.hasOwnProperty.call(payload, 'downloadUrl');
  const hasSha = Object.prototype.hasOwnProperty.call(payload, 'sha256');

  if (hasUrl) {
    const raw = String(payload.downloadUrl || '').trim();
    if (raw) {
      let url;
      try {
        url = new URL(raw);
      } catch {
        throw new HttpError(400, 'downloadUrl 必须是合法的绝对 URL', 'invalid_download_url');
      }
      if (url.protocol !== 'https:') {
        throw new HttpError(400, 'downloadUrl 必须使用 HTTPS', 'insecure_download_url');
      }
      if (!ALLOWED_APK_HOSTS.has(url.hostname)) {
        throw new HttpError(
          400,
          `downloadUrl 只允许 ${[...ALLOWED_APK_HOSTS].join(', ')}，收到 ${url.hostname}`,
          'untrusted_download_host',
        );
      }
      if (url.port && url.port !== '443') {
        throw new HttpError(400, 'downloadUrl 只允许默认 HTTPS 端口', 'untrusted_download_port');
      }
    } else if (requireArtifact) {
      throw new HttpError(400, '已发布版本必须填写 downloadUrl', 'missing_download_url');
    }
  }

  if (hasSha) {
    const sha = String(payload.sha256 || '').trim();
    if (sha && !isSafeSha256(sha)) {
      throw new HttpError(400, 'sha256 必须是 64 位十六进制', 'invalid_sha256');
    }
    if (!sha && requireArtifact) {
      throw new HttpError(400, '已发布版本必须填写 sha256', 'missing_sha256');
    }
  } else if (requireArtifact) {
    throw new HttpError(400, '已发布版本必须填写 sha256', 'missing_sha256');
  }
}

function publicVersionView(v) {
  return {
    versionCode: v.versionCode,
    versionName: v.versionName,
    tag: v.tag,
    downloadUrl: v.downloadUrl,
    changelog: v.changelog,
    channel: v.channel,
    prerelease: v.prerelease,
    publishedAt: v.updatedAt || v.createdAt,
    sizeBytes: v.sizeBytes,
    sha256: v.sha256,
  };
}

function publicContentView(content) {
  return {
    hero: content.hero,
    features: (content.features || []).filter((f) => f.enabled !== false),
    download: content.download,
    footer: content.footer,
    devs: content.devs,
    seo: content.seo,
    updatedAt: content.updatedAt,
  };
}

function publicAnnouncementView(a) {
  return {
    id: a.id,
    title: a.title,
    body: a.body,
    severity: a.severity,
    link: a.link,
    pinned: a.pinned,
    publishAt: a.publishAt,
    expireAt: a.expireAt,
  };
}

/** 用内容哈希做 ETag，内容没变直接 304。 */
function cachedJson(ctx, value, { maxAge = 60 } = {}) {
  const body = jsonStringify(value);
  const etag = etagFor(Buffer.from(body), 0);
  if (isFresh(ctx.req, etag, 0)) {
    return { status: 304, body: '', headers: { etag, 'cache-control': `public, max-age=${maxAge}` } };
  }
  return {
    status: 200,
    body,
    headers: {
      etag,
      'cache-control': `public, max-age=${maxAge}`,
      'content-type': 'application/json; charset=utf-8',
    },
  };
}

export function registerApiRoutes(router, deps) {
  const { config, repos, auth, oidc, logger, store } = deps;
  const { versions, content, announcements, users, audit, stats, sessions } = repos;

  const actorOf = (ctx) => ctx.identity?.user || null;
  const auditCtx = (ctx) => ({ ip: ctx.ip, userAgent: ctx.userAgent });

  async function record(ctx, action, { target = null, detail = null, result = 'ok' } = {}) {
    await audit.record({
      action,
      actor: actorOf(ctx),
      target,
      detail,
      result,
      ip: ctx.ip,
      userAgent: ctx.userAgent,
    });
  }

  /* ------------------------------- 健康检查 ------------------------------- */

  router.get('/api/health', async (ctx) => ({
    json: {
      ok: true,
      service: 'hamkit-website',
      env: config.env,
      time: new Date(ctx.now()).toISOString(),
      uptimeSec: Math.round(process.uptime()),
      oidcConfigured: config.oidcConfigured,
    },
  }), { channel: 'api' });

  router.get('/api/meta', async (ctx) => {
    const { problems, warnings } = validateConfig(config);
    return {
      json: {
        ok: true,
        site: 'HamKit',
        version: '1.0.0',
        env: config.env,
        baseUrl: config.baseUrl,
        oidcConfigured: config.oidcConfigured,
        oidcIssuer: config.oidc.issuer || null,
        loginUrl: '/auth/login',
        devLoginEnabled: config.devLoginEnabled,
        configProblems: problems,
        configWarnings: warnings,
        permissions: roleHas && ctx.identity ? ctx.identity.permissions : {},
      },
    };
  });

  /* -------------------------------- 前台公开 ------------------------------- */

  router.get('/api/public/content', async (ctx) => {
    const data = await content.get();
    return cachedJson(ctx, { ok: true, content: publicContentView(data), updatedAt: data.updatedAt }, { maxAge: 30 });
  });

  router.get('/api/public/announcements', async (ctx) => {
    const items = await announcements.list({ publicOnly: true, now: ctx.now() });
    return cachedJson(ctx, { ok: true, items: items.map(publicAnnouncementView) }, { maxAge: 30 });
  });

  router.get('/api/public/versions', async (ctx) => {
    const items = await versions.list({ includeUnpublished: false });
    return cachedJson(
      ctx,
      { ok: true, items: items.map(publicVersionView), latest: items[0] ? publicVersionView(items[0]) : null },
      { maxAge: 60 },
    );
  });

  router.get('/api/public/latest', async (ctx) => {
    // 默认给 stable 渠道：App 与下载按钮都应拿到正式版
    const latest = await versions.latest({ channel: ctx.query.channel || 'stable' });
    if (!latest) return cachedJson(ctx, { ok: true, latest: null }, { maxAge: 20 });
    return cachedJson(ctx, { ok: true, latest: publicVersionView(latest) }, { maxAge: 20 });
  });

  /** 计一次下载并跳转到真实 APK 地址。 */
  router.get('/api/public/download', async (ctx) => {
    const channel = ctx.query.channel || 'stable';
    const latest = await versions.latest({ channel });
    if (!latest || !latest.downloadUrl) {
      throw new HttpError(404, '暂无可下载版本', 'no_release');
    }
    await stats.record('download', {
      path: latest.versionName,
      ip: ctx.ip,
      ua: ctx.userAgent,
      ref: ctx.getHeader('referer'),
      meta: { versionCode: latest.versionCode, channel: latest.channel },
    });
    return { redirect: latest.downloadUrl, status: 302 };
  });

  /* --------------------------------- 会话 --------------------------------- */

  router.get('/api/session', async (ctx) => {
    const { user, permissions, reason } = ctx.identity;
    return {
      json: {
        ok: true,
        authenticated: Boolean(user),
        reason: user ? null : reason,
        user: user
          ? {
              id: user.id,
              username: user.username,
              displayName: user.displayName,
              email: user.email,
              avatar: user.avatar,
              role: user.role,
              idpRoles: user.idpRoles,
              lastLoginAt: user.lastLoginAt,
              loginCount: user.loginCount,
            }
          : null,
        permissions,
        roles: ROLES,
        oidcConfigured: config.oidcConfigured,
        devLoginEnabled: config.devLoginEnabled,
      },
    };
  });

  /* ------------------------------ 我的数据 ------------------------------- */

  /**
   * 导出当前登录用户的数据：资料、会话元数据、本人审计记录。
   * 不返回其他用户的数据，也不返回会话 sid/cookie。
   */
  router.get('/api/me/export', async (ctx) => {
    const user = auth.requireUser(ctx.identity);
    const sessionItems = sessions
      .list()
      .filter((s) => s.userId === user.id)
      .map((s) => ({
        role: s.role,
        createdAt: s.createdAt || null,
        lastSeenAt: s.lastSeenAt || null,
        expiresAt: s.expiresAt || null,
        userAgent: s.userAgent || null,
        ip: s.ip || null,
      }));
    const auditPage = await audit.list({ actorId: user.id, limit: 500 });
    await record(ctx, 'user.export', { target: user.id });
    return {
      headers: { 'cache-control': 'no-store' },
      json: {
        ok: true,
        exportedAt: new Date(ctx.now()).toISOString(),
        user: {
          id: user.id,
          username: user.username,
          displayName: user.displayName,
          email: user.email,
          avatar: user.avatar,
          role: user.role,
          idpRoles: user.idpRoles,
          createdAt: user.createdAt,
          updatedAt: user.updatedAt,
          lastLoginAt: user.lastLoginAt,
          loginCount: user.loginCount,
        },
        sessions: sessionItems,
        audit: auditPage.items || [],
      },
    };
  });

  /**
   * 注销当前账号（自助删除）。要求 CSRF + 明确输入 DELETE；
   * 仓储层会拒绝删除最后一个管理员。
   */
  router.delete('/api/me', async (ctx) => {
    const user = auth.requireUser(ctx.identity);
    auth.assertCsrf(ctx);
    const body = ctx.body && typeof ctx.body === 'object' ? ctx.body : {};
    if (body.confirm !== 'DELETE') {
      throw new HttpError(400, '请输入 DELETE 确认注销账号', 'confirmation_required');
    }
    await users.remove(user.id, { actor: user });
    sessions.revokeByUser(user.id);
    await record(ctx, 'user.self_delete', { target: user.id });
    return {
      headers: { 'cache-control': 'no-store' },
      json: { ok: true, deleted: true },
      cookies: auth.clearCookies(),
    };
  });

  /* --------------------------------- 版本 --------------------------------- */

  router.get('/api/versions', async (ctx) => {
    auth.requirePermission(ctx.identity, 'version:read');
    const items = await versions.list();
    return { json: { ok: true, items } };
  });

  router.get('/api/versions/:id', async (ctx) => {
    auth.requirePermission(ctx.identity, 'version:read');
    const item = await versions.get(ctx.params.id);
    if (!item) notFound('版本不存在');
    return { json: { ok: true, item } };
  });

  router.post('/api/versions', async (ctx) => {
    const actor = auth.assertWrite(ctx, 'version:write');
    const body = requireObject(ctx.body);
    const payload = pick(body, VERSION_PATCHABLE);
    validateVersionFields(
      { published: true, ...payload },
      { requireArtifact: (payload.published ?? true) !== false },
    );
    const item = await versions.create({ ...payload, updatedBy: actor.id });
    await record(ctx, 'version.create', {
      target: item.id,
      detail: { versionName: item.versionName, versionCode: item.versionCode, channel: item.channel },
    });
    return { status: 201, json: { ok: true, item } };
  });

  router.patch('/api/versions/:id', async (ctx) => {
    const actor = auth.assertWrite(ctx, 'version:write');
    const body = requireObject(ctx.body);
    const payload = pick(body, VERSION_PATCHABLE);
    const existing = await versions.get(ctx.params.id);
    if (!existing) notFound('版本不存在');
    validateVersionFields(
      { ...existing, ...payload },
      { requireArtifact: (payload.published ?? existing.published) !== false },
    );
    const item = await versions.update(ctx.params.id, { ...payload, updatedBy: actor.id });
    await record(ctx, 'version.update', {
      target: item.id,
      detail: { fields: Object.keys(pick(body, VERSION_PATCHABLE)), versionName: item.versionName },
    });
    return { json: { ok: true, item } };
  });

  router.delete('/api/versions/:id', async (ctx) => {
    auth.assertWrite(ctx, 'version:delete');
    const removed = await versions.remove(ctx.params.id);
    await record(ctx, 'version.delete', {
      target: removed.id,
      detail: { versionName: removed.versionName, versionCode: removed.versionCode },
    });
    return { json: { ok: true, removed } };
  });

  /* --------------------------------- 内容 --------------------------------- */

  router.get('/api/content', async (ctx) => {
    auth.requirePermission(ctx.identity, 'content:read');
    const data = await content.get();
    return { json: { ok: true, content: data } };
  });

  router.patch('/api/content', async (ctx) => {
    auth.assertWrite(ctx, 'content:write');
    const body = requireObject(ctx.body);
    const patch = pick(body, ['hero', 'features', 'download', 'footer', 'devs', 'seo']);
    if (!Object.keys(patch).length) {
      throw new HttpError(400, '没有可更新的字段', 'empty_patch');
    }
    const saved = await content.patch(patch, { actor: actorOf(ctx) });
    await record(ctx, 'content.update', { target: 'content', detail: { fields: Object.keys(patch) } });
    return { json: { ok: true, content: saved } };
  });

  router.post('/api/content/reset', async (ctx) => {
    auth.assertWrite(ctx, 'content:write');
    const saved = await content.reset({ actor: actorOf(ctx) });
    await record(ctx, 'content.reset', { target: 'content' });
    return { json: { ok: true, content: saved } };
  });

  /* --------------------------------- 公告 --------------------------------- */

  router.get('/api/announcements', async (ctx) => {
    auth.requirePermission(ctx.identity, 'announcement:read');
    const items = await announcements.list();
    return { json: { ok: true, items } };
  });

  router.post('/api/announcements', async (ctx) => {
    auth.assertWrite(ctx, 'announcement:write');
    const body = requireObject(ctx.body);
    const item = await announcements.create({ ...body, updatedBy: actorOf(ctx)?.id });
    await record(ctx, 'announcement.create', { target: item.id, detail: { title: item.title } });
    return { status: 201, json: { ok: true, item } };
  });

  router.patch('/api/announcements/:id', async (ctx) => {
    auth.assertWrite(ctx, 'announcement:write');
    const body = requireObject(ctx.body);
    const item = await announcements.update(ctx.params.id, { ...body, updatedBy: actorOf(ctx)?.id });
    await record(ctx, 'announcement.update', { target: item.id, detail: { fields: Object.keys(body) } });
    return { json: { ok: true, item } };
  });

  router.delete('/api/announcements/:id', async (ctx) => {
    auth.assertWrite(ctx, 'announcement:write');
    const removed = await announcements.remove(ctx.params.id);
    await record(ctx, 'announcement.delete', { target: removed.id, detail: { title: removed.title } });
    return { json: { ok: true, removed } };
  });

  /* --------------------------------- 用户 --------------------------------- */

  router.get('/api/users', async (ctx) => {
    auth.requirePermission(ctx.identity, 'user:read');
    const items = await users.list();
    return { json: { ok: true, items, counts: await users.counts(), roles: ROLES } };
  });

  router.patch('/api/users/:id', async (ctx) => {
    const actor = auth.assertWrite(ctx, 'user:write');
    const body = requireObject(ctx.body);
    if (ctx.params.id === actor.id && body.role && body.role !== actor.role) {
      throw new HttpError(409, '不能修改自己的角色', 'self_role_change');
    }
    let updated = null;
    if (body.role !== undefined) {
      updated = await users.setRole(ctx.params.id, body.role, { actor, lock: body.roleLocked !== false });
      await record(ctx, 'user.role', { target: ctx.params.id, detail: { role: body.role } });
    }
    if (body.disabled !== undefined) {
      updated = await users.setDisabled(ctx.params.id, body.disabled, { actor });
      await record(ctx, 'user.disable', { target: ctx.params.id, detail: { disabled: Boolean(body.disabled) } });
      if (body.disabled) {
        const revoked = sessions.revokeByUser(ctx.params.id);
        if (revoked) await record(ctx, 'user.sessions_revoked', { target: ctx.params.id, detail: { revoked } });
      }
    }
    if (body.note !== undefined) {
      updated = await users.patchNote?.(ctx.params.id, body.note, { actor }) ?? updated;
    }
    if (body.revokeSessions) {
      const revoked = sessions.revokeByUser(ctx.params.id);
      await record(ctx, 'user.sessions_revoked', { target: ctx.params.id, detail: { revoked } });
    }
    if (!updated) notFound('用户不存在');
    return { json: { ok: true, user: updated } };
  });

  router.delete('/api/users/:id', async (ctx) => {
    const actor = auth.assertWrite(ctx, 'user:write');
    if (ctx.params.id === actor.id) {
      throw new HttpError(409, '不能删除自己', 'self_delete');
    }
    const removed = await users.remove(ctx.params.id, { actor });
    sessions.revokeByUser(removed.id);
    await record(ctx, 'user.delete', { target: removed.id, detail: { username: removed.username } });
    return { json: { ok: true, removed } };
  });

  /* --------------------------------- 审计 --------------------------------- */

  router.get('/api/audit', async (ctx) => {
    auth.requirePermission(ctx.identity, 'audit:read');
    const limit = Math.min(500, Math.max(1, Number.parseInt(ctx.query.limit || '100', 10) || 100));
    const offset = Math.max(0, Number.parseInt(ctx.query.offset || '0', 10) || 0);
    const result = await audit.list({
      limit,
      offset,
      action: ctx.query.action || undefined,
      actorId: ctx.query.actorId || undefined,
      q: ctx.query.q || undefined,
    });
    return { json: { ok: true, ...result, actions: await audit.actions() } };
  });

  /* --------------------------------- 统计 --------------------------------- */

  router.get('/api/stats', async (ctx) => {
    auth.requirePermission(ctx.identity, 'stats:read');
    const days = Math.min(180, Math.max(1, Number.parseInt(ctx.query.days || '30', 10) || 30));
    const summary = await stats.summary({ days });
    const counts = await users.counts();
    return {
      json: {
        ok: true,
        stats: summary,
        users: counts,
        versions: {
          total: (await versions.list()).length,
          published: (await versions.list({ includeUnpublished: false })).length,
        },
        announcements: {
          total: (await announcements.list()).length,
          active: (await announcements.list({ publicOnly: true })).length,
        },
        sessions: { active: sessions.list().length },
      },
    };
  });

  router.get('/api/stats/events', async (ctx) => {
    auth.requirePermission(ctx.identity, 'stats:read');
    const items = await stats.events({
      date: ctx.query.date || undefined,
      limit: Math.min(1000, Number.parseInt(ctx.query.limit || '200', 10) || 200),
    });
    return { json: { ok: true, items } };
  });

  /* -------------------------------- 系统状态 ------------------------------- */

  router.get('/api/system', async (ctx) => {
    auth.requirePermission(ctx.identity, 'system:read');
    const { problems, warnings } = validateConfig(config);
    const probe = await oidc.probe();
    const dataFiles = await store.listFiles();
    const sizes = [];
    for (const name of dataFiles) {
      const key = name.replace(/\.json$/, '');
      const stat = await store.stat(key);
      if (stat) sizes.push({ file: name, sizeBytes: stat.size, mtime: stat.mtime });
    }
    return {
      json: {
        ok: true,
        env: config.env,
        node: process.version,
        uptimeSec: Math.round(process.uptime()),
        memoryMb: Math.round(process.memoryUsage().rss / 1048576),
        baseUrl: config.baseUrl,
        dataDir: config.dataDir,
        store: store.stats,
        files: sizes,
        oidc: {
          configured: config.oidcConfigured,
          issuer: config.oidc.issuer || null,
          clientId: config.oidc.clientId || null,
          clientAuthMethod: config.oidc.clientAuthMethod || (config.oidc.clientSecret ? 'client_secret_basic' : 'none'),
          scopes: config.oidc.scope,
          roleClaim: config.oidc.roleClaim,
          roleMap: config.oidc.roleMap,
          redirectUri: config.oidcRedirectUri,
          probe,
        },
        security: {
          cookieSecure: config.cookie.secure,
          sameSite: config.cookie.sameSite,
          trustProxy: config.trustProxy,
          hsts: config.security.hsts,
          statsStoreIp: config.stats.storeIp,
        },
        problems,
        warnings,
        activeSessions: sessions.list().map((s) => ({
          sid: s.sid.slice(0, 6) + '…',
          userId: s.userId,
          role: s.role,
          lastSeenAt: s.lastSeenAt,
          expiresAt: s.expiresAt,
        })),
      },
    };
  });

  router.post('/api/system/sessions/revoke', async (ctx) => {
    auth.assertWrite(ctx, 'system:read');
    const body = requireObject(ctx.body, '需要提供 sid 或 userId');
    let revoked = 0;
    if (body.sid) {
      const target = sessions.list().find((s) => s.sid.startsWith(body.sid));
      if (target) revoked = sessions.revoke(target.sid) ? 1 : 0;
    } else if (body.userId) {
      revoked = sessions.revokeByUser(body.userId);
    } else {
      throw new HttpError(400, '需要提供 sid 或 userId', 'invalid_body');
    }
    await record(ctx, 'system.sessions_revoke', { target: body.sid || body.userId, detail: { revoked } });
    return { json: { ok: true, revoked } };
  });

  /* --------------------------------- 兼容文件 ------------------------------ */

  router.get('/api/compat/preview', async (ctx) => {
    auth.requirePermission(ctx.identity, 'version:read');
    const files = await versions.compatFiles();
    return {
      json: {
        ok: true,
        'update.json': files['update.json'],
        'version.json': files['version.json'],
        channels: files.channels || {},
      },
    };
  });

  // 显式路由：保证即使静态目录里存在同名文件，也以数据为准
  router.get('/update.json', async (ctx) => {
    const files = await toVersionFiles(versions, { now: ctx.now() });
    return cachedJson(ctx, files['update.json'], { maxAge: 30 });
  }, { channel: 'site' });

  router.get('/version.json', async (ctx) => {
    const files = await toVersionFiles(versions, { now: ctx.now() });
    return cachedJson(ctx, files['version.json'], { maxAge: 30 });
  }, { channel: 'site' });

  router.get('/update_:channel.json', async (ctx) => {
    const channel = ctx.params.channel;
    if (!['stable', 'beta', 'nightly'].includes(channel)) notFound();
    const files = await versions.compatFiles();
    const payload = files.channels?.[channel];
    if (!payload) notFound(`渠道 ${channel} 暂无发布版本`);
    return cachedJson(ctx, payload, { maxAge: 30 });
  }, { channel: 'site' });

  logger.debug?.(`[routes] 已注册 ${router.routes.length} 条路由`);
  return router;
}

export { publicVersionView, publicContentView, publicAnnouncementView };

/** 供测试断言：路由清单。 */
export function hasPermission(role, permission) {
  return roleHas(role, permission);
}
