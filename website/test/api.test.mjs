/**
 * API 契约测试：公开内容、版本 CRUD、公告、内容编辑、用户与角色、审计、统计。
 * 全部走真实 HTTP；写入路径同时验证 CSRF 与权限门禁。
 */

import { test, before, after, describe } from 'node:test';
import assert from 'node:assert/strict';

import { startTestEnv, createClient, loginAs } from './helpers.mjs';

// 版本发布现在强制要求官方 CDN 地址 + 64 位 SHA-256；测试夹具与生产策略保持一致。
const TEST_SHA = 'a'.repeat(64);
const apkUrl = (code, name) =>
  `https://apk.hamkit.click/release/HamKit_${name}_${code}-release.apk`;

describe('公开接口（无需登录）', () => {
  let env;
  before(async () => {
    env = await startTestEnv();
    await env.repos.versions.create({
      versionCode: 15,
      versionName: '2.1.2',
      downloadUrl: 'https://apk.hamkit.click/release/HamKit_2.1.2_15-release.apk',
      changelog: '## 2.1.2\n\n- 修复若干问题',
      channel: 'stable',
      published: true,
    });
  });
  after(async () => {
    await env.close();
  });

  test('GET /api/health 返回服务状态', async () => {
    const client = createClient(env.url);
    const res = await client.get('/api/health');
    assert.equal(res.status, 200);
    assert.equal(res.body.ok, true);
    assert.equal(res.body.service, 'hamkit-website');
  });

  test('GET /api/public/content 输出首页文案（只含 enabled 功能）', async () => {
    const client = createClient(env.url);
    const res = await client.get('/api/public/content');
    assert.equal(res.status, 200);
    assert.equal(res.body.ok, true);
    assert.ok(res.body.content.hero.title);
    assert.ok(Array.isArray(res.body.content.features));
    assert.ok(res.body.content.features.every((f) => f.enabled !== false));
  });

  test('GET /api/public/latest 返回最新版本', async () => {
    const client = createClient(env.url);
    const res = await client.get('/api/public/latest');
    assert.equal(res.body.latest.versionName, '2.1.2');
    assert.equal(res.body.latest.versionCode, 15);
  });

  test('GET /api/public/versions 只返回已发布版本', async () => {
    await env.repos.versions.create({
      versionCode: 99,
      versionName: '9.9.9-draft',
      downloadUrl: 'https://example.com/draft.apk',
      changelog: '草稿',
      published: false,
    });
    const client = createClient(env.url);
    const res = await client.get('/api/public/versions');
    const names = res.body.items.map((v) => v.versionName);
    assert.ok(!names.includes('9.9.9-draft'), '未发布版本不得出现在公开列表');
    assert.ok(names.includes('2.1.2'));
  });

  test('GET /api/public/announcements 只返回生效中的公告', async () => {
    await env.repos.announcements.create({ title: '进行中的公告', body: '正文' });
    await env.repos.announcements.create({
      title: '已过期公告',
      body: '正文',
      publishAt: new Date(Date.now() - 86400_000).toISOString(),
      expireAt: new Date(Date.now() - 3600_000).toISOString(),
    });
    await env.repos.announcements.create({ title: '已停用公告', body: '正文', enabled: false });

    const client = createClient(env.url);
    const res = await client.get('/api/public/announcements');
    const titles = res.body.items.map((a) => a.title);
    assert.deepEqual(titles, ['进行中的公告']);
  });

  test('GET /api/public/download 计一次下载并 302 到 APK', async () => {
    const client = createClient(env.url);
    const res = await client.raw('GET', '/api/public/download', { redirect: 'manual' });
    assert.equal(res.status, 302);
    assert.equal(res.headers.get('location'), 'https://apk.hamkit.click/release/HamKit_2.1.2_15-release.apk');

    const summary = await env.repos.stats.summary({ days: 1 });
    assert.ok(summary.totals.download >= 1, '应记录下载计数');
  });

  test('公共读接口带 ETag 且支持 304', async () => {
    const client = createClient(env.url);
    const first = await client.get('/api/public/content');
    const etag = first.headers.get('etag');
    assert.ok(etag, '应返回 ETag');
    const raw = await client.raw('GET', '/api/public/content', {
      headers: { 'if-none-match': etag },
    });
    assert.equal(raw.status, 304);
  });

  test('未登录不能读取后台接口', async () => {
    const client = createClient(env.url);
    const res = await client.get('/api/versions');
    assert.equal(res.status, 401);
    assert.equal(res.body.code, 'unauthenticated');
  });
});

