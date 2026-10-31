/**
 * 通用 OIDC 客户端（授权码 + PKCE）—— 零第三方依赖，只用 node:crypto 与 fetch。
 *
 * 不绑定任何厂商：issuer / client_id / client_secret / scope / 端点覆盖
 * 全部来自环境变量，见 server/config.mjs。
 *
 * 实现清单（对齐 OpenID Connect Core 1.0 + RFC 7636 + RFC 8414）：
 *   - OIDC Discovery（/.well-known/openid-configuration），可由 OIDC_DISCOVERY_URL 覆盖
 *   - PKCE S256（RFC 7636）
 *   - state / nonce / code_verifier 生成与校验
 *   - 令牌端点 client_secret_basic 与 client_secret_post
 *   - JWKS 缓存 + 单飞（single-flight）+ 轮换强制刷新（带冷却，防打爆 IdP）
 *   - userinfo 端点（ID Token 缺少资料时补齐）
 */

import crypto from 'node:crypto';
import { verifyJwt, JwtError, DEFAULT_ALLOWED_ALGS } from './jwt.mjs';

export class OidcError extends Error {
  constructor(message, code = 'oidc_error', details = null) {
    super(message);
    this.name = 'OidcError';
    this.code = code;
    this.details = details;
  }
}

const DEFAULT_SCOPE = 'openid profile email';
const DEFAULT_TIMEOUT_MS = 10_000;

/** 去掉末尾斜杠，统一 issuer 形态，避免 iss 比对因尾斜杠失败。 */
export function normalizeIssuer(issuer) {
  return typeof issuer === 'string' ? issuer.replace(/\/+$/, '') : '';
}

function base64url(buffer) {
  return Buffer.from(buffer).toString('base64url');
}

/** PKCE code_verifier：43–128 个 unreserved 字符。 */
export function createCodeVerifier(bytes = 32) {
  return base64url(crypto.randomBytes(bytes));
}

/** S256 challenge = BASE64URL(SHA256(ASCII(verifier)))。 */
export function createCodeChallenge(verifier) {
  return base64url(crypto.createHash('sha256').update(verifier, 'ascii').digest());
}

export function randomToken(bytes = 32) {
  return base64url(crypto.randomBytes(bytes));
}

/** 带超时的 fetch，避免 IdP 卡住把本站拖死。 */
async function fetchWithTimeout(url, options = {}, timeoutMs = DEFAULT_TIMEOUT_MS) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } catch (err) {
    if (err?.name === 'AbortError') {
      throw new OidcError(`请求 ${url} 超时（${timeoutMs}ms）`, 'timeout');
    }
    throw new OidcError(`请求 ${url} 失败：${err.message}`, 'network_error');
  } finally {
    clearTimeout(timer);
  }
}

async function readJson(res, url) {
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    throw new OidcError(
      `${url} 返回非 JSON（HTTP ${res.status}）：${text.slice(0, 200)}`,
      'bad_response',
    );
  }
  return json;
}

/**
 * 创建一个 OIDC 客户端实例。
 *
 * @param {object} config 见 server/config.mjs 的 oidc 段
 */
