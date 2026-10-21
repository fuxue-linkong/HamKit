/**
 * 领域仓库：版本、站点内容、公告、用户、审计日志、统计。
 * 每个仓库都返回纯数据（不碰 HTTP），因此可以在测试里直接驱动。
 */

import { makeId, nowIso } from './store.mjs';

/* ------------------------------------------------------------------ *
 * 版本 / 更新
 * ------------------------------------------------------------------ */

const VERSION_KEY = 'versions';
const DEFAULT_VERSIONS = { items: [], updatedAt: null };

function normalizeVersion(input, previous = {}) {
  const versionCode = Number.parseInt(input.versionCode ?? previous.versionCode ?? 0, 10);  if (!Number.isFinite(versionCode) || versionCode < 1) {
    throw Object.assign(new Error('versionCode 必须是 ≥ 1 的整数'), { status: 400 });
  }
  const versionName = String(input.versionName ?? previous.versionName ?? '').trim();
  if (!versionName) {
    throw Object.assign(new Error('versionName 不能为空'), { status: 400 });
  }
  const downloadUrl = String(input.downloadUrl ?? previous.downloadUrl ?? '').trim();
  if (downloadUrl && !/^https?:\/\//i.test(downloadUrl) && !downloadUrl.startsWith('/')) {
    throw Object.assign(new Error('downloadUrl 必须是 http(s) 绝对地址或站内路径'), { status: 400 });
  }
  const channel = ['stable', 'beta', 'nightly'].includes(input.channel)
    ? input.channel
    : previous.channel || 'stable';
  return {
    id: previous.id || makeId('ver'),
    versionCode,
    versionName,
    tag: String(input.tag ?? previous.tag ?? `v${versionName}`).trim(),
    downloadUrl,
    changelog: String(input.changelog ?? previous.changelog ?? ''),
    channel,
    // 未显式给出 prerelease 时按渠道推导（改 channel 后无需再手工改 prerelease）
    prerelease: Boolean(
      input.prerelease ?? (channel !== 'stable' ? true : previous.prerelease ?? false),
    ),
    published: Boolean(input.published ?? previous.published ?? true),
    sizeBytes: Number.parseInt(input.sizeBytes ?? previous.sizeBytes ?? 0, 10) || null,
    sha256: String(input.sha256 ?? previous.sha256 ?? '').trim() || null,
    createdAt: previous.createdAt || nowIso(),
    updatedAt: nowIso(),
    updatedBy: input.updatedBy ?? previous.updatedBy ?? null,
  };
}

