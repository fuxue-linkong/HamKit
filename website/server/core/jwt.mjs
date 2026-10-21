/**
 * JWT 签发与校验 —— 仅使用 node:crypto，零第三方依赖。
 *
 * 支持算法：
 *   校验：RS256/384/512、PS256/384/512、ES256/384/512、EdDSA、HS256/384/512
 *   签发：HS256（本站会话 Cookie）、RS256（可选）
 *
 * 设计要点：
 *   - `verifyIdToken` 强制校验签名 + iss + aud + exp + nbf + iat，并按需校验 nonce。
 *   - ECDSA 的 JWS 签名是原始 (r||s) 定长拼接，不是 DER，必须用 ieee-p1363。
 *   - 所有比较（HMAC、nonce）走 timingSafeEqual，避免时序侧信道。
 */

import crypto from 'node:crypto';

const textEncoder = new TextEncoder();

/** 判断 token 的典型形态，避免把垃圾字符串当 JWT 解析。 */
export function looksLikeJwt(token) {
  return typeof token === 'string' && token.split('.').length === 3;
}

function b64urlToBuffer(segment) {
  return Buffer.from(segment, 'base64url');
}

function b64urlToJson(segment) {
  return JSON.parse(b64urlToBuffer(segment).toString('utf8'));
}

function bufferToB64url(buf) {
  return Buffer.from(buf).toString('base64url');
}

/**
 * 常量时间字符串比较。长度不同直接返回 false（长度本身不是秘密）。
 */
