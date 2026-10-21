/**
 * 认证编排层：把 OIDC 客户端、会话、用户仓库、审计串成完整登录流程。
 *
 * 关键安全点：
 *   - state / nonce / code_verifier 放在**签名过**的短期 Cookie 里（HttpOnly + SameSite=Lax），
 *     服务端不落盘，天然支持多实例；签名保证客户端无法伪造。
 *   - 回调必须 state 完全一致，否则拒绝（CSRF / 会话固定防护）。
 *   - 登录成功即换新 session id（不沿用任何客户端提供的值）。
 *   - 写操作需要 X-CSRF-Token 双提交。
 */

import {
  SESSION_COOKIE,
  CSRF_COOKIE,
  OAUTH_COOKIE,
  createCsrfToken,
  csrfMatches,
  permissionsForRole,
  roleHas,
  serializeCookie,
} from './session.mjs';
import { signJwt, verifyJwt } from './jwt.mjs';
import { OidcError } from './oidc.mjs';

export const OAUTH_COOKIE_TTL_SEC = 600;

/**
 * 判断 OIDC claims 是否表明完成了多因素认证。
 * 兼容常见写法：amr 数组、acr 字符串（含 mfa/loa2/loa3/aal2）。
 */
export function hasMfaClaim(claims = {}) {
  const amr = claims?.amr;
  if (Array.isArray(amr)) {
    if (amr.some((method) => /mfa|otp|hwk|swk|security.?key|webauthn/i.test(String(method)))) {
      return true;
    }
  }
  const acr = claims?.acr;
  if (typeof acr === 'string' && /mfa|loa2|loa3|aal2/i.test(acr)) {
    return true;
  }
  return false;
}