export function createVersionRepo(store) {
  const read = () => store.read(VERSION_KEY, DEFAULT_VERSIONS);

  const byCodeDesc = (a, b) => b.versionCode - a.versionCode;

  return {
    async list({ includeUnpublished = true } = {}) {
      const data = await read();
      const items = [...(data.items || [])].sort(byCodeDesc);
      return includeUnpublished ? items : items.filter((v) => v.published);
    },

    async get(id) {
      const data = await read();
      return (data.items || []).find((v) => v.id === id) || null;
    },

    /**
     * 最新可用版本（只考虑已发布）。
     * @param {object} [options]
     * @param {string} [options.channel] 指定渠道则严格按渠道取；未指定时优先 stable，
     *        避免 beta / nightly 抢走“最新版”的位置。
     */
    async latest({ channel } = {}) {
      const published = await this.list({ includeUnpublished: false });
      if (!published.length) return null;
      if (channel) return published.find((v) => v.channel === channel) || null;
      return published.find((v) => v.channel === 'stable') || published[0];
    },

    async create(input) {
      const item = normalizeVersion(input);
      await store.update(VERSION_KEY, DEFAULT_VERSIONS, (data) => {
        const items = data.items || [];
        if (items.some((v) => v.versionCode === item.versionCode)) {
          throw Object.assign(
            new Error(`versionCode ${item.versionCode} 已存在，请使用其他版本号`),
            { status: 409 },
          );
        }
        return { items: [...items, item], updatedAt: nowIso() };
      });
      return item;
    },

    /** 局部更新：只让显式传入的字段覆盖旧值，其余沿用（避免 undefined 冲掉默认推导）。 */
    async update(id, patch) {
      let updated = null;
      await store.update(VERSION_KEY, DEFAULT_VERSIONS, (data) => {
        const items = data.items || [];
        const idx = items.findIndex((v) => v.id === id);
        if (idx < 0) throw Object.assign(new Error('版本不存在'), { status: 404 });
        const previous = items[idx];
        // 只允许已声明的字段覆盖，避免 patch 里混入 id / createdAt 等身份字段
        const merged = { ...previous };
        for (const key of Object.keys(previous)) {
          if (patch[key] !== undefined) merged[key] = patch[key];
        }
        if (patch.updatedBy !== undefined) merged.updatedBy = patch.updatedBy;
        // prerelease 未显式给出时按渠道推导；merged 里带着旧值会让 ?? 失效，故此处显式处理
        if (patch.prerelease === undefined) {
          const channel = ['stable', 'beta', 'nightly'].includes(patch.channel)
            ? patch.channel
            : previous.channel;
          merged.prerelease = channel !== 'stable';
        }
        const next = normalizeVersion(merged, previous);
        // versionCode 唯一性校验（排除自身）
        if (items.some((v) => v.versionCode === next.versionCode && v.id !== id)) {
          throw Object.assign(new Error(`versionCode ${next.versionCode} 已被占用`), { status: 409 });
        }
        const copy = [...items];
        copy[idx] = next;
        updated = next;
        return { items: copy, updatedAt: nowIso() };
      });
      return updated;
    },

    async remove(id) {
      let removed = null;
      await store.update(VERSION_KEY, DEFAULT_VERSIONS, (data) => {
        const items = data.items || [];
        const target = items.find((v) => v.id === id);
        if (!target) throw Object.assign(new Error('版本不存在'), { status: 404 });
        removed = target;
        return { items: items.filter((v) => v.id !== id), updatedAt: nowIso() };
      });
      return removed;
    },

    /**
     * 兼容产物：App 与旧官网依赖的两个静态文件。
     *   update.json —— App 内更新检查（update.json / update_<渠道>.json）
     *   version.json —— 官网版本徽标
     * 由版本列表派生，禁止手改，保证只有一个事实来源。
     */
    async compatFiles() {
      const latest = await this.latest();
      const generatedAt = nowIso();
      if (!latest) {
        return {
          'update.json': {
            versionCode: 0,
            versionName: '',
            downloadUrl: '',
            changelog: '暂无已发布版本',
            sha256: '',
          },
          'version.json': { tag: null, prerelease: false, updated_at: generatedAt },
        };
      }
      const channels = {};
      for (const channel of ['stable', 'beta', 'nightly']) {
        const item = await this.latest({ channel });
        if (item) {
          channels[channel] = {
            versionCode: item.versionCode,
            versionName: item.versionName,
            downloadUrl: item.downloadUrl,
            changelog: item.changelog,
            sha256: item.sha256 || '',
          };
        }
      }
      return {
        'update.json': {
          versionCode: latest.versionCode,
          versionName: latest.versionName,
          downloadUrl: latest.downloadUrl,
          changelog: latest.changelog,
          sha256: latest.sha256 || '',
        },
        'version.json': {
          tag: latest.tag,
          prerelease: Boolean(latest.prerelease),
          updated_at: latest.updatedAt || generatedAt,
        },
        channels,
      };
    },
  };
}

/* ------------------------------------------------------------------ *
 * 站点内容（首页文案 / 功能卡 / 公告 / 下载配置 / 页脚）
 * ------------------------------------------------------------------ */

const CONTENT_KEY = 'content';

