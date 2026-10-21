/**
 * 配置加载：环境变量 + 可选 .env 文件。
 * 全部配置集中在 `loadConfig()` 返回的对象上，测试可注入覆盖值。
 *
 * 必填（生产环境）：
 *   OIDC_ISSUER, OIDC_CLIENT_ID, SESSION_SECRET
 * 强烈建议：
 *   BASE_URL（用于拼 OIDC redirect_uri，必须是外网可访问的绝对地址）
 *   OIDC_CLIENT_SECRET（公共客户端/PKCE-only 可留空）
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * 站点根目录 = website/（config.mjs 位于 website/server/config.mjs）。
 * 这里曾把根解析成仓库根目录，导致所有页面与 /assets/* 静默 404，
 * 因此用标记文件校验并在不一致时显式报错，而不是让它变成一个难查的 404。
 */
function detectWebsiteRoot() {
    const candidates = [path.resolve(__dirname, '..'), path.resolve(__dirname, '..', '..')];
    for (const candidate of candidates) {
        try {
            if (
                fs.existsSync(path.join(candidate, 'index.html')) &&
                fs.existsSync(path.join(candidate, 'package.json')) &&
                fs.existsSync(path.join(candidate, 'server'))
            ) {
                return candidate;
            }
        } catch {
            /* 尝试下一个候选 */
        }
    }
    return candidates[0];
}

export const WEBSITE_ROOT = detectWebsiteRoot();
export const WEBSITE_ROOT_CHECK = {
    root: WEBSITE_ROOT,
    hasIndex: fs.existsSync(path.join(WEBSITE_ROOT, 'index.html')),
    hasPackageJson: fs.existsSync(path.join(WEBSITE_ROOT, 'package.json')),
    hasPublicDir: fs.existsSync(path.join(WEBSITE_ROOT, 'public')),
};

/** 极简 .env 解析（不覆盖已存在的真实环境变量）。 */
export function loadDotEnv(file = path.join(WEBSITE_ROOT, '.env'), env = process.env) {
  const applied = [];
  let text = '';
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch {
    return applied;
  }
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq < 0) continue;
    const key = line.slice(0, eq).trim();
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) continue;
    let value = line.slice(eq + 1).trim();
    // 去掉成对引号，其余原样保留
    if (
      (value.startsWith('"') && value.endsWith('"') && value.length > 1) ||
      (value.startsWith("'") && value.endsWith("'") && value.length > 1)
    ) {
      value = value.slice(1, -1);
    }
    if (env[key] === undefined) {
      env[key] = value;
      applied.push(key);
    }
  }
  return applied;
}

function bool(value, fallback = false) {
  if (value === undefined || value === null || value === '') return fallback;
  return ['1', 'true', 'yes', 'on', 'y'].includes(String(value).toLowerCase());
}

function int(value, fallback) {
  const n = Number.parseInt(value, 10);
  return Number.isFinite(n) ? n : fallback;
}

function list(value) {
  if (!value) return [];
  return String(value)
    .split(/[,;\s]+/)
    .map((s) => s.trim())
    .filter(Boolean);
}

/** 用户名 → 角色的映射，形如 `alice:admin,bob:editor,carol:viewer`。 */
function roleMap(value) {
  const out = {};
  for (const pair of list(value)) {
    const [key, role] = pair.split(':').map((s) => s?.trim());
    if (key && role) out[key.toLowerCase()] = role;
  }
  return out;
}

const DEFAULT_SESSION_SECRET_FILE = '.session-secret';

/**
 * 读取或生成会话密钥。
 * 生产环境必须显式配置 SESSION_SECRET；开发环境退化为落盘随机值（重启保持登录态）。
 */
export function resolveSessionSecret({ explicit, dataDir, allowGenerate = true }) {
  if (explicit) return { secret: explicit, generated: false };
  if (!allowGenerate) return { secret: null, generated: false };
  const file = path.join(dataDir, DEFAULT_SESSION_SECRET_FILE);
  try {
    const existing = fs.readFileSync(file, 'utf8').trim();
    if (existing.length >= 32) return { secret: existing, generated: false };
  } catch {
    /* 文件不存在，走生成 */
  }
  const secret = crypto.randomBytes(48).toString('base64url');
  fs.mkdirSync(dataDir, { recursive: true });
  fs.writeFileSync(file, secret, { mode: 0o600 });
  return { secret, generated: true };
}

/**
 * 组装配置。
 * @param {NodeJS.ProcessEnv} env
 * @param {{ dataDir?: string, allowSecretGeneration?: boolean }} [overrides]
 */
