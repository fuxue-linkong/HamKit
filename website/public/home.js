/* ============================================================
   HamKit 官网首页交互
   —— 全部数据来自后端 API，接口不可用时优雅退化到页面内默认值
   依赖：/assets/site.js（HamKit.api / dom / fmt / toast / md）
   ============================================================ */

(function () {
    'use strict';

    const HamKit = window.HamKit;
    const { api, dom, fmt, toast, md } = HamKit;

    /* ---------------------------------------------------------- *
     * 滚动揭示
     * ---------------------------------------------------------- */
    const REVEAL_SELECTORS = [
        '.hero__badge', '.hero__title', '.hero__subtitle', '.hero__desc', '.hero__cta',
        '.section__head', '.feature', '.note', '.stack__group', '.dev-card', '.release-card',
    ];

    function initReveal() {
        REVEAL_SELECTORS.forEach((selector) => {
            dom.$$(selector).forEach((el) => el.setAttribute('data-reveal', ''));
        });
        const observer = new IntersectionObserver((entries) => {
            entries.forEach((entry) => {
                if (entry.isIntersecting) {
                    entry.target.classList.add('is-visible');
                    observer.unobserve(entry.target);
                }
            });
        }, { threshold: 0.08, rootMargin: '0px 0px -40px 0px' });
        dom.$$('[data-reveal]').forEach((el) => observer.observe(el));
    }

    /** 新插入的节点也要参与揭示动画。 */
    function revealNow(scope = document) {
        dom.$$('[data-reveal]', scope).forEach((el) => {
            const rect = el.getBoundingClientRect();
            if (rect.top < window.innerHeight) el.classList.add('is-visible');
        });
    }

    /* ---------------------------------------------------------- *
     * 导航：当前分区高亮 + 移动端菜单 + 登录态
     * ---------------------------------------------------------- */
    function initNav() {
        const links = dom.$$('.nav__links a[href^="#"]');
        const sections = dom.$$('main section[id]');
        if (sections.length) {
            const observer = new IntersectionObserver((entries) => {
                entries.forEach((entry) => {
                    if (!entry.isIntersecting) return;
                    links.forEach((link) => {
                        link.classList.toggle('is-active', link.getAttribute('href') === `#${entry.target.id}`);
                    });
                });
            }, { rootMargin: '-40% 0px -55% 0px' });
            sections.forEach((section) => observer.observe(section));
        }

        const toggle = dom.$('.nav__toggle');
        const menu = dom.$('.nav__links');
        toggle?.addEventListener('click', () => {
            const open = menu.classList.toggle('is-open');
            toggle.setAttribute('aria-expanded', String(open));
        });
        dom.$$('.nav__links a').forEach((link) =>
            link.addEventListener('click', () => {
                menu?.classList.remove('is-open');
                toggle?.setAttribute('aria-expanded', 'false');
            }),
        );

        // 登录态：显示"进入后台"或"登录"
        HamKit.session.load().then((state) => {
            const host = dom.$('[data-nav-auth]');
            if (!host) return;
            host.textContent = '';
            if (state.authenticated) {
                const user = state.user;
                host.appendChild(
                    dom.el('a', { class: 'user-chip', href: '/admin/', title: '进入管理后台' }, [
                        user.avatar
                            ? dom.el('img', { class: 'user-chip__avatar', src: user.avatar, alt: '', loading: 'lazy' })
                            : dom.el('span', { class: 'user-chip__avatar' }, [
                                  (user.displayName || user.username || '?').slice(0, 1).toUpperCase(),
                              ]),
                        dom.el('span', {}, [user.displayName || user.username]),
                        dom.el('span', { class: 'user-chip__role', text: fmt.role(user.role) }),
                    ]),
                );
            } else {
                host.appendChild(
                    dom.el('a', { class: 'nav__cta', href: HamKit.session.loginUrl('/admin/') }, ['登录']),
                );
            }
        });
    }

    /* ---------------------------------------------------------- *
     * 版本与下载
     * ---------------------------------------------------------- */

    const DOWNLOAD_ENDPOINT = '/api/public/download';

    function setBadge(text) {
        const badge = dom.$('#release-badge');
        if (badge) badge.textContent = text;
    }

    function renderDownloadButton(latest) {
        const btn = dom.$('#download-apk');
        if (!btn) return;
        if (!latest) {
            btn.href = 'https://github.com/fuxue-linkong/HamKit/releases/latest';
            btn.textContent = '前往 GitHub Releases';
            btn.removeAttribute('title');
            return;
        }
        // 走站内计数端点，再 302 到真实 APK 地址
        btn.href = latest.channel && latest.channel !== 'stable'
            ? `${DOWNLOAD_ENDPOINT}?channel=${encodeURIComponent(latest.channel)}`
            : DOWNLOAD_ENDPOINT;
        btn.textContent = `下载 APK · v${latest.versionName}`;
        btn.title = `Android ${latest.versionName}（versionCode ${latest.versionCode}）`;
    }

    function renderReleaseCard(latest, all) {
        const host = dom.$('#release-card');
        if (!host) return;
        host.textContent = '';
        if (!latest) {
            host.appendChild(
                dom.el('div', { class: 'empty' }, [
                    dom.el('div', { class: 'empty__icon' }, ['↓']),
                    dom.el('div', { class: 'empty__title' }, ['暂无可下载版本']),
                    dom.el('div', { class: 'empty__text' }, [
                        '版本信息由管理后台维护。你也可以先到 GitHub Releases 查看历史构建。',
                    ]),
                    dom.el('a', {
                        class: 'btn btn--ghost btn--sm u-mt',
                        href: 'https://github.com/fuxue-linkong/HamKit/releases',
                        target: '_blank',
                        rel: 'noopener',
                    }, ['GitHub Releases ↗']),
                ]),
            );
            return;
        }

        const meta = dom.el('div', { class: 'release-card__meta' }, [
            dom.el('span', { class: 'badge badge--accent' }, [`v${latest.versionName}`]),
            dom.el('span', { class: 'badge badge--muted u-mono' }, [`code ${latest.versionCode}`]),
            dom.el('span', { class: `badge ${latest.channel === 'stable' ? 'badge--success' : 'badge--warning'}` }, [
                fmt.channel(latest.channel),
            ]),
            latest.publishedAt ? dom.el('span', { class: 'badge badge--muted' }, [fmt.date(latest.publishedAt, { withTime: false })]) : null,
            latest.sizeBytes ? dom.el('span', { class: 'badge badge--muted' }, [fmt.bytes(latest.sizeBytes)]) : null,
        ]);

        const actions = dom.el('div', { class: 'release-card__actions' }, [
            dom.el('a', {
                class: 'btn btn--primary',
                href: latest.channel === 'stable' ? DOWNLOAD_ENDPOINT : `${DOWNLOAD_ENDPOINT}?channel=${latest.channel}`,
            }, ['下载 APK']),
        ]);
        if (latest.downloadUrl) {
            actions.appendChild(
                dom.el('a', {
                    class: 'btn btn--ghost',
                    href: latest.downloadUrl,
                    target: '_blank',
                    rel: 'noopener',
                }, ['原始地址 ↗']),
            );
        }
        if (latest.sha256) {
            actions.appendChild(
                dom.el('button', {
                    class: 'btn btn--subtle btn--sm',
                    type: 'button',
                    onclick: async (event) => {
                        const ok = await HamKit.copyText(latest.sha256);
                        toast[ok ? 'success' : 'error'](ok ? '已复制 SHA-256 校验值' : '复制失败，请手动选择');
                        void event;
                    },
                }, ['复制 SHA-256']),
            );
        }

        host.append(meta, actions);

        if (latest.changelog) {
            const changelog = dom.el('div', { class: 'release-card__changelog md' });
            md.renderInto(changelog, latest.changelog);
            host.appendChild(changelog);
        }

        // 历史版本
        const history = (all || []).filter((v) => v.versionCode !== latest.versionCode).slice(0, 6);
        if (history.length) {
            const details = dom.el('details', { class: 'release-card__history' });
            details.appendChild(dom.el('summary', {}, [`历史版本（${history.length}）`]));
            const list = dom.el('div', { class: 'release-card__history-list' });
            history.forEach((item) => {
                list.appendChild(
                    dom.el('a', {
                        class: 'release-card__history-item',
                        href: item.downloadUrl || (item.channel === 'stable' ? DOWNLOAD_ENDPOINT : `${DOWNLOAD_ENDPOINT}?channel=${item.channel}`),
                        target: item.downloadUrl ? '_blank' : null,
                        rel: item.downloadUrl ? 'noopener' : null,
                    }, [
                        dom.el('span', { class: 'u-mono' }, [`v${item.versionName}`]),
                        dom.el('span', { class: 'u-dim' }, [`code ${item.versionCode}`]),
                        dom.el('span', { class: 'u-dim' }, [fmt.channel(item.channel)]),
                        dom.el('span', { class: 'u-dim' }, [fmt.date(item.publishedAt, { withTime: false })]),
                    ]),
                );
            });
            details.appendChild(list);
            host.appendChild(details);
        }
    }

    async function loadVersions() {
        try {
            const data = await api.get('/api/public/versions');
            const latest = data.latest;
            const items = data.items || [];
            setBadge(latest ? `v${latest.versionName} · 现已上线` : '暂无发布版本');
            renderDownloadButton(latest);
            renderReleaseCard(latest, items);
        } catch {
            setBadge('查看 Releases');
            renderDownloadButton(null);
            renderReleaseCard(null, []);
        }
        revealNow();
    }

    /* ---------------------------------------------------------- *
     * 公告横幅
     * ---------------------------------------------------------- */
    async function loadAnnouncements() {
        const host = dom.$('#announce-host');
        if (!host) return;
        try {
            const data = await api.get('/api/public/announcements');
            const items = (data.items || []).slice(0, 3);
            if (!items.length) return;
            const list = dom.el('div', { class: 'announce' });
            items.forEach((item) => {
                const alert = dom.el('div', { class: `alert alert--${severityToType(item.severity)}` });
                alert.append(
                    dom.el('span', { class: 'alert__icon' }, [severityIcon(item.severity)]),
                    dom.el('div', { class: 'alert__body' }, [
                        dom.el('div', { class: 'alert__title' }, [
                            item.pinned ? '📌 ' : '',
                            item.title,
                        ]),
                        item.body ? dom.el('div', { class: 'md announce__body' }) : null,
                    ]),
                );
                if (item.body) md.renderInto(alert.querySelector('.announce__body'), item.body);
                if (item.link) {
                    alert.querySelector('.alert__body').appendChild(
                        dom.el('a', { class: 'announce__link', href: item.link }, ['查看详情 →']),
                    );
                }
                list.appendChild(alert);
            });
            host.appendChild(list);
            revealNow(host);
        } catch {
            /* 公告获取失败不影响页面 */
        }
    }

    function severityToType(severity) {
        return { info: 'info', success: 'success', warning: 'warning', critical: 'danger' }[severity] || 'info';
    }
    function severityIcon(severity) {
        return { info: 'ℹ', success: '✓', warning: '⚠', critical: '⛔' }[severity] || 'ℹ';
    }

    /* ---------------------------------------------------------- *
     * 首页内容（hero / 功能 / 下载说明 / 开发者 / 页脚）
     * ---------------------------------------------------------- */
    function applyHero(hero) {
        if (!hero) return;
        const set = (selector, value) => {
            const node = dom.$(selector);
            if (node && value) node.textContent = value;
        };
        set('#hero-subtitle', hero.subtitle);
        set('#hero-desc', hero.description);
        set('#hero-title', hero.title);
        const primary = dom.$('#hero-cta-primary');
        if (primary && hero.primaryCta) {
            primary.textContent = hero.primaryCta.label || primary.textContent;
            if (hero.primaryCta.href) primary.href = hero.primaryCta.href;
        }
        const secondary = dom.$('#hero-cta-secondary');
        if (secondary && hero.secondaryCta) {
            secondary.textContent = hero.secondaryCta.label || secondary.textContent;
            if (hero.secondaryCta.href) secondary.href = hero.secondaryCta.href;
        }
        if (hero.title) document.title = `${hero.title} · 业余无线电随身控制台`;
    }

    const FEATURE_ICONS = {
        positioning: '<circle cx="12" cy="12" r="3" fill="currentColor"/><ellipse cx="12" cy="12" rx="10" ry="4"/><ellipse cx="12" cy="12" rx="10" ry="4" transform="rotate(60 12 12)"/><ellipse cx="12" cy="12" rx="10" ry="4" transform="rotate(120 12 12)"/>',
        satellite: '<path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1"/><circle cx="12" cy="12" r="4" fill="currentColor"/>',
        'satellite-category': '<path d="M3 7h4l2 3 2-3h4M3 17h4l2-3 2 3h4"/><circle cx="18" cy="7" r="2.5"/><circle cx="18" cy="17" r="2.5"/>',
        cw: '<path d="M3 12h3M9 12h2M13 12h6M3 12l1.5-4M6 12l-1.5 4M11 12l1-3M13 12l-1 3M19 12l1.5-3M19 12l1.5 3"/>',
        sstv: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 15l4-4 3 3 4-5 4 6"/><circle cx="9" cy="9" r="1.3" fill="currentColor"/>',
        'sstv-tx': '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M12 16V8M8.5 11.5L12 8l3.5 3.5"/>',
        'hf-fax': '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 9h18M7 13h4M7 16h7"/>',
        qso: '<path d="M4 4h16v16H4z"/><path d="M8 9h8M8 13h8M8 17h4"/>',
        reminder: '<path d="M12 2a10 10 0 0 0-10 10 10 10 0 0 0 10 10 10 10 0 0 0 10-10 10 10 0 0 0-10-10z M12 6v6l4 2"/>',
        timecard: '<path d="M7 18a4 4 0 0 1 0-8 6 6 0 0 1 11.5 1.5A4.5 4.5 0 0 1 17 18H7z"/><path d="M8 14l1 2M12 13l1 2M16 14l1 2"/>',
        ft8: '<path d="M2 12h3l2-6 3 12 3-9 2 3h7"/>',
        aprs: '<path d="M12 2C8 2 5 5 5 9c0 5 7 13 7 13s7-8 7-13c0-4-3-7-7-7z"/><circle cx="12" cy="9" r="2.5" fill="currentColor"/><path d="M3 21h18"/>',
        default: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    };

    function featureIcon(id) {
        // 后台填的 id 可能用下划线或连字符，两种写法都认
        const key = String(id || '').trim().toLowerCase();
        return FEATURE_ICONS[key] || FEATURE_ICONS[key.replace(/_/g, '-')] || FEATURE_ICONS.default;
    }

    function renderFeatures(features) {
        const host = dom.$('#features-grid');
        if (!host || !features?.length) return;
        host.textContent = '';
        features.forEach((feature) => {
            const card = dom.el('article', { class: 'feature' });
            const icon = dom.el('div', { class: 'feature__icon' });
            icon.innerHTML =
                `<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.5">${featureIcon(feature.id)}</svg>`;
            card.appendChild(icon);
            card.appendChild(
                dom.el('h3', { class: 'feature__title' }, [
                    feature.title,
                    feature.badge ? dom.el('span', { class: 'feature__badge' }, [feature.badge]) : null,
                ]),
            );
            card.appendChild(dom.el('p', { class: 'feature__text' }, [feature.text]));
            if (feature.tags?.length) {
                const tags = dom.el('div', { class: 'feature__tags' });
                feature.tags.forEach((tag) => tags.appendChild(dom.el('span', {}, [tag])));
                card.appendChild(tags);
            }
            host.appendChild(card);
        });
        revealNow(host);
    }

    function applyDownload(section) {
        if (!section) return;
        const set = (selector, value) => {
            const node = dom.$(selector);
            if (node && value) node.textContent = value;
        };
        set('#download-title', section.title);
        set('#download-desc', section.description);
        const req = dom.$('#download-requirements');
        if (req && section.requirements?.length) {
            req.textContent = '';
            section.requirements.forEach((item) => req.appendChild(dom.el('li', {}, [item])));
        }
        const mirror = dom.$('#download-mirror');
        if (mirror) {
            if (section.showMirror !== false && section.mirrorUrl) {
                mirror.href = section.mirrorUrl;
                mirror.classList.remove('u-hidden');
            } else {
                mirror.classList.add('u-hidden');
            }
        }
        set('#download-min-android', section.minAndroid);
    }

    function renderDevs(devs) {
        const host = dom.$('#devs-grid');
        if (!host || !devs?.length) return;
        host.textContent = '';
        devs.forEach((dev) => {
            const card = dom.el('article', { class: 'dev-card' });
            const avatar = dom.el('div', { class: 'dev__avatar' });
            avatar.appendChild(
                dom.el('img', {
                    src: dev.avatar || (dev.github ? `https://github.com/${dev.github}.png` : '/images/avatar.jpg'),
                    alt: `${dev.name} 头像`,
                    loading: 'lazy',
                    onerror: function () {
                        this.style.display = 'none';
                    },
                }),
            );
            const links = dom.el('div', { class: 'dev-card__meta' });
            if (dev.email) links.appendChild(dom.el('a', { class: 'dev__link', href: `mailto:${dev.email}` }, ['Email']));
            if (dev.github) {
                links.appendChild(
                    dom.el('a', {
                        class: 'dev__link',
                        href: `https://github.com/${dev.github}`,
                        target: '_blank',
                        rel: 'noopener',
                    }, ['GitHub ↗']),
                );
            }
            card.append(
                avatar,
                dom.el('div', { class: 'dev-card__body' }, [
                    dom.el('div', { class: 'dev__name' }, [dev.name]),
                    links,
                ]),
            );
            host.appendChild(card);
        });
        revealNow(host);
    }

    function applyFooter(footer) {
        if (!footer) return;
        if (footer.tagline) {
            const node = dom.$('#footer-tagline');
            if (node) node.textContent = footer.tagline;
        }
        if (footer.copyright) {
            const node = dom.$('#footer-copyright');
            if (node) node.textContent = footer.copyright;
        }
        if (footer.signature) {
            const node = dom.$('#footer-signature');
            if (node) node.textContent = footer.signature;
        }
        const icp = dom.$('#footer-icp');
        if (icp) {
            if (footer.icp) {
                icp.textContent = footer.icp;
                icp.classList.remove('u-hidden');
            } else {
                icp.classList.add('u-hidden');
            }
        }
    }

    function applySeo(seo) {
        if (!seo) return;
        if (seo.title && !dom.$('#hero-title')?.textContent?.trim()) document.title = seo.title;
        if (seo.description) {
            const meta = dom.$('meta[name="description"]');
            if (meta) meta.setAttribute('content', seo.description);
        }
        if (seo.keywords?.length) {
            let meta = dom.$('meta[name="keywords"]');
            if (!meta) {
                meta = dom.el('meta', { name: 'keywords' });
                document.head.appendChild(meta);
            }
            const keywords = Array.isArray(seo.keywords) ? seo.keywords : String(seo.keywords).split(',');
            meta.setAttribute('content', keywords.join(','));
        }
    }

    async function loadContent() {
        try {
            const data = await api.get('/api/public/content');
            const content = data.content || {};
            applyHero(content.hero);
            renderFeatures(content.features);
            applyDownload(content.download);
            renderDevs(content.devs);
            applyFooter(content.footer);
            applySeo(content.seo);
        } catch {
            /* 内容接口失败时保留 HTML 里的静态默认内容 */
        }
    }

    /* ---------------------------------------------------------- *
     * 背景示波器波纹
     *
     * 结构：createWaveEngine（状态+采样） / drawFrame（渲染） /
     *       observe（尺寸与可见性监听）
     * 拖尾用 destination-out：画布初始全透明，若用半透明 fillRect
     * 做"擦除"，实际是逐帧叠加，alpha 会收敛到 255（整片糊死）；
     * destination-out 只把画布自身像素按比例变透明，可正常收敛，
     * 且不影响下层的 aurora 光晕 DOM。
     * ---------------------------------------------------------- */
    function initWave() {
        const canvas = dom.$('.aurora__wave');
        if (!canvas?.getContext) return;
        const ctx = canvas.getContext('2d');
        const prefersLight = () => window.matchMedia('(prefers-color-scheme: light)').matches;
        const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

        /* 所有可调参数收拢于此：
           alpha   波线不透明度；fade 每帧残影衰减比例（0.14 → 约 30 帧拖尾）
           mid     波形垂直中心（画布高度占比）
           cycles  波形周期数漂移范围；amp 振幅占比漂移范围
           step    采样点横向间距(px)；flow 每帧推进的采样点数
           glow    辉光半径(px) */
        const CONF = {
            colors: { dark: ['#4D8FFF'], light: ['#2D6BFF'] },
            alpha: 0.18, mid: 0.5,
            cyclesMin: -1.5, cyclesMax: 1.5, ampMin: 0.2, ampMax: 0.45,
            step: 2, flow: 2, glow: 8, lineWidth: 1.6,
            /* 方案 C 调整：保留清屏单线，但恢复荧光拖尾。
               fade 越小拖尾越长（0.14 → 约 30 帧；0.35 → 约 7 帧） */
            clearEachFrame: false,
            fade: 0.14,
        };

        /* 波形引擎：维护采样队列与相位，输出 y 坐标 */
        const createWaveEngine = () => {
            let samples = [];
            let phase = 0;
            let tau = 0;

            /* 原版噪声函数：多组正弦叠加产生 0~1 的平滑随机漂移量，
               驱动频率与振幅缓慢变化（视觉特征，保持不变） */
            const noise = (t, a, b, c, d) => Math.sin(t * a) * Math.cos(t * b + c) + Math.sin(t * d);
            const unit = (t, a, b, c, d) => (noise(t, a, b, c, d) + 2) / 4;

            return {
                get samples() { return samples; },
                push(count, nOverride) {
                    const n = nOverride || samples.length || 2;
                    for (let i = 0; i < count; i++) {
                        /* 方案 C：频率限制在低频区（约 0.15~0.55 周期/画布宽），
                           避免形成竖线梳；振幅缓慢呼吸 */
                        const raw = CONF.cyclesMin + unit(tau, 0.4, 0.17, 1.3, 0.09) * (CONF.cyclesMax - CONF.cyclesMin);
                        const cycles = 0.15 + Math.abs(raw) * 0.4 / 1.5;
                        const amp = CONF.ampMin + unit(tau, 0.35, 0.13, 0.7, 0.21) * (CONF.ampMax - CONF.ampMin);
                        phase += (2 * Math.PI * cycles) / n;
                        tau += 1 / 60;
                        samples.push(height * CONF.mid + height * amp * Math.sin(phase));
                    }
                },
                trim(maxLen) { if (samples.length > maxLen) samples = samples.slice(samples.length - maxLen); },
                /* 预热：按目标长度临时扩容 n，保证生成的整条波形
                   采样密度与稳态一致，不会因 n 太小产生高频锯齿 */
                reset(count) {
                    samples = [];
                    phase = 0;
                    this.push(count, count);
                },
            };
        };

        let width = 0;
        let height = 0;
        const engine = createWaveEngine();

        /* 按 DPR 缩放画布并重置采样队列 */
        const resize = () => {
            const dpr = Math.min(window.devicePixelRatio || 1, 2);
            const rect = canvas.getBoundingClientRect();
            width = rect.width || window.innerWidth;
            height = rect.height || window.innerHeight;
            canvas.width = Math.round(width * dpr);
            canvas.height = Math.round(height * dpr);
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
            engine.reset(Math.max(2, Math.ceil(width / CONF.step)));
        };

        /* 渲染一帧：方案 C 下每帧完全清屏只画当前线；
           否则用 destination-out 按比例衰减旧像素形成拖尾 */
        const drawFrame = () => {
            if (CONF.clearEachFrame) {
                ctx.clearRect(0, 0, width, height);
            } else {
                // 拖尾擦除：destination-out 按比例降低画布自身 alpha
                ctx.globalCompositeOperation = 'destination-out';
                ctx.fillStyle = `rgba(0,0,0,${CONF.fade})`;
                ctx.fillRect(0, 0, width, height);
                ctx.globalCompositeOperation = 'source-over';
            }

            engine.push(CONF.flow);
            engine.trim(Math.max(2, Math.ceil(width / CONF.step)));

            ctx.beginPath();
            engine.samples.forEach((y, i) => (i === 0 ? ctx.moveTo(0, y) : ctx.lineTo(i * CONF.step, y)));
            const color = CONF.colors[prefersLight() ? 'light' : 'dark'][0];
            ctx.strokeStyle = color;
            ctx.globalAlpha = CONF.alpha;
            ctx.lineWidth = CONF.lineWidth;
            ctx.shadowColor = color;
            ctx.shadowBlur = CONF.glow;
            ctx.stroke();
            ctx.globalAlpha = 1;
            ctx.shadowBlur = 0;
        };

        resize();
        drawFrame();

        if (reduced) return; // 用户偏好减弱动效：只画静态一帧

        /* 动画循环：页面不可见时暂停，避免后台空转与残影无限累积 */
        let running = false;
        let rafId = 0;
        const tick = () => {
            drawFrame();
            rafId = running ? requestAnimationFrame(tick) : 0;
        };
        const start = () => {
            if (running) return;
            running = true;
            rafId = requestAnimationFrame(tick);
        };
        const stop = () => {
            running = false;
            if (rafId) cancelAnimationFrame(rafId);
            rafId = 0;
        };

        document.addEventListener('visibilitychange', () => (document.hidden ? stop() : start()));
        start();

        /* ResizeObserver：容器尺寸或 DPR 变化时重建画布（比 window.resize
           更可靠，能感知布局变化导致的容器尺寸变化） */
        const ro = new ResizeObserver(() => {
            const dpr = Math.min(window.devicePixelRatio || 1, 2);
            const w = Math.round((canvas.getBoundingClientRect().width || window.innerWidth) * dpr);
            if (w !== canvas.width) resize();
        });
        ro.observe(canvas);
    }

    /* ---------------------------------------------------------- *
     * 版本徽标兜底：API 不可用时读静态 version.json
     * ---------------------------------------------------------- */
    async function fallbackBadge() {
        const badge = dom.$('#release-badge');
        if (!badge || badge.textContent !== '正在加载…') return;
        try {
            const res = await fetch('/version.json', { cache: 'no-store' });
            if (!res.ok) throw new Error('no version.json');
            const data = await res.json();
            if (data?.tag) badge.textContent = `${data.tag}${data.prerelease ? ' · 预发布' : ' · 现已上线'}`;
            else badge.textContent = '查看 Releases';
        } catch {
            badge.textContent = '查看 Releases';
        }
    }

    /* ---------------------------------------------------------- *
     * 启动
     * ---------------------------------------------------------- */
    function boot() {
        initReveal();
        initNav();
        initWave();
        loadContent();
        loadVersions().then(fallbackBadge);
        loadAnnouncements();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
})();