export const DEFAULT_CONTENT = {
  hero: {
    title: 'HamKit',
    subtitle: '从地面坐标到轨道预测，从摩尔斯电码到慢扫描电视。',
    description:
      '为业余无线电爱好者打造的随身控制台——分区定位、卫星过境预测与自定义分类、CW 摩尔斯训练、SSTV / HF Fax 图像解码、FT8 与 QSO 日志、APRS 一体化。',
    badgeText: '现已上线',
    primaryCta: { label: '查看应用预览', href: '#showcase' },
    secondaryCta: { label: '下载 APK', href: '#download' },
    screenshots: [],
  },
  features: [
    {
      id: 'positioning',
      title: '分区定位',
      text: '一键获取 GPS 坐标，实时计算 CQ Zone、ITU Zone 与 6 位 Maidenhead 网格定位，配合反向地理编码显示地址（3 秒去抖、失败指数退避自动恢复）。',
      tags: ['CQ Zone', 'ITU Zone', 'Maidenhead', 'Geocoder'],
      badge: null,
      enabled: true,
    },
    {
      id: 'satellite',
      title: '卫星过境预测',
      text: '自研 SGP4/SDP4 轨道计算引擎，支持 CelesTrak 与 SatNOGS 数据源，并行预测未来 48 小时过境。详情页给出实时方位 / 仰角 / 距离 / 速度与多普勒频移，内置 SatNOGS 转发器频率库与 AMSAT 状态，雷达图、星下点轨迹地图一应俱全。',
      tags: ['SGP4', 'CelesTrak', 'SatNOGS', '多普勒', '雷达图', 'AMSAT'],
      badge: null,
      enabled: true,
    },
    {
      id: 'satellite-category',
      title: '卫星自定义分类',
      text: '标签式分类，一颗卫星可归入多个分类；原「收藏」已平移为内置分类「我的关注」，历史数据自动迁移。「组织」与「提醒」彻底分离——把 50 颗卫星批量归入资料型分类，不会瞬间产生 50 条闹钟。分类配置可导出分享，导入按 id 合并。',
      tags: ['自定义分类', '配置导入导出', '筛选'],
      badge: null,
      enabled: true,
    },
    {
      id: 'cw',
      title: 'CW 摩尔斯练习',
      text: '完整 Koch 课程（26 课）+ 字符组 / 呼号 / 文本训练。AudioTrack 实时合成正弦波，可调 WPM、音调与播放模式，练习进度本地跟踪，随时接着上次继续。',
      tags: ['Koch 方法', '字符组', '呼号训练', '文本训练', '进度跟踪'],
      badge: null,
      enabled: true,
    },
    {
      id: 'sstv',
      title: 'SSTV 慢扫描电视',
      text: '纯 Kotlin 自研 DSP，零第三方依赖：正交鉴频 + VIS 头滑窗识别（±120 Hz 频偏仍可锁定），PD / Robot / Martin / Scottie / Wrasse 全族共 19 种模式。行同步 + slant 最小二乘校正补偿收发时钟差，信号瀑布图辅助调谐，实时逐行预览，VIS 缺失时可盲扫反推模式。',
      tags: ['19 种模式', 'VIS 识别', '盲扫', '瀑布图', '失谐补偿'],
      badge: null,
      enabled: true,
    },
    {
      id: 'sstv-tx',
      title: 'SSTV 发射与图库',
      text: '自研编码器可从相册或图库选图发射，支持呼号叠加与 VIS 头开关，无电台时可导出 WAV 用音频线接 LINE IN。解码结果自动入库：浏览、全屏查看、分享、导出相册，也能导入 WAV 录音离线重解——当时没解好的帧还有机会救回来。',
      tags: ['编码发射', '呼号叠加', '导出 WAV', '录音重解'],
      badge: null,
      enabled: true,
    },
    {
      id: 'hf-fax',
      title: 'HF Fax 气象传真',
      text: '接收 WEFAX 天气图：120 行/分，无 VIS、无行同步、灰度连续成像——与 SSTV 完全不同的协议，因此独立成页。适合守听海岸电台的天气图播发。',
      tags: ['WEFAX', '120 行/分', '灰度成像'],
      badge: null,
      enabled: true,
    },
    {
      id: 'reminder',
      title: '过境提醒',
      text: 'AlarmManager 精确闹钟 + WorkManager 每日刷新，AOS 前可配提前量提醒。重启自动恢复，支持仅白天模式；提醒开关逐星独立，与分类互不干扰。',
      tags: ['AlarmManager', 'WorkManager', 'BootReceiver'],
      badge: null,
      enabled: true,
    },
    {
      id: 'ft8',
      title: 'FT8 数字模式',
      text: '基于 WSJT-X / ft8_lib 标准：LDPC(174,91) 软判决解码、CRC-14、GFSK 调制与 15 秒时隙对齐，支持与真实 FT8 信号互通；内置波段选择与消息编辑，可一键生成待发消息。',
      tags: ['FT8', 'LDPC(174,91)', 'GFSK', '软判决'],
      badge: '打磨中',
      enabled: true,
    },
    {
      id: 'aprs',
      title: 'APRS 位置报告',
      text: '接入 APRS-IS：实时接收报文与站点列表，站点地图定位，支持消息收发与符号选择。坚守合规——不计算也不发放 Passcode，未填写时以只读模式连接（pass -1，可收不可发），首次连接前需确认持照与责任声明。',
      tags: ['APRS-IS', '站点地图', '消息收发', '只读模式'],
      badge: null,
      enabled: true,
    },
    {
      id: 'qso',
      title: 'QSO 通联日志',
      text: 'FT8 通联记录自动落库，支持增删改与呼号前缀 / 频段 / 时间范围筛选，标记完成状态。可导出 ADIF 3.1.4（.adi）与 ADX 兼容 XML（.xml），按当前筛选或全部记录导出，保存到文件或直接分享。',
      tags: ['ADIF 3.1.4', 'ADX XML', '筛选', '分享导出'],
      badge: null,
      enabled: true,
    },
  ],
  download: {
    title: '下载 HamKit',
    description: '直接下载最新 APK，或前往 GitHub Releases 查看历史版本。',
    mirrorUrl: 'https://apk.hamkit.click/',
    showMirror: true,
    minAndroid: 'Android 8.0（API 26）及以上',
    requirements: ['需定位权限', 'targetSdk 37', 'MIT License'],
  },
  footer: {
    tagline: '为业余无线电爱好者而生的随身控制台。',
    icp: '',
    copyright: '© 2026 HamKit · MIT License',
    signature: '73 · SK',
  },  devs: [
    {
      name: 'fuxuelingkong',
      email: 'fuxuelingkong@outlook.com',
      github: 'fuxue-linkong',
      avatar: './images/avatar.jpg',
    },
    {
      name: 'Run Liu',
      email: 'liurun637@gmail.com',
      github: '2048lr',
      avatar: 'https://github.com/2048lr.png',
    },
  ],
  seo: {
    title: 'HamKit · 业余无线电随身控制台',
    description:
      'HamKit —— 为业余无线电爱好者打造的随身控制台。分区定位、卫星过境预测与自定义分类、CW 摩尔斯训练、SSTV / HF Fax 图像解码、FT8 与 QSO 日志、APRS。',
    keywords: [
      'HamKit',
      '业余无线电',
      'SGP4',
      '卫星过境',
      'CW',
      '摩尔斯',
      'SSTV',
      'HF Fax',
      'FT8',
      'APRS',
      'QSO 日志',
      'ADIF',
    ],
  },
  updatedAt: null,
  updatedBy: null,
};