export function timingSafeEqualString(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const bufA = Buffer.from(a, 'utf8');
  const bufB = Buffer.from(b, 'utf8');
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

/** 算法族 → 该族允许的 JWK kty / alg，防止 alg 混淆攻击。 */
const ALG_RULES = {
  RS256: { kty: 'RSA', hash: 'RSA-SHA256', sigAlg: 'RSA-SHA256' },
  RS384: { kty: 'RSA', hash: 'RSA-SHA384', sigAlg: 'RSA-SHA384' },
  RS512: { kty: 'RSA', hash: 'RSA-SHA512', sigAlg: 'RSA-SHA512' },
  PS256: { kty: 'RSA', hash: 'RSA-SHA256', sigAlg: 'RSA-SHA256', pss: true, saltLength: 32 },
  PS384: { kty: 'RSA', hash: 'RSA-SHA384', sigAlg: 'RSA-SHA384', pss: true, saltLength: 48 },
  PS512: { kty: 'RSA', hash: 'RSA-SHA512', sigAlg: 'RSA-SHA512', pss: true, saltLength: 64 },
  ES256: { kty: 'EC', hash: 'RSA-SHA256', sigAlg: 'RSA-SHA256', dsaEncoding: 'ieee-p1363' },
  ES384: { kty: 'EC', hash: 'RSA-SHA384', sigAlg: 'RSA-SHA384', dsaEncoding: 'ieee-p1363' },
  ES512: { kty: 'EC', hash: 'RSA-SHA512', sigAlg: 'RSA-SHA512', dsaEncoding: 'ieee-p1363' },
  EdDSA: { kty: 'OKP', hash: null, sigAlg: null },
};

/** 对称算法：仅通过显式传入 `secret` 启用，绝不接受 JWK（防止 alg 混淆攻击）。 */
const HMAC_ALGS = { HS256: 'sha256', HS384: 'sha384', HS512: 'sha512' };

export const SUPPORTED_VERIFY_ALGS = Object.keys(ALG_RULES);

/** 默认放行的 ID Token 算法。对称算法默认禁止（避免 HS256 用公钥当密钥的经典绕过）。 */
export const DEFAULT_ALLOWED_ALGS = [
  'RS256', 'RS384', 'RS512',
  'PS256', 'PS384', 'PS512',
  'ES256', 'ES384', 'ES512',
  'EdDSA',
];

export class JwtError extends Error {
  constructor(message, code = 'invalid_token') {
    super(message);
    this.name = 'JwtError';
    this.code = code;
  }
}

/**
 * 用 node:crypto 校验签名。故意不接受 HS* 之外的对称算法当 JWK 使用。
 */
function verifySignature({ alg, data, signature, key }) {
  const rule = ALG_RULES[alg];
  if (!rule) throw new JwtError(`不支持的算法 ${alg}`, 'unsupported_alg');
  if (rule.kty === 'OKP') {
    // Ed25519：algorithm 传 null 表示直接用 key 的类型
    return crypto.verify(null, data, key, signature);
  }
  const options = {};
  if (rule.dsaEncoding) options.dsaEncoding = rule.dsaEncoding;
  if (rule.pss) {
    options.padding = crypto.constants.RSA_PKCS1_PSS_PADDING;
    options.saltLength = rule.saltLength;
  }
  return crypto.verify(rule.hash, data, { key, ...options }, signature);
}

/** 把 JWK 转成 KeyObject；拒绝对称密钥与不匹配的 kty。 */
export function jwkToPublicKey(jwk, alg) {
  const rule = ALG_RULES[alg];
  if (!rule) throw new JwtError(`不支持的算法 ${alg}`, 'unsupported_alg');
  if (!jwk || typeof jwk !== 'object') throw new JwtError('JWK 无效', 'invalid_jwk');
  if (jwk.kty === 'oct') throw new JwtError('拒绝使用对称密钥校验 ID Token', 'invalid_jwk');
  if (jwk.kty !== rule.kty) {
    throw new JwtError(`JWK kty=${jwk.kty} 与算法 ${alg} 不匹配`, 'invalid_jwk');
  }
  try {
    return crypto.createPublicKey({ key: jwk, format: 'jwk' });
  } catch (err) {
    throw new JwtError(`JWK 解析失败：${err.message}`, 'invalid_jwk');
  }
}

/** RFC 7638 JWK 指纹，用于在多个候选密钥里定位 kid。 */
export function jwkThumbprint(jwk) {
  let canonical;
  if (jwk.kty === 'RSA') {
    canonical = { e: jwk.e, kty: jwk.kty, n: jwk.n };
  } else if (jwk.kty === 'EC') {
    canonical = { crv: jwk.crv, kty: jwk.kty, x: jwk.x, y: jwk.y };
  } else if (jwk.kty === 'OKP') {
    canonical = { crv: jwk.crv, kty: jwk.kty, x: jwk.x };
  } else {
    throw new JwtError(`无法计算 kty=${jwk.kty} 的指纹`, 'invalid_jwk');
  }
  return bufferToB64url(
    crypto.createHash('sha256').update(JSON.stringify(canonical)).digest(),
  );
}

/** 从 JWKS 里选出候选密钥（kid 优先，缺失时按 kty 全量尝试）。 */
export function selectJwksCandidates(jwks, { kid, alg }) {
  const keys = Array.isArray(jwks?.keys) ? jwks.keys : [];
  const rule = ALG_RULES[alg];
  const usable = keys.filter((k) => {
    if (!k || k.use === 'enc') return false;
    if (rule && k.kty !== rule.kty) return false;
    // IdP 可能在 JWK 上标注 alg，标注了就必须一致
    if (k.alg && k.alg !== alg) return false;
    return true;
  });
  if (kid) {
    const byKid = usable.filter((k) => k.kid === kid);
    if (byKid.length) return byKid;
    // kid 对不上时退回指纹匹配（部分 IdP 不发 kid）
    const byPrint = usable.filter((k) => {
      try {
        return jwkThumbprint(k) === kid;
      } catch {
        return false;
      }
    });
    if (byPrint.length) return byPrint;
    return [];
  }
  return usable;
}

/**
 * 校验 ID Token / 任意 JWS。
 *
 * @param {object} options
 * @param {string} options.token          紧凑序列化 JWT
 * @param {(header: object, payload: object) => Promise<object[]>|object[]} options.getKeys
 *        返回候选 JWK 列表的回调。实现方可做 JWKS 缓存与轮换刷新：
 *        首次返回缓存，验签失败时返回强制刷新的列表。
 * @param {string|string[]} options.issuer   期望的 iss（可给多个）
 * @param {string|string[]} [options.audience] 期望的 aud
 * @param {string} [options.nonce]         期望的 nonce（OIDC 必须校验）
 * @param {string} [options.azp]           期望的 azp（多受众场景）
 * @param {string[]} [options.allowedAlgs] 允许的算法
 * @param {number} [options.clockToleranceSec=60]
 * @param {number} [options.maxAgeSec]     如提供，则校验 auth_time 的新鲜度
 * @param {number} [options.now]           当前时间（测试注入用）
 * @returns {Promise<object>} 校验通过的 payload
 */
export async function verifyJwt({
  token,
  getKeys,
  secret,
  issuer,
  audience,
  nonce,
  azp,
  allowedAlgs = DEFAULT_ALLOWED_ALGS,
  clockToleranceSec = 60,
  maxAgeSec,
  now = Date.now(),
}) {
  if (!looksLikeJwt(token)) throw new JwtError('不是合法的 JWT', 'malformed');

  const [headerSeg, payloadSeg, signatureSeg] = token.split('.');
  let header;
  let payload;
  try {
    header = b64urlToJson(headerSeg);
    payload = b64urlToJson(payloadSeg);
  } catch {
    throw new JwtError('JWT 头部或载荷不是合法 JSON', 'malformed');
  }

  const alg = header.alg;
  if (!alg || alg === 'none') throw new JwtError('拒绝 alg=none', 'unsupported_alg');
  if (!allowedAlgs.includes(alg)) {
    throw new JwtError(`算法 ${alg} 不在允许列表内`, 'unsupported_alg');
  }
  const isHmac = Boolean(HMAC_ALGS[alg]);
  if (isHmac && !secret) {
    throw new JwtError(`${alg} 需要显式提供 secret`, 'unsupported_alg');
  }
  if (!isHmac && !ALG_RULES[alg]) throw new JwtError(`算法 ${alg} 不受支持`, 'unsupported_alg');
  if (header.crit) throw new JwtError('不支持 crit 扩展', 'unsupported_alg');

  // 时间声明校验
  const nowSec = Math.floor(now / 1000);
  const skew = Number(clockToleranceSec) || 0;
  if (typeof payload.exp !== 'number') throw new JwtError('缺少 exp', 'invalid_claims');
  if (nowSec - skew >= payload.exp) throw new JwtError('令牌已过期', 'expired');
  if (typeof payload.iat === 'number' && payload.iat - skew > nowSec) {
    throw new JwtError('iat 在未来', 'invalid_claims');
  }
  if (typeof payload.nbf === 'number' && payload.nbf - skew > nowSec) {
    throw new JwtError('令牌尚未生效', 'invalid_claims');
  }
  if (typeof maxAgeSec === 'number') {
    if (typeof payload.auth_time !== 'number') {
      throw new JwtError('要求 max_age 但缺少 auth_time', 'invalid_claims');
    }
    if (nowSec - payload.auth_time - skew > maxAgeSec) {
      throw new JwtError('登录时间过旧，需重新认证', 'reauth_required');
    }
  }

  // iss 校验
  if (issuer) {
    const allowed = Array.isArray(issuer) ? issuer : [issuer];
    if (!allowed.includes(payload.iss)) {
      throw new JwtError(`iss 不匹配：${payload.iss}`, 'invalid_claims');
    }
  }

  // aud 校验（aud 可以是字符串或数组）
  if (audience) {
    const allowed = Array.isArray(audience) ? audience : [audience];
    const aud = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
    const hit = aud.some((a) => allowed.includes(a));
    if (!hit) throw new JwtError('aud 不匹配', 'invalid_claims');
    // 多受众时必须声明 azp
    if (aud.length > 1) {
      if (typeof payload.azp !== 'string') {
        throw new JwtError('多受众令牌缺少 azp', 'invalid_claims');
      }
      if (!allowed.includes(payload.azp)) {
        throw new JwtError('azp 不匹配', 'invalid_claims');
      }
    }
    if (azp && payload.azp && payload.azp !== azp) {
      throw new JwtError('azp 不匹配', 'invalid_claims');
    }
  }

  // nonce 校验（防重放）
  if (nonce !== undefined) {
    if (typeof payload.nonce !== 'string' || !timingSafeEqualString(payload.nonce, nonce)) {
      throw new JwtError('nonce 不匹配', 'invalid_claims');
    }
  }

  // 签名校验：HMAC 用显式 secret；非对称走 getKeys（可能返回“旧列表 → 刷新后列表”）
  const signingInput = `${headerSeg}.${payloadSeg}`;
  const signingBytes = textEncoder.encode(signingInput);
  const signature = b64urlToBuffer(signatureSeg);

  if (isHmac) {
    const expected = crypto.createHmac(HMAC_ALGS[alg], secret).update(signingInput).digest();
    if (!crypto.timingSafeEqual(expected, signature)) {
      throw new JwtError('签名校验失败', 'bad_signature');
    }
    return payload;
  }

  if (typeof getKeys !== 'function') {
    throw new JwtError('非对称算法校验需要 getKeys 回调', 'unsupported_alg');
  }

  const keySets = await getKeys(header, payload);
  // 归一化：允许返回单个 JWKS（{ keys: [...] }）或 JWKS 列表（用于“旧缓存 + 刷新后”双试）
  const sets = Array.isArray(keySets) ? keySets : [keySets];

  let lastError = null;
  for (const jwks of sets) {
    const candidates = selectJwksCandidates(jwks, { kid: header.kid, alg });
    for (const jwk of candidates) {
      try {
        const key = jwkToPublicKey(jwk, alg);
        if (verifySignature({ alg, data: signingBytes, signature, key })) {
          return payload;
        }
      } catch (err) {
        lastError = err;
      }
    }
  }
  throw new JwtError(
    lastError ? `签名校验失败：${lastError.message}` : '签名校验失败：找不到匹配的密钥',
    'bad_signature',
  );
}

/**
 * 签发 JWT（本站会话 Cookie 使用 HS256；也支持 RS256 私钥）。
 * 用于会话令牌，不用于 ID Token。
 */
export function signJwt(payload, { alg = 'HS256', secret, privateKey, header = {} } = {}) {
  const now = Math.floor(Date.now() / 1000);
  const body = { iat: now, ...payload };
  const headerOut = { alg, typ: 'JWT', ...header };
  const signingInput = `${bufferToB64url(JSON.stringify(headerOut))}.${bufferToB64url(JSON.stringify(body))}`;

  let signature;
  if (alg === 'HS256' || alg === 'HS384' || alg === 'HS512') {
    if (!secret) throw new JwtError('HS* 签发需要 secret', 'sign_error');
    const hash = alg.replace('HS', 'sha');
    signature = crypto.createHmac(hash, secret).update(signingInput).digest();
  } else if (ALG_RULES[alg]) {
    if (!privateKey) throw new JwtError(`${alg} 签发需要 privateKey`, 'sign_error');
    const rule = ALG_RULES[alg];
    const options = {};
    if (rule.dsaEncoding) options.dsaEncoding = rule.dsaEncoding;
    if (rule.pss) {
      options.padding = crypto.constants.RSA_PKCS1_PSS_PADDING;
      options.saltLength = rule.saltLength;
    }
    signature = crypto.sign(rule.hash, Buffer.from(signingInput), { key: privateKey, ...options });
  } else {
    throw new JwtError(`不支持的签发算法 ${alg}`, 'sign_error');
  }

  return `${signingInput}.${bufferToB64url(signature)}`;
}

/** 不验签地解码（仅用于日志排查 / 测试断言，禁止用于鉴权决策）。 */
export function decodeJwtUnsafe(token) {
  if (!looksLikeJwt(token)) return null;
  const [h, p] = token.split('.');
  try {
    return { header: b64urlToJson(h), payload: b64urlToJson(p) };
  } catch {
    return null;
  }
}