describe('版本管理', () => {
  let env;
  let admin;
  before(async () => {
    env = await startTestEnv();
    admin = createClient(env.url);
    await loginAs(admin, 'admin');
  });
  after(async () => {
    await env.close();
  });

  test('创建 → 列表 → 详情 → 修改 → 删除 全链路', async () => {
    const created = await admin.post('/api/versions', {
      versionCode: 20,
      versionName: '3.0.0',
      downloadUrl: 'https://apk.hamkit.click/release/HamKit_3.0.0_20-release.apk',
      sha256: TEST_SHA,
      changelog: '## 3.0.0\n\n- 全新 SSTV',
      channel: 'stable',
    });
    assert.equal(created.status, 201);
    const id = created.body.item.id;
    assert.equal(created.body.item.tag, 'v3.0.0', 'tag 应默认派生');

    const list = await admin.get('/api/versions');
    assert.equal(list.body.items.length, 1);

    const detail = await admin.get(`/api/versions/${id}`);
    assert.equal(detail.body.item.versionName, '3.0.0');

    const patched = await admin.patch(`/api/versions/${id}`, { channel: 'beta' });
    assert.equal(patched.body.item.channel, 'beta');
    assert.equal(patched.body.item.prerelease, true, '非 stable 渠道默认标记预发布');

    const removed = await admin.del(`/api/versions/${id}`);
    assert.equal(removed.status, 200);
    assert.equal((await admin.get('/api/versions')).body.items.length, 0);
  });

  test('versionCode 重复被拒绝（409）', async () => {
    const base = { versionCode: 30, versionName: '3.1.0', downloadUrl: apkUrl(30, '3.1.0'), sha256: TEST_SHA };
    await admin.post('/api/versions', base);
    const dup = await admin.post('/api/versions', {
      ...base,
      versionName: '3.1.1',
      downloadUrl: apkUrl(30, '3.1.1'),
    });
    assert.equal(dup.status, 409);
  });

  test('非法 versionCode / versionName 被拒绝（400）', async () => {
    assert.equal((await admin.post('/api/versions', { versionCode: 0, versionName: 'x' })).status, 400);
    assert.equal((await admin.post('/api/versions', { versionCode: 1, versionName: '  ' })).status, 400);
  });

  test('非 http(s) 的 downloadUrl 被拒绝', async () => {
    const res = await admin.post('/api/versions', {
      versionCode: 40,
      versionName: '4.0.0',
      downloadUrl: 'javascript:alert(1)',
    });
    assert.equal(res.status, 400);
  });

  test('非官方 CDN 的 downloadUrl 被拒绝', async () => {
    const res = await admin.post('/api/versions', {
      versionCode: 41,
      versionName: '4.1.0',
      downloadUrl: 'https://evil.example/app.apk',
      sha256: TEST_SHA,
    });
    assert.equal(res.status, 400);
    assert.equal(res.body.code, 'untrusted_download_host');
  });

  test('已发布版本缺少 sha256 被拒绝', async () => {
    const res = await admin.post('/api/versions', {
      versionCode: 42,
      versionName: '4.2.0',
      downloadUrl: apkUrl(42, '4.2.0'),
    });
    assert.equal(res.status, 400);
    assert.equal(res.body.code, 'missing_sha256');
  });

  test('版本 channels 字段可派生分渠道更新文件', async () => {
    await admin.post('/api/versions', {
      versionCode: 50,
      versionName: '5.0.0',
      channel: 'stable',
      downloadUrl: apkUrl(50, '5.0.0'),
      sha256: TEST_SHA,
    });
    await admin.post('/api/versions', {
      versionCode: 51,
      versionName: '5.1.0-beta',
      channel: 'beta',
      downloadUrl: apkUrl(51, '5.1.0-beta'),
      sha256: TEST_SHA,
    });
    const files = await env.repos.versions.compatFiles();
    assert.ok(files.channels.stable);
    assert.ok(files.channels.beta);
    assert.equal(files.channels.stable.versionCode, 50);
    assert.equal(files.channels.beta.versionCode, 51);
    assert.equal(files['update.json'].versionCode, 50, 'update.json 取 stable 最新');
  });
});