/** 浅层合并：只让白名单字段可写，避免后台塞进任意结构。 */
function mergeDeep(base, patch) {
  if (patch === undefined) return base;
  if (Array.isArray(patch)) return patch;
  if (patch === null || typeof patch !== 'object') return patch;
  if (base === null || typeof base !== 'object' || Array.isArray(base)) return patch;
  const out = { ...base };
  for (const [key, value] of Object.entries(patch)) {
    out[key] = mergeDeep(base[key], value);
  }
  return out;
}

export function createContentRepo(store) {
  return {
    async get() {
      // 注意：这里的合并不能省。若线上存的是老版本写入的 content，
      // mergeDeep 会把新增的默认键（如后来加的 feature 卡）补回去；
      // 直接返回 stored 会丢掉这些新默认值。
      const stored = await store.read(CONTENT_KEY, null);
      if (!stored) return { ...DEFAULT_CONTENT };
      return mergeDeep(DEFAULT_CONTENT, stored);
    },
    async patch(patch, { actor = null } = {}) {
      let saved = null;
      await store.update(CONTENT_KEY, DEFAULT_CONTENT, (current) => {
        const base = current && Object.keys(current).length ? current : DEFAULT_CONTENT;
        saved = mergeDeep(mergeDeep(DEFAULT_CONTENT, base), patch);
        saved.updatedAt = nowIso();
        saved.updatedBy = actor?.id || null;
        return saved;
      });
      return saved;
    },
    async reset({ actor = null } = {}) {
      const fresh = { ...DEFAULT_CONTENT, updatedAt: nowIso(), updatedBy: actor?.id || null };
      await store.write(CONTENT_KEY, fresh);
      return fresh;
    },
  };
}

/* ------------------------------------------------------------------ *
 * 公告
 * ------------------------------------------------------------------ */

const ANNOUNCEMENT_KEY = 'announcements';

function normalizeAnnouncement(input, previous = {}) {
  const title = String(input.title ?? previous.title ?? '').trim();
  if (!title) throw Object.assign(new Error('公告标题不能为空'), { status: 400 });
  const severity = ['info', 'success', 'warning', 'critical'].includes(input.severity)
    ? input.severity
    : previous.severity || 'info';
  const toIso = (v, fallback) => {
    if (v === null || v === '') return null;
    if (v === undefined) return fallback;
    const d = new Date(v);
    if (Number.isNaN(d.getTime())) {
      throw Object.assign(new Error(`时间格式无效：${v}`), { status: 400 });
    }
    return d.toISOString();
  };
  const publishAt = toIso(input.publishAt, previous.publishAt ?? nowIso());
  const expireAt = toIso(input.expireAt, previous.expireAt ?? null);
  if (publishAt && expireAt && new Date(expireAt) <= new Date(publishAt)) {
    throw Object.assign(new Error('失效时间必须晚于发布时间'), { status: 400 });
  }
  return {
    id: previous.id || makeId('ann'),
    title,
    body: String(input.body ?? previous.body ?? ''),
    severity,
    link: String(input.link ?? previous.link ?? '').trim() || null,
    pinned: Boolean(input.pinned ?? previous.pinned ?? false),
    enabled: Boolean(input.enabled ?? previous.enabled ?? true),
    publishAt: publishAt || nowIso(),
    expireAt,
    createdAt: previous.createdAt || nowIso(),
    updatedAt: nowIso(),
    updatedBy: input.updatedBy ?? previous.updatedBy ?? null,
  };
}

