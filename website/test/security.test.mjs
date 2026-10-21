/**
 * 安全测试：JWT 算法混淆、Cookie 伪造、路径穿越、敏感文件、安全响应头、限流。
 */

import { test, before, after, describe } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';

import { startTestEnv, createClient, loginAs } from './helpers.mjs';
import { hasMfaClaim } from '../server/core/auth.mjs';
import { verifyJwt, signJwt, JwtError } from '../server/core/jwt.mjs';
import { createMockOidcProvider } from '../server/dev/mock-oidc.mjs';

function b64url(value) {
  return Buffer.from(value).toString('base64url');
}

describe('MFA 声明识别', () => {
  test('amr 包含 otp/webauthn 视为 MFA', () => {
    assert.equal(hasMfaClaim({ amr: ['pwd', 'otp'] }), true);
    assert.equal(hasMfaClaim({ amr: ['pwd', 'webauthn'] }), true);
  });

  test('acr 包含 loa2/loa3/aal2 视为 MFA', () => {
    assert.equal(hasMfaClaim({ acr: 'loa2' }), true);
    assert.equal(hasMfaClaim({ acr: 'urn:example:aal2' }), true);
  });

  test('只有密码/空 claims 不视为 MFA', () => {
    assert.equal(hasMfaClaim({ amr: ['pwd'] }), false);
    assert.equal(hasMfaClaim({}), false);
    assert.equal(hasMfaClaim(null), false);
  });
});

describe('JWT 校验', () => {
  let mock;
  before(async () => {
    mock = await createMockOidcProvider({ quiet: true });
  });
  after(async () => {
    await mock.close();
  });

  test('alg=none 一律拒绝', async () => {
    const header = b64url(JSON.stringify({ alg: 'none', typ: 'JWT' }));
    const payload = b64url(JSON.stringify({ sub: 'attacker', exp: Math.floor(Date.now() / 1000) + 600 }));
    await assert.rejects(
      () => verifyJwt({ token: `${header}.${payload}.`, getKeys: () => [mock.jwks] }),
      (err) => err instanceof JwtError && err.code === 'unsupported_alg',
    );
  });

  test('把 RSA 公钥当 HMAC 密钥的算法混淆攻击被拒绝', async () => {
    // 攻击者用 JWKS 里的公钥当 HS256 密钥签名，期望校验方误用对称算法
    const publicKeyPem = crypto
      .createPublicKey({ key: mock.jwks.keys[0], format: 'jwk' })
      .export({ type: 'spki', format: 'pem' });
    const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT', kid: mock.jwks.keys[0].kid }));
    const payload = b64url(JSON.stringify({ sub: 'attacker', exp: Math.floor(Date.now() / 1000) + 600 }));
    const signature = b64url(crypto.createHmac('sha256', publicKeyPem).update(`${header}.${payload}`).digest());
    await assert.rejects(
      () => verifyJwt({ token: `${header}.${payload}.${signature}`, getKeys: () => [mock.jwks] }),
      (err) => err.code === 'unsupported_alg',
    );
  });

  test('未显式提供 secret 时 HS256 不可用', async () => {
    const token = signJwt({ sub: 'x' }, { alg: 'HS256', secret: 'whatever' });
    await assert.rejects(
      () => verifyJwt({ token, getKeys: () => [mock.jwks], allowedAlgs: ['HS256', 'RS256'] }),
      (err) => err.code === 'unsupported_alg',
    );
  });

  test('过期令牌被拒绝（expired）', async () => {
    const token = signJwt(
      { sub: 'x', exp: Math.floor(Date.now() / 1000) - 10 },
      { alg: 'HS256', secret: 'secret' },
    );
    await assert.rejects(
      () => verifyJwt({ token, secret: 'secret', allowedAlgs: ['HS256'], clockToleranceSec: 0 }),
      (err) => err.code === 'expired',
    );
  });

  test('iss 不匹配被拒绝', async () => {
    const token = signJwt({ sub: 'x', iss: 'https://evil.example.com' }, { alg: 'HS256', secret: 'secret' });
    await assert.rejects(
      () => verifyJwt({ token, secret: 'secret', allowedAlgs: ['HS256'], issuer: 'https://good.example.com' }),
      (err) => err.code === 'invalid_claims',
    );
  });

  test('aud 不匹配被拒绝', async () => {
    const token = signJwt({ sub: 'x', aud: 'other-client' }, { alg: 'HS256', secret: 'secret' });
    await assert.rejects(
      () => verifyJwt({ token, secret: 'secret', allowedAlgs: ['HS256'], audience: 'my-client' }),
      (err) => err.code === 'invalid_claims',
    );
  });

  test('nonce 不匹配被拒绝（重放防护）', async () => {
    const token = signJwt({ sub: 'x', nonce: 'expected-nonce' }, { alg: 'HS256', secret: 'secret' });
    await assert.rejects(
      () => verifyJwt({ token, secret: 'secret', allowedAlgs: ['HS256'], nonce: 'other-nonce' }),
      (err) => err.code === 'invalid_claims',
    );
  });

  test('签名被篡改被拒绝', async () => {
    const token = signJwt(
      { sub: 'x', exp: Math.floor(Date.now() / 1000) + 600 },
      { alg: 'HS256', secret: 'secret' },
    );
    const forged = token.slice(0, -3) + 'AAA';
    await assert.rejects(
      () => verifyJwt({ token: forged, secret: 'secret', allowedAlgs: ['HS256'] }),
      (err) => err.code === 'bad_signature',
    );
  });

  test('格式错误的令牌被拒绝（malformed）', async () => {
    await assert.rejects(
      () => verifyJwt({ token: 'not-a-jwt', secret: 'secret', allowedAlgs: ['HS256'] }),
      (err) => err.code === 'malformed',
    );
    await assert.rejects(
      () => verifyJwt({ token: 'aaa.bbb.ccc', secret: 'secret', allowedAlgs: ['HS256'] }),
      (err) => err.code === 'malformed',
    );
  });
});

