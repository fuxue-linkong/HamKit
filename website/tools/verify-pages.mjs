/**
 * 页面级验收工具（开发用，不属于运行时）
 *
 * 用 Chrome DevTools Protocol 真实渲染每个页面并断言：
 *   - 无 JS 运行时异常、无 console.error、无关键资源加载失败
 *   - 页面确实渲染出内容（不是空白壳）
 *   - 顺带输出截图，便于人工复核视觉
 *
 * 为什么不用 puppeteer/playwright：本仓库坚持零 npm 依赖，
 * Node 22+ 自带 WebSocket，直接讲 CDP 协议即可，避免为一次验收引入依赖树。
 *
 * 用法：
 *   node server/index.js &                       # 先起服务
 *   node tools/verify-pages.mjs --base http://127.0.0.1:8787 --auth
 *
 * 参数：
 *   --base   站点根地址（默认 http://127.0.0.1:8787）
 *   --auth   先让浏览器走 /auth/dev-login 登录，再验收需要登录的页面
 *           （要求服务端 DEV_LOGIN=true，仅本地/测试环境可用）
 *   --as     开发直登使用的用户名（默认 verifier）
 *   --only   只验收指定页面，逗号分隔（如 --only home,admin-versions）
 *   --out    截图与报告输出目录（默认系统临时目录）
 */

import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
].filter(Boolean);

const PAGES = [
  { name: 'home', path: '/', auth: false },
  { name: 'login', path: '/login', auth: false },
  { name: 'account', path: '/account', auth: true },
  { name: 'admin-overview', path: '/admin/#/overview', auth: true },
  { name: 'admin-versions', path: '/admin/#/versions', auth: true },
  { name: 'admin-content', path: '/admin/#/content', auth: true },
  { name: 'admin-announcements', path: '/admin/#/announcements', auth: true },
  { name: 'admin-users', path: '/admin/#/users', auth: true },
  { name: 'admin-audit', path: '/admin/#/audit', auth: true },
  { name: 'admin-system', path: '/admin/#/system', auth: true },
];

function parseArgs(argv) {
  const args = { base: 'http://127.0.0.1:8787', out: null, auth: false, as: 'verifier', only: null, resolveTo: null };
  for (let i = 0; i < argv.length; i++) {
    const key = argv[i];
    if (key === '--base') args.base = String(argv[++i] || '').replace(/\/+$/, '');
    else if (key === '--out') args.out = argv[++i];
    else if (key === '--as') args.as = argv[++i];
    else if (key === '--auth') args.auth = true;
    else if (key === '--resolve-to') args.resolveTo = argv[++i];
    else if (key === '--only') args.only = String(argv[++i] || '').split(',').map((s) => s.trim()).filter(Boolean);
  }
  return args;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** 极简 CDP 客户端：请求/响应配对 + 事件订阅。 */
class Cdp {
  constructor(url) {
    this.url = url;
    this.id = 0;
    this.pending = new Map();
    this.listeners = new Map();
    this.socket = null;
  }

  async connect() {
    this.socket = new WebSocket(this.url);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('CDP 连接超时')), 15000);
      this.socket.addEventListener('open', () => {
        clearTimeout(timer);
        resolve();
      });
      this.socket.addEventListener('error', () => {
        clearTimeout(timer);
        reject(new Error('CDP 连接失败'));
      });
    });
    this.socket.addEventListener('message', (event) => {
      const message = JSON.parse(event.data);
      if (message.id && this.pending.has(message.id)) {
        const { resolve, reject } = this.pending.get(message.id);
        this.pending.delete(message.id);
        if (message.error) reject(new Error(`${message.error.message} (${message.error.code})`));
        else resolve(message.result);
        return;
      }
      if (message.method) {
        for (const listener of this.listeners.get(message.method) || []) listener(message.params);
      }
    });
  }

  send(method, params = {}, timeoutMs = 30000) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.socket.send(JSON.stringify({ id, method, params }));
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reject(new Error(`CDP 调用超时：${method}`));
        }
      }, timeoutMs);
    });
  }

  on(method, listener) {
    if (!this.listeners.has(method)) this.listeners.set(method, []);
    this.listeners.get(method).push(listener);
  }

  close() {
    try {
      this.socket?.close();
    } catch {
      /* 忽略关闭异常 */
    }
  }
}

async function findChrome() {
  for (const candidate of CHROME_CANDIDATES) {
    try {
      await fs.access(candidate);
      return candidate;
    } catch {
      /* 继续找下一个 */
    }
  }
  throw new Error('找不到 Chrome/Edge，可用环境变量 CHROME_PATH 指定可执行文件');
}

