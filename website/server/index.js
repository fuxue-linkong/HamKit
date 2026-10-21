/**
 * 生产启动入口：`node server/index.js`
 *
 * 流程：加载 .env → 组装配置 → 自检 → 启动 HTTP → 打印访问地址与自检结果。
 * 未配置 OIDC 时**仍会启动**：首页可正常访问，/login 会显示配置引导，
 * 便于先把站点跑起来再补 IdP 参数。
 */

import { loadConfig, loadDotEnv, validateConfig } from './config.mjs';
import { startServer } from './http/app.mjs';
import { createMockOidcProvider } from './dev/mock-oidc.mjs';

export async function main({ env = process.env, argv = process.argv.slice(2) } = {}) {
  const applied = loadDotEnv(undefined, env);
  const config = loadConfig(env);

  const useMock = argv.includes('--mock-oidc') || env.OIDC_PROVIDER === 'mock';
  let mock = null;
  let effectiveConfig = config;

  if (useMock) {
    if (config.isProduction) {
      console.error('[启动失败] 生产环境（NODE_ENV=production）不允许使用内置 Mock OIDC。');
      process.exitCode = 1;
      return null;
    }
    mock = await createMockOidcProvider({ quiet: true });
    effectiveConfig = loadConfig({
      ...env,
      OIDC_ISSUER: mock.issuer,
      OIDC_CLIENT_ID: env.OIDC_CLIENT_ID || 'hamkit-website',
      OIDC_CLIENT_SECRET: env.OIDC_CLIENT_SECRET || 'mock-secret',
      DEV_LOGIN: env.DEV_LOGIN || 'true',
    });
    console.log(`[mock-oidc] 已启动内置身份提供方：${mock.issuer}`);
  }

  const { problems, warnings } = validateConfig(effectiveConfig);
  const logger = console;

  console.log('HamKit 官网服务启动中…');
  console.log(`  环境        : ${effectiveConfig.env}`);
  console.log(`  监听         : ${effectiveConfig.host}:${effectiveConfig.port}`);
  console.log(`  对外 BASE_URL: ${effectiveConfig.baseUrl}`);
  console.log(`  数据目录     : ${effectiveConfig.dataDir}`);
  console.log(`  OIDC issuer  : ${effectiveConfig.oidc.issuer || '（未配置）'}`);
  console.log(`  回调地址     : ${effectiveConfig.oidcRedirectUri}`);
  if (applied.length) console.log(`  已加载 .env  : ${applied.join(', ')}`);
  for (const w of warnings) console.log(`  [告警] ${w}`);
  for (const p of problems) console.log(`  [缺失] ${p}`);
  if (problems.length) {
    console.log('  → 站点仍会启动，但 /auth/login 不可用；请补齐上述配置后重启。');
  }

  const running = await startServer(effectiveConfig, { logger });

  console.log('');
  console.log(`✅ 已启动：${running.url}`);
  console.log(`   首页      ${running.url}/`);
  console.log(`   登录      ${running.url}/login`);
  console.log(`   后台      ${running.url}/admin/`);
  console.log(`   健康检查  ${running.url}/api/health`);
  if (mock) console.log(`   Mock IdP  ${mock.issuer}/.well-known/openid-configuration`);

  const shutdown = async (signal) => {
    console.log(`\n收到 ${signal}，正在关闭…`);
    try {
      await running.close();
      await mock?.close();
    } finally {
      process.exit(0);
    }
  };
  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));

  return { running, config: effectiveConfig, mock };
}

const isDirectRun =
  process.argv[1] && import.meta.url === new URL(`file://${process.argv[1].replace(/\\/g, '/')}`).href;

if (isDirectRun || process.argv[1]?.endsWith('index.js')) {
  main().catch((err) => {
    console.error('[启动失败]', err);
    process.exitCode = 1;
  });
}