describe('会话与 Cookie 安全', () => {
  let env;
  before(async () => {
    env = await startTestEnv();
  });
  after(async () => {
    await env.close();
  });

  test('伪造的会话 Cookie 被拒绝', async () => {
    const client = createClient(env.url);
    client.setCookie('hamkit_session', signJwt({ sub: 'someone', sid: 'x', role: 'admin' }, { alg: 'HS256', secret: 'wrong-secret' }));
    const res = await client.get('/api/session');
    assert.equal(res.body.authenticated, false);
    assert.equal(res.body.reason, 'invalid');
  });

  test('篡改 Cookie 里的角色不影响服务端判定', async (t) => {
    const victim = createClient(env.url);
    await loginAs(victim, 'viewer');
    const token = victim.jar.get('hamkit_session');
    // 把载荷里的 viewer 改成 admin，签名随即失效
    const [h, p, s] = token.split('.');
    const payload = JSON.parse(Buffer.from(p, 'base64url').toString());
    payload.role = 'admin';
    const forged = `${h}.${b64url(JSON.stringify(payload))}.${s}`;
    victim.clearCookies();
    victim.setCookie('hamkit_session', forged);
    const res = await victim.get('/api/session');
    assert.equal(res.body.authenticated, false, '改载荷必须导致签名校验失败');
  });

  test('登录后 CSRF Cookie 对 JS 可见、会话 Cookie 不可见', async () => {
    const client = createClient(env.url);
    const res = await client.raw('GET', '/auth/dev-login?role=admin', { redirect: 'manual' });
    const cookies = res.headers.getSetCookie();
    const session = cookies.find((c) => c.startsWith('hamkit_session='));
    const csrf = cookies.find((c) => c.startsWith('hamkit_csrf='));
    assert.match(session, /HttpOnly/, '会话 Cookie 必须 HttpOnly');
    assert.ok(!/HttpOnly/.test(csrf), 'CSRF Cookie 需要被前端读取，不能 HttpOnly');
  });

  test('未登录时写接口返回 401 而不是 403（便于前端跳登录）', async () => {
    const client = createClient(env.url);
    const res = await client.post('/api/versions', { versionCode: 1, versionName: '1.0.0' });
    assert.equal(res.status, 401);
  });
});