export function createAnnouncementRepo(store) {
  const read = () => store.read(ANNOUNCEMENT_KEY, { items: [] });
  return {
    async list({ publicOnly = false, now = Date.now() } = {}) {
      const data = await read();
      let items = [...(data.items || [])];
      if (publicOnly) {
        items = items.filter(
          (a) =>
            a.enabled &&
            new Date(a.publishAt).getTime() <= now &&
            (!a.expireAt || new Date(a.expireAt).getTime() > now),
        );
      }
      return items.sort((a, b) => {
        if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
        return new Date(b.publishAt) - new Date(a.publishAt);
      });
    },
    async get(id) {
      return (await read()).items.find((a) => a.id === id) || null;
    },
    async create(input) {
      const item = normalizeAnnouncement(input);
      await store.update(ANNOUNCEMENT_KEY, { items: [] }, (data) => ({
        items: [...(data.items || []), item],
      }));
      return item;
    },
    async update(id, patch) {
      let updated = null;
      await store.update(ANNOUNCEMENT_KEY, { items: [] }, (data) => {
        const items = data.items || [];
        const idx = items.findIndex((a) => a.id === id);
        if (idx < 0) throw Object.assign(new Error('公告不存在'), { status: 404 });
        const next = normalizeAnnouncement({ ...items[idx], ...patch }, items[idx]);
        updated = next;
        const copy = [...items];
        copy[idx] = next;
        return { items: copy };
      });
      return updated;
    },
    async remove(id) {
      let removed = null;
      await store.update(ANNOUNCEMENT_KEY, { items: [] }, (data) => {
        const items = data.items || [];
        removed = items.find((a) => a.id === id) || null;
        if (!removed) throw Object.assign(new Error('公告不存在'), { status: 404 });
        return { items: items.filter((a) => a.id !== id) };
      });
      return removed;
    },
  };
}

/* ------------------------------------------------------------------ *
 * 用户与角色
 * ------------------------------------------------------------------ */

const USER_KEY = 'users';

/**
 * 把 IdP 下发的角色声明归一化成「小写角色名数组」。
 *
 * 必须兼容三种真实形态（不同 IdP 差别很大，这里踩过坑）：
 *   1. 字符串数组：`["admin", "editor"]` —— 标准 OIDC、Casdoor 的 JWT-Custom
 *   2. 逗号/空白分隔字符串：`"admin editor"`、`"admin,editor"`
 *   3. **对象数组**：`[{ owner, name, displayName, ... }]`
 *      Casdoor 默认 TokenFormat=JWT 时 ID Token 的 `roles` 就是 `[]*Role` 对象数组。
 *      早先实现用 `String(role)` 得到 `"[object Object]"`，导致 OIDC_ROLE_MAP 静默匹配失败。
 *      这里按 name → displayName → key 的顺序取名字，取不到就丢弃该元素。
 */
export function normalizeRoleClaim(value) {
  const pickName = (item) => {
    if (typeof item === 'string') return item.trim();
    if (item && typeof item === 'object') {
      const candidate = item.name ?? item.role ?? item.displayName ?? item.key ?? item.value;
      return typeof candidate === 'string' ? candidate.trim() : '';
    }
    if (typeof item === 'number') return String(item);
    return '';
  };

  const raw = Array.isArray(value)
    ? value
    : typeof value === 'string'
      ? value.split(/[\s,]+/)
      : value && typeof value === 'object'
        ? [value]
        : [];

  return raw
    .map(pickName)
    .filter(Boolean);
}

function normalizeUserOidc(profile, config) {
  const claims = profile || {};
  const sub = claims.sub;
  if (!sub) throw Object.assign(new Error('OIDC profile 缺少 sub'), { status: 400 });
  const email = (claims.email || '').toLowerCase() || null;
  const username =
    claims.preferred_username || claims.nickname || claims.name || (email ? email.split('@')[0] : sub);
  const roleClaimKey = config?.oidc?.roleClaim || 'roles';
  const idpRoles = normalizeRoleClaim(claims[roleClaimKey]);
  return {
    oidcSub: String(sub),
    email,
    emailVerified: Boolean(claims.email_verified),
    username: String(username),
    displayName: String(claims.name || username),
    avatar: claims.picture || null,
    idpRoles,
    issuer: claims.iss || null,
  };
}