export function loadConfig(env = process.env, overrides = {}) {
  const dataDir = path.resolve(
    overrides.dataDir || env.DATA_DIR || path.join(WEBSITE_ROOT, 'data'),
  );
  const port = int(env.PORT, 8787);
  const host = env.HOST || '0.0.0.0';
  const baseUrl = (env.BASE_URL || `http://localhost:${port}`).replace(/\/+$/, '');

  const oidcIssuer = (env.OIDC_ISSUER || '').replace(/\/+$/, '');
  const oidcClientId = env.OIDC_CLIENT_ID || '';

  const { secret, generated } = resolveSessionSecret({
    explicit: env.SESSION_SECRET,
    dataDir,
    allowGenerate: overrides.allowSecretGeneration !== false,
  });

  const config = {
    env: env.NODE_ENV || 'development',
    isProduction: (env.NODE_ENV || 'development') === 'production',
    port,
    host,
    baseUrl,
    /** 反向代理层数；>0 时信任 X-Forwarded-* 头 */
    trustProxy: int(env.TRUST_PROXY, 0),
    dataDir,
    websiteRoot: WEBSITE_ROOT,
    publicDir: path.join(WEBSITE_ROOT, 'public'),
    cookie: {
      name: env.SESSION_COOKIE_NAME || 'hamkit_session',
      /** HTTPS 部署时必须为 true（默认按 BASE_URL 协议推断） */
      secure: bool(env.COOKIE_SECURE, baseUrl.startsWith('https://')),
      sameSite: env.COOKIE_SAMESITE || 'Lax',
      domain: env.COOKIE_DOMAIN || undefined,
      maxAgeSec: int(env.SESSION_TTL_HOURS, 8) * 3600,
    },
    oidc: {
      issuer: oidcIssuer,
      clientId: oidcClientId,
      clientSecret: env.OIDC_CLIENT_SECRET || '',
      clientAuthMethod: env.OIDC_CLIENT_AUTH_METHOD || undefined,
      scope: env.OIDC_SCOPE || 'openid profile email',
      discoveryUrl: env.OIDC_DISCOVERY_URL || undefined,
      authorizationEndpoint: env.OIDC_AUTHORIZATION_ENDPOINT || undefined,
      tokenEndpoint: env.OIDC_TOKEN_ENDPOINT || undefined,
      jwksUri: env.OIDC_JWKS_URI || undefined,
      userinfoEndpoint: env.OIDC_USERINFO_ENDPOINT || undefined,
      endSessionEndpoint: env.OIDC_END_SESSION_ENDPOINT || undefined,
      allowedAlgs: list(env.OIDC_ALLOWED_ALGS),
      cacheTtlSec: int(env.OIDC_CACHE_TTL_SEC, 600),
      jwksCooldownSec: int(env.OIDC_JWKS_COOLDOWN_SEC, 30),
      clockToleranceSec: int(env.OIDC_CLOCK_TOLERANCE_SEC, 60),
      useUserInfo: bool(env.OIDC_USE_USERINFO, true),
      /** 传给 IdP 的额外授权参数，形如 `kc_idp_hint=github,prompt=select_account` */
      extraAuthParams: Object.fromEntries(
        list(env.OIDC_EXTRA_AUTH_PARAMS)
          .map((p) => p.split('='))
          .filter((p) => p.length === 2)
          .map(([k, v]) => [k.trim(), v.trim()]),
      ),
      /** IdP 声明的角色/组 → 本站角色的映射 */
      roleClaim: env.OIDC_ROLE_CLAIM || 'roles',
      roleMap: roleMap(env.OIDC_ROLE_MAP),
    },
    auth: {
      /** 首个登录者自动成为管理员（仅在用户库为空时生效） */
      firstUserIsAdmin: bool(env.FIRST_USER_IS_ADMIN, false),
      /** 只有这些邮箱/用户名能成为管理员 */
      bootstrapAdmins: list(env.BOOTSTRAP_ADMINS).map((s) => s.toLowerCase()),
      /** 邮箱域名白名单，非空时仅允许这些域名的账号登录 */
      allowedEmailDomains: list(env.ALLOWED_EMAIL_DOMAINS).map((s) => s.toLowerCase()),
      /** 是否允许 viewer 角色登录后台（false 则仅 admin/editor 可进） */
      allowViewerLogin: bool(env.ALLOW_VIEWER_LOGIN, true),
      /**
       * 要求 admin/editor 的 OIDC 登录带 MFA 声明（amr 包含 mfa/otp/hwk，或 acr 含 mfa/loa2/loa3）。
       * 默认关闭以免锁死未配置 MFA 的 IdP；生产环境建议开启。
       */
      requireMfa: bool(env.OIDC_REQUIRE_MFA, false),
    },
    session: {
      secret,
      secretGenerated: generated,
      registryFile: 'sessions',
    },
    security: {
      /** 管理 API 的速率限制（每窗口每 IP 的请求数） */
      rateLimitWindowSec: int(env.RATE_LIMIT_WINDOW_SEC, 60),
      rateLimitMax: int(env.RATE_LIMIT_MAX, 240),
      loginRateLimitMax: int(env.LOGIN_RATE_LIMIT_MAX, 30),
      hsts: bool(env.ENABLE_HSTS, baseUrl.startsWith('https://')),
    },
    stats: {
      enabled: bool(env.STATS_ENABLED, true),
      /** 统计事件保留天数 */
      retentionDays: int(env.STATS_RETENTION_DAYS, 180),
      /** 后端解析出的真实 IP 是否入库（隐私考虑可关闭） */
      storeIp: bool(env.STATS_STORE_IP, false),
    },
    logLevel: env.LOG_LEVEL || (bool(env.DEBUG) ? 'debug' : 'info'),
    /**
     * 开发直登：本地没有 IdP 时用 /auth/dev-login 直接进后台。
     * 生产环境（NODE_ENV=production）无条件关闭，即使显式打开也只记录告警。
     */
    devLoginRequested: bool(env.DEV_LOGIN, false),
    devLoginEnabled:
      (env.NODE_ENV || 'development') !== 'production' && bool(env.DEV_LOGIN, false),
  };

  Object.defineProperty(config, 'isHttps', {
    enumerable: false,
    configurable: true,
    get() {
      return String(config.baseUrl).startsWith('https://');
    },
  });
  // 派生量全部做成 getter：测试/嵌入式场景改 baseUrl 后自动跟随
  Object.defineProperty(config, 'oidcRedirectUri', {
    enumerable: false,
    configurable: true,
    get() {
      return `${config.baseUrl}/auth/callback`;
    },
  });
  Object.defineProperty(config, 'postLogoutRedirectUri', {
    enumerable: false,
    configurable: true,
    get() {
      return `${config.baseUrl}/`;
    },
  });
  Object.defineProperty(config, 'oidcConfigured', {
    value: Boolean(
      (oidcIssuer || config.oidc.discoveryUrl || config.oidc.authorizationEndpoint) &&
        oidcClientId,
    ),
    enumerable: false,
  });

  return config;
}