export function createOidcClient(config, { logger = console, now = () => Date.now() } = {}) {
  const issuer = normalizeIssuer(config.issuer);
  const discoveryUrl =
    config.discoveryUrl ||
    (issuer ? `${issuer}/.well-known/openid-configuration` : null);

  const allowedAlgs = config.allowedAlgs?.length ? config.allowedAlgs : DEFAULT_ALLOWED_ALGS;

  let discoveryCache = { value: null, expiresAt: 0 };
  let discoveryInflight = null;
  let jwksCache = { value: null, expiresAt: 0 };
  let jwksInflight = null;
  let jwksForceRefreshAt = 0;

  const cacheTtlMs = (config.cacheTtlSec ?? 600) * 1000;
  const forceRefreshCooldownMs = (config.jwksCooldownSec ?? 30) * 1000;

  /** 端点来源优先级：显式环境变量 > Discovery > 报错。 */
  async function getEndpoints() {
    if (config.authorizationEndpoint && config.tokenEndpoint && config.jwksUri) {
      return {
        issuer: issuer || config.authorizationEndpoint,
        authorization_endpoint: config.authorizationEndpoint,
        token_endpoint: config.tokenEndpoint,
        jwks_uri: config.jwksUri,
        userinfo_endpoint: config.userinfoEndpoint || null,
        end_session_endpoint: config.endSessionEndpoint || null,
        __source: 'env',
      };
    }
    if (!discoveryUrl) {
      throw new OidcError('未配置 OIDC_ISSUER，也没有完整的端点环境变量', 'not_configured');
    }
    const t = now();
    if (discoveryCache.value && discoveryCache.expiresAt > t) return discoveryCache.value;
    if (discoveryInflight) return discoveryInflight;
    discoveryInflight = (async () => {
      try {
        const res = await fetchWithTimeout(discoveryUrl, {
          headers: { accept: 'application/json' },
        });
        if (!res.ok) {
          throw new OidcError(
            `Discovery 失败：HTTP ${res.status} @ ${discoveryUrl}`,
            'discovery_failed',
          );
        }
        const doc = await readJson(res, discoveryUrl);
        if (!doc?.authorization_endpoint || !doc?.token_endpoint || !doc?.jwks_uri) {
          throw new OidcError('Discovery 文档缺少必要端点', 'discovery_failed', doc);
        }
        if (issuer && normalizeIssuer(doc.issuer) !== issuer) {
          throw new OidcError(
            `Discovery 的 issuer（${doc.issuer}）与配置不一致（${issuer}）`,
            'discovery_failed',
          );
        }
        discoveryCache = { value: doc, expiresAt: now() + cacheTtlMs };
        return doc;
      } finally {
        discoveryInflight = null;
      }
    })();
    return discoveryInflight;
  }

  async function fetchJwks(jwksUri, { force = false } = {}) {
    const t = now();
    if (!force && jwksCache.value && jwksCache.expiresAt > t) return jwksCache.value;
    if (jwksInflight) return jwksInflight;
    jwksInflight = (async () => {
      try {
        const res = await fetchWithTimeout(jwksUri, { headers: { accept: 'application/json' } });
        if (!res.ok) {
          if (jwksCache.value) {
            logger.warn?.(`[oidc] JWKS 刷新失败（HTTP ${res.status}），继续使用旧缓存`);
            return jwksCache.value;
          }
          throw new OidcError(`JWKS 获取失败：HTTP ${res.status}`, 'jwks_failed');
        }
        const doc = await readJson(res, jwksUri);
        if (!Array.isArray(doc?.keys)) throw new OidcError('JWKS 文档缺少 keys', 'jwks_failed');
        jwksCache = { value: doc, expiresAt: now() + cacheTtlMs };
        return doc;
      } finally {
        jwksInflight = null;
      }
    })();
    return jwksInflight;
  }

  /**
   * 供 verifyJwt 使用的密钥回调：先给缓存，再决定是否附上强制刷新的列表。
   * 冷却窗口内不重复强制刷新 —— 否则伪造 kid 的请求会造成对 IdP 的放大攻击。
   */
  function keyProvider(jwksUri) {
    return async () => {
      const cached = await fetchJwks(jwksUri);
      const t = now();
      const mayForceRefresh = t >= jwksForceRefreshAt;
      if (!mayForceRefresh) return [cached];
      jwksForceRefreshAt = t + forceRefreshCooldownMs;
      try {
        const fresh = await fetchJwks(jwksUri, { force: true });
        return [cached, fresh];
      } catch (err) {
        logger.warn?.(`[oidc] JWKS 强制刷新失败：${err.message}`);
        return [cached];
      }
    };
  }

  /** 生成授权 URL 与本次登录所需的临时凭据。 */
  async function buildAuthorizationUrl({ redirectUri, scope, extraParams = {} }) {
    const endpoints = await getEndpoints();
    const state = randomToken(24);
    const nonce = randomToken(24);
    const codeVerifier = createCodeVerifier();
    const url = new URL(endpoints.authorization_endpoint);
    const params = {
      response_type: 'code',
      client_id: config.clientId,
      redirect_uri: redirectUri,
      scope: scope || config.scope || DEFAULT_SCOPE,
      state,
      nonce,
      code_challenge: createCodeChallenge(codeVerifier),
      code_challenge_method: 'S256',
      ...extraParams,
    };
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, String(v));
    }
    return { url: url.toString(), state, nonce, codeVerifier };
  }

  /** 组装令牌端点的客户端认证（basic / post / none）。 */
  function clientAuth() {
    const method = config.clientAuthMethod || (config.clientSecret ? 'client_secret_basic' : 'none');
    const headers = { 'content-type': 'application/x-www-form-urlencoded' };
    const body = {};
    if (method === 'client_secret_basic') {
      if (!config.clientSecret) throw new OidcError('client_secret_basic 需要 clientSecret', 'not_configured');
      headers.authorization =
        'Basic ' + Buffer.from(`${config.clientId}:${config.clientSecret}`).toString('base64');
    } else if (method === 'client_secret_post') {
      if (!config.clientSecret) throw new OidcError('client_secret_post 需要 clientSecret', 'not_configured');
      body.client_secret = config.clientSecret;
    } else if (method !== 'none') {
      throw new OidcError(`不支持的 clientAuthMethod：${method}`, 'not_configured');
    }
    return { method, headers, body };
  }

  /** 用授权码换令牌，并校验 ID Token。 */
  async function exchangeCode({ code, redirectUri, codeVerifier, nonce, expectedAzp }) {
    const endpoints = await getEndpoints();
    const auth = clientAuth();
    const form = new URLSearchParams({
      grant_type: 'authorization_code',
      code,
      redirect_uri: redirectUri,
      client_id: config.clientId,
      code_verifier: codeVerifier,
      ...auth.body,
    });

    const res = await fetchWithTimeout(endpoints.token_endpoint, {
      method: 'POST',
      headers: auth.headers,
      body: form.toString(),
    });
    const payload = await readJson(res, endpoints.token_endpoint);
    if (!res.ok) {
      const desc = payload?.error_description || payload?.error || `HTTP ${res.status}`;
      throw new OidcError(`令牌交换失败：${desc}`, 'token_exchange_failed', payload);
    }
    if (!payload?.id_token) {
      throw new OidcError('令牌响应缺少 id_token', 'token_exchange_failed', payload);
    }

    const claims = await verifyJwt({
      token: payload.id_token,
      getKeys: keyProvider(endpoints.jwks_uri),
      issuer: issuer || normalizeIssuer(endpoints.issuer),
      audience: config.clientId,
      nonce,
      azp: expectedAzp,
      allowedAlgs,
      clockToleranceSec: config.clockToleranceSec ?? 60,
      maxAgeSec: config.maxAgeSec,
    });

    let profile = claims;
    // 有 access_token 且配了 userinfo 时补齐资料；失败不影响登录（ID Token 已够用）
    if (payload.access_token && endpoints.userinfo_endpoint && config.useUserInfo !== false) {
      try {
        const uiRes = await fetchWithTimeout(endpoints.userinfo_endpoint, {
          headers: { authorization: `Bearer ${payload.access_token}`, accept: 'application/json' },
        });
        if (uiRes.ok) {
          const ui = await readJson(uiRes, endpoints.userinfo_endpoint);
          if (ui?.sub && ui.sub !== claims.sub) {
            logger.warn?.('[oidc] userinfo.sub 与 id_token.sub 不一致，忽略 userinfo');
          } else if (ui) {
            profile = { ...ui, ...claims };
          }
        }
      } catch (err) {
        logger.warn?.(`[oidc] userinfo 获取失败（忽略）：${err.message}`);
      }
    }

    return {
      claims,
      profile,
      accessToken: payload.access_token || null,
      refreshToken: payload.refresh_token || null,
      expiresIn: payload.expires_in || null,
      idToken: payload.id_token,
    };
  }

  /** 登出：RP-Initiated Logout（IdP 未提供该端点时返回 null，由调用方清 Cookie）。 */
  async function buildEndSessionUrl({ postLogoutRedirectUri, idTokenHint, state }) {
    let endpoints;
    try {
      endpoints = await getEndpoints();
    } catch {
      return null;
    }
    if (!endpoints.end_session_endpoint) return null;
    const url = new URL(endpoints.end_session_endpoint);
    if (postLogoutRedirectUri) url.searchParams.set('post_logout_redirect_uri', postLogoutRedirectUri);
    if (idTokenHint) url.searchParams.set('id_token_hint', idTokenHint);
    if (state) url.searchParams.set('state', state);
    return url.toString();
  }

  /** 预检：能否连通 IdP（管理后台“系统状态”展示用）。 */
  async function probe() {
    try {
      const endpoints = await getEndpoints();
      const res = await fetchWithTimeout(endpoints.jwks_uri, {
        headers: { accept: 'application/json' },
      });
      const doc = res.ok ? await readJson(res, endpoints.jwks_uri) : null;
      return {
        ok: res.ok,
        issuer: endpoints.issuer,
        endpoints: {
          authorization: endpoints.authorization_endpoint,
          token: endpoints.token_endpoint,
          jwks: endpoints.jwks_uri,
          userinfo: endpoints.userinfo_endpoint || null,
          endSession: endpoints.end_session_endpoint || null,
        },
        keyCount: Array.isArray(doc?.keys) ? doc.keys.length : 0,
        error: res.ok ? null : `HTTP ${res.status}`,
      };
    } catch (err) {
      return {
        ok: false,
        issuer: issuer || null,
        endpoints: null,
        keyCount: 0,
        error: err instanceof OidcError ? err.message : String(err?.message || err),
      };
    }
  }

  return {
    get issuer() {
      return issuer;
    },
    getEndpoints,
    buildAuthorizationUrl,
    exchangeCode,
    buildEndSessionUrl,
    probe,
    /** 测试/运维用：清空缓存 */
    clearCaches() {
      discoveryCache = { value: null, expiresAt: 0 };
      jwksCache = { value: null, expiresAt: 0 };
    },
    get cachedJwks() {
      return jwksCache.value;
    },
  };
}

export { verifyJwt, JwtError };