describe('静态资源与路径安全', () => {
  let env;
  before(async () => {
    env = await startTestEnv();
  });
  after(async () => {
    await env.close();
  });

  const forbidden = [
    '/.env',
    '/.session-secret',
    '/server/index.js',
    '/server/config.mjs',
    '/package.json',
    '/data/versions.json',
    '/test/helpers.mjs',
    '/build/build-pages.js',
  ];

  for (const path of forbidden) {
    test(`GET ${path} 不可访问`, async () => {
      const client = createClient(env.url);
      const res = await client.raw('GET', path, { redirect: 'manual' });
      assert.equal(res.status, 404, `${path} 不应被公开`);
    });
  }

  test('路径穿越尝试被拒绝', async () => {
    const client = createClient(env.url);
    for (const attempt of [
      '/../package.json',
      '/..%2fpackage.json',
      '/images/../../package.json',
      '/%2e%2e/%2e%2e/etc/passwd',
      '/....//package.json',
    ]) {
      const res = await client.raw('GET', attempt, { redirect: 'manual' });
      assert.ok([400, 404].includes(res.status), `${attempt} 应被拒绝，实际 ${res.status}`);
    }
  });

  test('未注册路径返回 404（JSON 请求给 JSON 错误）', async () => {
    const client = createClient(env.url);
    const res = await client.get('/api/nope');
    assert.equal(res.status, 404);
    assert.equal(res.body.code, 'not_found');
  });

  test('方法不匹配返回 405 并带 Allow 头', async () => {
    const client = createClient(env.url);
    const res = await client.raw('DELETE', '/api/health', { redirect: 'manual' });
    assert.equal(res.status, 405);
    assert.match(res.headers.get('allow'), /GET/);
  });

  test('所有响应都带安全响应头', async () => {
    const client = createClient(env.url);
    const res = await client.raw('GET', '/api/health');
    assert.equal(res.headers.get('x-content-type-options'), 'nosniff');
    assert.equal(res.headers.get('x-frame-options'), 'DENY');
    assert.equal(res.headers.get('referrer-policy'), 'strict-origin-when-cross-origin');
    const csp = res.headers.get('content-security-policy');
    assert.match(csp, /default-src 'self'/);
    assert.match(csp, /frame-ancestors 'none'/);
    assert.match(csp, /object-src 'none'/);
  });

  test('超大的请求体被拒绝（413）', async () => {
    const client = createClient(env.url);
    await loginAs(client, 'admin');
    const huge = { changelog: 'x'.repeat(2 * 1024 * 1024) };
    const res = await client.post('/api/versions', { versionCode: 1, versionName: '1.0.0', ...huge });
    assert.equal(res.status, 413);
  });

  test('非 JSON 的请求体被拒绝（415 或 400）', async () => {
    const client = createClient(env.url);
    await loginAs(client, 'admin');
    const res = await client.raw('POST', '/api/versions', {
      body: 'plain text body',
      headers: { 'content-type': 'text/plain', 'x-csrf-token': client.jar.get('hamkit_csrf') },
    });
    assert.equal(res.status, 415);
  });
});

describe('限流', () => {
  let env;
  before(async () => {
    env = await startTestEnv({ env: { LOGIN_RATE_LIMIT_MAX: '3', RATE_LIMIT_MAX: '1000' } });
  });
  after(async () => {
    await env.close();
  });

  test('登录端点超过阈值后返回 429 并带 Retry-After', async () => {
    let last = null;
    for (let i = 0; i < 6; i++) {
      last = await createClient(env.url).raw('GET', '/auth/login', { redirect: 'manual' });
      if (last.status === 429) break;
    }
    assert.equal(last.status, 429, '应触发登录限流');
    assert.ok(Number(last.headers.get('retry-after')) >= 1);
    const body = await last.json();
    assert.equal(body.code, 'rate_limited');
  });
});
