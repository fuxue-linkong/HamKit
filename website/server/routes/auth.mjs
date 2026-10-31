/**
 * 认证路由：/auth/login、/auth/callback、/auth/logout、/auth/check。
 *
 * - login 支持 ?returnTo=/admin/versions 回跳（只允许站内相对路径）
 * - callback 成功后 302 回 returnTo；失败 302 到 /login?error=...，避免把错误细节暴露成 JSON
 * - check 用于静态页面（如旧版首页）判断登录态
 */

import { HttpError } from '../core/auth.mjs';

export function registerAuthRoutes(router, deps) {
  const { config, auth, oidc, stats } = deps;
  void oidc;
  void stats;

  router.get(
    '/auth/login',
    async (ctx) => {
      if (!config.oidcConfigured) {
        return {
          redirect: '/login?error=not_configured',
          status: 302,
        };
      }
      const returnTo = ctx.query.returnTo || ctx.getHeader('referer') || '/admin/';
      const { url, cookie } = await auth.startLogin({
        returnTo,
        ip: ctx.ip,
        userAgent: ctx.userAgent,
      });
      return { redirect: url, status: 302, cookies: [cookie] };
    },
    { channel: 'api', name: 'auth.login' },
  );

  router.get(
    '/auth/callback',
    async (ctx) => {
      const error = ctx.query.error;
      if (error) {
        const desc = ctx.query.error_description || error;
        return {
          redirect: `/login?error=${encodeURIComponent(desc)}`,
          status: 302,
          cookies: auth.clearCookies(),
        };
      }
      const code = ctx.query.code;
      const state = ctx.query.state;
      if (!code || !state) {
        throw new HttpError(400, '回调缺少 code 或 state 参数', 'invalid_callback');
      }
      const result = await auth.handleCallback({
        code,
        state,
        cookies: ctx.cookies,
        ip: ctx.ip,
        userAgent: ctx.userAgent,
      });
      return {
        redirect: result.returnTo,
        status: 302,
        cookies: result.cookies,
      };
    },
    { channel: 'api', name: 'auth.callback' },
  );

  /** 主动登出：撤销会话，可选联动 IdP 单点登出。 */
  router.post(
    '/auth/logout',
    async (ctx) => {
      const wantsIdpLogout = ctx.query.idp === '1' || ctx.body?.idp === true;
      const result = await auth.handleLogout({
        identity: ctx.identity,
        ip: ctx.ip,
        userAgent: ctx.userAgent,
      });
      const target = wantsIdpLogout && result.redirectUrl ? result.redirectUrl : '/';
      return { redirect: target, status: 302, cookies: result.cookies };
    },
    { channel: 'api', name: 'auth.logout' },
  );

  /** GET 版本，方便 <a href="/auth/logout"> 直接可用。 */
  router.get(
    '/auth/logout',
    async (ctx) => {
      const result = await auth.handleLogout({
        identity: ctx.identity,
        ip: ctx.ip,
        userAgent: ctx.userAgent,
      });
      const target = ctx.query.idp === '1' && result.redirectUrl ? result.redirectUrl : '/';
      return { redirect: target, status: 302, cookies: result.cookies };
    },
    { channel: 'api', name: 'auth.logout.get' },
  );

  /** 登录态探测：供静态页面/前端脚本使用。 */
  router.get(
    '/auth/check',
    async (ctx) => {
      const { user, permissions } = ctx.identity;
      if (!user) {
        return { status: 401, json: { ok: false, authenticated: false, loginUrl: '/auth/login' } };
      }
      return {
        json: {
          ok: true,
          authenticated: true,
          user: { username: user.username, displayName: user.displayName, role: user.role },
          permissions,
        },
      };
    },
    { channel: 'api', name: 'auth.check' },
  );

  /**
   * 开发直登：只在 DEV_LOGIN=true 且非生产环境注册。
   * 生产环境该路由不存在，访问直接 404。
   */
  if (config.devLoginEnabled) {
    router.get(
      '/auth/dev-login',
      async (ctx) => {
        const role = ['admin', 'editor', 'viewer'].includes(ctx.query.role)
          ? ctx.query.role
          : 'admin';
        const result = await auth.devLogin({
          role,
          username: ctx.query.as || null,
          ip: ctx.ip,
          userAgent: ctx.userAgent,
        });
        // 审计由 auth.devLogin 内部记录（action=auth.login.dev），此处不重复统计
        return {
          redirect: ctx.query.returnTo && ctx.query.returnTo.startsWith('/') ? ctx.query.returnTo : '/admin/',
          status: 302,
          cookies: result.cookies,
        };
      },
      { channel: 'api', name: 'auth.dev_login' },
    );
    deps.logger.warn?.(
      '[auth] DEV_LOGIN 已开启：/auth/dev-login 可无凭据登录，切勿在生产环境启用',
    );
  }

  return router;
}