/** 配置自检：返回问题列表（启动时打印，缺失关键项时页面给出引导）。 */
export function validateConfig(config) {
  const problems = [];
  const warnings = [];

  if (!config.oidc.issuer && !config.oidc.discoveryUrl) {
    if (!config.oidc.authorizationEndpoint || !config.oidc.tokenEndpoint || !config.oidc.jwksUri) {
      problems.push(
        '缺少 OIDC 配置：请设置 OIDC_ISSUER（推荐）或同时设置 OIDC_AUTHORIZATION_ENDPOINT / OIDC_TOKEN_ENDPOINT / OIDC_JWKS_URI',
      );
    }
  }
  if (!config.oidc.clientId) problems.push('缺少 OIDC_CLIENT_ID');
  if (!config.session.secret) problems.push('缺少 SESSION_SECRET');
  if (config.isProduction && config.session.secretGenerated) {
    warnings.push('SESSION_SECRET 未显式配置，已落盘随机生成；多实例部署必须显式配置同一密钥');
  }
  if (config.isProduction && !config.isHttps) {
    warnings.push(`BASE_URL 不是 https（${config.baseUrl}），生产环境应使用 HTTPS`);
  }
  if (config.isProduction && config.oidc.clientSecret && config.oidc.clientAuthMethod === undefined) {
    warnings.push('已配置 OIDC_CLIENT_SECRET，默认使用 client_secret_basic；若 IdP 要求 client_secret_post 请设置 OIDC_CLIENT_AUTH_METHOD');
  }
  if (config.trustProxy === 0 && config.isProduction) {
    warnings.push('未设置 TRUST_PROXY，反向代理后客户端 IP 会记录成代理地址');
  }
  if (config.isProduction && config.devLoginRequested) {
    warnings.push('生产环境已忽略 DEV_LOGIN=true，开发直登不可用（这是预期行为）');
  }
  if (config.isProduction && config.auth.firstUserIsAdmin) {
    warnings.push('FIRST_USER_IS_ADMIN=true：首个登录者会成为管理员，存在抢先接管风险；初始化后请改为 false');
  }
  if (!config.auth.firstUserIsAdmin && config.auth.bootstrapAdmins.length === 0) {
    const hasRoleMap = Object.keys(config.oidc.roleMap || {}).length > 0;
    if (!hasRoleMap) {
      warnings.push('未配置 BOOTSTRAP_ADMINS 且 OIDC_ROLE_MAP 为空：可能没有任何管理员可以登录后台');
    }
  }
  if (config.isProduction && !config.auth.requireMfa) {
    warnings.push('OIDC_REQUIRE_MFA=false：后台管理员未强制 MFA；建议在 IdP 开启 MFA 后设为 true');
  }
  return { problems, warnings, ok: problems.length === 0 };
}