describe('App 兼容文件（update.json / version.json）', () => {
  let env;
  let admin;
  before(async () => {
    env = await startTestEnv();
    admin = createClient(env.url);
    await loginAs(admin, 'admin');
  });
  after(async () => {
    await env.close();
  });

  test('后台发布版本后，App 拉取的 update.json 同步变化', async () => {
    // 初始无版本：App 拿到空壳而不是 404
    const empty = await createClient(env.url).get('/update.json');
    assert.equal(empty.status, 200);
    assert.equal(empty.body.versionCode, 0);

    await admin.post('/api/versions', {
      versionCode: 16,
      versionName: '2.2.0',
      downloadUrl: 'https://apk.hamkit.click/release/HamKit_2.2.0_16-release.apk',
      sha256: TEST_SHA,
      changelog: '## 2.2.0 更新日志\n\n- 优先从官网拉取更新日志',
    });

    const site = createClient(env.url);
    const update = await site.get('/update.json');
    assert.equal(update.status, 200);
    assert.deepEqual(Object.keys(update.body).sort(), [
      'changelog',
      'downloadUrl',
      'sha256',
      'versionCode',
      'versionName',
    ]);
    assert.equal(update.body.versionCode, 16);
    assert.equal(update.body.versionName, '2.2.0');
    assert.equal(update.body.downloadUrl, 'https://apk.hamkit.click/release/HamKit_2.2.0_16-release.apk');
    assert.match(update.body.changelog, /2\.2\.0 更新日志/);

    const version = await site.get('/version.json');
    assert.equal(version.status, 200);
    assert.deepEqual(Object.keys(version.body).sort(), ['prerelease', 'tag', 'updated_at']);
    assert.equal(version.body.tag, 'v2.2.0');
    assert.equal(version.body.prerelease, false);
  });

  test('分渠道更新文件 update_beta.json 只反映 beta 渠道', async () => {
    await admin.post('/api/versions', {
      versionCode: 17,
      versionName: '2.3.0-beta1',
      channel: 'beta',
      downloadUrl: 'https://apk.hamkit.click/release/HamKit_2.3.0-beta1_17-release.apk',
      sha256: TEST_SHA,
      changelog: 'beta 通道',
    });
    const site = createClient(env.url);
    const beta = await site.get('/update_beta.json');
    assert.equal(beta.status, 200);
    assert.equal(beta.body.versionName, '2.3.0-beta1');

    const stable = await site.get('/update.json');
    assert.equal(stable.body.versionName, '2.2.0', 'stable 通道不受 beta 影响');
    assert.equal(stable.body.versionCode, 16, 'update.json 只走 stable');

    const missing = await site.get('/update_nightly.json');
    assert.equal(missing.status, 404, '没有 nightly 版本时该渠道文件不存在');
  });

  test('后台删除版本后 update.json 回退到上一个版本', async () => {
    const list = await admin.get('/api/versions');
    const beta = list.body.items.find((v) => v.channel === 'beta');
    await admin.del(`/api/versions/${beta.id}`);
    const betaFile = await createClient(env.url).get('/update_beta.json');
    assert.equal(betaFile.status, 404, '该渠道已无版本，应 404');
    const stable = await createClient(env.url).get('/update.json');
    assert.equal(stable.body.versionName, '2.2.0');
  });

  test('GET /api/compat/preview 让后台预览 App 将看到的报文', async () => {
    const res = await admin.get('/api/compat/preview');
    assert.equal(res.status, 200);
    assert.ok(res.body['update.json']);
    assert.ok(res.body['version.json']);
  });
});