export function createUserRepo(store, { config, logger = console } = {}) {
  const read = () => store.read(USER_KEY, { items: [] });

  /**
   * 角色决策（优先级从高到低）：
   *   1) 库内被管理员锁定过的角色 —— 人工设置永远优先于 IdP 声明
   *   2) BOOTSTRAP_ADMINS 命中（邮箱 / 用户名 / sub）
   *   3) OIDC_ROLE_MAP 把 IdP 角色映射成本站角色
   *   4) 库内既有角色（续期不变）
   *   5) 首个登录用户（FIRST_USER_IS_ADMIN）
   *   6) 默认 viewer
   */
  function resolveRole({ existing, oidc, userCount, cfg }) {
    if (existing?.roleLocked) return existing.role;
    const identities = [oidc.email, oidc.username, oidc.oidcSub]
      .filter(Boolean)
      .map((s) => String(s).toLowerCase());
    if (identities.some((id) => cfg.auth.bootstrapAdmins.includes(id))) return 'admin';
    for (const idpRole of oidc.idpRoles) {
      const mapped = cfg.oidc.roleMap?.[String(idpRole).toLowerCase()];
      if (mapped) return mapped;
    }
    if (existing?.role) return existing.role;
    if (userCount === 0 && cfg.auth.firstUserIsAdmin) return 'admin';
    return 'viewer';
  }

  function checkEmailPolicy(oidc, cfg) {
    const domains = cfg.auth.allowedEmailDomains;
    if (!domains.length) return;
    if (!oidc.email) {
      throw Object.assign(new Error('本站要求邮箱登录，但 IdP 未返回 email'), { status: 403 });
    }
    const domain = oidc.email.split('@')[1] || '';
    if (!domains.includes(domain)) {
      throw Object.assign(new Error(`邮箱域名 @${domain} 不在允许列表内`), { status: 403 });
    }
  }

  return {
    async list() {
      const data = await read();
      return [...(data.items || [])].sort((a, b) => (b.lastLoginAt || '').localeCompare(a.lastLoginAt || ''));
    },

    async get(id) {
      return (await read()).items.find((u) => u.id === id) || null;
    },

    async findBySub(sub) {
      return (await read()).items.find((u) => u.oidcSub === sub) || null;
    },

    /**
     * 登录时落库：首次创建，之后更新资料与登录统计。
     * 返回 { user, created, roleChanged }。
     */
    async upsertFromOidc(profile, { ip = null, userAgent = null, forceRole = null } = {}) {
      const oidc = normalizeUserOidc(profile, config);
      checkEmailPolicy(oidc, config);
      let result = null;
      await store.update(USER_KEY, { items: [] }, (data) => {
        const items = data.items || [];
        const idx = items.findIndex((u) => u.oidcSub === oidc.oidcSub);
        const existing = idx >= 0 ? items[idx] : null;
        const resolved = forceRole || resolveRole({ existing, oidc, userCount: items.length, cfg: config });
        if (!['admin', 'editor', 'viewer'].includes(resolved)) {
          throw Object.assign(new Error(`解析出的角色非法：${resolved}`), { status: 400 });
        }
        const role = resolved;
        const now = nowIso();
        const merged = {
          id: existing?.id || makeId('usr'),
          oidcSub: oidc.oidcSub,
          issuer: oidc.issuer,
          email: oidc.email,
          emailVerified: oidc.emailVerified,
          username: oidc.username,
          displayName: oidc.displayName,
          avatar: oidc.avatar,
          idpRoles: oidc.idpRoles,
          role,
          roleLocked: existing?.roleLocked ?? false,
          disabled: existing?.disabled ?? false,
          note: existing?.note ?? '',
          createdAt: existing?.createdAt || now,
          updatedAt: now,
          lastLoginAt: now,
          lastLoginIp: ip,
          lastLoginUserAgent: userAgent ? String(userAgent).slice(0, 240) : null,
          loginCount: (existing?.loginCount || 0) + 1,
        };
        const copy = idx >= 0 ? [...items] : [...items, merged];
        if (idx >= 0) copy[idx] = merged;
        result = {
          user: merged,
          created: idx < 0,
          roleChanged: Boolean(existing && existing.role !== role),
        };
        return { items: copy };
      });
      return result;
    },

    async setRole(id, role, { actor = null, lock = true } = {}) {
      if (!['admin', 'editor', 'viewer'].includes(role)) {
        throw Object.assign(new Error(`未知角色：${role}`), { status: 400 });
      }
      let updated = null;
      await store.update(USER_KEY, { items: [] }, (data) => {
        const items = data.items || [];
        const idx = items.findIndex((u) => u.id === id);
        if (idx < 0) throw Object.assign(new Error('用户不存在'), { status: 404 });
        const admins = items.filter((u) => u.role === 'admin' && !u.disabled);
        // 不允许把最后一个管理员降级，否则后台永久锁死
        if (items[idx].role === 'admin' && role !== 'admin' && admins.length <= 1) {
          throw Object.assign(new Error('不能降级最后一个管理员账号'), { status: 409 });
        }
        const next = {
          ...items[idx],
          role,
          roleLocked: lock,
          updatedAt: nowIso(),
          updatedBy: actor?.id || null,
        };
        updated = next;
        const copy = [...items];
        copy[idx] = next;
        return { items: copy };
      });
      return updated;
    },

    async setDisabled(id, disabled, { actor = null } = {}) {
      let updated = null;
      await store.update(USER_KEY, { items: [] }, (data) => {
        const items = data.items || [];
        const idx = items.findIndex((u) => u.id === id);
        if (idx < 0) throw Object.assign(new Error('用户不存在'), { status: 404 });
        const admins = items.filter((u) => u.role === 'admin' && !u.disabled);
        if (disabled && items[idx].role === 'admin' && admins.length <= 1) {
          throw Object.assign(new Error('不能停用最后一个管理员账号'), { status: 409 });
        }
        const next = { ...items[idx], disabled: Boolean(disabled), updatedAt: nowIso(), updatedBy: actor?.id || null };
        updated = next;
        const copy = [...items];
        copy[idx] = next;
        return { items: copy };
      });
      return updated;
    },

    async remove(id, { actor = null } = {}) {
      let removed = null;
      await store.update(USER_KEY, { items: [] }, (data) => {
        const items = data.items || [];
        const target = items.find((u) => u.id === id);
        if (!target) throw Object.assign(new Error('用户不存在'), { status: 404 });
        const admins = items.filter((u) => u.role === 'admin' && !u.disabled);
        if (target.role === 'admin' && admins.length <= 1) {
          throw Object.assign(new Error('不能删除最后一个管理员账号'), { status: 409 });
        }
        removed = target;
        return { items: items.filter((u) => u.id !== id) };
      });
      return removed;
    },

    async counts() {
      const items = (await read()).items || [];
      const byRole = { admin: 0, editor: 0, viewer: 0 };
      let disabled = 0;
      for (const u of items) {
        if (u.disabled) disabled++;
        else byRole[u.role] = (byRole[u.role] || 0) + 1;
      }
      return { total: items.length, byRole, disabled };
    },

    /** 管理员备注（纯文本，最长 500 字）。 */
    async patchNote(id, note, { actor = null } = {}) {
      let updated = null;
      await store.update(USER_KEY, { items: [] }, (data) => {
        const items = data.items || [];
        const idx = items.findIndex((u) => u.id === id);
        if (idx < 0) throw Object.assign(new Error('用户不存在'), { status: 404 });
        const next = {
          ...items[idx],
          note: String(note ?? '').slice(0, 500),
          updatedAt: nowIso(),
          updatedBy: actor?.id || null,
        };
        updated = next;
        const copy = [...items];
        copy[idx] = next;
        return { items: copy };
      });
      return updated;
    },
  };
}