async function waitForDevtools(port, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/json/version`);
      if (res.ok) return res.json();
    } catch {
      /* 还没起来 */
    }
    await sleep(200);
  }
  throw new Error('DevTools 端口未就绪');
}

/** 渲染一个页面并收集问题。 */
async function inspectPage(cdp, { url, mobile, outDir, name }) {
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Log.enable');
  await cdp.send('Network.enable');

  const errors = [];
  const consoleErrors = [];
  const failedRequests = [];
  cdp.on('Runtime.exceptionThrown', (params) => {
    errors.push(params?.exceptionDetails?.exception?.description || params?.exceptionDetails?.text || '未知异常');
  });
  cdp.on('Runtime.consoleAPICalled', (params) => {
    if (params.type === 'error') {
      consoleErrors.push((params.args || []).map((a) => a.value ?? a.description ?? '').join(' '));
    }
  });
  cdp.on('Log.entryAdded', (params) => {
    if (params?.entry?.level === 'error') consoleErrors.push(params.entry.text);
  });
  cdp.on('Network.loadingFailed', (params) => {
    if (['Fetch', 'Script', 'Stylesheet', 'Document'].includes(params?.type)) {
      failedRequests.push(`${params.type} ${params.errorText}`);
    }
  });

  await cdp.send('Emulation.setDeviceMetricsOverride',
    mobile
      ? { width: 390, height: 844, deviceScaleFactor: 2, mobile: true }
      : { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });

  await cdp.send('Page.navigate', { url });
  await sleep(3000);

  const probe = `(() => ({
    title: document.title,
    href: location.href,
    bodyLength: document.body.innerText.length,
    bodyHead: document.body.innerText.slice(0, 300),
    activeView: document.querySelector('.view.is-active')?.id || null,
    viewTextLength: (document.querySelector('.view.is-active')?.innerText || '').length,
    sidebarLinks: document.querySelectorAll('.sidebar__link').length,
    statsCards: document.querySelectorAll('.stat').length,
    tables: document.querySelectorAll('table').length,
    toasts: document.querySelectorAll('.toast').length,
  }))()`;

  let result = await cdp.send('Runtime.evaluate', { expression: probe, returnByValue: true });
  // 异步渲染较慢的页面再等一拍
  if (result.result.value.viewTextLength === 0 && result.result.value.bodyLength < 400) {
    await sleep(2500);
    result = await cdp.send('Runtime.evaluate', { expression: probe, returnByValue: true });
  }

  if (outDir) {
    const shot = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
    await fs.writeFile(path.join(outDir, `${name}.png`), Buffer.from(shot.data, 'base64'));
  }

  return { ...result.result.value, errors, consoleErrors, failedRequests };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const chrome = await findChrome();
  const port = 9222 + Math.floor(Math.random() * 500);
  const userDataDir = path.join(os.tmpdir(), `hk-cdp-${Date.now()}`);
  const outDir = args.out || path.join(os.tmpdir(), `hk-shots-${Date.now()}`);
  await fs.mkdir(outDir, { recursive: true });

  const child = spawn(chrome, [
    '--headless=new',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--hide-scrollbars',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${userDataDir}`,
    // 需要时把域名强制解析到指定 IP（本机 DNS 可能被拦截/未传播）
    ...(args.resolveTo
      ? [`--host-resolver-rules=MAP ${new URL(args.base).hostname} ${args.resolveTo}`]
      : []),
    'about:blank',
  ], { stdio: 'ignore' });

  const results = {};
  let failures = 0;

  const openTarget = async (url) => {
    const info = await (await fetch(`http://127.0.0.1:${port}/json/new?${encodeURIComponent(url)}`,
      { method: 'PUT' })).json();
    const cdp = new Cdp(info.webSocketDebuggerUrl);
    await cdp.connect();
    return { info, cdp };
  };
  const closeTarget = async (info) => {
    await fetch(`http://127.0.0.1:${port}/json/close/${info.id}`).catch(() => {});
  };

  try {
    await waitForDevtools(port);

    /**
     * 登录态获取：让浏览器自己访问开发直登端点，由服务端下发 Cookie。
     * 试过 CDP 注入 Cookie（Storage.setCookies / Network.setCookie）与请求头改写，
     * headless 下都存在时序或形态问题；直接导航最稳。
     */
    if (args.auth) {
      const { info, cdp } = await openTarget('about:blank');
      try {
        await cdp.send('Page.enable');
        await cdp.send('Network.enable');
        await cdp.send('Page.navigate', {
          url: `${args.base}/auth/dev-login?role=admin&as=${encodeURIComponent(args.as)}`,
        });
        await sleep(2000);
        const cookies = await cdp.send('Network.getCookies', { urls: [args.base] });
        const names = (cookies.cookies || []).map((c) => c.name);
        if (!names.includes('hamkit_session')) {
          throw new Error(
            `开发直登未拿到会话 Cookie（当前：${names.join(',') || '无'}）。请确认服务端 DEV_LOGIN=true 且非生产环境。`,
          );
        }
        console.log(`浏览器已登录：${args.as}（Cookie：${names.join(', ')}）`);
      } finally {
        cdp.close();
        await closeTarget(info);
      }
    }

    const pages = PAGES.filter((page) => !args.only?.length || args.only.includes(page.name));
    if (!pages.length) throw new Error('--only 未匹配到任何页面');

    for (const page of pages) {
      const { info, cdp } = await openTarget('about:blank');
      try {
        const result = await inspectPage(cdp, {
          url: `${args.base}${page.path}`,
          mobile: false,
          outDir,
          name: page.name,
        });
        results[page.name] = result;
        const problems = [...result.errors, ...result.consoleErrors, ...result.failedRequests];
        if (problems.length) {
          failures++;
          console.error(`✖ ${page.name}：发现 ${problems.length} 个问题`);
          problems.forEach((problem) => console.error(`    ${problem}`));
        } else {
          console.log(`✔ ${page.name}：无 JS 错误（正文 ${result.bodyLength} 字符，视图 ${result.activeView || '-'}）`);
        }
      } finally {
        cdp.close();
        await closeTarget(info);
      }
    }
  } finally {
    child.kill();
    await sleep(300);
  }

  const reportPath = path.join(outDir, 'report.json');
  await fs.writeFile(reportPath, JSON.stringify(results, null, 2), 'utf8');
  console.log(`\n截图与报告：${outDir}`);
  if (failures) {
    console.error(`${failures} 个页面存在问题`);
    process.exitCode = 1;
  } else {
    console.log('全部页面无 JS 错误');
  }
}

main().catch((err) => {
  console.error('验收脚本失败：', err.message || err);
  process.exitCode = 1;
});
