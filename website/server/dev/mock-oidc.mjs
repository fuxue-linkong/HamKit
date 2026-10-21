/**
 * 内置 Mock OIDC 提供方 —— 仅用于本地开发与自动化测试。
 *
 * 实现了一个符合规范的迷你 IdP：
 *   GET  /.well-known/openid-configuration
 *   GET  /jwks
 *   GET  /authorize      → 返回一个选人页面（或带 login_hint 直接放行）
 *   POST /authorize       → 提交选人结果，302 回带 code & state
 *   POST /token           → 校验 PKCE，签发 RS256 的 id_token / access_token
 *   GET  /userinfo
 *   GET  /endsession
 *
 * 用它可以把「登录 → 会话 → 权限 → 审计」整条链路做成离线可跑、
 * 可断言端到端测试，不依赖任何外部身份服务。
 */

import http from 'node:http';
import crypto from 'node:crypto';

const USERS = [
  {
    sub: 'mock-admin-0001',
    name: '管理员 示例',
    preferred_username: 'admin',
    email: 'admin@example.com',
    email_verified: true,
    roles: ['hamkit-admin'],
  },
  {
    sub: 'mock-editor-0002',
    name: '编辑 示例',
    preferred_username: 'editor',
    email: 'editor@example.com',
    email_verified: true,
    roles: ['hamkit-editor'],
  },
  {
    sub: 'mock-viewer-0003',
    name: '访客 示例',
    preferred_username: 'viewer',
    email: 'viewer@example.com',
    email_verified: true,
    roles: ['hamkit-viewer'],
  },
  {
    sub: 'mock-outsider-0004',
    name: '外部用户',
    preferred_username: 'outsider',
    email: 'outsider@other-domain.test',
    email_verified: true,
    roles: [],
  },
];

function b64url(input) {
  return Buffer.from(input).toString('base64url');
}

/**
 * @param {object} [options]
 * @param {number} [options.port]      端口，0 表示随机
 * @param {string} [options.host]
 * @param {boolean} [options.quiet]
 * @param {number} [options.tokenTtlSec]
 * @param {string} [options.clientId]  仅用于展示，实际不做白名单限制（测试需要灵活）
 */
export async function createMockOidcProvider({
  port = 0,
  host = '127.0.0.1',
  quiet = false,
  tokenTtlSec = 600,
  users = USERS,
} = {}) {
  // 生成一次性 RSA 密钥（1024 位足够测试，生成快）
  const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const jwk = publicKey.export({ format: 'jwk' });
  const kid = crypto.createHash('sha256').update(JSON.stringify(jwk)).digest('base64url').slice(0, 16);

  /** @type {Map<string, object>} */
  const codes = new Map();
  /** @type {Map<string, object>} */
  const accessTokens = new Map();

  let issuer = '';
  const log = (...args) => {
    if (!quiet) console.log('[mock-oidc]', ...args);
  };

  function signIdToken({ user, clientId, nonce, now, authTime }) {
    const header = { alg: 'RS256', typ: 'JWT', kid };
    const payload = {
      iss: issuer,
      sub: user.sub,
      aud: clientId,
      azp: clientId,
      iat: now,
      exp: now + tokenTtlSec,
      auth_time: authTime,
      nonce,
      name: user.name,
      preferred_username: user.preferred_username,
      email: user.email,
      email_verified: user.email_verified,
      roles: user.roles,
    };
    const signingInput = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(payload))}`;
    const signature = crypto.sign('RSA-SHA256', Buffer.from(signingInput), privateKey);
    return `${signingInput}.${b64url(signature)}`;
  }

  function findUser(loginHint) {
    if (!loginHint) return null;
    const needle = String(loginHint).toLowerCase();
    return (
      users.find((u) => u.preferred_username.toLowerCase() === needle) ||
      users.find((u) => u.email.toLowerCase() === needle) ||
      users.find((u) => u.sub === loginHint) ||
      null
    );
  }

  function renderPicker(params) {
    const options = users
      .map(
        (u) =>
          `<li><button name="sub" value="${u.sub}">
             <strong>${u.preferred_username}</strong>
             <span>${u.name} · ${u.email} · roles=${JSON.stringify(u.roles)}</span>
           </button></li>`,
      )
      .join('');
    const hidden = [...params.entries()]
      .map(([k, v]) => `<input type="hidden" name="${escapeAttr(k)}" value="${escapeAttr(v)}">`)
      .join('');
    return `<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<title>Mock OIDC 选择身份</title>