describe('账号数据权利', () => {
  let env;
  let admin;
  let viewer;
  before(async () => {
    env = await startTestEnv();
    admin = createClient(env.url);
    viewer = createClient(env.url);
    await loginAs(admin, 'admin');
    await loginAs(viewer, 'viewer');
  });
  after(async () => {
    await env.close();
  });

  test('用户可以导出自己的数据，但不会泄露会话 sid', async () => {
    const res = await viewer.get('/api/me/export');
    assert.equal(res.status, 200);
    assert.ok(res.body.user.email);
    assert.ok(Array.isArray(res.body.sessions));
    assert.ok(Array.isArray(res.body.audit));
    assert.ok(!JSON.stringify(res.body.sessions).includes('"sid"'));
  });

  test('普通用户可以注销自己的账号，随后会话失效', async () => {
    const res = await viewer.del('/api/me', { body: { confirm: 'DELETE' } });
    assert.equal(res.status, 200);
    assert.equal(res.body.deleted, true);
    const after = await viewer.get('/api/session');
    assert.equal(after.body.authenticated, false);
  });

  test('最后一个管理员不能注销自己', async () => {
    const res = await admin.del('/api/me', { body: { confirm: 'DELETE' } });
    assert.equal(res.status, 409);
  });
});

describe('站点内容与公告管理', () => {
  let env;
  let admin;
  let editor;
  let viewer;
  before(async () => {
    env = await startTestEnv();
    admin = createClient(env.url);
    editor = createClient(env.url);
    viewer = createClient(env.url);
    await loginAs(admin, 'admin');
    await loginAs(editor, 'editor');
    await loginAs(viewer, 'viewer');
  });
  after(async () => {
    await env.close();
  });

  test('editor 可改首页文案，改动即时反映到公开接口', async () => {
    const res = await editor.patch('/api/content', {
      hero: { subtitle: '写在天线上的诗。' },
    });
    assert.equal(res.status, 200);
    assert.equal(res.body.content.hero.title, 'HamKit', '未提交的字段应保持默认');

    const publicRes = await createClient(env.url).get('/api/public/content');
    assert.equal(publicRes.body.content.hero.subtitle, '写在天线上的诗。');
  });

  test('新增/停用功能卡后公开接口同步', async () => {
    await editor.patch('/api/content', {
      features: [
        { id: 'sstv', title: 'SSTV 慢扫描电视', text: '纯 Kotlin DSP', tags: ['PD-120'], enabled: true },
        { id: 'hidden', title: '未上线', text: 'x', enabled: false },
      ],
    });
    const publicRes = await createClient(env.url).get('/api/public/content');
    const ids = publicRes.body.content.features.map((f) => f.id);
    assert.deepEqual(ids, ['sstv']);
  });

  test('viewer 不能写内容（403）', async () => {
    const res = await viewer.patch('/api/content', { hero: { title: 'hacked' } });
    assert.equal(res.status, 403);
    assert.equal(res.body.code, 'forbidden');
  });

  test('缺少 CSRF 令牌的写请求被拒绝（403）', async () => {
    // 直接构造不带 X-CSRF-Token 的请求
    const res = await editor.raw('PATCH', '/api/content', {
      body: { hero: { title: 'no-csrf' } },
      headers: { 'content-type': 'application/json' },
    });
    assert.equal(res.status, 403);
    const body = await res.json();
    assert.equal(body.code, 'csrf_failed');
  });

  test('CSRF 令牌与 Cookie 不一致时被拒绝', async () => {
    const res = await editor.raw('PATCH', '/api/content', {
      body: { hero: { title: 'bad-csrf' } },
      headers: { 'content-type': 'application/json', 'x-csrf-token': 'forged-token-value' },
    });
    assert.equal(res.status, 403);
    assert.equal((await res.json()).code, 'csrf_failed');
  });

  test('公告 CRUD 与置顶排序', async () => {
    const a = await editor.post('/api/announcements', { title: '普通公告', body: '内容' });
    assert.equal(a.status, 201);
    const pinned = await editor.post('/api/announcements', {
      title: '重要公告',
      body: '内容',
      severity: 'warning',
      pinned: true,
    });
    assert.equal(pinned.status, 201);

    const list = await editor.get('/api/announcements');
    assert.equal(list.body.items[0].title, '重要公告', '置顶公告排在最前');

    const patched = await editor.patch(`/api/announcements/${a.body.item.id}`, { severity: 'critical' });
    assert.equal(patched.body.item.severity, 'critical');

    const removed = await editor.del(`/api/announcements/${pinned.body.item.id}`);
    assert.equal(removed.status, 200);
    assert.equal((await editor.get('/api/announcements')).body.items.length, 1);
  });

  test('公告失效时间早于发布时间被拒绝', async () => {
    const res = await editor.post('/api/announcements', {
      title: '时间颠倒',
      body: 'x',
      publishAt: '2026-01-02T00:00:00Z',
      expireAt: '2026-01-01T00:00:00Z',
    });
    assert.equal(res.status, 400);
  });

  test('viewer 只读：可读审计但不能写公告', async () => {
    assert.equal((await viewer.get('/api/audit')).status, 200);
    assert.equal((await viewer.post('/api/announcements', { title: 'x' })).status, 403);
  });
});