/* ------------------------------------------------------------------ *
 * 审计日志
 * ------------------------------------------------------------------ */

const AUDIT_KEY = 'audit';

export function createAuditRepo(store, { limit = 5000 } = {}) {
  let memoryCache = null;

  async function load() {
    if (memoryCache) return memoryCache;
    memoryCache = await store.read(AUDIT_KEY, { items: [] });
    return memoryCache;
  }

  return {
    /**
     * 记录一条操作。写入永不抛错（审计失败不应该让业务请求失败）。
     */
    async record({ action, actor = null, target = null, detail = null, ip = null, userAgent = null, result = 'ok' }) {
      try {
        const entry = {
          id: makeId('aud'),
          at: nowIso(),
          action,
          result,
          actorId: actor?.id || null,
          actorName: actor?.displayName || actor?.username || null,
          actorRole: actor?.role || null,
          target: target ? String(target) : null,
          detail: detail || null,
          ip,
          userAgent: userAgent ? String(userAgent).slice(0, 200) : null,
        };
        await store.update(AUDIT_KEY, { items: [] }, (data) => {
          const items = [...(data.items || []), entry];
          const trimmed = items.length > limit ? items.slice(items.length - limit) : items;
          memoryCache = { items: trimmed };
          return memoryCache;
        });
        return entry;
      } catch {
        return null;
      }
    },

    async list({ limit: take = 100, offset = 0, action, actorId, q } = {}) {
      const data = await load();
      let items = [...(data.items || [])].reverse();
      if (action) items = items.filter((e) => e.action === action);
      if (actorId) items = items.filter((e) => e.actorId === actorId);
      if (q) {
        const needle = String(q).toLowerCase();
        items = items.filter((e) =>
          [e.action, e.actorName, e.target, JSON.stringify(e.detail || '')]
            .filter(Boolean)
            .some((v) => String(v).toLowerCase().includes(needle)),
        );
      }
      const total = items.length;
      return { total, items: items.slice(offset, offset + take) };
    },

    async actions() {
      const data = await load();
      const set = new Set((data.items || []).map((e) => e.action));
      return [...set].sort();
    },
  };
}