<style>
 body{font-family:system-ui,'Microsoft YaHei',sans-serif;background:#0B0E14;color:#E6EDF3;margin:0;padding:40px}
 .box{max-width:640px;margin:0 auto;background:rgba(22,27,34,.7);border:1px solid rgba(255,255,255,.08);border-radius:16px;padding:28px}
 h1{font-size:20px;margin:0 0 4px}
 p{color:#7D8590;font-size:13px;margin:0 0 20px}
 ul{list-style:none;padding:0;margin:0;display:flex;flex-direction:column;gap:10px}
 button{width:100%;text-align:left;background:#11151D;border:1px solid rgba(255,255,255,.12);color:inherit;
        border-radius:10px;padding:14px 16px;cursor:pointer;font-size:14px;display:flex;flex-direction:column;gap:4px}
 button:hover{border-color:#4D8FFF;background:rgba(77,143,255,.12)}
 button span{color:#7D8590;font-size:12px}
 code{background:#11151D;padding:2px 6px;border-radius:6px;font-size:12px}
</style></head><body><div class="box">
<h1>Mock OIDC 提供方</h1>
<p>client_id=<code>${escapeAttr(params.get('client_id') || '')}</code> · scope=<code>${escapeAttr(params.get('scope') || '')}</code> · nonce=<code>${escapeAttr((params.get('nonce') || '').slice(0, 10))}…</code></p>
<form method="POST" action="/authorize">${hidden}<ul>${options}</ul></form>
</div></body></html>`;
  }

  function escapeAttr(value) {
    return String(value).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  function redirectWithError(res, redirectUri, state, error, description) {
    const url = new URL(redirectUri);
    url.searchParams.set('error', error);
    if (description) url.searchParams.set('error_description', description);
    if (state) url.searchParams.set('state', state);
    res.writeHead(302, { location: url.toString() });
    res.end();
  }

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, issuer || 'http://127.0.0.1');
    const path = url.pathname;

    if (path === '/.well-known/openid-configuration') {
      return json(res, {
        issuer,
        authorization_endpoint: `${issuer}/authorize`,
        token_endpoint: `${issuer}/token`,
        jwks_uri: `${issuer}/jwks`,
        userinfo_endpoint: `${issuer}/userinfo`,
        end_session_endpoint: `${issuer}/endsession`,
        response_types_supported: ['code'],
        subject_types_supported: ['public'],
        id_token_signing_alg_values_supported: ['RS256'],
        scopes_supported: ['openid', 'profile', 'email'],
        token_endpoint_auth_methods_supported: ['client_secret_basic', 'client_secret_post', 'none'],
        code_challenge_methods_supported: ['S256'],
      });
    }

    if (path === '/jwks') {
      return json(res, { keys: [{ ...jwk, kid, alg: 'RS256', use: 'sig' }] });
    }

    if (path === '/authorize' && req.method === 'GET') {
      const params = url.searchParams;
      const redirectUri = params.get('redirect_uri');
      const state = params.get('state');
      if (!redirectUri) return text(res, 400, '缺少 redirect_uri');
      if (params.get('response_type') !== 'code') {
        return redirectWithError(res, redirectUri, state, 'unsupported_response_type');
      }
      if (params.get('code_challenge_method') !== 'S256' || !params.get('code_challenge')) {
        return redirectWithError(res, redirectUri, state, 'invalid_request', '仅支持 PKCE S256');
      }
      const hinted = findUser(params.get('login_hint') || params.get('sub'));
      if (hinted) return issueCode(res, params, hinted);
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      return res.end(renderPicker(params));
    }

    if (path === '/authorize' && req.method === 'POST') {
      const body = await readForm(req);
      const params = new URLSearchParams(body);
      const user = users.find((u) => u.sub === params.get('sub'));
      if (!user) return text(res, 400, '未选择身份');
      return issueCode(res, params, user);
    }

    if (path === '/token' && req.method === 'POST') {
      const body = new URLSearchParams(await readForm(req));
      const grantType = body.get('grant_type');
      if (grantType !== 'authorization_code') {
        return json(res, { error: 'unsupported_grant_type' }, 400);
      }
      const record = codes.get(body.get('code'));
      if (!record) return json(res, { error: 'invalid_grant', error_description: 'code 无效或已使用' }, 400);
      codes.delete(body.get('code'));

      // PKCE 校验
      const verifier = body.get('code_verifier') || '';
      const challenge = b64url(crypto.createHash('sha256').update(verifier, 'ascii').digest());
      if (challenge !== record.codeChallenge) {
        return json(res, { error: 'invalid_grant', error_description: 'code_verifier 校验失败' }, 400);
      }
      if (body.get('redirect_uri') !== record.redirectUri) {
        return json(res, { error: 'invalid_grant', error_description: 'redirect_uri 不一致' }, 400);
      }

      const now = Math.floor(Date.now() / 1000);
      const accessToken = b64url(crypto.randomBytes(24));
      accessTokens.set(accessToken, { user: record.user, exp: now + tokenTtlSec });
      const idToken = signIdToken({
        user: record.user,
        clientId: record.clientId,
        nonce: record.nonce,
        now,
        authTime: record.authTime,
      });
      log(`签发令牌 sub=${record.user.sub} client=${record.clientId}`);
      return json(res, {
        access_token: accessToken,
        token_type: 'Bearer',
        expires_in: tokenTtlSec,
        id_token: idToken,
        scope: record.scope,
      });
    }

    if (path === '/userinfo') {
      const auth = String(req.headers.authorization || '');
      const token = auth.startsWith('Bearer ') ? auth.slice(7) : '';
      const record = accessTokens.get(token);
      if (!record || record.exp < Math.floor(Date.now() / 1000)) {
        return json(res, { error: 'invalid_token' }, 401);
      }
      const { user } = record;
      return json(res, {
        sub: user.sub,
        name: user.name,
        preferred_username: user.preferred_username,
        email: user.email,
        email_verified: user.email_verified,
        roles: user.roles,
      });
    }

    if (path === '/endsession') {
      const target = url.searchParams.get('post_logout_redirect_uri') || '/';
      res.writeHead(302, { location: target });
      return res.end();
    }

    return text(res, 404, `Mock IdP 无此端点：${path}`);
  });

  function issueCode(res, params, user) {
    const redirectUri = params.get('redirect_uri');
    const state = params.get('state');
    const code = b64url(crypto.randomBytes(24));
    codes.set(code, {
      user,
      clientId: params.get('client_id') || '',
      redirectUri,
      nonce: params.get('nonce') || null,
      codeChallenge: params.get('code_challenge') || '',
      scope: params.get('scope') || 'openid',
      authTime: Math.floor(Date.now() / 1000),
    });
    const target = new URL(redirectUri);
    target.searchParams.set('code', code);
    if (state) target.searchParams.set('state', state);
    log(`放行 sub=${user.sub} → ${redirectUri}`);
    res.writeHead(302, { location: target.toString() });
    res.end();
  }

  function json(res, value, status = 200) {
    const body = JSON.stringify(value);
    res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(body) });
    res.end(body);
  }

  function text(res, status, value) {
    res.writeHead(status, { 'content-type': 'text/plain; charset=utf-8' });
    res.end(value);
  }

  async function readForm(req) {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    return Buffer.concat(chunks).toString('utf8');
  }

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, resolve);
  });
  const address = server.address();
  issuer = `http://${host === '0.0.0.0' ? '127.0.0.1' : host}:${address.port}`;

  return {
    server,
    issuer,
    port: address.port,
    jwks: { keys: [{ ...jwk, kid, alg: 'RS256', use: 'sig' }] },
    users,
    async close() {
      server.closeAllConnections?.();
      server.closeIdleConnections?.();
      await new Promise((resolve) => server.close(resolve));
    },
  };
}

export { USERS as MOCK_USERS };