describe('用户与角色管理', () => {
  let env;
  let admin;
  let editor;
  before(async () => {
    env = await startTestEnv();
    admin = createClient(env.url);
    editor = createClient(env.url);
    // 两个管理员：root 负责读，a2 负责角色变更（避免“改自己角色”与“最后一个管理员”互相干扰）
    await loginAs(admin, 'admin', '&as=root');
    await loginAs(createClient(env.url), 'admin', '&as=a2');
    await loginAs(createClient(env.url), 'admin', '&as=a3');
    await loginAs(editor, 'editor', '&as=writer');
  });
  after(async () => {
    await env.close();
  });

  test('管理员可列出用户并看到角色统计', async () => {
    const res = await admin.get('/api/users');
    assert.equal(res.status, 200);
    assert.ok(res.body.counts.byRole.admin >= 2);
    assert.equal(res.body.counts.byRole.editor, 1);
  });

  test('editor 不能写用户（403）', async () => {
    const list = await admin.get('/api/users');
    const target = list.body.items.find((u) => u.username === 'writer');
    const res = await editor.patch(`/api/users/${target.id}`, { role: 'admin' });
    assert.equal(res.status, 403);
  });

  test('管理员可调整角色，重新登录后生效', async () => {
    const list = await admin.get('/api/users');
    const target = list.body.items.find((u) => u.username === 'writer');
    const res = await admin.patch(`/api/users/${target.id}`, { role: 'viewer' });
    assert.equal(res.status, 200);
    assert.equal(res.body.user.role, 'viewer');

    const fresh = createClient(env.url);
    await loginAs(fresh, 'viewer', '&as=writer');
    assert.equal((await fresh.get('/api/session')).body.user.role, 'viewer');
  });

  test('降级非唯一管理员是允许的', async () => {
    const a2 = createClient(env.url);
    await loginAs(a2, 'admin', '&as=a2');
    const list = await admin.get('/api/users');
    const a3 = list.body.items.find((u) => u.username === 'a3');
    assert.ok(a3, '应存在 a3 管理员');
    const res = await a2.patch(`/api/users/${a3.id}`, { role: 'viewer' });
    assert.equal(res.status, 200);
  });

  test('管理员不能修改自己的角色（HTTP 层护栏）', async () => {
    const self = createClient(env.url);
    await loginAs(self, 'admin', '&as=self-admin');
    const list = await self.get('/api/users');
    const me = list.body.items.find((u) => u.username === 'self-admin');
    const res = await self.patch(`/api/users/${me.id}`, { role: 'editor' });
    assert.equal(res.status, 409);
    assert.equal(res.body.code, 'self_role_change');
  });

  test('仓储层拒绝降级/停用/删除唯一管理员', async () => {
    // 把除 a2 之外的管理员都降级，制造“唯一管理员”局面
    const all = await env.repos.users.list();
    for (const u of all) {
      if (u.role === 'admin' && u.username !== 'a2') {
        await env.repos.users.setRole(u.id, 'viewer').catch(() => {});
      }
    }
    const admins = (await env.repos.users.list()).filter((u) => u.role === 'admin' && !u.disabled);
    assert.equal(admins.length, 1, '前置条件：仅剩一个管理员');

    await assert.rejects(() => env.repos.users.setRole(admins[0].id, 'viewer'), /最后一个管理员/);
    await assert.rejects(() => env.repos.users.setDisabled(admins[0].id, true), /最后一个管理员/);
    await assert.rejects(() => env.repos.users.remove(admins[0].id), /最后一个管理员/);
  });

  test('不能删除自己', async () => {
    const self = createClient(env.url);
    await loginAs(self, 'admin', '&as=self2');
    const list = await self.get('/api/users');
    const me = list.body.items.find((u) => u.username === 'self2');
    const res = await self.del(`/api/users/${me.id}`);
    assert.equal(res.status, 409);
    assert.equal(res.body.code, 'self_delete');
  });

  test('停用用户后其会话立即失效', async () => {
    const victim = createClient(env.url);
    await loginAs(victim, 'viewer', '&as=victim');
    assert.equal((await victim.get('/api/session')).body.authenticated, true);

    const adminApi = createClient(env.url);
    await loginAs(adminApi, 'admin', '&as=a4');
    const list = await adminApi.get('/api/users');
    const target = list.body.items.find((u) => u.username === 'victim');
    const res = await adminApi.patch(`/api/users/${target.id}`, { disabled: true });
    assert.equal(res.status, 200);

    assert.equal((await victim.get('/api/session')).body.authenticated, false, '停用后旧会话必须失效');
  });

  test('删除用户后其账号消失', async () => {
    const adminApi = createClient(env.url);
    await loginAs(adminApi, 'admin', '&as=a5');
    const list = await adminApi.get('/api/users');
    const target = list.body.items.find((u) => u.username === 'victim');
    assert.ok(target, '应存在 victim');
    assert.equal((await adminApi.del(`/api/users/${target.id}`)).status, 200);
    const after = await adminApi.get('/api/users');
    assert.ok(!after.body.items.some((u) => u.username === 'victim'));
  });
});

