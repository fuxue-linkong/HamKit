/* ============================================================
   HamKit 管理后台前端
   —— 纯浏览器 JS（ES5 语法为主），无构建工具、无框架、无外部依赖。
   依赖：/assets/site.js（HamKit.api / session / dom / fmt / toast / confirm /
                          sparkline / copyText / debounce）
         /assets/markdown.js（HamKit.md.renderInto，内部已转义）
   安全约定：任何来自 API 的字符串都必须经 dom.escape 或 textContent 进入 DOM；
             不使用 innerHTML 拼接未转义数据。
   ============================================================ */

(function (global) {
    'use strict';

    var HamKit = (global.HamKit = global.HamKit || {});
    var dom = HamKit.dom;
    var fmt = HamKit.fmt;
    var api = HamKit.api;
    var session = HamKit.session;
    var toast = HamKit.toast;
    var $ = dom.$;
    var $$ = dom.$$;
    var el = dom.el;
    var esc = dom.escape;

    /* ============================================================
       常量与小工具
       ============================================================ */

    var ROLE_OPTIONS = ['admin', 'editor', 'viewer'];
    var CHANNEL_OPTIONS = ['stable', 'beta', 'nightly'];
    var SEVERITY_OPTIONS = ['info', 'success', 'warning', 'critical'];
    var SEVERITY_BADGE = { info: 'badge--info', success: 'badge--success', warning: 'badge--warning', critical: 'badge--danger' };
    var SEVERITY_TEXT = { info: '信息', success: '成功', warning: '警告', critical: '严重' };

    /** 数组 ↔ 文本域（每行一项）。 */
    function linesToArray(text) {
        return String(text == null ? '' : text)
            .split(/\r?\n/)
            .map(function (line) { return line.trim(); })
            .filter(Boolean);
    }
    function arrayToLines(value) {
        return Array.isArray(value) ? value.join('\n') : (value == null ? '' : String(value));
    }
    /** 逗号 / 顿号分隔 → 数组。 */
    function csvToArray(text) {
        return String(text == null ? '' : text)
            .split(/[,，]/)
            .map(function (s) { return s.trim(); })
            .filter(Boolean);
    }
    function arrayToCsv(value) {
        return Array.isArray(value) ? value.join(', ') : '';
    }
    /** 过滤 null/undefined，供 dom.el 的子节点数组使用。 */
    function list() {
        return Array.prototype.slice.call(arguments).filter(function (v) { return v !== null && v !== undefined && v !== false; });
    }
    /** ISO → datetime-local 输入值（本地时区）。 */
    function isoToLocalInput(value) {
        if (!value) return '';
        var d = new Date(value);
        if (isNaN(d.getTime())) return '';
        var pad = function (n) { return String(n).padStart(2, '0'); };
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
            'T' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    }
    /** datetime-local 输入值 → ISO（留空返回 null）。 */
    function localInputToIso(value) {
        var raw = String(value == null ? '' : value).trim();
        if (!raw) return null;
        var d = new Date(raw);
        if (isNaN(d.getTime())) return null;
        return d.toISOString();
    }
    /** 结构化字段的可见文本（对象转 JSON，数组换行）。 */
    function toTextValue(value) {
        if (value == null) return '';
        if (Array.isArray(value)) return value.join('\n');
        if (typeof value === 'object') return JSON.stringify(value);
        return String(value);
    }
    /** JSON 美化（失败则退回 String）。 */
    function prettyJson(value) {
        try {
            return JSON.stringify(value, null, 2);
        } catch (err) {
            return String(value);
        }
    }
    /** 统一的空态。 */
    function emptyState(icon, title, text) {
        return el('div', { class: 'empty' }, [
            el('div', { class: 'empty__icon', text: icon || '∅' }),
            el('div', { class: 'empty__title', text: title || '暂无数据' }),
            text ? el('div', { class: 'empty__text', text: text }) : null,
        ]);
    }
    function loadingLine(text) {
        return el('div', { class: 'loading-line' }, [
            el('span', { class: 'btn__spinner' }),
            el('span', { text: text || '加载中…' }),
        ]);
    }
    function clear(node) {
        while (node && node.firstChild) node.removeChild(node.firstChild);
        return node;
    }
    /** 构建 <dl class="kv">；值为 Node 时直接插入。 */
    function kvBlock(pairs) {
        var dl = el('dl', { class: 'kv' });
        pairs.forEach(function (pair) {
            if (!pair) return;
            dl.appendChild(el('dt', { text: pair[0] }));
            var dd = el('dd');
            if (pair[1] instanceof Node) dd.appendChild(pair[1]);
            else dd.textContent = pair[1] === null || pair[1] === undefined || pair[1] === '' ? '—' : String(pair[1]);
            dl.appendChild(dd);
        });
        return dl;
    }
    /** 角色徽标。 */
    function roleBadge(role) {
        return el('span', { class: 'badge role-' + (ROLE_OPTIONS.indexOf(role) >= 0 ? role : 'viewer'), text: fmt.role(role) });
    }

    /* ============================================================
       视图注册表
       ============================================================ */

    var VIEWS = {
        overview: { title: '概览', desc: '站点访问、下载与最近操作一览', read: 'stats:read' },
        versions: { title: '版本管理', desc: '发布版本、渠道与 App 更新兼容文件', read: 'version:read', actions: true },
        content: { title: '首页内容', desc: '首屏文案、功能卡、下载区与页脚', read: 'content:read', actions: true },
        announcements: { title: '公告管理', desc: '站点公告的发布、置顶与时效', read: 'announcement:read', actions: true },
        users: { title: '用户管理', desc: '角色分配、停用与强制下线', read: 'user:read' },
        audit: { title: '审计日志', desc: '所有后台写操作的留痕', read: 'audit:read' },
        system: { title: '系统状态', desc: '运行环境、OIDC、安全配置与在线会话', read: 'system:read' },
        forbidden: { title: '无访问权限', desc: '当前账号没有可用的后台权限' },
    };

    var NAV_ITEMS = [
        { view: 'overview', label: '概览', read: 'stats:read', icon: 'M3 13h4l3 7 4-16 3 9h4' },
        { view: 'versions', label: '版本管理', read: 'version:read', icon: 'M21 16V8l-9-5-9 5v8l9 5 9-5zM3 8l9 5 9-5M12 21v-8' },
        { view: 'content', label: '首页内容', read: 'content:read', icon: 'M4 4h16v16H4zM8 9h8M8 13h8M8 17h4' },
        { view: 'announcements', label: '公告', read: 'announcement:read', icon: 'M3 11l18-7-7 18-2.5-7.5L3 11z' },
        { view: 'users', label: '用户', read: 'user:read', icon: 'M16 21v-2a4 4 0 00-8 0v2M12 11a4 4 0 100-8 4 4 0 000 8z' },
        { view: 'audit', label: '审计日志', read: 'audit:read', icon: 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2M9 12h6M9 16h4' },
        { view: 'system', label: '系统状态', read: 'system:read', icon: 'M12 15a3 3 0 100-6 3 3 0 000 6zM19.4 15a1.6 1.6 0 00.3 1.8l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.6 1.6 0 00-2.7 1.1V21a2 2 0 11-4 0v-.1A1.6 1.6 0 005.5 19l-.1.1a2 2 0 11-2.8-2.8l.1-.1A1.6 1.6 0 003 13.6H3a2 2 0 110-4h.1A1.6 1.6 0 004.9 8L4.8 8a2 2 0 112.8-2.8l.1.1A1.6 1.6 0 0010.4 4.2V4a2 2 0 114 0v.1a1.6 1.6 0 002.7 1.1l.1-.1a2 2 0 112.8 2.8l-.1.1a1.6 1.6 0 001.1 2.7H21a2 2 0 110 4h-.1a1.6 1.6 0 00-1.5 1z' },
    ];

    /* ============================================================
       应用状态
       ============================================================ */

    var state = {
        view: null,
        access: {},          // view → 是否可读
        features: [],        // 首页功能卡编辑缓存
        overviewSeries: [],  // 概览曲线数据（窗口尺寸变化时重绘）
        loaded: {},          // view → 最近一次加载时间
        audit: { limit: 20, offset: 0, action: '', q: '', total: 0, actions: [] },
        session: null,
    };

    /** 当前是否需要跳过缓存。 */
    function invalidate(view) {
        if (view) delete state.loaded[view];
        else state.loaded = {};
    }

    /** 允许访问的第一个视图。 */
    function firstAllowedView() {
        for (var i = 0; i < NAV_ITEMS.length; i++) {
            if (state.access[NAV_ITEMS[i].view]) return NAV_ITEMS[i].view;
        }
        return 'forbidden';
    }

    /** 通用带错误提示的请求。 */
    function load(path, options, failMessage) {
        return api.get(path, options).catch(function (err) {
            if (!(err && err.isAuth)) toast.error(failMessage || (err && err.friendly) || '加载失败');
            throw err;
        });
    }

    /* ============================================================
       骨架：侧边栏、顶栏、抽屉、路由
       ============================================================ */

    function iconSvg(path, size) {
        var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('width', size || 15);
        svg.setAttribute('height', size || 15);
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('fill', 'none');
        svg.setAttribute('stroke', 'currentColor');
        svg.setAttribute('stroke-width', '2');
        svg.setAttribute('stroke-linecap', 'round');
        svg.setAttribute('stroke-linejoin', 'round');
        var p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        p.setAttribute('d', path);
        svg.appendChild(p);
        return svg;
    }

    function renderShell() {
        var nav = $('#nav');
        clear(nav);
        NAV_ITEMS.forEach(function (item) {
            if (!state.access[item.view]) return;
            var link = el('button', {
                class: 'sidebar__link',
                type: 'button',
                dataset: { view: item.view },
                onclick: function () { location.hash = '#/' + item.view; closeDrawer(); },
            }, [iconSvg(item.icon), el('span', { text: item.label })]);
            nav.appendChild(link);
        });

        var user = state.session && state.session.user;
        var box = $('#nav-user');
        clear(box);
        if (user) {
            var avatar = el('div', { class: 'sidebar__user-avatar' });
            if (user.avatar) avatar.appendChild(el('img', { src: user.avatar, alt: '', class: 'sidebar__user-avatar' }));
            else avatar.textContent = String(user.displayName || user.username || '?').slice(0, 1).toUpperCase();
            box.appendChild(avatar);
            box.appendChild(el('div', { class: 'sidebar__user-info' }, [
                el('span', { class: 'sidebar__user-name', text: user.displayName || user.username || '未知用户' }),
                el('span', { class: 'sidebar__user-role', text: fmt.role(user.role) }),
            ]));
        } else {
            box.appendChild(el('div', { class: 'sidebar__user-info' }, [
                el('span', { class: 'sidebar__user-name', text: '未登录' }),
                el('span', { class: 'sidebar__user-role', text: '—' }),
            ]));
        }
    }

    function setActiveNav(view) {
        $$('#nav .sidebar__link').forEach(function (link) {
            link.classList.toggle('is-active', link.dataset.view === view);
        });
    }

    function closeDrawer() {
        $('#sidebar').classList.remove('is-open');
        $('#scrim').classList.remove('is-open');
        $('#burger').setAttribute('aria-expanded', 'false');
    }

    function toggleDrawer() {
        var open = !$('#sidebar').classList.contains('is-open');
        $('#sidebar').classList.toggle('is-open', open);
        $('#scrim').classList.toggle('is-open', open);
        $('#burger').setAttribute('aria-expanded', open ? 'true' : 'false');
    }

    function setTopbar(view) {
        var meta = VIEWS[view] || VIEWS.overview;
        $('#view-title').textContent = meta.title;
        $('#view-desc').textContent = meta.desc || '';
    }

    function currentViewFromHash() {
        var raw = String(location.hash || '').replace(/^#\/?/, '').split('?')[0].trim();
        return VIEWS[raw] ? raw : null;
    }

    function showView(view) {
        if (view !== 'forbidden' && !state.access[view]) view = firstAllowedView();
        state.view = view;
        $$('.view').forEach(function (node) {
            node.classList.toggle('is-active', node.dataset.view === view);
        });
        setActiveNav(view);
        setTopbar(view);
        clear($('#view-actions'));
        closeDrawer();
        refresh(view);
    }

    function route() {
        var target = currentViewFromHash() || firstAllowedView();
        if (target === 'forbidden' && !state.access.forbidden) state.access.forbidden = true;
        showView(target);
    }

    /** 重新渲染当前视图（force=true 时绕过缓存）。 */
    function refresh(view, force) {
        var target = view || state.view;
        if (!target || target === 'forbidden') return renderForbidden();
        if (!state.access[target]) return renderForbidden();
        if (!force && state.loaded[target] && Date.now() - state.loaded[target] < 15000) return;
        state.loaded[target] = Date.now();
        var fn = RENDERERS[target];
        if (fn) fn();
    }

    function renderForbidden() {
        var view = $('#view-forbidden');
        clear(view);
        view.appendChild(el('div', { class: 'card' }, [
            emptyState('🔒', '没有可用的后台权限',
                '当前账号（' + fmt.role(state.session && state.session.user && state.session.user.role) +
                '）没有任何后台读取权限。请联系管理员分配角色，或换一个账号登录。'),
            el('div', { class: 'u-row', style: 'justify-content:center;margin-top:14px' }, [
                el('button', { class: 'btn btn--ghost', type: 'button', onclick: function () { session.logout({}); } }, ['退出登录']),
            ]),
        ]));
    }

    /* ============================================================
       概览
       ============================================================ */

    function statCard(label, value, sub, variant) {
        return el('div', { class: 'stat' + (variant ? ' stat--' + variant : '') }, [
            el('div', { class: 'stat__label', text: label }),
            el('div', { class: 'stat__value', text: value }),
            sub ? el('div', { class: 'stat__sub', text: sub }) : null,
        ]);
    }

    function legendItem(color, label) {
        return el('span', { class: 'chart-legend__item' }, [
            el('span', { class: 'chart-legend__swatch', style: 'background:' + color }),
            el('span', { text: label }),
        ]);
    }

    /** 审计条目（概览与审计页共用）。 */
    function auditItem(entry) {
        var detail = entry.detail == null ? null : prettyJson(entry.detail);
        return el('div', { class: 'audit-item' }, [
            el('div', { class: 'audit-item__time' }, [
                el('div', { text: fmt.date(entry.at, { seconds: true }) }),
                el('div', { class: 'u-dim', text: fmt.relative(entry.at) }),
            ]),
            el('div', { class: 'audit-item__main' }, [
                el('div', { class: 'audit-item__action' }, [
                    el('span', { class: 'badge badge--accent', text: entry.action || '—' }),
                    entry.target ? el('span', { class: 'u-mono u-dim', style: 'margin-left:8px', text: entry.target }) : null,
                ]),
                detail
                    ? el('pre', { class: 'audit-item__detail', text: detail })
                    : el('div', { class: 'u-dim', text: '无附加详情' }),
            ]),
            el('div', { class: 'audit-item__who' }, [
                el('div', { text: entry.actorName || '系统' }),
                el('div', { class: 'u-dim', text: fmt.role(entry.actorRole) + (entry.ip ? ' · ' + String(entry.ip) : '') }),
            ]),
        ]);
    }

    function renderOverview() {
        var view = $('#view-overview');
        clear(view);
        view.appendChild(loadingLine('正在加载统计数据…'));

        Promise.all([
            load('/api/stats', { query: { days: 30 } }, '统计数据加载失败'),
            load('/api/audit', { query: { limit: 8 } }, '最近操作加载失败').catch(function () { return { items: [] }; }),
        ]).then(function (res) {
            var data = res[0] || {};
            var audit = res[1] || {};
            var stats = data.stats || {};
            var today = stats.today || {};
            var win = stats.window || {};
            var users = data.users || {};
            var byRole = users.byRole || {};
            var versions = data.versions || {};
            var ann = data.announcements || {};
            var sessions = data.sessions || {};
            var totals = stats.totals || {};

            clear(view);
            view.appendChild(el('div', { class: 'stat-grid' }, [
                statCard('今日访问', fmt.number(today.views), '页面浏览', ''),
                statCard('今日下载', fmt.number(today.downloads), 'APK 下载', 'success'),
                statCard('今日登录', fmt.number(today.logins), '成功登录', 'info'),
                statCard('近 ' + (win.days || 30) + ' 天访问', fmt.number(win.views), '累计浏览', 'warning'),
            ]));

            var chartCard = el('div', { class: 'card' }, [
                el('div', { class: 'card__head card__head--bordered' }, [
                    el('div', {}, [
                        el('div', { class: 'card__title', text: '近 30 天趋势' }),
                        el('div', { class: 'card__desc', text: '页面访问 / 下载 / 登录的每日数量' }),
                    ]),
                ]),
            ]);
            var canvas = el('canvas', { class: 'chart' });
            chartCard.appendChild(canvas);
            chartCard.appendChild(el('div', { class: 'chart-legend' }, [
                legendItem('#4D8FFF', '访问'),
                legendItem('#3FB950', '下载'),
                legendItem('#E8B34C', '登录'),
            ]));
            view.appendChild(chartCard);

            // canvas 需要先进入布局才能量到尺寸
            state.overviewSeries = stats.series || [];
            requestAnimationFrame(function () {
                HamKit.sparkline(canvas, state.overviewSeries, { keys: ['views', 'downloads', 'logins'] });
            });

            var stateCard = el('div', { class: 'card' }, [
                el('div', { class: 'card__head card__head--bordered' }, [
                    el('div', { class: 'card__title', text: '站点状态' }),
                ]),
                el('div', { class: 'stat-grid' }, [
                    statCard('用户', fmt.number(users.total), '管理员 ' + fmt.number(byRole.admin || 0) + ' · 编辑 ' + fmt.number(byRole.editor || 0) + ' · 访客 ' + fmt.number(byRole.viewer || 0)),
                    statCard('版本', fmt.number(versions.total), '已发布 ' + fmt.number(versions.published || 0)),
                    statCard('生效公告', fmt.number(ann.active || 0), '共 ' + fmt.number(ann.total || 0) + ' 条'),
                    statCard('在线会话', fmt.number(sessions.active), '累计浏览量 ' + fmt.number(totals.pageview || 0)),
                ]),
            ]);
            view.appendChild(stateCard);

            var auditCard = el('div', { class: 'card' }, [
                el('div', { class: 'card__head card__head--bordered' }, [
                    el('div', { class: 'card__title', text: '最近操作' }),
                    el('div', { class: 'card__actions' }, [
                        state.access.audit
                            ? el('button', { class: 'btn btn--subtle btn--sm', type: 'button', onclick: function () { location.hash = '#/audit'; } }, ['查看全部'])
                            : null,
                    ]),
                ]),
            ]);
            var items = audit.items || [];
            if (!items.length) auditCard.appendChild(emptyState('📋', '暂无操作记录'));
            else {
                var wrap = el('div', { class: 'audit-list' });
                items.forEach(function (entry) { wrap.appendChild(auditItem(entry)); });
                auditCard.appendChild(wrap);
            }
            view.appendChild(auditCard);
        }).catch(function () {
            clear(view);
            view.appendChild(el('div', { class: 'card' }, [emptyState('⚠', '统计数据加载失败', '请稍后重试或检查后端服务是否正常。')]));
        });
    }

    /* ============================================================
       版本管理
       ============================================================ */

    function setActions(nodes) {
        var box = $('#view-actions');
        clear(box);
        (nodes || []).forEach(function (node) { if (node) box.appendChild(node); });
    }

    function renderVersionsActions() {
        var nodes = [];
        if (state.access.versions && session.can('version:read')) {
            nodes.push(el('button', {
                class: 'btn btn--ghost btn--sm', type: 'button',
                onclick: function () { toggleCompatPanel(); },
            }, ['兼容文件预览']));
        }
        if (session.can('version:write')) {
            nodes.push(el('button', {
                class: 'btn btn--primary btn--sm', type: 'button',
                onclick: function () { openVersionModal(null); },
            }, ['新建版本']));
        }
        setActions(nodes);
    }

    function changelogBlock(text) {
        var wrap = el('div', { class: 'version-item__changelog' });
        var head = el('div', { class: 'u-row u-row--between' }, [
            el('span', { class: 'u-dim', text: '更新说明' }),
            el('button', {
                class: 'btn btn--subtle btn--sm', type: 'button',
                onclick: function () {
                    var body = wrap.querySelector('.md');
                    if (!body) return;
                    var hidden = body.classList.toggle('u-hidden');
                    this.textContent = hidden ? '展开' : '折叠';
                },
            }, ['折叠']),
        ]);
        var body = el('div');
        HamKit.md.renderInto(body, text || '（未填写更新说明）');
        wrap.appendChild(head);
        wrap.appendChild(body);
        return wrap;
    }

    function versionItem(item) {
        var badges = [
            el('span', { class: 'badge badge--accent', text: fmt.channel(item.channel) }),
            item.published ? el('span', { class: 'badge badge--success', text: '已发布' }) : el('span', { class: 'badge badge--muted', text: '未发布' }),
            item.prerelease ? el('span', { class: 'badge badge--warning', text: '预发布' }) : null,
            item.tag ? el('span', { class: 'badge', text: item.tag }) : null,
        ];
        var metaBits = list(
            item.sizeBytes ? el('span', { text: '体积 ' + fmt.bytes(item.sizeBytes) }) : null,
            el('span', { text: '更新于 ' + fmt.date(item.updatedAt) + '（' + fmt.relative(item.updatedAt) + '）' }),
        );
        var body = el('div', { class: 'version-item__body' }, [
            el('div', { class: 'version-item__title' }, list(
                el('span', { text: item.versionName || '（未命名）' }),
                el('span', { class: 'badge badge--muted', text: 'code ' + item.versionCode }),
            ).concat(badges)),
            el('div', { class: 'version-item__meta' }, metaBits),
            el('div', { class: 'version-item__url', text: item.downloadUrl ? fmt.urlShort(item.downloadUrl) : '未填写下载地址' }),
            item.sha256 ? el('div', { class: 'version-item__url', text: 'sha256 ' + item.sha256 }) : null,
            item.changelog ? changelogBlock(item.changelog) : null,
        ]);
        var actions = el('div', { class: 'version-item__actions' });
        if (session.can('version:write')) {
            actions.appendChild(el('button', {
                class: 'btn btn--ghost btn--sm', type: 'button',
                onclick: function () { openVersionModal(item); },
            }, ['编辑']));
        }
        if (session.can('version:delete')) {
            actions.appendChild(el('button', {
                class: 'btn btn--danger btn--sm', type: 'button',
                onclick: function (event) { removeVersion(item, event.currentTarget); },
            }, ['删除']));
        }
        return el('div', { class: 'version-item' }, [
            el('div', { class: 'version-item__code', text: String(item.versionCode) }),
            body,
            actions.childNodes.length ? actions : null,
        ]);
    }

    function removeVersion(item, button) {
        HamKit.confirm({
            title: '删除版本',
            text: '确认删除 ' + (item.versionName || item.versionCode) + '？该操作不可撤销，App 的更新检查会立即生效。',
            confirmText: '删除',
            danger: true,
        }).then(function (ok) {
            if (!ok) return;
            var restore = dom.busy(button, '删除中…');
            api.del('/api/versions/' + encodeURIComponent(item.id))
                .then(function () {
                    toast.success('版本已删除');
                    invalidate('versions');
                    invalidate('overview');
                    refresh('versions', true);
                })
                .catch(function (err) { toast.error((err && err.friendly) || '删除失败'); })
                .then(function () { restore(); });
        });
    }

    function renderVersions() {
        var view = $('#view-versions');
        clear(view);
        view.appendChild(loadingLine('正在加载版本列表…'));
        renderVersionsActions();

        load('/api/versions', {}, '版本列表加载失败').then(function (data) {
            clear(view);
            var items = (data && data.items) || [];
            if (!items.length) {
                view.appendChild(el('div', { class: 'card' }, [
                    emptyState('📦', '还没有任何版本', '点击右上角「新建版本」发布第一个 APK 版本。'),
                ]));
                return;
            }
            var listBox = el('div', { class: 'version-list' });
            items.forEach(function (item) { listBox.appendChild(versionItem(item)); });
            view.appendChild(listBox);
        }).catch(function () {
            clear(view);
            view.appendChild(el('div', { class: 'card' }, [emptyState('⚠', '版本列表加载失败')]));
        });
    }

    /** 兼容文件预览面板（update.json / version.json / 各渠道）。 */
    var compatVisible = false;
    function toggleCompatPanel() {
        compatVisible = !compatVisible;
        var existing = $('#compat-panel');
        if (existing) existing.remove();
        if (!compatVisible) return;
        var panel = el('div', { class: 'card', id: 'compat-panel' }, [
            el('div', { class: 'card__head card__head--bordered' }, [
                el('div', {}, [
                    el('div', { class: 'card__title', text: '兼容文件预览' }),
                    el('div', { class: 'card__desc', text: 'App 从这里读取更新信息：/update.json 决定更新提示，/version.json 供官网显示版本徽标，/update_<渠道>.json 为分渠道版本。' }),
                ]),
                el('div', { class: 'card__actions' }, [
                    el('button', {
                        class: 'btn btn--ghost btn--sm', type: 'button',
                        onclick: function () { compatVisible = false; panel.remove(); },
                    }, ['关闭']),
                ]),
            ]),
            loadingLine('正在生成预览…'),
        ]);
        var view = $('#view-versions');
        view.insertBefore(panel, view.firstChild);

        load('/api/compat/preview', {}, '兼容文件预览加载失败').then(function (data) {
            var blocks = [
                { name: 'update.json', value: data['update.json'] },
                { name: 'version.json', value: data['version.json'] },
            ];
            var channels = data.channels || {};
            CHANNEL_OPTIONS.forEach(function (channel) {
                if (channels[channel]) blocks.push({ name: 'update_' + channel + '.json', value: channels[channel] });
            });
            while (panel.lastChild) panel.removeChild(panel.lastChild);
            panel.appendChild(el('div', { class: 'card__head card__head--bordered u-mt' }, [
                el('div', {}, [
                    el('div', { class: 'card__title', text: '兼容文件预览' }),
                    el('div', { class: 'card__desc', text: 'App 从这里读取更新信息：/update.json 决定更新提示，/version.json 供官网显示版本徽标，/update_<渠道>.json 为分渠道版本。' }),
                ]),
                el('div', { class: 'card__actions' }, [
                    el('button', {
                        class: 'btn btn--ghost btn--sm', type: 'button',
                        onclick: function () { compatVisible = false; panel.remove(); },
                    }, ['关闭']),
                ]),
            ]));
            blocks.forEach(function (block) {
                var text = prettyJson(block.value);
                panel.appendChild(el('div', { class: 'u-row u-row--between u-mt' }, [
                    el('div', { class: 'u-mono', text: '/' + block.name }),
                    el('button', {
                        class: 'btn btn--ghost btn--sm', type: 'button',
                        onclick: function () {
                            HamKit.copyText(text).then(function (ok) {
                                if (ok) toast.success('已复制 ' + block.name);
                                else toast.error('复制失败，请手动选择文本');
                            });
                        },
                    }, ['复制']),
                ]));
                panel.appendChild(el('pre', { class: 'code-preview', text: text }));
            });
        }).catch(function () {
            while (panel.lastChild) panel.removeChild(panel.lastChild);
            panel.appendChild(emptyState('⚠', '预览生成失败'));
        });
    }

    function openVersionModal(item) {
        var isEdit = Boolean(item);
        var form = el('div', { class: 'u-stack' });
        var fields = [
            { key: 'versionCode', label: 'versionCode', type: 'number', required: true, placeholder: '例如 102', hint: '整数，必须唯一且 ≥ 1，App 用它判断是否需要更新' },
            { key: 'versionName', label: 'versionName', type: 'text', required: true, placeholder: '例如 1.0.2' },
            { key: 'tag', label: 'tag', type: 'text', placeholder: '留空自动生成 v<versionName>' },
            { key: 'channel', label: '渠道', type: 'select', options: CHANNEL_OPTIONS.map(function (c) { return { value: c, label: fmt.channel(c) + '（' + c + '）' }; }) },
            { key: 'downloadUrl', label: '下载地址', type: 'text', placeholder: 'https://apk.hamkit.click/release/HamKit_...apk' },
            { key: 'sizeBytes', label: '文件体积（字节）', type: 'number', placeholder: '例如 24117248' },
            { key: 'sha256', label: 'sha256', type: 'text', placeholder: '64 位十六进制；已发布版本必填' },
        ];
        fields.forEach(function (f) {
            var control;
            if (f.type === 'select') {
                control = el('select', { class: 'select', dataset: { field: f.key } });
                f.options.forEach(function (opt) { control.appendChild(el('option', { value: opt.value, text: opt.label })); });
            } else {
                control = el('input', { class: 'input', type: f.type, dataset: { field: f.key }, placeholder: f.placeholder || '' });
            }
            form.appendChild(el('div', { class: 'field' }, [
                el('label', { class: 'field__label' }, list(
                    el('span', { text: f.label }),
                    f.required ? el('span', { class: 'req', text: '*' }) : null,
                )),
                control,
                f.hint ? el('div', { class: 'field__hint', text: f.hint }) : null,
            ]));
        });

        form.appendChild(el('div', { class: 'grid-2' }, [
            el('label', { class: 'switch' }, [
                el('input', { type: 'checkbox', dataset: { field: 'prerelease' } }),
                el('span', { class: 'switch__track' }),
                el('span', { class: 'switch__label', text: '预发布（非稳定渠道自动勾选）' }),
            ]),
            el('label', { class: 'switch' }, [
                el('input', { type: 'checkbox', dataset: { field: 'published' } }),
                el('span', { class: 'switch__track' }),
                el('span', { class: 'switch__label', text: '已发布（对 App 与前台可见）' }),
            ]),
        ]));

        var changelog = el('textarea', { class: 'textarea textarea--mono', rows: 8, dataset: { field: 'changelog' }, placeholder: '支持 Markdown，例如：\n- 修复卫星过境预测偏差\n- 新增 FT8 频段' });
        form.appendChild(el('div', { class: 'field' }, [
            el('label', { class: 'field__label' }, [el('span', { text: 'changelog' })]),
            changelog,
            el('div', { class: 'field__hint', text: '支持 Markdown 语法，会展示在 App 更新弹窗与官网版本列表中' }),
        ]));

        var initial = item || { channel: 'stable', published: true, prerelease: false, versionCode: '', versionName: '', tag: '', downloadUrl: '', sizeBytes: '', sha256: '', changelog: '' };
        dom.fill(form, {
            versionCode: initial.versionCode || '',
            versionName: initial.versionName || '',
            tag: initial.tag || '',
            channel: initial.channel || 'stable',
            downloadUrl: initial.downloadUrl || '',
            sizeBytes: initial.sizeBytes || '',
            sha256: initial.sha256 || '',
            prerelease: Boolean(initial.prerelease),
            published: initial.published !== false,
        });
        changelog.value = initial.changelog || '';

        openModal({
            title: isEdit ? '编辑版本' : '新建版本',
            body: form,
            wide: true,
            confirmText: isEdit ? '保存修改' : '创建版本',
            onConfirm: function (button) {
                var values = dom.collect(form);
                var payload = {
                    versionCode: Number(values.versionCode),
                    versionName: String(values.versionName || '').trim(),
                    tag: String(values.tag || '').trim(),
                    channel: values.channel,
                    downloadUrl: String(values.downloadUrl || '').trim(),
                    sizeBytes: values.sizeBytes ? Number(values.sizeBytes) : null,
                    sha256: String(values.sha256 || '').trim(),
                    prerelease: Boolean(values.prerelease),
                    published: Boolean(values.published),
                    changelog: String(values.changelog || ''),
                };
                if (!payload.versionCode || payload.versionCode < 1) return toast.error('versionCode 必须是 ≥ 1 的整数');
                if (!payload.versionName) return toast.error('versionName 不能为空');
                var restore = dom.busy(button, '保存中…');
                var req = isEdit
                    ? api.patch('/api/versions/' + encodeURIComponent(item.id), payload)
                    : api.post('/api/versions', payload);
                req.then(function () {
                    toast.success(isEdit ? '版本已更新' : '版本已创建');
                    closeModal();
                    invalidate('versions');
                    invalidate('overview');
                    refresh('versions', true);
                }).catch(function (err) {
                    toast.error((err && err.friendly) || '保存失败');
                }).then(function () { restore(); });
            },
        });
    }

    /* ============================================================
       首页内容编辑
       ============================================================ */

    function field(label, control, hint) {
        return el('div', { class: 'field' }, [
            el('label', { class: 'field__label' }, [el('span', { text: label })]),
            control,
            hint ? el('div', { class: 'field__hint', text: hint }) : null,
        ]);
    }
    function textField(label, key, value, hint) {
        return field(label, el('input', { class: 'input', type: 'text', dataset: { field: key }, value: value == null ? '' : value }), hint);
    }
    function areaField(label, key, value, hint, rows) {
        return field(label, el('textarea', { class: 'textarea', rows: rows || 3, dataset: { field: key }, value: value == null ? '' : value }), hint);
    }
    function card(title, desc, children, extraHead) {
        var head = el('div', { class: 'card__head card__head--bordered' }, [
            el('div', {}, list(
                el('div', { class: 'card__title', text: title }),
                desc ? el('div', { class: 'card__desc', text: desc }) : null,
            )),
            extraHead || null,
        ]);
        return el('div', { class: 'card' }, [head].concat(children));
    }

    function featureRow(item) {
        var row = el('div', { class: 'feature-row' });
        var tagInput = el('input', { class: 'input', type: 'text', value: arrayToCsv(item.tags), placeholder: '标签，用逗号分隔' });
        var enabled = el('input', { type: 'checkbox' });
        enabled.checked = item.enabled !== false;
        row.appendChild(field('ID', el('input', { class: 'input', type: 'text', value: item.id || '', placeholder: '唯一标识，如 positioning' })));
        row.appendChild(field('标题', el('input', { class: 'input', type: 'text', value: item.title || '' })));
        var textArea = el('textarea', { class: 'textarea feature-row__span', rows: 2, value: item.text || '' });
        row.appendChild(field('描述', textArea));
        row.appendChild(field('徽标文字', el('input', { class: 'input', type: 'text', value: item.badge || '', placeholder: '可选' })));
        row.appendChild(field('标签（逗号分隔）', tagInput));
        var actions = el('div', { class: 'field' }, [
            el('label', { class: 'field__label' }, [el('span', { text: '操作' })]),
            el('div', { class: 'u-row u-wrap' }, [
                el('button', { class: 'btn btn--ghost btn--sm', type: 'button', onclick: function () { moveFeature(item, -1); } }, ['↑ 上移']),
                el('button', { class: 'btn btn--ghost btn--sm', type: 'button', onclick: function () { moveFeature(item, 1); } }, ['↓ 下移']),
                el('button', { class: 'btn btn--danger btn--sm', type: 'button', onclick: function () { removeFeature(item); } }, ['删除']),
            ]),
        ]);
        row.appendChild(actions);
        row.appendChild(el('div', { class: 'feature-row__span u-row' }, [
            el('label', { class: 'switch' }, [enabled, el('span', { class: 'switch__track' }), el('span', { class: 'switch__label', text: '启用（前台展示）' })]),
            el('span', { class: 'feature-row__handle', text: '⋮⋮', title: '顺序由上下移动调整' }),
        ]));
        item._row = row;
        item._tags = tagInput;
        item._enabled = enabled;
        item._id = row.children[0].querySelector('input');
        item._title = row.children[1].querySelector('input');
        item._text = textArea;
        item._badge = row.children[3].querySelector('input');
        return row;
    }

    /** 把 features 状态刷成表单当前值，避免增删/移动时丢改动。 */
    function syncFeatures() {
        state.features.forEach(function (item) {
            if (!item._row) return;
            item.id = String(item._id.value || '').trim();
            item.title = String(item._title.value || '').trim();
            item.text = String(item._text.value || '');
            item.badge = String(item._badge.value || '').trim() || null;
            item.tags = csvToArray(item._tags.value);
            item.enabled = Boolean(item._enabled.checked);
        });
    }
    function featureBody() {
        return cleanFeatures(state.features.map(function (item) {
            return { id: item.id, title: item.title, text: item.text, badge: item.badge, tags: item.tags, enabled: item.enabled };
        }));
    }
    function cleanFeatures(items) {
        return items.filter(function (item) { return item.id || item.title || item.text; });
    }
    function rerenderFeatures() {
        var host = $('#feature-list');
        if (!host) return;
        clear(host);
        state.features.forEach(function (item) { host.appendChild(featureRow(item)); });
    }
    function moveFeature(item, delta) {
        syncFeatures();
        var index = state.features.indexOf(item);
        var next = index + delta;
        if (next < 0 || next >= state.features.length) return;
        state.features.splice(index, 1);
        state.features.splice(next, 0, item);
        rerenderFeatures();
    }
    function removeFeature(item) {
        syncFeatures();
        var index = state.features.indexOf(item);
        if (index >= 0) state.features.splice(index, 1);
        rerenderFeatures();
    }

    function renderContentActions() {
        var nodes = [];
        if (session.can('content:write')) {
            nodes.push(el('button', {
                class: 'btn btn--ghost btn--sm', type: 'button',
                onclick: function () { resetContent(); },
            }, ['恢复默认']));
            nodes.push(el('button', {
                class: 'btn btn--primary btn--sm', type: 'button',
                onclick: function (event) { saveContent(event.currentTarget); },
            }, ['保存全部']));
        }
        setActions(nodes);
    }

    function resetContent() {
        HamKit.confirm({
            title: '恢复默认内容',
            text: '将把首页所有文案、功能卡、下载区、页脚与 SEO 重置为内置默认值，当前修改会丢失。确认继续？',
            confirmText: '恢复默认',
            danger: true,
        }).then(function (ok) {
            if (!ok) return;
            api.post('/api/content/reset', {})
                .then(function (data) {
                    toast.success('已恢复默认内容');
                    renderContentForm((data && data.content) || {});
                })
                .catch(function (err) { toast.error((err && err.friendly) || '恢复失败'); });
        });
    }

    function saveContent(button) {
        var form = $('#content-form');
        if (!form) return;
        syncFeatures();
        var hero = {
            title: String(($('#c-hero-title') || {}).value || ''),
            subtitle: String(($('#c-hero-subtitle') || {}).value || ''),
            description: String(($('#c-hero-description') || {}).value || ''),
            badgeText: String(($('#c-hero-badge') || {}).value || ''),
            primaryCta: {
                label: String(($('#c-primary-label') || {}).value || ''),
                href: String(($('#c-primary-href') || {}).value || ''),
            },
            secondaryCta: {
                label: String(($('#c-secondary-label') || {}).value || ''),
                href: String(($('#c-secondary-href') || {}).value || ''),
            },
        };
        var featureList = featureBody();
        if (!featureList.length) return toast.error('至少保留一个功能卡');
        var download = {
            title: String(($('#c-dl-title') || {}).value || ''),
            description: String(($('#c-dl-description') || {}).value || ''),
            mirrorUrl: String(($('#c-dl-mirror') || {}).value || ''),
            showMirror: Boolean($('#c-dl-showmirror') && $('#c-dl-showmirror').checked),
            minAndroid: String(($('#c-dl-minandroid') || {}).value || ''),
            requirements: linesToArray(($('#c-dl-requirements') || {}).value),
        };
        var footer = {
            tagline: String(($('#c-footer-tagline') || {}).value || ''),
            icp: String(($('#c-footer-icp') || {}).value || ''),
            copyright: String(($('#c-footer-copyright') || {}).value || ''),
            signature: String(($('#c-footer-signature') || {}).value || ''),
        };
        var seo = {
            title: String(($('#c-seo-title') || {}).value || ''),
            description: String(($('#c-seo-description') || {}).value || ''),
            keywords: linesToArray(($('#c-seo-keywords') || {}).value),
        };
        var devs = $$('.dev-row', form).map(function (row) {
            return {
                name: String((row.querySelector('[data-dev="name"]') || {}).value || '').trim(),
                email: String((row.querySelector('[data-dev="email"]') || {}).value || '').trim(),
                github: String((row.querySelector('[data-dev="github"]') || {}).value || '').trim(),
                avatar: String((row.querySelector('[data-dev="avatar"]') || {}).value || '').trim(),
            };
        }).filter(function (dev) { return dev.name || dev.email || dev.github; });

        var restore = dom.busy(button, '保存中…');
        api.patch('/api/content', { hero: hero, features: featureList, download: download, footer: footer, seo: seo, devs: devs })
            .then(function (data) {
                toast.success('首页内容已保存');
                renderContentForm((data && data.content) || {});
            })
            .catch(function (err) { toast.error((err && err.friendly) || '保存失败'); })
            .then(function () { restore(); });
    }

    function renderContentForm(content) {
        var view = $('#view-content');
        clear(view);
        var canWrite = session.can('content:write');
        var hero = content.hero || {};
        var primary = hero.primaryCta || {};
        var secondary = hero.secondaryCta || {};
        var download = content.download || {};
        var footer = content.footer || {};
        var seo = content.seo || {};

        var form = el('div', { class: 'u-stack', id: 'content-form' });
        if (!canWrite) {
            form.appendChild(el('div', { class: 'alert alert--warning' }, [
                el('span', { class: 'alert__icon', text: '!' }),
                el('div', { class: 'alert__body' }, [el('div', { text: '当前账号只有只读权限，表单不可编辑。' })]),
            ]));
        }

        form.appendChild(card('首屏 Hero', '首页最上方的标题与按钮', [
            el('div', { class: 'grid-2' }, [
                field('标题', el('input', { class: 'input', id: 'c-hero-title', value: hero.title || '' })),
                field('徽标文字', el('input', { class: 'input', id: 'c-hero-badge', value: hero.badgeText || '' })),
            ]),
            field('副标题', el('input', { class: 'input', id: 'c-hero-subtitle', value: hero.subtitle || '' })),
            field('描述', el('textarea', { class: 'textarea', rows: 3, id: 'c-hero-description', value: hero.description || '' })),
            el('div', { class: 'grid-2' }, [
                field('主按钮文字', el('input', { class: 'input', id: 'c-primary-label', value: primary.label || '' })),
                field('主按钮链接', el('input', { class: 'input', id: 'c-primary-href', value: primary.href || '' })),
            ]),
            el('div', { class: 'grid-2' }, [
                field('次按钮文字', el('input', { class: 'input', id: 'c-secondary-label', value: secondary.label || '' })),
                field('次按钮链接', el('input', { class: 'input', id: 'c-secondary-href', value: secondary.href || '' })),
            ]),
        ]));

        var featureCard = card('功能卡', '顺序、启用状态与文案都可调整；停用的不会出现在前台', [], el('div', { class: 'card__actions' }, [
            el('button', {
                class: 'btn btn--ghost btn--sm', type: 'button',
                onclick: function () {
                    syncFeatures();
                    state.features.push({ id: '', title: '', text: '', badge: null, tags: [], enabled: true });
                    rerenderFeatures();
                },
            }, ['新增功能卡']),
        ]));
        featureCard.appendChild(el('div', { class: 'feature-editor', id: 'feature-list' }));
        form.appendChild(featureCard);

        form.appendChild(card('下载区', '下载区块的标题与 Android 版本说明', [
            el('div', { class: 'grid-2' }, [
                field('标题', el('input', { class: 'input', id: 'c-dl-title', value: download.title || '' })),
                field('最低 Android 版本', el('input', { class: 'input', id: 'c-dl-minandroid', value: download.minAndroid || '' })),
            ]),
            field('描述', el('textarea', { class: 'textarea', rows: 2, id: 'c-dl-description', value: download.description || '' })),
            el('div', { class: 'grid-2' }, [
                field('镜像地址', el('input', { class: 'input', id: 'c-dl-mirror', value: download.mirrorUrl || '' })),
                el('div', { class: 'field' }, [
                    el('label', { class: 'field__label' }, [el('span', { text: '显示镜像入口' })]),
                    el('label', { class: 'switch' }, [
                        el('input', { type: 'checkbox', id: 'c-dl-showmirror', checked: download.showMirror ? true : null }),
                        el('span', { class: 'switch__track' }),
                        el('span', { class: 'switch__label', text: '在前台展示镜像下载按钮' }),
                    ]),
                ]),
            ]),
            field('安装要求（每行一项）', el('textarea', { class: 'textarea', rows: 4, id: 'c-dl-requirements', value: arrayToLines(download.requirements) })),
        ]));

        form.appendChild(card('页脚', '页脚文案与备案信息', [
            el('div', { class: 'grid-2' }, [
                field('标语', el('input', { class: 'input', id: 'c-footer-tagline', value: footer.tagline || '' })),
                field('备案号', el('input', { class: 'input', id: 'c-footer-icp', value: footer.icp || '' })),
            ]),
            el('div', { class: 'grid-2' }, [
                field('版权', el('input', { class: 'input', id: 'c-footer-copyright', value: footer.copyright || '' })),
                field('签名（如 73 · SK）', el('input', { class: 'input', id: 'c-footer-signature', value: footer.signature || '' })),
            ]),
        ]));

        form.appendChild(card('SEO', '影响搜索引擎摘要', [
            field('标题', el('input', { class: 'input', id: 'c-seo-title', value: seo.title || '' })),
            field('描述', el('textarea', { class: 'textarea', rows: 3, id: 'c-seo-description', value: seo.description || '' })),
            field('关键词（每行一项）', el('textarea', { class: 'textarea', rows: 4, id: 'c-seo-keywords', value: arrayToLines(seo.keywords) })),
        ]));

        var devHost = el('div', { class: 'u-stack' });
        (content.devs || []).forEach(function (dev) { devHost.appendChild(devRow(dev)); });
        form.appendChild(card('开发者', '展示在页脚的作者信息', [devHost], el('div', { class: 'card__actions' }, [
            el('button', {
                class: 'btn btn--ghost btn--sm', type: 'button',
                onclick: function () {
                    var row = devRow({});
                    devHost.appendChild(row);
                },
            }, ['新增开发者']),
        ])));

        form.appendChild(el('div', { class: 'u-row', style: 'justify-content:flex-end' }, [
            content.updatedAt ? el('span', { class: 'u-dim', text: '最近更新：' + fmt.date(content.updatedAt) }) : null,
        ]));

        view.appendChild(form);
        state.features = (content.features || []).map(function (item) {
            return { id: item.id || '', title: item.title || '', text: item.text || '', badge: item.badge || null, tags: Array.isArray(item.tags) ? item.tags.slice() : [], enabled: item.enabled !== false };
        });
        rerenderFeatures();

        // 只读账号：整个表单禁用（含功能卡的增删按钮与开发者新增按钮）
        if (!canWrite) {
            $$('input, textarea, select, button', form).forEach(function (node) { node.disabled = true; });
            $$('.dev-row .btn--danger', form).forEach(function (node) { node.textContent = '—'; });
        }
    }

    function devRow(dev) {
        var row = el('div', { class: 'grid-2 dev-row' });
        row.appendChild(field('名称', el('input', { class: 'input', type: 'text', dataset: { dev: 'name' }, value: dev.name || '' })));
        row.appendChild(field('邮箱', el('input', { class: 'input', type: 'text', dataset: { dev: 'email' }, value: dev.email || '' })));
        row.appendChild(field('GitHub', el('input', { class: 'input', type: 'text', dataset: { dev: 'github' }, value: dev.github || '' })));
        row.appendChild(el('div', { class: 'field' }, [
            el('label', { class: 'field__label' }, [el('span', { text: '头像地址' })]),
            el('div', { class: 'u-row' }, [
                el('input', { class: 'input u-grow', type: 'text', dataset: { dev: 'avatar' }, value: dev.avatar || '' }),
                el('button', {
                    class: 'btn btn--danger btn--sm', type: 'button',
                    onclick: function () { row.remove(); },
                }, ['移除']),
            ]),
        ]));
        return row;
    }

    function renderContent() {
        var view = $('#view-content');
        clear(view);
        view.appendChild(loadingLine('正在加载首页内容…'));
        renderContentActions();
        load('/api/content', {}, '首页内容加载失败').then(function (data) {
            renderContentForm((data && data.content) || {});
        }).catch(function () {
            clear(view);
            view.appendChild(el('div', { class: 'card' }, [emptyState('⚠', '首页内容加载失败')]));
        });
    }

    /* ============================================================
       公告
       ============================================================ */

    function announcementActive(item) {
        var now = Date.now();
        if (item.enabled === false) return false;
        if (item.publishAt && new Date(item.publishAt).getTime() > now) return false;
        if (item.expireAt && new Date(item.expireAt).getTime() <= now) return false;
        return true;
    }

    function renderAnnouncementsActions() {
        var nodes = [];
        if (session.can('announcement:write')) {
            nodes.push(el('button', {
                class: 'btn btn--primary btn--sm', type: 'button',
                onclick: function () { openAnnouncementModal(null); },
            }, ['新建公告']));
        }
        setActions(nodes);
    }

    function announcementCard(item) {
        var active = announcementActive(item);
        var wrap = el('div', { class: 'card' }, [
            el('div', { class: 'card__head' }, [
                el('div', { class: 'u-grow' }, [
                    el('div', { class: 'card__title u-wrap' }, list(
                        el('span', { text: item.title || '（无标题）' }),
                        el('span', { class: 'badge ' + (SEVERITY_BADGE[item.severity] || 'badge--info'), text: SEVERITY_TEXT[item.severity] || item.severity || '信息' }),
                        item.pinned ? el('span', { class: 'badge badge--accent', text: '置顶' }) : null,
                        item.enabled === false ? el('span', { class: 'badge badge--muted', text: '已停用' }) : null,
                        active ? el('span', { class: 'badge badge--success badge--dot', text: '生效中' }) : el('span', { class: 'badge badge--muted badge--dot', text: '未生效' }),
                    )),
                    el('div', { class: 'card__desc' }, [
                        el('span', { text: '发布 ' + fmt.date(item.publishAt) + '（' + fmt.relative(item.publishAt) + '）' }),
                        el('span', { text: ' · ' }),
                        el('span', { text: item.expireAt ? '失效 ' + fmt.date(item.expireAt) : '永不过期' }),
                        item.link ? el('span', { text: ' · 链接 ' + fmt.urlShort(item.link, 36) }) : null,
                    ]),
                ]),
                el('div', { class: 'card__actions' }, list(
                    session.can('announcement:write')
                        ? el('button', { class: 'btn btn--ghost btn--sm', type: 'button', onclick: function () { openAnnouncementModal(item); } }, ['编辑'])
                        : null,
                    session.can('announcement:write')
                        ? el('button', { class: 'btn btn--danger btn--sm', type: 'button', onclick: function (event) { removeAnnouncement(item, event.currentTarget); } }, ['删除'])
                        : null,
                )),
            ]),
        ]);
        if (item.body) {
            var body = el('div');
            HamKit.md.renderInto(body, item.body);
            wrap.appendChild(body);
        } else {
            wrap.appendChild(el('div', { class: 'u-dim', text: '（无正文）' }));
        }
        return wrap;
    }

    function removeAnnouncement(item, button) {
        HamKit.confirm({
            title: '删除公告',
            text: '确认删除「' + (item.title || '') + '」？删除后前台立即不再展示。',
            confirmText: '删除',
            danger: true,
        }).then(function (ok) {
            if (!ok) return;
            var restore = dom.busy(button, '删除中…');
            api.del('/api/announcements/' + encodeURIComponent(item.id))
                .then(function () {
                    toast.success('公告已删除');
                    invalidate('announcements');
                    invalidate('overview');
                    refresh('announcements', true);
                })
                .catch(function (err) { toast.error((err && err.friendly) || '删除失败'); })
                .then(function () { restore(); });
        });
    }

    function openAnnouncementModal(item) {
        var isEdit = Boolean(item);
        var form = el('div', { class: 'u-stack' });
        form.appendChild(field('标题', el('input', { class: 'input', type: 'text', dataset: { field: 'title' }, placeholder: '例如 服务维护通知' }), '必填'));
        form.appendChild(field('正文（Markdown）', el('textarea', { class: 'textarea textarea--mono', rows: 7, dataset: { field: 'body' }, placeholder: '支持 Markdown：标题、列表、链接、代码块…' })));
        var severity = el('select', { class: 'select', dataset: { field: 'severity' } });
        SEVERITY_OPTIONS.forEach(function (s) { severity.appendChild(el('option', { value: s, text: SEVERITY_TEXT[s] + '（' + s + '）' })); });
        form.appendChild(el('div', { class: 'grid-2' }, [
            field('级别', severity),
            field('跳转链接', el('input', { class: 'input', type: 'text', dataset: { field: 'link' }, placeholder: '可选，站内路径或 https 链接' })),
        ]));
        form.appendChild(el('div', { class: 'grid-2' }, [
            field('发布时间', el('input', { class: 'input', type: 'datetime-local', dataset: { field: 'publishAt' } }), '留空表示立即发布'),
            field('失效时间', el('input', { class: 'input', type: 'datetime-local', dataset: { field: 'expireAt' } }), '留空表示永不过期'),
        ]));
        form.appendChild(el('div', { class: 'grid-2' }, [
            el('label', { class: 'switch' }, [
                el('input', { type: 'checkbox', dataset: { field: 'pinned' } }),
                el('span', { class: 'switch__track' }),
                el('span', { class: 'switch__label', text: '置顶' }),
            ]),
            el('label', { class: 'switch' }, [
                el('input', { type: 'checkbox', dataset: { field: 'enabled' } }),
                el('span', { class: 'switch__track' }),
                el('span', { class: 'switch__label', text: '启用' }),
            ]),
        ]));

        var initial = item || { severity: 'info', pinned: false, enabled: true };
        dom.fill(form, {
            title: initial.title || '',
            body: initial.body || '',
            severity: initial.severity || 'info',
            link: initial.link || '',
            publishAt: isoToLocalInput(initial.publishAt),
            expireAt: isoToLocalInput(initial.expireAt),
            pinned: Boolean(initial.pinned),
            enabled: initial.enabled !== false,
        });

        openModal({
            title: isEdit ? '编辑公告' : '新建公告',
            body: form,
            confirmText: isEdit ? '保存修改' : '创建公告',
            onConfirm: function (button) {
                var values = dom.collect(form);
                var publishAt = localInputToIso(values.publishAt);
                var expireAt = localInputToIso(values.expireAt);
                if (values.publishAt && !publishAt) return toast.error('发布时间格式无效');
                if (values.expireAt && !expireAt) return toast.error('失效时间格式无效');
                if (publishAt && expireAt && new Date(expireAt) <= new Date(publishAt)) return toast.error('失效时间必须晚于发布时间');
                var payload = {
                    title: String(values.title || '').trim(),
                    body: String(values.body || ''),
                    severity: values.severity,
                    link: String(values.link || '').trim(),
                    pinned: Boolean(values.pinned),
                    enabled: Boolean(values.enabled),
                    publishAt: publishAt,
                    expireAt: expireAt,
                };
                if (!payload.title) return toast.error('公告标题不能为空');
                var restore = dom.busy(button, '保存中…');
                var req = isEdit
                    ? api.patch('/api/announcements/' + encodeURIComponent(item.id), payload)
                    : api.post('/api/announcements', payload);
                req.then(function () {
                    toast.success(isEdit ? '公告已更新' : '公告已创建');
                    closeModal();
                    invalidate('announcements');
                    invalidate('overview');
                    refresh('announcements', true);
                }).catch(function (err) {
                    toast.error((err && err.friendly) || '保存失败');
                }).then(function () { restore(); });
            },
        });
    }

    function renderAnnouncements() {
        var view = $('#view-announcements');
        clear(view);
        view.appendChild(loadingLine('正在加载公告…'));
        renderAnnouncementsActions();
        load('/api/announcements', {}, '公告加载失败').then(function (data) {
            clear(view);
            var items = ((data && data.items) || []).slice().sort(function (a, b) {
                if (Boolean(a.pinned) !== Boolean(b.pinned)) return a.pinned ? -1 : 1;
                return new Date(b.publishAt || 0) - new Date(a.publishAt || 0);
            });
            if (!items.length) {
                view.appendChild(el('div', { class: 'card' }, [emptyState('📢', '还没有公告', '点击右上角「新建公告」发布第一条。')]));
                return;
            }
            items.forEach(function (item) { view.appendChild(announcementCard(item)); });
        }).catch(function () {
            clear(view);
            view.appendChild(el('div', { class: 'card' }, [emptyState('⚠', '公告加载失败')]));
        });
    }

    /* ============================================================
       用户管理
       ============================================================ */

    function userAvatar(user) {
        if (user.avatar) return el('img', { src: user.avatar, alt: '', class: 'sidebar__user-avatar' });
        var node = el('div', { class: 'sidebar__user-avatar', text: String(user.displayName || user.username || '?').slice(0, 1).toUpperCase() });
        return node;
    }

    function userRow(user) {
        var isSelf = Boolean(state.session && state.session.user && state.session.user.id === user.id);
        var row = el('tr');

        row.appendChild(el('td', {}, [
            el('div', { class: 'u-row' }, [
                userAvatar(user),
                el('div', { class: 'u-stack u-stack--sm' }, [
                    el('div', { class: 'u-row' }, list(
                        el('span', { text: user.displayName || user.username || '未知' }),
                        isSelf ? el('span', { class: 'badge badge--muted', text: '当前账号' }) : null,
                        user.disabled ? el('span', { class: 'badge badge--danger', text: '已停用' }) : null,
                    )),
                    el('div', { class: 'u-dim u-mono', text: '@' + (user.username || '—') }),
                ]),
            ]),
        ]));

        row.appendChild(el('td', {}, [
            el('div', { text: user.email || '—' }),
            el('div', { class: 'u-dim u-truncate', text: user.oidcSub ? 'sub ' + user.oidcSub : '' }),
        ]));

        var roleCell = el('td');
        if (session.can('user:write')) {
            var select = el('select', { class: 'select', style: 'min-width:118px' });
            ROLE_OPTIONS.forEach(function (role) { select.appendChild(el('option', { value: role, text: fmt.role(role) })); });
            select.value = ROLE_OPTIONS.indexOf(user.role) >= 0 ? user.role : 'viewer';
            select.addEventListener('change', function () {
                var next = select.value;
                if (next === user.role) return;
                changeRole(user, next, select);
            });
            roleCell.appendChild(select);
        } else {
            roleCell.appendChild(roleBadge(user.role));
        }
        if (user.roleLocked) roleCell.appendChild(el('div', { class: 'u-dim', style: 'font-size:11px', text: '已锁定（不随 IdP 变化）' }));
        row.appendChild(roleCell);

        var idpRoles = Array.isArray(user.idpRoles) ? user.idpRoles : [];
        row.appendChild(el('td', {}, [
            idpRoles.length
                ? el('div', { class: 'tag-list' }, idpRoles.map(function (role) { return el('span', { class: 'tag', text: String(role) }); }))
                : el('span', { class: 'u-dim', text: '—' }),
        ]));

        row.appendChild(el('td', {}, [
            el('div', { text: fmt.relative(user.lastLoginAt) }),
            el('div', { class: 'u-dim', text: fmt.number(user.loginCount || 0) + ' 次登录' }),
        ]));

        var actions = el('div', { class: 'table__actions' });
        if (session.can('user:write')) {
            actions.appendChild(el('button', {
                class: 'btn ' + (user.disabled ? 'btn--ghost' : 'btn--danger') + ' btn--sm', type: 'button',
                onclick: function (event) { toggleDisabled(user, event.currentTarget); },
            }, [user.disabled ? '启用' : '停用']));
            actions.appendChild(el('button', {
                class: 'btn btn--ghost btn--sm', type: 'button',
                onclick: function (event) { revokeSessions(user, event.currentTarget); },
            }, ['踢出会话']));
            actions.appendChild(el('button', {
                class: 'btn btn--danger btn--sm', type: 'button',
                disabled: isSelf ? true : null,
                title: isSelf ? '不能删除自己' : '',
                onclick: function (event) { removeUser(user, event.currentTarget); },
            }, ['删除']));
        } else {
            actions.appendChild(el('span', { class: 'u-dim', text: '只读' }));
        }
        row.appendChild(el('td', {}, [actions]));
        return row;
    }

    function changeRole(user, role, select) {
        api.patch('/api/users/' + encodeURIComponent(user.id), { role: role })
            .then(function () {
                toast.success('已将 ' + (user.displayName || user.username) + ' 设为' + fmt.role(role));
                invalidate('users');
                refresh('users', true);
            })
            .catch(function (err) {
                toast.error((err && err.friendly) || '角色修改失败');
                select.value = ROLE_OPTIONS.indexOf(user.role) >= 0 ? user.role : 'viewer';
            });
    }

    function toggleDisabled(user, button) {
        var next = !user.disabled;
        var ask = next
            ? HamKit.confirm({
                title: '停用账号',
                text: '停用「' + (user.displayName || user.username) + '」后，其全部在线会话会立即被强制下线。确认继续？',
                confirmText: '停用',
                danger: true,
            })
            : Promise.resolve(true);
        ask.then(function (ok) {
            if (!ok) return;
            var restore = dom.busy(button, '处理中…');
            api.patch('/api/users/' + encodeURIComponent(user.id), { disabled: next })
                .then(function () {
                    toast.success(next ? '账号已停用并踢出会话' : '账号已启用');
                    invalidate('users');
                    refresh('users', true);
                })
                .catch(function (err) { toast.error((err && err.friendly) || '操作失败'); })
                .then(function () { restore(); });
        });
    }

    function revokeSessions(user, button) {
        HamKit.confirm({
            title: '踢出全部会话',
            text: '将强制「' + (user.displayName || user.username) + '」的所有在线会话下线，对方需要重新登录。',
            confirmText: '踢出',
            danger: true,
        }).then(function (ok) {
            if (!ok) return;
            var restore = dom.busy(button, '处理中…');
            api.patch('/api/users/' + encodeURIComponent(user.id), { revokeSessions: true })
                .then(function () { toast.success('已踢出该用户的全部会话'); })
                .catch(function (err) { toast.error((err && err.friendly) || '操作失败'); })
                .then(function () { restore(); });
        });
    }

    function removeUser(user, button) {
        HamKit.confirm({
            title: '删除用户',
            text: '确认删除「' + (user.displayName || user.username) + '」？其会话会被立即撤销，操作不可撤销。',
            confirmText: '删除',
            danger: true,
        }).then(function (ok) {
            if (!ok) return;
            var restore = dom.busy(button, '删除中…');
            api.del('/api/users/' + encodeURIComponent(user.id))
                .then(function () {
                    toast.success('用户已删除');
                    invalidate('users');
                    invalidate('overview');
                    refresh('users', true);
                })
                .catch(function (err) { toast.error((err && err.friendly) || '删除失败'); })
                .then(function () { restore(); });
        });
    }

    function renderUsers() {
        var view = $('#view-users');
        clear(view);
        view.appendChild(loadingLine('正在加载用户列表…'));
        setActions([]);
        load('/api/users', {}, '用户列表加载失败').then(function (data) {
            clear(view);
            var items = (data && data.items) || [];
            var counts = (data && data.counts) || {};
            var byRole = counts.byRole || {};

            view.appendChild(el('div', { class: 'stat-grid' }, [
                statCard('用户总数', fmt.number(counts.total), '已停用 ' + fmt.number(counts.disabled || 0)),
                statCard('管理员', fmt.number(byRole.admin || 0), '可管理全部功能', ''),
                statCard('编辑', fmt.number(byRole.editor || 0), '内容与版本维护', 'info'),
                statCard('访客', fmt.number(byRole.viewer || 0), '只读权限', 'success'),
            ]));

            if (!items.length) {
                view.appendChild(el('div', { class: 'card' }, [emptyState('👤', '暂无用户', '用户会在首次通过 OIDC 登录后自动创建。')]));
                return;
            }

            var table = el('table', { class: 'table' }, [
                el('thead', {}, [el('tr', {}, [
                    el('th', { text: '用户' }),
                    el('th', { text: '邮箱' }),
                    el('th', { text: '角色' }),
                    el('th', { text: 'IdP 角色' }),
                    el('th', { text: '最近登录' }),
                    el('th', { text: '操作' }),
                ])]),
            ]);
            var body = el('tbody');
            items.forEach(function (user) { body.appendChild(userRow(user)); });
            table.appendChild(body);
            view.appendChild(el('div', { class: 'card card--flush' }, [el('div', { class: 'table-wrap' }, [table])]));
        }).catch(function () {
            clear(view);
            view.appendChild(el('div', { class: 'card' }, [emptyState('⚠', '用户列表加载失败')]));
        });
    }

    /* ============================================================
       审计日志
       ============================================================ */

    function renderAudit() {
        var view = $('#view-audit');
        clear(view);
        setActions([]);

        var actionSelect = el('select', { class: 'select', style: 'min-width:190px' });
        var search = el('input', { class: 'input', type: 'search', placeholder: '搜索动作 / 操作人 / 目标 / 详情', style: 'min-width:240px' });
        actionSelect.addEventListener('change', function () {
            state.audit.action = actionSelect.value;
            state.audit.offset = 0;
            loadAudit();
        });
        search.value = state.audit.q;
        search.addEventListener('input', HamKit.debounce(function () {
            state.audit.q = search.value.trim();
            state.audit.offset = 0;
            loadAudit();
        }, 350));

        view.appendChild(el('div', { class: 'card' }, [
            el('div', { class: 'u-row u-wrap' }, [
                actionSelect,
                search,
                el('button', {
                    class: 'btn btn--ghost btn--sm', type: 'button',
                    onclick: function () {
                        state.audit.action = '';
                        state.audit.q = '';
                        state.audit.offset = 0;
                        actionSelect.value = '';
                        search.value = '';
                        loadAudit();
                    },
                }, ['重置']),
            ]),
        ]));

        var host = el('div', { class: 'card', id: 'audit-host' }, [loadingLine('正在加载审计日志…')]);
        view.appendChild(host);

        state.audit.select = actionSelect;

        function fillActions(actions) {
            var current = actionSelect.value;
            clear(actionSelect);
            actionSelect.appendChild(el('option', { value: '', text: '全部动作' }));
            (actions || []).forEach(function (action) {
                actionSelect.appendChild(el('option', { value: action, text: action }));
            });
            actionSelect.value = current;
        }

        function loadAudit() {
            clear(host);
            host.appendChild(loadingLine('正在加载审计日志…'));
            load('/api/audit', {
                query: {
                    limit: state.audit.limit,
                    offset: state.audit.offset,
                    action: state.audit.action,
                    q: state.audit.q,
                },
            }, '审计日志加载失败').then(function (data) {
                var items = (data && data.items) || [];
                var total = (data && data.total) || 0;
                state.audit.total = total;
                if (data && data.actions) fillActions(data.actions);
                clear(host);

                var from = total === 0 ? 0 : state.audit.offset + 1;
                var to = Math.min(state.audit.offset + state.audit.limit, total);
                var pageCount = Math.max(1, Math.ceil(total / state.audit.limit));
                var page = Math.floor(state.audit.offset / state.audit.limit) + 1;

                host.appendChild(el('div', { class: 'card__head card__head--bordered' }, [
                    el('div', {}, [
                        el('div', { class: 'card__title', text: '操作记录' }),
                        el('div', { class: 'card__desc', text: '第 ' + from + '–' + to + ' 条 / 共 ' + fmt.number(total) + ' 条（第 ' + page + ' / ' + pageCount + ' 页）' }),
                    ]),
                    el('div', { class: 'card__actions' }, [
                        el('button', {
                            class: 'btn btn--ghost btn--sm', type: 'button',
                            disabled: state.audit.offset <= 0 ? true : null,
                            onclick: function () {
                                state.audit.offset = Math.max(0, state.audit.offset - state.audit.limit);
                                loadAudit();
                            },
                        }, ['上一页']),
                        el('button', {
                            class: 'btn btn--ghost btn--sm', type: 'button',
                            disabled: state.audit.offset + state.audit.limit >= total ? true : null,
                            onclick: function () {
                                state.audit.offset += state.audit.limit;
                                loadAudit();
                            },
                        }, ['下一页']),
                    ]),
                ]));

                if (!items.length) {
                    host.appendChild(emptyState('📋', '没有匹配的记录', '试试调整动作过滤或清空搜索关键字。'));
                    return;
                }
                var wrap = el('div', { class: 'audit-list' });
                items.forEach(function (entry) { wrap.appendChild(auditItem(entry)); });
                host.appendChild(wrap);
            }).catch(function () {
                clear(host);
                host.appendChild(emptyState('⚠', '审计日志加载失败'));
            });
        }

        loadAudit();
    }

    /* ============================================================
       系统状态
       ============================================================ */

    function dotStatus(ok, okText, badText) {
        return el('span', { class: 'dot-status ' + (ok ? 'dot-status--ok' : 'dot-status--bad'), text: ok ? (okText || '正常') : (badText || '异常') });
    }

    function renderSystem() {
        var view = $('#view-system');
        clear(view);
        view.appendChild(loadingLine('正在加载系统信息…'));
        setActions([]);

        load('/api/system', {}, '系统信息加载失败').then(function (data) {
            clear(view);
            var store = data.store || {};
            var oidc = data.oidc || {};
            var probe = oidc.probe || {};
            var security = data.security || {};
            var files = data.files || [];
            var activeSessions = data.activeSessions || [];

            // 运行环境
            view.appendChild(card('运行环境', '进程与数据目录', [
                kvBlock([
                    ['环境 env', data.env],
                    ['Node', data.node],
                    ['运行时长', fmt.duration(data.uptimeSec)],
                    ['内存（RSS）', fmt.number(data.memoryMb) + ' MB'],
                    ['Base URL', data.baseUrl],
                    ['数据目录', data.dataDir],
                    ['存储写入次数', fmt.number(store.writes)],
                ]),
            ]));

            // OIDC
            var roleMapText = Object.keys(oidc.roleMap || {}).length
                ? Object.keys(oidc.roleMap).map(function (key) { return key + ' → ' + oidc.roleMap[key]; }).join('，')
                : '（未配置 OIDC_ROLE_MAP）';
            view.appendChild(card('OIDC 单点登录', '身份提供方与角色映射', [
                el('div', { class: 'u-row u-wrap' }, [
                    dotStatus(Boolean(oidc.configured), '已配置', '未配置'),
                    el('span', { class: 'u-dim', text: oidc.configured ? '' : '缺少 issuer 或 clientId 时无法登录' }),
                ]),
                kvBlock([
                    ['issuer', oidc.issuer],
                    ['clientId', oidc.clientId],
                    ['clientAuthMethod', oidc.clientAuthMethod],
                    ['scopes', oidc.scopes],
                    ['roleClaim', oidc.roleClaim],
                    ['roleMap', roleMapText],
                    ['redirectUri', oidc.redirectUri],
                ]),
                el('div', { class: 'u-row u-wrap' }, [
                    el('span', { class: 'u-dim', text: '发现文档探测：' }),
                    dotStatus(Boolean(probe.ok), '连通', '不可用'),
                    probe.keyCount !== undefined ? el('span', { class: 'u-dim', text: '签发密钥 ' + fmt.number(probe.keyCount) + ' 个' }) : null,
                    probe.error ? el('span', { class: 'badge badge--danger', text: String(probe.error) }) : null,
                ]),
            ]));

            // 安全
            view.appendChild(card('安全配置', 'Cookie、代理与统计隐私', [
                kvBlock([
                    ['cookieSecure', security.cookieSecure ? '开启' : '关闭'],
                    ['sameSite', security.sameSite],
                    ['trustProxy', String(security.trustProxy)],
                    ['hsts', security.hsts ? '开启' : '关闭'],
                    ['statsStoreIp', security.statsStoreIp ? '记录 IP' : '不记录 IP'],
                ]),
            ]));

            var problems = data.problems || [];
            var warnings = data.warnings || [];
            if (problems.length) {
                var dangerBox = el('div', { class: 'card' });
                problems.forEach(function (text) {
                    dangerBox.appendChild(el('div', { class: 'alert alert--danger' }, [
                        el('span', { class: 'alert__icon', text: '✕' }),
                        el('div', { class: 'alert__body' }, [
                            el('div', { class: 'alert__title', text: '配置缺失' }),
                            el('div', { text: text }),
                        ]),
                    ]));
                });
                view.appendChild(dangerBox);
            }
            if (warnings.length) {
                var warnBox = el('div', { class: 'card' });
                warnings.forEach(function (text) {
                    warnBox.appendChild(el('div', { class: 'alert alert--warning' }, [
                        el('span', { class: 'alert__icon', text: '!' }),
                        el('div', { class: 'alert__body' }, [
                            el('div', { class: 'alert__title', text: '配置告警' }),
                            el('div', { text: text }),
                        ]),
                    ]));
                });
                view.appendChild(warnBox);
            }
            if (!problems.length && !warnings.length) {
                view.appendChild(el('div', { class: 'alert alert--success' }, [
                    el('span', { class: 'alert__icon', text: '✓' }),
                    el('div', { class: 'alert__body' }, [el('div', { text: '配置自检通过，未发现问题或告警。' })]),
                ]));
            }

            // 在线会话
            var sessionCard = card('在线会话', '仅显示脱敏后的会话标识，可强制下线', []);
            if (!activeSessions.length) {
                sessionCard.appendChild(emptyState('🔌', '当前没有在线会话'));
            } else {
                var table = el('table', { class: 'table' }, [
                    el('thead', {}, [el('tr', {}, [
                        el('th', { text: '会话' }),
                        el('th', { text: '用户' }),
                        el('th', { text: '角色' }),
                        el('th', { text: '最近活跃' }),
                        el('th', { text: '过期时间' }),
                        el('th', { text: '操作' }),
                    ])]),
                ]);
                var body = el('tbody');
                activeSessions.forEach(function (item) {
                    body.appendChild(el('tr', {}, [
                        el('td', { class: 'u-mono', text: item.sid }),
                        el('td', { class: 'u-mono', text: item.userId || '—' }),
                        el('td', {}, [roleBadge(item.role)]),
                        el('td', {}, [
                            el('div', { text: fmt.relative(item.lastSeenAt) }),
                            el('div', { class: 'u-dim', text: fmt.date(item.lastSeenAt, { seconds: true }) }),
                        ]),
                        el('td', { class: 'u-dim', text: fmt.date(item.expiresAt) }),
                        el('td', {}, [
                            el('div', { class: 'table__actions' }, [
                                el('button', {
                                    class: 'btn btn--danger btn--sm', type: 'button',
                                    onclick: function (event) { revokeSystemSession(item, event.currentTarget); },
                                }, ['强制下线']),
                            ]),
                        ]),
                    ]));
                });
                table.appendChild(body);
                sessionCard.appendChild(el('div', { class: 'table-wrap' }, [table]));
            }
            view.appendChild(sessionCard);

            // 配置文件体积
            var fileCard = card('数据文件', 'data 目录下的持久化文件', []);
            if (!files.length) {
                fileCard.appendChild(emptyState('🗂', '暂无数据文件'));
            } else {
                fileCard.appendChild(kvBlock(files.map(function (file) {
                    return [file.file, fmt.bytes(file.sizeBytes) + '　更新于 ' + fmt.date(file.mtime)];
                })));
            }
            view.appendChild(fileCard);
        }).catch(function () {
            clear(view);
            view.appendChild(el('div', { class: 'card' }, [emptyState('⚠', '系统信息加载失败', '仅管理员可查看系统状态。')]));
        });
    }

    function revokeSystemSession(item, button) {
        HamKit.confirm({
            title: '强制下线',
            text: '确认强制下线会话 ' + item.sid + '？该用户需要重新登录。',
            confirmText: '强制下线',
            danger: true,
        }).then(function (ok) {
            if (!ok) return;
            var restore = dom.busy(button, '处理中…');
            api.post('/api/system/sessions/revoke', { sid: item.sid })
                .then(function (result) {
                    if (result && result.revoked) toast.success('已强制下线该会话');
                    else toast.warning('该会话可能已过期，未发生变更');
                    invalidate('system');
                    refresh('system', true);
                })
                .catch(function (err) { toast.error((err && err.friendly) || '操作失败'); })
                .then(function () { restore(); });
        });
    }

    /* ============================================================
       通用弹窗
       ============================================================ */

    var modalState = { node: null, keyHandler: null };

    function closeModal() {
        if (modalState.node) modalState.node.remove();
        if (modalState.keyHandler) document.removeEventListener('keydown', modalState.keyHandler);
        modalState = { node: null, keyHandler: null };
    }

    function openModal(options) {
        closeModal();
        var modal = el('div', { class: 'modal is-open', role: 'dialog', 'aria-modal': 'true' });
        var panel = el('div', { class: 'modal__panel' + (options.wide ? ' modal__panel--wide' : '') });
        var confirmButton = el('button', {
            class: 'btn ' + (options.danger ? 'btn--danger' : 'btn--primary'),
            type: 'button',
            onclick: function () { options.onConfirm(confirmButton); },
        }, [options.confirmText || '保存']);

        panel.appendChild(el('div', { class: 'modal__head' }, [
            el('div', { class: 'modal__title', text: options.title || '编辑' }),
            el('button', { class: 'modal__close', type: 'button', 'aria-label': '关闭', onclick: closeModal }, ['×']),
        ]));
        panel.appendChild(el('div', { class: 'modal__body' }, [options.body]));
        panel.appendChild(el('div', { class: 'modal__foot' }, [
            el('button', { class: 'btn btn--ghost', type: 'button', onclick: closeModal }, ['取消']),
            confirmButton,
        ]));

        modal.appendChild(panel);
        modal.addEventListener('click', function (event) { if (event.target === modal) closeModal(); });
        var onKey = function (event) { if (event.key === 'Escape') closeModal(); };
        document.addEventListener('keydown', onKey);
        document.body.appendChild(modal);
        modalState = { node: modal, keyHandler: onKey };

        var first = panel.querySelector('.input, .textarea, .select');
        if (first) first.focus();
        return modal;
    }

    /* ============================================================
       渲染器登记与引导
       ============================================================ */

    var RENDERERS = {
        overview: renderOverview,
        versions: renderVersions,
        content: renderContent,
        announcements: renderAnnouncements,
        users: renderUsers,
        audit: renderAudit,
        system: renderSystem,
    };

    function bindShell() {
        $('#burger').addEventListener('click', toggleDrawer);
        $('#scrim').addEventListener('click', closeDrawer);
        $('#btn-logout').addEventListener('click', function () {
            HamKit.confirm({ title: '退出登录', text: '确认退出当前账号？', confirmText: '退出' }).then(function (ok) {
                if (ok) session.logout({});
            });
        });
        window.addEventListener('hashchange', route);
        window.addEventListener('resize', HamKit.debounce(function () {
            if (state.view !== 'overview') return;
            var canvas = $('#view-overview canvas.chart');
            if (canvas && state.overviewSeries.length) {
                HamKit.sparkline(canvas, state.overviewSeries, { keys: ['views', 'downloads', 'logins'] });
            }
        }, 250));
    }

    function boot() {
        bindShell();
        session.load({ force: true }).then(function (data) {
            state.session = data;
            if (!data.authenticated) {
                session.requireLogin();
                return;
            }
            NAV_ITEMS.forEach(function (item) {
                state.access[item.view] = session.can(item.read);
            });
            state.access.forbidden = true;
            renderShell();
            if (!location.hash) {
                location.hash = '#/' + firstAllowedView();
            }
            route();
        }).catch(function (err) {
            HamKit.handleError(err, { returnTo: location.pathname });
        });
    }

    HamKit.admin = {
        state: state,
        refresh: refresh,
        invalidate: invalidate,
        openModal: openModal,
        closeModal: closeModal,
        route: route,
        boot: boot,
    };

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
})(window);
