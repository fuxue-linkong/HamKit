/**
 * 端到端登录链路：本站 → Mock IdP → 回调 → 会话 → 权限。
 * 验证的是真实 HTTP 往返，不是 mock 函数调用。
 */

import { test, before, after, describe } from 'node:test';
import assert from 'node:assert/strict';

import { startTestEnv, createClient, oidcLogin, loginAs } from './helpers.mjs';

describe('OIDC 登录链路（端到端）', () => {
  /** @type {Awaited<ReturnType<typeof startTestEnv>>} */
  let env;

  before(async () => {
    env = await startTestEnv();
  });
  after(async () => {
    await env.close();
  });

  test('Mock IdP 的 discovery 文档可被标准客户端消费', async () => {
    const res = await fetch(`${env.mock.issuer}/.well-known/openid-configuration`);
    assert.equal(res.status, 200);
    const doc = await res.json();
    assert.equal(doc.issuer, env.mock.issuer);
    assert.ok(doc.authorization_endpoint.endsWith('/authorize'));
    assert.ok(doc.token_endpoint.endsWith('/token'));
    assert.ok(doc.jwks_uri.endsWith('/jwks'));
    assert.deepEqual(doc.code_challenge_methods_supported, ['S256']);
  });

  test('完整授权码 + PKCE 流程可登录并建立会话', async () => {
    const client = createClient(env.url);
    const result = await oidcLogin(client, { as: 'admin', returnTo: '/admin/versions' });

    assert.equal(result.status, 302, '回调应重定向');
    assert.equal(result.location, '/admin/versions', '应回到 returnTo 指定的站内地址');
    assert.ok(client.jar.get('hamkit_session'), '应下发会话 Cookie');
    assert.ok(client.jar.get('hamkit_csrf'), '应下发 CSRF Cookie');

    const session = await client.get('/api/session');
    assert.equal(session.status, 200);
    assert.equal(session.body.authenticated, true);
    assert.equal(session.body.user.username, 'admin');
    assert.equal(session.body.user.role, 'admin');
    assert.equal(session.body.user.email, 'admin@example.com');
    assert.equal(session.body.permissions['version:write'], true);
    assert.equal(session.body.permissions['user:write'], true);
  });

  test('会话 Cookie 带 HttpOnly / SameSite / Path，CSRF Cookie 不带 HttpOnly', async () => {
    const client = createClient(env.url);
    const start = await client.raw('GET', '/auth/login', { redirect: 'manual' });
    const oauthCookie = start.headers
      .getSetCookie()
      .find((line) => line.startsWith('hamkit_oauth='));
    assert.ok(oauthCookie, '应下发临时 oauth Cookie');
    assert.match(oauthCookie, /HttpOnly/);
    assert.match(oauthCookie, /SameSite=Lax/);
    assert.match(oauthCookie, /Path=\//);

    const full = await oidcLogin(createClient(env.url), { as: 'editor' });
    assert.equal(full.status, 302);
  });

  test('IdP 声明的角色经 OIDC_ROLE_MAP 映射为本站角色', async () => {
    const cases = [
      ['editor', 'editor'],
      ['viewer', 'viewer'],
      ['admin', 'admin'],
    ];
    for (const [as, expectedRole] of cases) {
      const client = createClient(env.url);
      await oidcLogin(client, { as });
      const session = await client.get('/api/session');
      assert.equal(session.body.user.role, expectedRole, `${as} 应映射为 ${expectedRole}`);
    }
  });

  test('state 不匹配时拒绝登录（CSRF 防护）', async () => {
    const client = createClient(env.url);
    const start = await client.raw('GET', '/auth/login', { redirect: 'manual' });
    const authorizeUrl = new URL(start.headers.get('location'));
    authorizeUrl.searchParams.set('login_hint', 'admin');
    const idp = await client.raw('GET', authorizeUrl.toString(), { redirect: 'manual' });
    const cb = new URL(idp.headers.get('location'));
    cb.searchParams.set('state', 'tampered-state-value');

    const res = await client.raw('GET', `${cb.pathname}${cb.search}`, { redirect: 'manual' });
    assert.equal(res.status, 400);
    assert.equal(client.jar.get('hamkit_session'), undefined, '不应建立会话');
  });

  test('缺少 oauth Cookie 的回调被拒绝（会话已过期场景）', async () => {
    const client = createClient(env.url);
    const res = await client.raw('GET', '/auth/callback?code=whatever&state=whatever', {
      redirect: 'manual',
    });
    assert.equal(res.status, 400);
    const body = await res.json();
    assert.equal(body.code, 'oauth_state_missing');
  });

  test('IdP 返回 error 时回到登录页并展示原因', async () => {
    const client = createClient(env.url);
    const res = await client.raw(
      'GET',
      '/auth/callback?error=access_denied&error_description=' + encodeURIComponent('用户取消授权'),
      { redirect: 'manual' },
    );
    assert.equal(res.status, 302);
    assert.match(res.headers.get('location'), /^\/login\?error=/);
    assert.match(decodeURIComponent(res.headers.get('location')), /用户取消授权/);
  });

  test('登出撤销会话，旧 Cookie 立即失效', async () => {
    const client = createClient(env.url);
    await oidcLogin(client, { as: 'admin' });
    assert.equal((await client.get('/api/session')).body.authenticated, true);

    // 登出会用 Max-Age=0 清掉 Cookie，因此必须先留存旧令牌再登出
    const stale = client.jar.get('hamkit_session');
    assert.ok(stale, '登录后应存在会话 Cookie');

    const logout = await client.raw('POST', '/auth/logout', { redirect: 'manual' });
    assert.equal(logout.status, 302);
    assert.ok(
      logout.headers.getSetCookie().some((line) => /^hamkit_session=;/.test(line)),
      '登出应下发清除会话 Cookie 的指令',
    );

    // 重新塞回旧会话 Cookie：签名依然有效，但登记表已撤销 → 必须失败
    client.clearCookies();
    client.setCookie('hamkit_session', stale);
    const after = await client.get('/api/session');
    assert.equal(after.body.authenticated, false);
    assert.equal(after.body.reason, 'revoked');
  });

  test('未登录访问 /auth/check 返回 401 与登录地址', async () => {
    const client = createClient(env.url);
    const res = await client.get('/auth/check');
    assert.equal(res.status, 401);
    assert.equal(res.body.authenticated, false);
    assert.equal(res.body.loginUrl, '/auth/login');
  });

  test('开发直登可建立指定角色的会话', async () => {
    const client = createClient(env.url);
    const result = await loginAs(client, 'editor');
    assert.equal(result.status, 302);
    const session = await client.get('/api/session');
    assert.equal(session.body.user.role, 'editor');
    assert.equal(session.body.user.username, 'dev-editor');
  });

  test('开放重定向被拦截：外部 returnTo 回落到 /admin/', async () => {
    const client = createClient(env.url);
    const result = await oidcLogin(client, {
      as: 'admin',
      returnTo: 'https://evil.example.com/steal',
    });
    assert.equal(result.location, '/admin/');
  });

  test('协议相对地址 //evil.com 同样被拦截', async () => {
    const client = createClient(env.url);
    const result = await oidcLogin(client, { as: 'admin', returnTo: '//evil.example.com' });
    assert.equal(result.location, '/admin/');
  });
});

describe('未配置 OIDC 的环境', () => {
  let env;
  before(async () => {
    env = await startTestEnv({ mockOidc: false, devLogin: false, env: { OIDC_ISSUER: '', OIDC_CLIENT_ID: '' } });
  });
  after(async () => {
    await env.close();
  });

  test('/auth/login 引导到登录页的错误提示而不是抛 500', async () => {
    const client = createClient(env.url);
    const res = await client.raw('GET', '/auth/login', { redirect: 'manual' });
    assert.equal(res.status, 302);
    assert.equal(res.headers.get('location'), '/login?error=not_configured');
  });

  test('/api/meta 报告缺失的配置项，便于部署自检', async () => {
    const client = createClient(env.url);
    const res = await client.get('/api/meta');
    assert.equal(res.status, 200);
    assert.equal(res.body.oidcConfigured, false);
    assert.ok(res.body.configProblems.length >= 1);
  });

  test('未开启 DEV_LOGIN 时开发直登路由不存在', async () => {
    const client = createClient(env.url);
    const res = await client.raw('GET', '/auth/dev-login?role=admin', { redirect: 'manual' });
    assert.equal(res.status, 404);
  });
});