describe('审计与统计', () => {
  let env;
  let admin;
  before(async () => {
    env = await startTestEnv();
    admin = createClient(env.url);
    await loginAs(admin, 'admin');
  });
  after(async () => {
    await env.close();
  });

  test('写操作留下审计记录（含操作人、目标、明细）', async () => {
    await admin.post('/api/versions', {
      versionCode: 7,
      versionName: '0.7.0',
      downloadUrl: apkUrl(7, '0.7.0'),
      sha256: TEST_SHA,
    });
    const res = await admin.get('/api/audit?limit=50');
    assert.equal(res.status, 200);
    const entry = res.body.items.find((e) => e.action === 'version.create');
    assert.ok(entry, '应存在 version.create 审计记录');
    assert.equal(entry.actorRole, 'admin');
    assert.equal(entry.detail.versionName, '0.7.0');
    assert.ok(entry.at);
  });

  test('登录也写入审计', async () => {
    const res = await admin.get('/api/audit?action=auth.login.dev');
    assert.ok(res.body.items.length >= 1);
    assert.ok(res.body.actions.includes('version.create'));
  });

  test('审计支持关键字与动作过滤', async () => {
    const byAction = await admin.get('/api/audit?action=content.update');
    assert.ok(byAction.body.items.every((e) => e.action === 'content.update'));
    const byQuery = await admin.get('/api/audit?q=版本');
    assert.ok(Array.isArray(byQuery.body.items));
  });

  test('统计汇总包含今日与近 N 天窗口', async () => {
    await env.repos.stats.record('pageview', { path: '/' });
    await env.repos.stats.record('pageview', { path: '/' });
    await env.repos.stats.record('download', { path: '1.0.0' });

    const res = await admin.get('/api/stats?days=7');
    assert.equal(res.status, 200);
    assert.ok(res.body.stats.today.views >= 2);
    assert.ok(res.body.stats.window.downloads >= 1);
    assert.ok(Array.isArray(res.body.stats.series));
    assert.ok(res.body.users.total >= 1);
    assert.ok(res.body.versions.total >= 0);
  });

  test('系统状态返回自检信息但不泄露密钥', async () => {
    const res = await admin.get('/api/system');
    assert.equal(res.status, 200);
    assert.equal(typeof res.body.oidc.configured, 'boolean');
    assert.ok(res.body.oidc.redirectUri.endsWith('/auth/callback'));
    assert.ok(Array.isArray(res.body.files));
    const serialized = JSON.stringify(res.body);
    assert.ok(!serialized.includes('mock-secret'), '响应不得包含 client_secret');
    assert.ok(!serialized.includes(env.config.session.secret), '响应不得包含会话密钥');
  });

  test('viewer 无权访问系统状态（403）', async () => {
    const viewer = createClient(env.url);
    await loginAs(viewer, 'viewer');
    assert.equal((await viewer.get('/api/system')).status, 403);
  });
});