/* ------------------------------------------------------------------ *
 * 统计（访问量 / 下载量）
 * ------------------------------------------------------------------ */

const STATS_KEY = 'stats';
const STATS_SHARD_SEQ = '000';

function dayKey(ts = Date.now()) {
  return new Date(ts).toISOString().slice(0, 10);
}

export function createStatsRepo(store, { config } = {}) {
  const enabled = config?.stats?.enabled !== false;

  return {
    enabled,

    /** 记一次事件（页访问 / 下载 / 登录）。 */
    async record(type, { path = null, ip = null, ua = null, ref = null, userId = null, meta = null } = {}) {
      if (!enabled) return;
      try {
        await store.append(
          'stats',
          {
            t: Date.now(),
            type,
            path,
            ref: ref ? String(ref).slice(0, 200) : null,
            userId,
            ua: ua ? String(ua).slice(0, 160) : null,
            ip: config?.stats?.storeIp ? ip : null,
            meta,
          },
          { shard: dayKey() },
        );
        await store.update(STATS_KEY, { totals: {}, days: [] }, (data) => {
          const totals = { ...(data.totals || {}) };
          totals[type] = (totals[type] || 0) + 1;

          const today = dayKey();
          const days = (data.days || []).map((d) =>
            d.date === today ? { date: d.date, counts: { ...d.counts } } : d,
          );
          let entry = days.find((d) => d.date === today);
          if (!entry) {
            entry = { date: today, counts: {} };
            days.push(entry);
          }
          entry.counts[type] = (entry.counts[type] || 0) + 1;

          const trimmed = days
            .sort((a, b) => a.date.localeCompare(b.date))
            .slice(-(config?.stats?.retentionDays || 180));
          return { totals, days: trimmed };
        });
      } catch {
        /* 统计失败不影响业务 */
      }
    },

    /** 汇总：总量 + 近 N 天曲线 + 路径 Top。 */
    async summary({ days = 30 } = {}) {
      const data = await store.read(STATS_KEY, { totals: {}, days: [] });
      const cutoff = Date.now() - days * 86400_000;
      const recent = (data.days || []).filter((d) => new Date(d.date).getTime() >= cutoff);
      const series = recent
        .slice()
        .sort((a, b) => a.date.localeCompare(b.date))
        .map((d) => ({
          date: d.date,
          views: d.counts?.pageview || 0,
          downloads: d.counts?.download || 0,
          logins: d.counts?.login || 0,
        }));
      const windowTotals = recent.reduce(
        (acc, d) => {
          acc.views += d.counts?.pageview || 0;
          acc.downloads += d.counts?.download || 0;
          acc.logins += d.counts?.login || 0;
          return acc;
        },
        { views: 0, downloads: 0, logins: 0 },
      );
      const today = (data.days || []).find((d) => d.date === dayKey());
      return {
        totals: data.totals || {},
        window: { days, ...windowTotals },
        today: {
          views: today?.counts?.pageview || 0,
          downloads: today?.counts?.download || 0,
          logins: today?.counts?.login || 0,
        },
        series,
      };
    },

    /** 原始事件（排查用，按日分片）。 */
    async events({ date, limit = 200 } = {}) {
      const shard = date || dayKey();
      return store.readJsonl('stats', { shard, limit });
    },
  };
}

export { dayKey };
