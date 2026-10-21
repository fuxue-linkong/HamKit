/* ============================================================
   HamKit 登录页 / 用户中心 共用逻辑
   ------------------------------------------------------------
   - 纯浏览器 JS（IIFE，无构建、无框架、无 import）
   - 依赖 /assets/site.js 提供的 HamKit.{api,session,dom,fmt,toast,handleError,copyText}
   - 会话契约：GET /api/session；用户中心额外使用 GET /api/me/export、DELETE /api/me
   - 页面通过 <body data-auth-page="login|account"> 声明自己是哪一页
   - 所有动态字符串走 textContent / HamKit.dom.el，不用 innerHTML 拼数据
   ============================================================ */

(function (global) {
    'use strict';

    const HamKit = (global.HamKit = global.HamKit || {});
    const { api, session, dom, fmt, toast, handleError, copyText } = HamKit;

    /** 默认回跳地址：与后端 sanitizeReturnTo 的兜底保持一致。 */
    const DEFAULT_RETURN_TO = '/admin/';

    /** 权限 → 中文说明。必须覆盖 server/core/session.mjs 的 PERMISSIONS 全部键。 */
    const PERMISSION_LABELS = {
        'version:read': '查看版本列表',
        'version:write': '新增 / 修改版本',
        'version:delete': '删除版本',
        'content:read': '查看站点内容',
        'content:write': '编辑站点内容',
        'announcement:read': '查看公告',
        'announcement:write': '发布 / 修改公告',
        'stats:read': '查看访问统计',
        'audit:read': '查看审计日志',
        'user:read': '查看用户列表',
        'user:write': '管理用户与角色',
        'system:read': '查看系统状态与会话',
    };

    /** 回跳地址白名单校验：只允许站内相对路径，防开放重定向。 */
    function sanitizeReturnTo(value) {
        if (typeof value !== 'string') return DEFAULT_RETURN_TO;
        if (!value) return DEFAULT_RETURN_TO;
        if (!value.startsWith('/') || value.startsWith('//') || value.includes('\\')) {
            return DEFAULT_RETURN_TO;
        }
        return value.slice(0, 512);
    }

    /** 从 location.search 读取 ?returnTo= 并校验。 */
    function readReturnTo() {
        let raw = null;
        try {
            raw = new URLSearchParams(global.location.search).get('returnTo');
        } catch {
            raw = null;
        }
        return sanitizeReturnTo(raw);
    }

    /** 当前 returnTo（登录成功后要回到的地方）。 */
    function currentReturnTo() {
        return readReturnTo();
    }

    /** 读取 ?error= 并解码（兼容多次编码），返回友好中文文案。 */
    function readErrorNotice() {
        let raw = null;
        try {
            raw = new URLSearchParams(global.location.search).get('error');
        } catch {
            raw = null;
        }
        if (!raw) return null;

        let value = raw;
        for (let i = 0; i < 2; i += 1) {
            try {
                const next = decodeURIComponent(value);
                if (next === value) break;
                value = next;
            } catch {
                break;
            }
        }
        value = value.trim();
        if (!value) return null;

        const friendly = {
            not_configured: '站点尚未配置 OIDC，请联系管理员设置 OIDC_ISSUER / OIDC_CLIENT_ID',
            oauth_state_missing: '登录会话已过期或校验失败，请重新点击登录',
            oauth_state_invalid: '登录会话已过期或校验失败，请重新点击登录',
            state_mismatch: '登录会话已过期或校验失败，请重新点击登录',
            access_denied: '你取消了授权',
        }[value];

        return friendly || value;
    }

    /* ---------------------------------------------------------- *
     * 小工具
     * ---------------------------------------------------------- */

    /** 带图标的提示条。type: danger | warning | info | success */
    function alertNode(type, message, { title = null } = {}) {
        const icons = { danger: '✕', warning: '!', info: 'i', success: '✓' };
        return dom.el('div', { class: `alert alert--${type}`, role: type === 'danger' ? 'alert' : 'status' }, [
            dom.el('span', { class: 'alert__icon', 'aria-hidden': 'true' }, [icons[type] || 'i']),
            dom.el('div', { class: 'alert__body' }, [
                title ? dom.el('div', { class: 'alert__title', text: title }) : null,
                dom.el('div', { text: message }),
            ]),
        ]);
    }

    /** 注册一个「点了就跳转」的按钮（避免用 <a> 模拟禁用态）。 */
    function goButton({ label, className = 'btn btn--primary', href, disabled = false }) {
        const button = dom.el('button', { class: className, type: 'button', text: label });
        if (disabled) {
            button.disabled = true;
            button.setAttribute('aria-disabled', 'true');
        } else {
            button.addEventListener('click', () => {
                global.location.href = href;
            });
        }
        return button;
    }

    /** 「复制」按钮：复制给定文本并提示结果。 */
    function copyButton(value, { label = '复制', okText = '已复制到剪贴板' } = {}) {
        return dom.el('button', {
            class: 'btn btn--ghost btn--sm',
            type: 'button',
            text: label,
            title: `复制：${value}`,
            onclick: async () => {
                const ok = await copyText(value);
                if (ok) toast.success(okText);
                else toast.error('复制失败，请手动选择文本复制');
            },
        });
    }

    /** `.login-meta__row` 一行：左标签 + 右内容。 */
    function metaRow(label, content) {
        return dom.el('div', { class: 'login-meta__row' }, [
            dom.el('span', { text: label }),
            dom.el('span', {}, [content]),
        ]);
    }

    /** 键值行（用于会话/IdP 卡片，与后台 .kv 风格一致）。 */
    function kvRow(label, content) {
        const isNode = content instanceof Node;
        return dom.el('div', { class: 'u-row u-row--between u-wrap' }, [
            dom.el('span', { class: 'u-muted', text: label }),
            isNode ? content : dom.el('span', { class: 'u-mono', text: String(content) }),
        ]);
    }

    /** 键值行，右侧固定等宽字体、左对齐（用于账号 ID 这类长标识）。 */
    function kvRowMono(label, value) {
        const text = String(value ?? '');
        return dom.el('div', { class: 'u-row u-row--between u-wrap' }, [
            dom.el('span', { class: 'u-muted', text: label }),
            dom.el('span', { class: 'u-mono u-truncate', title: text, text }),
        ]);
    }

    /** 角色徽标（复用 .badge 与 .role-* 配色）。 */
    function roleBadge(role) {
        return dom.el('span', { class: `badge role-${role || 'viewer'}`, text: fmt.role(role) });
    }

    /** 「是 / 否」小徽标。 */
    function yesNoBadge(value) {
        return value
            ? dom.el('span', { class: 'badge badge--success', text: '是' })
            : dom.el('span', { class: 'badge badge--muted', text: '否' });
    }

    /** 头像：有 user.avatar 用图片，否则用 displayName 首字母。 */
    function avatarNode(user) {
        if (user && user.avatar) {
            return dom.el('img', {
                class: 'profile-card__avatar',
                src: user.avatar,
                alt: `${user.displayName || user.username || '用户'} 的头像`,
                referrerpolicy: 'no-referrer',
                loading: 'lazy',
            });
        }
        const source = String((user && (user.displayName || user.username)) || '?').trim();
        const initial = source ? Array.from(source)[0].toUpperCase() : '?';
        return dom.el('div', { class: 'profile-card__avatar', 'aria-hidden': 'true', text: initial });
    }

    /**
     * 显示容器。
     * 刻意不在这里清空内容：早先实现在这里做了 `textContent = ''`，
     * 一旦后续渲染步骤未完成（或抛错），页面就会留下一个"已显示但完全空白"的容器，
     * 看起来像页面坏了。各渲染函数只清空自己负责的子节点。
     */
    function show(container) {
        if (!container) return null;
        container.hidden = false;
        return container;
    }

    function hide(container) {
        if (container) container.hidden = true;
    }

    /** 清空容器（渲染函数内部使用）。 */
    function clear(container) {
        if (container) container.textContent = '';
        return container;
    }

    /* ============================================================
     * 登录页
     * ============================================================ */

    /** 渲染 ?error= 提示。 */
    function renderLoginNotice(noticeEl) {
        const message = readErrorNotice();
        if (noticeEl) {
            noticeEl.textContent = '';
            noticeEl.hidden = !message;
        }
        if (!message) return null;
        const node = alertNode('danger', message, { title: '登录未完成' });
        noticeEl.appendChild(node);
        return node;
    }

    /** 已登录时的替代视图：不再显示 OIDC 按钮，提供进入后台 / 退出。 */
    function renderAlreadyLoggedIn(noticeEl, providersEl, metaEl, current) {
        hide(providersEl);
        hide(metaEl);
        // 已登录时把「登录未完成」的错误提示也收起来：状态已经变了
        if (noticeEl) {
            noticeEl.textContent = '';
            noticeEl.hidden = false;
            noticeEl.appendChild(
                alertNode('info', '当前浏览器已经持有有效会话，无需重复登录。', { title: '你已登录' }),
            );
        }

        const user = current.user || {};
        const returnTo = currentReturnTo();
        const backLink = sanitizeReturnTo(returnTo);
        const providers = show(providersEl);
        if (!providers) return;
        providers.append(
            dom.el('div', { class: 'login-meta__row' }, [
                dom.el('span', { text: '当前账号' }),
                dom.el('span', { class: 'u-row u-wrap' }, [
                    dom.el('strong', { text: user.displayName || user.username || '未知用户' }),
                    roleBadge(user.role),
                ]),
            ]),
            goButton({
                label: `进入后台（${backLink}）`,
                className: 'btn btn--primary btn--lg btn--block',
                href: backLink,
            }),
            dom.el('button', {
                class: 'btn btn--ghost btn--block',
                type: 'button',
                text: '退出登录',
                onclick: () => session.logout(),
            }),
        );
    }

    /** 配置引导 + 开发直登区块（全部内容来自 /api/meta，缺字段时不臆造）。 */
    function renderLoginMeta(meta, { oidcUsable }) {
        const facts = meta && typeof meta === 'object' ? meta : {};
        const problems = Array.isArray(facts.configProblems) ? facts.configProblems : [];
        const warnings = Array.isArray(facts.configWarnings) ? facts.configWarnings : [];
        const devLoginEnabled = facts.devLoginEnabled === true;
        const configured = facts.oidcConfigured === true;
        const issuer = facts.oidcIssuer ? String(facts.oidcIssuer) : null;
        const baseUrl = typeof facts.baseUrl === 'string' && facts.baseUrl ? facts.baseUrl.replace(/\/+$/, '') : null;
        const callbackUrl = baseUrl ? `${baseUrl}/auth/callback` : null;

        const children = [dom.el('div', { class: 'u-row u-row--between u-wrap' }, [
            dom.el('span', { text: '站点配置' }),
            configured
                ? dom.el('span', { class: 'badge badge--success badge--dot', text: 'OIDC 已配置' })
                : dom.el('span', { class: 'badge badge--warning badge--dot', text: 'OIDC 未配置' }),
        ])];

        children.push(metaRow('OIDC 状态', configured ? '已配置' : '未配置'));
        if (issuer) children.push(metaRow('OIDC Issuer', dom.el('code', { text: issuer })));
        if (facts.env) children.push(metaRow('运行环境', String(facts.env)));

        if (callbackUrl) {
            children.push(metaRow('回调地址', dom.el('code', { text: callbackUrl })));
            children.push(dom.el('div', { class: 'u-row u-wrap' }, [
                copyButton(callbackUrl, { label: '复制回调地址' }),
                dom.el('span', { class: 'u-dim', text: '请在 IdP 侧登记该 Redirect URI' }),
            ]));
        }

        // 配置问题 / 告警
        for (const problem of problems) children.push(alertNode('danger', String(problem), { title: '配置缺失' }));
        for (const warning of warnings) children.push(alertNode('warning', String(warning), { title: '配置告警' }));

        // 未配置时的补齐指引
        if (!oidcUsable) {
            children.push(
                alertNode('info', '补齐下列环境变量后重启服务，登录按钮即可使用；本页也会自动刷新配置引导。', {
                    title: '如何配置 OIDC',
                }),
                dom.el('div', { class: 'u-mono u-dim', text: 'OIDC_ISSUER' }),
                dom.el('div', { class: 'u-mono u-dim', text: 'OIDC_CLIENT_ID' }),
                dom.el('div', { class: 'u-mono u-dim', text: 'OIDC_CLIENT_SECRET' }),
                dom.el('div', { class: 'u-mono u-dim', text: 'BASE_URL' }),
            );
        }

        // 本地开发直登（仅当 /api/meta 明确返回 devLoginEnabled: true）
        if (devLoginEnabled) {
            children.push(
                dom.el('div', { class: 'u-mt u-muted', text: '本地开发登录' }),
                dom.el('div', { class: 'dev-roles' }, [
                    goButton({ label: '管理员', className: 'btn btn--ghost btn--sm', href: '/auth/dev-login?role=admin' }),
                    goButton({ label: '编辑', className: 'btn btn--ghost btn--sm', href: '/auth/dev-login?role=editor' }),
                    goButton({ label: '访客', className: 'btn btn--ghost btn--sm', href: '/auth/dev-login?role=viewer' }),
                ]),
                alertNode('warning', '开发直登仅用于本地调试，生产环境不可用。'),
            );
        }

        return children;
    }

    async function initLoginPage() {
        const noticeEl = document.getElementById('authNotice');
        const providersEl = document.getElementById('authProviders');
        const metaEl = document.getElementById('authMeta');
        const oidcBtn = document.getElementById('authOidcBtn');

        renderLoginNotice(noticeEl);

        // 会话 + 元信息并行获取；/api/meta 失败不阻塞登录主流程
        const [current, meta] = await Promise.all([
            session.load(),
            api.get('/api/meta').catch(() => null),
        ]);

        if (current.authenticated) {
            renderAlreadyLoggedIn(noticeEl, providersEl, metaEl, current);
            return;
        }

        const oidcUsable = meta ? meta.oidcConfigured === true : current.oidcConfigured === true;
        show(providersEl);
        if (oidcBtn) {
            oidcBtn.disabled = !oidcUsable;
            if (!oidcUsable) oidcBtn.setAttribute('aria-disabled', 'true');
        }

        const metaHost = show(metaEl);
        if (!metaHost) return;

        if (!meta) {
            metaHost.appendChild(
                alertNode('warning', '无法读取 /api/meta，配置引导暂不可用；登录入口仍可尝试。', {
                    title: '配置信息获取失败',
                }),
            );
        }
        if (!oidcUsable) {
            metaHost.appendChild(
                alertNode('warning', '站点尚未配置 OIDC，主按钮已置灰；请先按下方说明补齐环境变量。'),
            );
        }
        for (const child of renderLoginMeta(meta, { oidcUsable })) metaHost.appendChild(child);
    }

    function onOidcClick() {
        global.location.href = `/auth/login?returnTo=${encodeURIComponent(currentReturnTo())}`;
    }

    /* ============================================================
     * 用户中心
     * ============================================================ */

    function renderProfileCard(host, user) {
        const rows = [
            ['用户名', user.username || '—'],
            ['邮箱', user.email || '—'],
            ['上次登录', fmt.relative(user.lastLoginAt)],
            ['登录次数', `${fmt.number(user.loginCount != null ? user.loginCount : 0)} 次`],
        ];

        host.append(
            avatarNode(user),
            dom.el('div', { class: 'profile-card__name', text: user.displayName || user.username || '未知用户' }),
            dom.el('div', { class: 'u-stack u-stack--sm u-grow' },
                rows.map(([label, value]) =>
                    dom.el('div', { class: 'u-row u-row--between u-wrap' }, [
                        dom.el('span', { class: 'u-muted', text: label }),
                        dom.el('span', { title: String(value), text: String(value) }),
                    ]))),
            roleBadge(user.role),
            kvRowMono('账号 ID', user.id || '—'),
        );
    }

    function renderPermissionCard(listEl, entryEl, current) {
        const permissions = current.permissions && typeof current.permissions === 'object' ? current.permissions : {};
        // 固定以「服务端下发的全部权限键」为遍历来源，未映射的键回退显示原始键名
        for (const key of Object.keys(permissions)) {
            const allowed = Boolean(permissions[key]);
            listEl.appendChild(
                dom.el('div', { class: `perm ${allowed ? 'perm--yes' : 'perm--no'}`, title: allowed ? '已具备' : '不具备' }, [
                    dom.el('span', { class: 'perm__mark', 'aria-hidden': 'true', text: allowed ? '✓' : '✕' }),
                    dom.el('span', { text: PERMISSION_LABELS[key] || key }),
                    dom.el('span', { class: 'u-dim', text: key }),
                ]),
            );
        }
        if (!listEl.childElementCount) {
            listEl.appendChild(dom.el('div', { class: 'u-muted', text: '服务端未下发权限项。' }));
        }

        if (session.can('version:read', 'content:read', 'announcement:read', 'stats:read', 'audit:read', 'user:read', 'system:read')) {
            entryEl.appendChild(
                dom.el('a', { class: 'btn btn--primary btn--sm', href: '/admin/', text: '进入管理后台' }),
            );
        } else {
            entryEl.appendChild(
                dom.el('span', {
                    class: 'u-muted',
                    text: '当前账号没有任何后台读取权限，请联系管理员分配角色。',
                }),
            );
        }
    }

    async function exportAccountData() {
        try {
            const data = await api.get('/api/me/export');
            const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
            const url = global.URL.createObjectURL(blob);
            const anchor = document.createElement('a');
            anchor.href = url;
            anchor.download = `hamkit-account-${new Date().toISOString().slice(0, 10)}.json`;
            document.body.appendChild(anchor);
            anchor.click();
            anchor.remove();
            global.setTimeout(() => global.URL.revokeObjectURL(url), 1000);
            toast.success('账号数据已导出');
        } catch (error) {
            handleError(error, { returnTo: '/account' });
        }
    }

    async function deleteAccount() {
        if (!global.confirm('注销后账号资料会被删除，且无法恢复。确定继续吗？')) return;
        const typed = global.prompt('请输入 DELETE（大写）确认注销账号');
        if (typed !== 'DELETE') {
            toast.info('已取消注销');
            return;
        }
        try {
            await api.del('/api/me', { body: { confirm: 'DELETE' } });
            toast.success('账号已注销');
            global.location.href = '/';
        } catch (error) {
            handleError(error, { returnTo: '/account' });
        }
    }

    function renderSessionCard(host, current) {
        const user = current.user || {};
        host.append(
            dom.el('p', { class: 'u-muted', text:
                '本站会话保存在 HttpOnly Cookie 中，默认有效期 8 小时（可由服务端 SESSION_TTL_HOURS 调整）。'
                + '会话到期或服务端撤销后需要重新登录，退出登录会立即撤销服务端会话记录。' }),
            kvRow('会话状态', '有效'),
            kvRow('角色', fmt.role(user.role)),
            kvRow('OIDC 单点登录', current.oidcConfigured ? '已配置' : '未配置'),
            kvRow('上次登录', `${fmt.relative(user.lastLoginAt)}（${fmt.date(user.lastLoginAt)}）`),
            dom.el('div', { class: 'u-row u-wrap u-mt' }, [
                dom.el('button', {
                    class: 'btn btn--ghost',
                    type: 'button',
                    text: '退出登录',
                    onclick: () => session.logout(),
                }),
                dom.el('button', {
                    class: 'btn btn--danger',
                    type: 'button',
                    text: '同时退出身份提供方',
                    onclick: () => session.logout({ idp: true }),
                }),
            ]),
            dom.el('p', { class: 'u-dim', text:
                '「同时退出身份提供方」会跳转到 IdP 的注销入口，从而一并结束其他站点的单点登录状态。' }),
            dom.el('div', { class: 'u-stack u-stack--sm u-mt' }, [
                dom.el('h3', { class: 'u-mt', text: '隐私与数据' }),
                dom.el('p', { class: 'u-muted', text:
                    '你可以导出本站保存的账号资料、会话元数据与本人审计记录，或注销账号。'
                    + '注销仅删除官网账号；App 本地日志/图库/Passcode 请在各设备上自行清除。' }),
                dom.el('div', { class: 'u-row u-wrap' }, [
                    dom.el('button', {
                        class: 'btn btn--ghost',
                        type: 'button',
                        text: '导出我的数据',
                        onclick: () => exportAccountData(),
                    }),
                    dom.el('button', {
                        class: 'btn btn--danger',
                        type: 'button',
                        text: '注销账号',
                        onclick: () => deleteAccount(),
                    }),
                ]),
            ]),
        );
    }

    function renderIdpCard(host, current, user) {
        const idpRoles = Array.isArray(user.idpRoles) ? user.idpRoles : [];

        if (idpRoles.length) {
            host.appendChild(
                dom.el('div', { class: 'u-row u-wrap' }, [
                    dom.el('span', { class: 'u-muted', text: 'IdP 声明的角色' }),
                    dom.el('span', { class: 'tag-list' }, idpRoles.map((r) => dom.el('span', { class: 'tag', text: String(r) }))),
                ]),
            );
        } else {
            host.appendChild(
                alertNode('info', 'IdP 未返回角色声明（roles claim 为空）。若希望按 IdP 角色自动授权，'
                    + '可用环境变量 OIDC_ROLE_MAP 把 IdP 角色映射成 admin / editor / viewer，'
                    + '并用 OIDC_ROLE_CLAIM 指定读取哪个 claim（默认 roles）。'),
            );
        }

        host.append(
            kvRow('本站角色', fmt.role(user.role)),
            kvRow('角色来源', '人工设置优先于 IdP 角色声明'),
            kvRow('IdP 角色映射', 'OIDC_ROLE_MAP'),
            kvRow('角色声明字段', 'OIDC_ROLE_CLAIM（默认 roles）'),
            kvRow('OIDC Issuer', current.oidcConfigured ? '已配置' : '未配置'),
            dom.el('p', { class: 'u-dim', text:
                '角色判定顺序：管理员在后台手动锁定过的角色 → 引导管理员白名单 → OIDC_ROLE_MAP 映射结果 → '
                + '账号既有角色 → 首个登录用户（可配置）→ 默认 viewer。' }),
        );
    }

    async function initAccountPage() {
        // 每次初始化都重新查询元素：既不依赖调用时机，也能在缺失时给出明确提示
        // （而不是抛 TypeError 后留下一个空白页面）
        const required = {
            gateEl: 'authGate',
            gridEl: 'authAccount',
            profileEl: 'authProfile',
            permListEl: 'authPermList',
            entryEl: 'authAdminEntry',
            sessionBodyEl: 'authSessionBody',
            idpBodyEl: 'authIdpBody',
        };
        const nodes = {};
        const missing = [];
        for (const [key, id] of Object.entries(required)) {
            nodes[key] = document.getElementById(id);
            if (!nodes[key]) missing.push(id);
        }
        const { gateEl, gridEl, profileEl, permListEl, entryEl, sessionBodyEl, idpBodyEl } = nodes;

        if (missing.length) {
            // 页面结构与脚本不匹配：明确报错，便于排查（通常是静态文件被旧版本缓存 / 部署不完整）
            const message = `用户中心页面结构不完整，缺少元素：${missing.join(', ')}。请强制刷新（Ctrl+F5）或重新部署静态文件。`;
            if (gridEl) hide(gridEl);
            if (gateEl) {
                gateEl.hidden = false;
                gateEl.textContent = '';
                gateEl.appendChild(alertNode('danger', message));
            }
            throw new Error(message);
        }

        // 未登录：requireLogin() 会跳转登录页；会话探测失败则抛错，由调用方提示
        const current = await session.requireLogin({ returnTo: '/account' });
        if (!current.authenticated || !current.user) {
            hide(gridEl);
            show(gateEl);
            return;
        }

        const user = current.user;
        hide(gateEl);
        show(gridEl);

        renderProfileCard(profileEl, user);
        renderPermissionCard(permListEl, entryEl, current);
        renderSessionCard(sessionBodyEl, current);
        renderIdpCard(idpBodyEl, current, user);
    }

    /* ============================================================
     * 启动
     * ============================================================ */

    function boot() {
        const page = document.body ? document.body.dataset.authPage : null;

        if (page === 'login') {
            const oidcBtn = document.getElementById('authOidcBtn');
            if (oidcBtn) oidcBtn.addEventListener('click', onOidcClick);
            initLoginPage().catch((error) => {
                const noticeEl = document.getElementById('authNotice');
                const apiError = handleError(error, { silent: true });
                if (noticeEl) {
                    noticeEl.textContent = '';
                    noticeEl.hidden = false;
                    noticeEl.appendChild(alertNode('danger', apiError.friendly || '登录页初始化失败。'));
                }
            });
            return;
        }

        if (page === 'account') {
            initAccountPage().catch((error) => {
                // 会话探测失败 / 页面结构异常：在页面上明确说明，而不是留一个空白页
                const gateEl = document.getElementById('authGate');
                if (gateEl) {
                    gateEl.hidden = false;
                    gateEl.textContent = '';
                    gateEl.appendChild(
                        alertNode('danger', error?.friendly || error?.message || '用户中心加载失败，请稍后重试。'),
                    );
                    gateEl.appendChild(
                        dom.el('button', {
                            class: 'btn btn--ghost btn--sm u-mt',
                            type: 'button',
                            text: '重新加载',
                            onclick: () => global.location.reload(),
                        }),
                    );
                }
                handleError(error, { returnTo: '/account' });
            });
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', boot, { once: true });
    } else {
        boot();
    }

    /** 对外暴露（便于控制台调试与后续复用）。 */
    HamKit.auth = {
        PERMISSION_LABELS,
        DEFAULT_RETURN_TO,
        sanitizeReturnTo,
        currentReturnTo,
        readErrorNotice,
        initLoginPage,
        initAccountPage,
    };
})(window);