export class HttpError extends Error {
  constructor(status, message, code = null, details = null) {
    super(message);
    this.name = 'HttpError';
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

export function createAuthService({ config, oidc, sessions, users, audit, stats, registry, logger = console }) {
  const cookieBase = {
    path: '/',
    sameSite: config.cookie.sameSite,
    secure: config.cookie.secure,
    domain: config.cookie.domain,
  };

  function sessionCookie(token, maxAge) {
    return serializeCookie(SESSION_COOKIE, token, { ...cookieBase, httpOnly: true, maxAge });
  }
  function csrfCookie(token, maxAge) {
    // CSRF 令牌必须能被前端 JS 读取（双提交模式），因此不加 HttpOnly
    return serializeCookie(CSRF_COOKIE, token, { ...cookieBase, httpOnly: false, maxAge });
  }
  function oauthCookie(value, maxAge) {
    return serializeCookie(OAUTH_COOKIE, value, { ...cookieBase, httpOnly: true, maxAge });
  }
  function clearCookies() {
    return [
      serializeCookie(SESSION_COOKIE, '', { ...cookieBase, httpOnly: true, maxAge: 0 }),
      serializeCookie(CSRF_COOKIE, '', { ...cookieBase, httpOnly: false, maxAge: 0 }),
      serializeCookie(OAUTH_COOKIE, '', { ...cookieBase, httpOnly: true, maxAge: 0 }),
    ];
  }

  /** 解析请求身份：不抛错，未登录时返回 user=null。 */
  async function resolveIdentity(req) {
    const cookies = req.cookies || {};
    const token = cookies[config.cookie.name] || cookies[SESSION_COOKIE];
    const result = await sessions.verify(token);
    if (!result.ok) {
      return { user: null, session: null, permissions: {}, reason: result.reason };
    }
    const user = await users.get(result.payload.sub);
    if (!user) return { user: null, session: null, permissions: {}, reason: 'user_missing' };
    if (user.disabled) return { user: null, session: null, permissions: {}, reason: 'disabled', user };
    const role = user.role;
    return {
      user,
      session: result.payload,
      permissions: permissionsForRole(role),
      reason: null,
    };
  }

  /** 只要求登录，不要求具体后台权限（用于用户自己的数据导出/注销）。 */
  function requireUser(identity) {
    if (!identity?.user) {
      throw new HttpError(401, '请先登录', 'unauthenticated');
    }
    return identity.user;
  }

  /** CSRF 双提交校验（写操作必须调用；不附带权限断言）。 */
  function assertCsrf(ctx) {
    const header = ctx.getHeader('x-csrf-token');
    const cookie = ctx.cookies?.[CSRF_COOKIE];
    if (!csrfMatches(cookie, header)) {
      throw new HttpError(403, 'CSRF 校验失败，请刷新页面后重试', 'csrf_failed');
    }
  }

  /** 权限断言，失败抛 401/403。 */
  function requirePermission(identity, permission) {
    if (!identity?.user) {
      throw new HttpError(401, '请先登录', 'unauthenticated');
    }
    if (!roleHas(identity.user.role, permission)) {
      throw new HttpError(403, `当前角色（${identity.user.role}）无权执行：${permission}`, 'forbidden', {
        permission,
        role: identity.user.role,
      });
    }
    return identity.user;
  }

  /**
   * 写操作统一入口：校验权限 + CSRF 双提交。
   * @param {object} ctx 请求上下文（含 cookies 与 headers）
   */
  function assertWrite(ctx, permission) {
    const user = requirePermission(ctx.identity, permission);
    assertCsrf(ctx);
    return user;
  }

  /** 发起登录：生成授权 URL 并写入临时凭据 Cookie。 */
  async function startLogin({ returnTo = '/admin/', ip = null, userAgent = null } = {}) {
    const safeReturnTo = sanitizeReturnTo(returnTo);
    const { url, state, nonce, codeVerifier } = await oidc.buildAuthorizationUrl({
      redirectUri: config.oidcRedirectUri,
      scope: config.oidc.scope,
      extraParams: config.oidc.extraAuthParams,
    });
    const cookieValue = signJwt(
      {
        state,
        nonce,
        codeVerifier,
        returnTo: safeReturnTo,
        exp: Math.floor(Date.now() / 1000) + OAUTH_COOKIE_TTL_SEC,
      },
      { alg: 'HS256', secret: config.session.secret + '.oauth' },
    );
    logger.debug?.(`[auth] 发起登录 ip=${ip} returnTo=${safeReturnTo}`);
    return { url, cookie: oauthCookie(cookieValue, OAUTH_COOKIE_TTL_SEC) };
  }

  /** 只允许站内相对路径，防止开放重定向。 */
  function sanitizeReturnTo(value) {
    if (typeof value !== 'string' || !value) return '/admin/';
    if (!value.startsWith('/') || value.startsWith('//')) return '/admin/';
    if (value.includes('\\')) return '/admin/';
    return value.slice(0, 512);
  }

  /**
   * 处理回调：校验 state → 换令牌 → 落库 → 建会话。
   * @returns {Promise<{ returnTo: string, cookies: string[], user: object, created: boolean }>}
   */
  async function handleCallback({ code, state, cookies, ip = null, userAgent = null }) {
    const raw = cookies?.[OAUTH_COOKIE];
    if (!raw) {
      throw new HttpError(400, '登录会话已过期，请重新登录', 'oauth_state_missing');
    }
    let pending;
    try {
      pending = await verifyJwt({
        token: raw,
        secret: config.session.secret + '.oauth',
        allowedAlgs: ['HS256'],
        clockToleranceSec: 0,
      });
    } catch {
      throw new HttpError(400, '登录会话无效或已过期，请重新登录', 'oauth_state_invalid');
    }
    if (!state || pending.state !== state) {
      throw new HttpError(400, 'state 校验失败，可能遭遇 CSRF，请重新登录', 'state_mismatch');
    }

    let result;
    try {
      result = await oidc.exchangeCode({
        code,
        redirectUri: config.oidcRedirectUri,
        codeVerifier: pending.codeVerifier,
        nonce: pending.nonce,
        expectedAzp: config.oidc.clientId,
      });
    } catch (err) {
      if (err instanceof OidcError) {
        throw new HttpError(401, `OIDC 登录失败：${err.message}`, err.code, err.details);
      }
      throw err;
    }

    const { user, created, roleChanged } = await users.upsertFromOidc(result.profile, {
      ip: config.stats.storeIp ? ip : null,
      userAgent,
    });

    if (user.disabled) {
      throw new HttpError(403, '该账号已被停用，请联系管理员', 'account_disabled');
    }
    if (!config.auth.allowViewerLogin && user.role === 'viewer') {
      throw new HttpError(403, '该账号没有后台访问权限', 'no_backend_access');
    }
    if (
      config.auth.requireMfa &&
      ['admin', 'editor'].includes(user.role) &&
      !hasMfaClaim(result.claims)
    ) {
      throw new HttpError(
        403,
        '该账号需要多因素认证（MFA）：请在身份提供方开启 MFA 后重新登录',
        'mfa_required',
      );
    }

    const session = sessions.create(user, { userAgent, ip });
    const csrf = createCsrfToken();

    await audit.record({
      action: created ? 'auth.login.first' : 'auth.login',
      actor: user,
      target: user.id,
      detail: { role: user.role, roleChanged, email: user.email, idpRoles: user.idpRoles },
      ip,
      userAgent,
    });
    await stats.record('login', { userId: user.id, ip, ua: userAgent });

    logger.info?.(`[auth] 登录成功 ${user.username}(${user.role}) created=${created}`);
    return {
      returnTo: sanitizeReturnTo(pending.returnTo),
      cookies: [
        sessionCookie(session.token, config.cookie.maxAgeSec),
        csrfCookie(csrf, config.cookie.maxAgeSec),
        oauthCookie('', 0),
      ],
      user,
      created,
    };
  }

  /** 登出：撤销当前会话（可要求连同 IdP 一起登出）。 */
  async function handleLogout({ identity, ip = null, userAgent = null }) {
    const sid = identity?.session?.sid;
    if (sid) {
      registry.revoke(sid);
      await audit.record({
        action: 'auth.logout',
        actor: identity.user,
        target: identity.user?.id,
        ip,
        userAgent,
      });
    }
    let redirectUrl = null;
    if (config.oidcConfigured) {
      try {
        redirectUrl = await oidc.buildEndSessionUrl({
          postLogoutRedirectUri: config.postLogoutRedirectUri,
        });
      } catch {
        redirectUrl = null;
      }
    }
    return { cookies: clearCookies(), redirectUrl };
  }

  /**
   * 开发用直登（仅 DEV_LOGIN=true 生效）。
   * 用途：本地没有 IdP 时自测后台；生产环境会被硬性拒绝并在启动时告警。
   */
  async function devLogin({ role = 'admin', username = null, ip = null, userAgent = null }) {
    if (!config.devLoginEnabled) {
      throw new HttpError(404, '未开启开发登录', 'dev_login_disabled');
    }
    if (!['admin', 'editor', 'viewer'].includes(role)) {
      throw new HttpError(400, `未知角色：${role}`, 'invalid_role');
    }
    const name = username || `dev-${role}`;
    // 开发直登要得到指定角色，必须绕过 IdP 角色映射：显式告知用户仓库“这是人工指定角色”。
    const { user, created } = await users.upsertFromOidc(
      {
        sub: `dev:${name}`,
        preferred_username: name,
        name: `${name}（开发）`,
        email: `${name}@localhost`,
        email_verified: true,
        [config.oidc.roleClaim]: [],
      },
      { userAgent, forceRole: role },
    );
    const session = sessions.create(user, { userAgent, ip });
    const csrf = createCsrfToken();
    await audit.record({
      action: 'auth.login.dev',
      actor: user,
      target: user.id,
      detail: { role: user.role, created },
      ip,
      userAgent,
      result: 'dev',
    });
    return {
      user,
      cookies: [sessionCookie(session.token, config.cookie.maxAgeSec), csrfCookie(csrf, config.cookie.maxAgeSec)],
    };
  }

  return {
    resolveIdentity,
    requireUser,
    requirePermission,
    assertCsrf,
    assertWrite,
    startLogin,
    handleCallback,
    handleLogout,
    devLogin,
    sanitizeReturnTo,
    sessionCookie,
    csrfCookie,
    clearCookies,
  };
}
