/* ============================================================
   HamKit 前端共享核心
   —— API 客户端（带 CSRF 与错误归一化）、会话、DOM、格式化、提示
   依赖：无（markdown.js 为可选增强）
   ============================================================ */

(function (global) {
    'use strict';

    const HamKit = (global.HamKit = global.HamKit || {});

    /* ---------------------------------------------------------- *
     * API 客户端
     * ---------------------------------------------------------- */

    const CSRF_COOKIE = 'hamkit_csrf';

    function readCookie(name) {
        const match = document.cookie.match(new RegExp(`(?:^|;\\s*)${name}=([^;]*)`));
        return match ? decodeURIComponent(match[1]) : null;
    }

    /** 归一化的 API 错误。 */
    class ApiError extends Error {
        constructor(message, { status = 0, code = 'error', details = null } = {}) {
            super(message);
            this.name = 'ApiError';
            this.status = status;
            this.code = code;
            this.details = details;
        }
        get isAuth() {
            return this.status === 401;
        }
        get isForbidden() {
            return this.status === 403;
        }
        get isNotFound() {
            return this.status === 404;
        }
        /** 面向用户的完整提示（区分网络错误与业务错误）。 */
        get friendly() {
            if (this.status === 0) return `网络请求失败：${this.message}`;
            if (this.status === 401) return '登录状态已失效，请重新登录';
            if (this.status === 403 && this.code === 'csrf_failed') return '会话校验失败，请刷新页面重试';
            if (this.status === 403) return this.message || '没有权限执行该操作';
            if (this.status === 429) return this.message || '操作过于频繁，请稍后再试';
            if (this.status >= 500) return `服务器错误（${this.status}）：${this.message}`;
            return this.message || `请求失败（${this.status}）`;
        }
    }

    /**
     * 发送请求。
     * @param {string} path
     * @param {{method?:string, body?:any, query?:object, headers?:object, raw?:boolean}} [options]
     */
    async function request(path, options = {}) {
        const { method = 'GET', body, query, headers = {}, raw = false } = options;
        let url = path;
        if (query && Object.keys(query).length) {
            const params = new URLSearchParams();
            for (const [key, value] of Object.entries(query)) {
                if (value !== undefined && value !== null && value !== '') params.set(key, String(value));
            }
            const qs = params.toString();
            if (qs) url += (url.includes('?') ? '&' : '?') + qs;
        }

        const finalHeaders = { accept: 'application/json', ...headers };
        const init = { method, headers: finalHeaders, credentials: 'same-origin' };

        if (body !== undefined && body !== null && !['GET', 'HEAD'].includes(method.toUpperCase())) {
            if (typeof body === 'string') {
                init.body = body;
            } else {
                finalHeaders['content-type'] = 'application/json';
                init.body = JSON.stringify(body);
            }
            const csrf = readCookie(CSRF_COOKIE);
            if (csrf) finalHeaders['x-csrf-token'] = csrf;
        }

        let response;
        try {
            response = await fetch(url, init);
        } catch (err) {
            throw new ApiError(err?.message || '无法连接服务器');
        }

        if (raw) return response;

        if (response.status === 204) return null;

        const text = await response.text();
        let payload = null;
        if (text) {
            try {
                payload = JSON.parse(text);
            } catch {
                payload = null;
            }
        }

        if (!response.ok) {
            throw new ApiError(payload?.error || response.statusText, {
                status: response.status,
                code: payload?.code || 'error',
                details: payload?.details || null,
            });
        }
        return payload;
    }

    const api = {
        request,
        ApiError,
        get: (path, options) => request(path, { ...options, method: 'GET' }),
        post: (path, body, options) => request(path, { ...options, method: 'POST', body }),
        patch: (path, body, options) => request(path, { ...options, method: 'PATCH', body }),
        put: (path, body, options) => request(path, { ...options, method: 'PUT', body }),
        del: (path, options) => request(path, { ...options, method: 'DELETE' }),
        /** 直接走浏览器跳转（下载、登出等）。 */
        visit: (path) => {
            global.location.href = path;
        },
    };

    /* ---------------------------------------------------------- *
     * 会话
     * ---------------------------------------------------------- */

    let sessionCache = null;
    let sessionPromise = null;

    const session = {
        /** 获取会话（带缓存；force=true 时强制刷新）。 */
        async load({ force = false } = {}) {
            if (!force && sessionCache) return sessionCache;
            if (!force && sessionPromise) return sessionPromise;
            sessionPromise = api
                .get('/api/session')
                .then((data) => {
                    sessionCache = {
                        authenticated: Boolean(data?.authenticated),
                        user: data?.user || null,
                        permissions: data?.permissions || {},
                        oidcConfigured: Boolean(data?.oidcConfigured),
                        devLoginEnabled: Boolean(data?.devLoginEnabled),
                        reason: data?.reason || null,
                        /** 服务端明确答复"未登录"；其它情况说明是请求本身失败 */
                        authFailed: false,
                    };
                    return sessionCache;
                })
                .catch((error) => {
                    // 关键区分：会话探测"失败"不等于"未登录"。
                    // 若把网络故障当成未登录，用户会看到"需要登录"并丢失回跳地址。
                    sessionCache = {
                        authenticated: false,
                        user: null,
                        permissions: {},
                        oidcConfigured: false,
                        devLoginEnabled: false,
                        reason: 'unreachable',
                        authFailed: true,
                        error: error instanceof ApiError ? error : new ApiError(String(error?.message || error)),
                    };
                    return sessionCache;
                })
                .finally(() => {
                    sessionPromise = null;
                });
            return sessionPromise;
        },
        get cached() {
            return sessionCache;
        },
        /** 权限判断：满足其一即可。 */
        can(...permissions) {
            if (!sessionCache?.authenticated) return false;
            return permissions.some((p) => Boolean(sessionCache.permissions?.[p]));
        },
        get role() {
            return sessionCache?.user?.role || null;
        },
        /**
         * 需要登录：未登录则跳转登录页并带回跳地址。
         * 会话探测失败（网络/服务异常）时抛出错误，交给调用方提示与重试，
         * 绝不能在"无法确认身份"时把用户当成未登录踢走。
         */
        async requireLogin({ returnTo } = {}) {
            const data = await session.load();
            if (data.authenticated) return data;
            if (data.authFailed) {
                throw data.error instanceof ApiError
                    ? data.error
                    : new ApiError('无法确认登录状态，请稍后重试');
            }
            const target = returnTo || global.location.pathname + global.location.search;
            global.location.href = `/auth/login?returnTo=${encodeURIComponent(target)}`;
            return data;
        },
        loginUrl(returnTo) {
            const target = returnTo || global.location.pathname + global.location.search;
            return `/auth/login?returnTo=${encodeURIComponent(target)}`;
        },
        logout({ idp = false } = {}) {
            const form = document.createElement('form');
            form.method = 'POST';
            form.action = `/auth/logout${idp ? '?idp=1' : ''}`;
            const csrf = readCookie(CSRF_COOKIE);
            if (csrf) {
                const input = document.createElement('input');
                input.type = 'hidden';
                input.name = 'csrf';
                input.value = csrf;
                form.appendChild(input);
            }
            document.body.appendChild(form);
            form.submit();
        },
    };

    /* ---------------------------------------------------------- *
     * DOM 工具
     * ---------------------------------------------------------- */

    const dom = {
        $(selector, root = document) {
            return root.querySelector(selector);
        },
        $$(selector, root = document) {
            return Array.from(root.querySelectorAll(selector));
        },
        /** 创建元素：el('div', {class:'x'}, ['文本', child]) */
        el(tag, attrs = {}, children = []) {
            const node = document.createElement(tag);
            for (const [key, value] of Object.entries(attrs)) {
                if (value === null || value === undefined || value === false) continue;
                if (key === 'class') node.className = value;
                else if (key === 'text') node.textContent = value;
                else if (key === 'html') node.innerHTML = value;
                else if (key === 'dataset') Object.assign(node.dataset, value);
                else if (key.startsWith('on') && typeof value === 'function') {
                    node.addEventListener(key.slice(2).toLowerCase(), value);
                } else if (key === 'value') node.value = value;
                else if (value === true) node.setAttribute(key, '');
                else node.setAttribute(key, String(value));
            }
            for (const child of [].concat(children)) {
                if (child === null || child === undefined || child === false) continue;
                node.append(child instanceof Node ? child : document.createTextNode(String(child)));
            }
            return node;
        },
        /** 事件委托 */
        on(root, eventName, selector, handler) {
            if (!root) return () => {};
            const listener = (event) => {
                const target = event.target.closest(selector);
                if (target && root.contains(target)) handler(event, target);
            };
            root.addEventListener(eventName, listener);
            return () => root.removeEventListener(eventName, listener);
        },
        escape(value) {
            return String(value ?? '').replace(/[&<>"']/g, (c) => ({
                '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
            }[c]));
        },
        /** 让按钮进入 loading 状态，返回恢复函数。 */
        busy(button, label = '处理中…') {
            if (!button) return () => {};
            const original = button.innerHTML;
            const disabled = button.disabled;
            button.disabled = true;
            button.innerHTML = `<span class="btn__spinner"></span><span>${dom.escape(label)}</span>`;
            return () => {
                button.disabled = disabled;
                button.innerHTML = original;
            };
        },
        /** 表单收集：data-field 属性作为键。 */
        collect(scope) {
            const out = {};
            dom.$$('[data-field]', scope).forEach((node) => {
                const key = node.dataset.field;
                if (node.type === 'checkbox') out[key] = node.checked;
                else if (node.type === 'number') out[key] = node.value === '' ? null : Number(node.value);
                else out[key] = node.value;
            });
            return out;
        },
        /** 用对象填充 data-field 表单。 */
        fill(scope, values = {}) {
            dom.$$('[data-field]', scope).forEach((node) => {
                const key = node.dataset.field;
                const value = values[key];
                if (value === undefined || value === null) return;
                if (node.type === 'checkbox') node.checked = Boolean(value);
                else node.value = value;
            });
        },
    };

    /* ---------------------------------------------------------- *
     * 格式化
     * ---------------------------------------------------------- */

    const fmt = {
        /** 本地时间（默认 zh-CN，24 小时制）。 */
        date(value, { withTime = true, seconds = false } = {}) {
            if (!value) return '—';
            const date = value instanceof Date ? value : new Date(value);
            if (Number.isNaN(date.getTime())) return '—';
            const options = { year: 'numeric', month: '2-digit', day: '2-digit' };
            if (withTime) {
                options.hour = '2-digit';
                options.minute = '2-digit';
                options.hour12 = false;
                if (seconds) options.second = '2-digit';
            }
            return date.toLocaleString('zh-CN', options).replace(/\//g, '-');
        },
        /** 相对时间：3 分钟前 / 2 天前。 */
        relative(value) {
            if (!value) return '—';
            const date = value instanceof Date ? value : new Date(value);
            if (Number.isNaN(date.getTime())) return '—';
            const diff = Date.now() - date.getTime();
            const future = diff < 0;
            const abs = Math.abs(diff);
            const units = [
                [86400_000, '天'],
                [3600_000, '小时'],
                [60_000, '分钟'],
                [1000, '秒'],
            ];
            for (const [ms, label] of units) {
                if (abs >= ms) {
                    const n = Math.floor(abs / ms);
                    return future ? `${n} ${label}后` : `${n} ${label}前`;
                }
            }
            return '刚刚';
        },
        bytes(value) {
            const n = Number(value);
            if (!Number.isFinite(n) || n <= 0) return '—';
            const units = ['B', 'KB', 'MB', 'GB'];
            let size = n;
            let i = 0;
            while (size >= 1024 && i < units.length - 1) {
                size /= 1024;
                i++;
            }
            return `${size.toFixed(size >= 10 || i === 0 ? 0 : 1)} ${units[i]}`;
        },
        number(value) {
            const n = Number(value);
            if (!Number.isFinite(n)) return '0';
            return n.toLocaleString('zh-CN');
        },
        /** 紧凑数字：1200 → 1.2k */
        compact(value) {
            const n = Number(value);
            if (!Number.isFinite(n)) return '0';
            if (Math.abs(n) < 1000) return String(n);
            if (Math.abs(n) < 1_000_000) return `${(n / 1000).toFixed(1)}k`;
            return `${(n / 1_000_000).toFixed(1)}M`;
        },
        duration(seconds) {
            const s = Math.max(0, Math.floor(Number(seconds) || 0));
            const d = Math.floor(s / 86400);
            const h = Math.floor((s % 86400) / 3600);
            const m = Math.floor((s % 3600) / 60);
            if (d) return `${d} 天 ${h} 小时`;
            if (h) return `${h} 小时 ${m} 分`;
            if (m) return `${m} 分 ${s % 60} 秒`;
            return `${s} 秒`;
        },
        /** 角色中文名 */
        role(role) {
            return { admin: '管理员', editor: '编辑', viewer: '访客' }[role] || role || '未知';
        },
        /** 渠道中文名 */
        channel(channel) {
            return { stable: '稳定版', beta: '测试版', nightly: '每夜版' }[channel] || channel || '—';
        },
        /** URL 简短显示（保留主机与末段） */
        urlShort(value, max = 48) {
            const raw = String(value || '');
            if (raw.length <= max) return raw;
            try {
                const url = new URL(raw);
                const tail = url.pathname.split('/').filter(Boolean).pop() || '';
                return `${url.host}/…/${tail}`.slice(0, max + 8);
            } catch {
                return `${raw.slice(0, max)}…`;
            }
        },
    };

    /* ---------------------------------------------------------- *
     * 轻提示
     * ---------------------------------------------------------- */

    const ICONS = { success: '✓', error: '✕', warning: '!', info: 'i' };

    function ensureToastHost() {
        let host = document.querySelector('.toast-host');
        if (!host) {
            host = dom.el('div', { class: 'toast-host', role: 'status', 'aria-live': 'polite' });
            document.body.appendChild(host);
        }
        return host;
    }

    /**
     * 弹一条提示。
     * @param {string|{title?:string, text?:string}} input
     * @param {{type?:'success'|'error'|'warning'|'info', timeout?:number}} [options]
     */
    function toast(input, options = {}) {
        const payload = typeof input === 'string' ? { text: input } : input || {};
        const type = options.type || payload.type || 'info';
        const timeout = options.timeout ?? (type === 'error' ? 6000 : 3600);
        const host = ensureToastHost();

        const node = dom.el('div', { class: `toast toast--${type}` });
        node.append(
            dom.el('span', { class: 'toast__icon' }, [ICONS[type] || 'i']),
            dom.el('div', { class: 'toast__body' }, [
                payload.title ? dom.el('div', { class: 'toast__title', text: payload.title }) : null,
                payload.text ? dom.el('div', { class: 'toast__text', html: dom.escape(payload.text) }) : null,
            ]),
        );
        const close = () => {
            node.classList.add('is-leaving');
            setTimeout(() => node.remove(), 220);
        };
        node.append(dom.el('button', { class: 'toast__close', type: 'button', 'aria-label': '关闭', onclick: close }, ['×']));
        host.appendChild(node);
        if (timeout > 0) setTimeout(close, timeout);
        return close;
    }

    toast.success = (text, options) => toast({ text }, { ...options, type: 'success' });
    toast.error = (text, options) => toast({ text }, { ...options, type: 'error' });
    toast.warning = (text, options) => toast({ text }, { ...options, type: 'warning' });
    toast.info = (text, options) => toast({ text }, { ...options, type: 'info' });

    /** 统一处理 API 错误：401 跳登录，其余弹提示。 */
    function handleError(error, { returnTo, silent = false } = {}) {
        const apiError = error instanceof ApiError ? error : new ApiError(error?.message || String(error));
        if (apiError.isAuth) {
            if (!silent) toast.warning('登录状态已失效，正在跳转登录…');
            setTimeout(() => {
                global.location.href = session.loginUrl(returnTo);
            }, 600);
            return apiError;
        }
        if (!silent) toast.error(apiError.friendly);
        return apiError;
    }

    /* ---------------------------------------------------------- *
     * 确认对话框（Promise 化）
     * ---------------------------------------------------------- */

    function confirmDialog({ title = '确认操作', text = '', confirmText = '确认', cancelText = '取消', danger = false } = {}) {
        return new Promise((resolve) => {
            const modal = dom.el('div', { class: 'modal is-open', role: 'dialog', 'aria-modal': 'true' });
            const panel = dom.el('div', { class: 'modal__panel' });
            const finish = (value) => {
                modal.remove();
                document.removeEventListener('keydown', onKey);
                resolve(value);
            };
            const onKey = (event) => {
                if (event.key === 'Escape') finish(false);
                if (event.key === 'Enter') finish(true);
            };
            panel.append(
                dom.el('div', { class: 'modal__head' }, [dom.el('div', { class: 'modal__title', text: title })]),
                dom.el('div', { class: 'modal__body' }, [dom.el('p', { class: 'u-muted', text })]),
                dom.el('div', { class: 'modal__foot' }, [
                    dom.el('button', { class: 'btn btn--ghost', type: 'button', onclick: () => finish(false) }, [cancelText]),
                    dom.el('button', {
                        class: `btn ${danger ? 'btn--danger' : 'btn--primary'}`,
                        type: 'button',
                        onclick: () => finish(true),
                    }, [confirmText]),
                ]),
            );
            modal.appendChild(panel);
            modal.addEventListener('click', (event) => {
                if (event.target === modal) finish(false);
            });
            document.addEventListener('keydown', onKey);
            document.body.appendChild(modal);
            panel.querySelector('.btn:last-child')?.focus();
        });
    }

    /* ---------------------------------------------------------- *
     * 轻量折线图（无依赖，用于后台统计）
     * ---------------------------------------------------------- */

    function sparkline(canvas, series, { keys = [], colors = [] } = {}) {
        if (!canvas || !canvas.getContext) return;
        const ctx = canvas.getContext('2d');
        const dpr = Math.min(global.devicePixelRatio || 1, 2);
        const rect = canvas.getBoundingClientRect();
        const width = Math.max(120, rect.width || canvas.clientWidth || 300);
        const height = Math.max(80, rect.height || 160);
        canvas.width = Math.round(width * dpr);
        canvas.height = Math.round(height * dpr);
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.clearRect(0, 0, width, height);

        const palette = colors.length ? colors : ['#4D8FFF', '#3FB950', '#E8B34C'];
        const padding = { top: 12, right: 8, bottom: 20, left: 30 };
        const plotW = width - padding.left - padding.right;
        const plotH = height - padding.top - padding.bottom;

        const points = series || [];
        const maxValue = Math.max(
            1,
            ...points.flatMap((row) => keys.map((key) => Number(row[key]) || 0)),
        );

        // 网格与刻度
        ctx.strokeStyle = 'rgba(128,140,160,0.18)';
        ctx.fillStyle = 'rgba(128,140,160,0.8)';
        ctx.font = '10px ui-monospace, monospace';
        ctx.lineWidth = 1;
        for (let i = 0; i <= 3; i++) {
            const y = padding.top + (plotH / 3) * i;
            ctx.beginPath();
            ctx.moveTo(padding.left, y);
            ctx.lineTo(width - padding.right, y);
            ctx.stroke();
            const value = Math.round(maxValue * (1 - i / 3));
            ctx.fillText(String(value), 4, y + 3);
        }

        if (!points.length) {
            ctx.fillStyle = 'rgba(128,140,160,0.8)';
            ctx.fillText('暂无数据', padding.left + 6, padding.top + plotH / 2);
            return;
        }

        const stepX = points.length > 1 ? plotW / (points.length - 1) : 0;
        keys.forEach((key, index) => {
            const color = palette[index % palette.length];
            ctx.beginPath();
            ctx.strokeStyle = color;
            ctx.lineWidth = 2;
            points.forEach((row, i) => {
                const x = padding.left + stepX * i;
                const y = padding.top + plotH - (Number(row[key]) || 0) / maxValue * plotH;
                if (i === 0) ctx.moveTo(x, y);
                else ctx.lineTo(x, y);
            });
            ctx.stroke();

            // 端点与面积
            ctx.globalAlpha = 0.14;
            ctx.lineTo(padding.left + stepX * (points.length - 1), padding.top + plotH);
            ctx.lineTo(padding.left, padding.top + plotH);
            ctx.closePath();
            ctx.fillStyle = color;
            ctx.fill();
            ctx.globalAlpha = 1;
        });

        // 首末日期
        ctx.fillStyle = 'rgba(128,140,160,0.85)';
        ctx.fillText(points[0].date?.slice(5) || '', padding.left, height - 5);
        const lastLabel = points[points.length - 1].date?.slice(5) || '';
        ctx.fillText(lastLabel, padding.left + plotW - ctx.measureText(lastLabel).width, height - 5);
    }

    /* ---------------------------------------------------------- *
     * 其他
     * ---------------------------------------------------------- */

    /** 复制到剪贴板（带降级）。 */
    async function copyText(text) {
        try {
            if (navigator.clipboard?.writeText) {
                await navigator.clipboard.writeText(text);
                return true;
            }
        } catch {
            /* 降级到 execCommand */
        }
        try {
            const area = document.createElement('textarea');
            area.value = text;
            area.style.position = 'fixed';
            area.style.opacity = '0';
            document.body.appendChild(area);
            area.select();
            const ok = document.execCommand('copy');
            area.remove();
            return ok;
        } catch {
            return false;
        }
    }

    /** 记录页面级异常，便于排查与自动化验收断言。 */
    global.__hkPageErrors = global.__hkPageErrors || [];
    global.addEventListener('error', (event) => {
        global.__hkPageErrors.push({ type: 'error', message: event.message, source: event.filename, line: event.lineno });
    });
    global.addEventListener('unhandledrejection', (event) => {
        const reason = event.reason;
        global.__hkPageErrors.push({
            type: 'unhandledrejection',
            message: reason instanceof Error ? `${reason.name}: ${reason.message}` : String(reason),
            stack: reason instanceof Error ? reason.stack : null,
        });
    });

    /** 简易防抖。 */
    function debounce(fn, wait = 300) {
        let timer = null;
        return (...args) => {
            clearTimeout(timer);
            timer = setTimeout(() => fn(...args), wait);
        };
    }

    Object.assign(HamKit, {
        api,
        session,
        dom,
        fmt,
        toast,
        handleError,
        confirm: confirmDialog,
        sparkline,
        copyText,
        debounce,
        readCookie,
    });
})(window);
