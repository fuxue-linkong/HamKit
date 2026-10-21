/**
 * 角色声明归一化与角色映射的回归测试。
 *
 * 背景：不同 IdP 下发的角色 claim 形态不同，其中最坑的是 Casdoor 默认
 * TokenFormat=JWT 时 `roles` 为 `[]*Role` **对象数组**。若只按字符串处理，
 * String(对象) 会得到 "[object Object]"，OIDC_ROLE_MAP 静默失效。
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import { normalizeRoleClaim } from '../server/store/repositories.mjs';
import { startTestEnv } from './helpers.mjs';

describe('角色声明归一化 normalizeRoleClaim', () => {
  test('字符串数组（标准 OIDC / Casdoor JWT-Custom）', () => {
    assert.deepEqual(normalizeRoleClaim(['admin', 'editor']), ['admin', 'editor']);
  });

  test('逗号分隔字符串', () => {
    assert.deepEqual(normalizeRoleClaim('hamkit-admin,hamkit-editor'), ['hamkit-admin', 'hamkit-editor']);
  });

  test('空白分隔字符串', () => {
    assert.deepEqual(normalizeRoleClaim('admin editor  viewer'), ['admin', 'editor', 'viewer']);
  });

  test('对象数组（Casdoor 默认 JWT 的 []*Role）取 name', () => {
    const casdoorRoles = [
      { owner: 'hamkit', name: 'hamkit-admin', displayName: 'HamKit 管理员', createdTime: '2026-10-01T00:00:00Z' },
      { owner: 'hamkit', name: 'hamkit-editor', displayName: 'HamKit 编辑' },
    ];
    assert.deepEqual(normalizeRoleClaim(casdoorRoles), ['hamkit-admin', 'hamkit-editor']);
  });

  test('对象数组里没有 name 时回退到 displayName / key / value', () => {
    assert.deepEqual(
      normalizeRoleClaim([{ displayName: 'admin' }, { key: 'editor' }, { value: 'viewer' }]),
      ['admin', 'editor', 'viewer'],
    );
  });

  test('取不出名字的元素被丢弃，不产生 [object Object]', () => {
    const result = normalizeRoleClaim([{ owner: 'x' }, 'editor', null, 42, {}]);
    assert.ok(!result.includes('[object Object]'), '绝不能出现 [object Object]');
    assert.deepEqual(result, ['editor', '42']);
  });

  test('单个对象也接受', () => {
    assert.deepEqual(normalizeRoleClaim({ name: 'admin' }), ['admin']);
  });

  test('缺失 / 非法值返回空数组', () => {
    for (const input of [undefined, null, '', 0, false, {}]) {
      assert.deepEqual(normalizeRoleClaim(input), [], `输入 ${JSON.stringify(input)} 应为空数组`);
    }
  });

  test('数组里的空白字符串被过滤', () => {
    assert.deepEqual(normalizeRoleClaim(['  ', 'admin', '']), ['admin']);
  });
});

describe('对象数组角色也能完成 OIDC_ROLE_MAP 映射', () => {
  test('角色 claim 为对象数组时仍能映射成本站角色', async () => {
    // 直接驱动用户仓库：profile 形态模拟 Casdoor 默认 JWT（roles 为对象数组）
    const env = await startTestEnv({
      env: { OIDC_ROLE_MAP: 'hamkit-admin:admin,hamkit-editor:editor' },
    });
    try {
      const { user } = await env.repos.users.upsertFromOidc({
        sub: 'oidc-object-roles-1',
        preferred_username: 'objrole',
        email: 'objrole@example.com',
        iss: env.mock.issuer,
        // Casdoor 默认 TokenFormat=JWT 的真实形态
        roles: [{ owner: 'builtin', name: 'hamkit-editor', displayName: '编辑' }],
      });
      assert.equal(user.role, 'editor', '对象数组角色必须能映射为 editor');
      assert.deepEqual(user.idpRoles, ['hamkit-editor'], 'idpRoles 应存归一化后的名字');
    } finally {
      await env.close();
    }
  });

  test('对照：字符串数组同样能映射', async () => {
    const env = await startTestEnv({
      env: { OIDC_ROLE_MAP: 'hamkit-admin:admin,hamkit-editor:editor' },
    });
    try {
      const { user } = await env.repos.users.upsertFromOidc({
        sub: 'oidc-string-roles-1',
        preferred_username: 'strrole',
        email: 'strrole@example.com',
        iss: env.mock.issuer,
        roles: ['hamkit-admin'],
      });
      assert.equal(user.role, 'admin');
    } finally {
      await env.close();
    }
  });

  test('未映射的 IdP 角色不会误升级权限', async () => {
    const env = await startTestEnv({
      env: { OIDC_ROLE_MAP: 'hamkit-admin:admin', FIRST_USER_IS_ADMIN: 'false' },
    });
    try {
      const { user } = await env.repos.users.upsertFromOidc({
        sub: 'oidc-unknown-role-1',
        preferred_username: 'nobody',
        email: 'nobody@example.com',
        iss: env.mock.issuer,
        roles: [{ name: 'some-unmapped-role' }],
      });
      assert.equal(user.role, 'viewer', '未映射角色应保持最低权限 viewer');
    } finally {
      await env.close();
    }
  });
});
