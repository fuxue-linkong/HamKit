/* ============================================================
   极简 Markdown 渲染器（无依赖、防 XSS）
   支持：标题 / 粗斜体 / 行内代码 / 代码块 / 列表 / 引用 /
        链接 / 图片 / 分隔线 / 表格 / 段落
   用法：HamKit.md.render(text) -> HTML 字符串
   ============================================================ */

(function (global) {
    'use strict';

    /** HTML 转义：所有文本节点都必须先过这里，杜绝注入。 */
    function escapeHtml(text) {
        return String(text ?? '').replace(/[&<>"']/g, (c) => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            "'": '&#39;',
        }[c]));
    }

    /** 只放行安全协议，挡掉 javascript: / data: 等。 */
    function safeUrl(url) {
        const raw = String(url ?? '').trim();
        if (/^(https?:|mailto:|tel:|\/|#|\.\/|\.\.\/)/i.test(raw)) return raw;
        return '#';
    }

    /** 行内元素解析（在已转义的文本上操作）。 */
    function inline(text) {
        let out = escapeHtml(text);

        // 行内代码优先（内部不再解析其他语法）
        const codes = [];
        out = out.replace(/`([^`]+)`/g, (_m, code) => {
            codes.push(code);
            return `\u0000CODE${codes.length - 1}\u0000`;
        });

        // 图片 ![alt](src)
        out = out.replace(/!\[([^\]]*)\]\(([^)\s]+)(?:\s+&quot;([^&]*)&quot;)?\)/g, (_m, alt, src) => {
            return `<img src="${escapeHtml(safeUrl(src))}" alt="${alt}" loading="lazy">`;
        });

        // 链接 [text](href)
        out = out.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_m, label, href) => {
            const url = safeUrl(href);
            const external = /^https?:/i.test(url);
            return `<a href="${escapeHtml(url)}"${external ? ' target="_blank" rel="noopener noreferrer"' : ''}>${label}</a>`;
        });

        // 粗体 / 斜体 / 删除线
        out = out
            .replace(/\*\*\*([^*]+)\*\*\*/g, '<strong><em>$1</em></strong>')
            .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
            .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
            .replace(/~~([^~]+)~~/g, '<del>$1</del>');

        // 还原行内代码
        out = out.replace(/\u0000CODE(\d+)\u0000/g, (_m, i) => `<code>${codes[Number(i)]}</code>`);
        return out;
    }

    /** 主渲染函数。 */
    function render(markdown, options = {}) {
        const text = String(markdown ?? '').replace(/\r\n?/g, '\n');
        if (!text.trim()) return '';
        const lines = text.split('\n');
        const html = [];
        let i = 0;

        const flushParagraph = (buffer) => {
            if (!buffer.length) return;
            // 单个换行保留为 <br>，贴近用户期待
            html.push(`<p>${buffer.map((line) => inline(line)).join('<br>')}</p>`);
            buffer.length = 0;
        };
        const paragraph = [];

        while (i < lines.length) {
            const line = lines[i];

            // 代码块
            const fence = line.match(/^\s*(```|~~~)\s*([\w+-]*)\s*$/);
            if (fence) {
                flushParagraph(paragraph);
                const marker = fence[1];
                const lang = fence[2] || '';
                const body = [];
                i++;
                while (i < lines.length && !new RegExp(`^\\s*${marker}\\s*$`).test(lines[i])) {
                    body.push(lines[i]);
                    i++;
                }
                i++; // 跳过结束围栏
                html.push(
                    `<pre><code${lang ? ` class="language-${escapeHtml(lang)}"` : ''}>${escapeHtml(body.join('\n'))}</code></pre>`,
                );
                continue;
            }

            // 空行 = 段落分隔
            if (!line.trim()) {
                flushParagraph(paragraph);
                i++;
                continue;
            }

            // 分隔线
            if (/^\s*([-*_])\s*\1\s*\1[\s\-*_]*$/.test(line)) {
                flushParagraph(paragraph);
                html.push('<hr>');
                i++;
                continue;
            }

            // 标题（# 级别随选项可降级，便于嵌在卡片里）
            const heading = line.match(/^(#{1,6})\s+(.*)$/);
            if (heading) {
                flushParagraph(paragraph);
                const level = Math.min(6, heading[1].length + (options.headingOffset || 0));
                html.push(`<h${level}>${inline(heading[2].trim())}</h${level}>`);
                i++;
                continue;
            }

            // 引用（支持多行）
            if (/^\s*>\s?/.test(line)) {
                flushParagraph(paragraph);
                const quoted = [];
                while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
                    quoted.push(lines[i].replace(/^\s*>\s?/, ''));
                    i++;
                }
                html.push(`<blockquote>${render(quoted.join('\n'), options)}</blockquote>`);
                continue;
            }

            // 表格（表头 + 分隔行）
            if (/\|/.test(line) && i + 1 < lines.length && /^\s*\|?[\s:|-]+\|[\s:|-]*$/.test(lines[i + 1])) {
                flushParagraph(paragraph);
                const parseRow = (row) =>
                    row.replace(/^\s*\|/, '').replace(/\|\s*$/, '').split('|').map((c) => c.trim());
                const head = parseRow(line);
                i += 2;
                const body = [];
                while (i < lines.length && /\|/.test(lines[i]) && lines[i].trim()) {
                    body.push(parseRow(lines[i]));
                    i++;
                }
                html.push(
                    `<table><thead><tr>${head.map((c) => `<th>${inline(c)}</th>`).join('')}</tr></thead>` +
                    `<tbody>${body
                        .map((row) => `<tr>${row.map((c) => `<td>${inline(c)}</td>`).join('')}</tr>`)
                        .join('')}</tbody></table>`,
                );
                continue;
            }

            // 列表（有序 / 无序，支持一层缩进嵌套）
            if (/^\s*([-*+]|\d+\.)\s+/.test(line)) {
                flushParagraph(paragraph);
                const ordered = /^\s*\d+\.\s+/.test(line);
                const items = [];
                let current = null;
                while (i < lines.length && /^\s*([-*+]|\d+\.)\s+/.test(lines[i])) {
                    const content = lines[i].replace(/^\s*([-*+]|\d+\.)\s+/, '');
                    const indented = /^\s{2,}/.test(lines[i]) || /^\s*\t/.test(lines[i]);
                    const isNested = indented && current;
                    if (isNested) {
                        current.sub.push(content);
                    } else {
                        current = { text: content, sub: [] };
                        items.push(current);
                    }
                    i++;
                }
                const renderItems = (list) =>
                    list
                        .map((item) => {
                            const sub = item.sub.length
                                ? `<ul>${item.sub.map((s) => `<li>${inline(s)}</li>`).join('')}</ul>`
                                : '';
                            return `<li>${inline(item.text)}${sub}</li>`;
                        })
                        .join('');
                html.push(`<${ordered ? 'ol' : 'ul'}>${renderItems(items)}</${ordered ? 'ol' : 'ul'}>`);
                continue;
            }

            paragraph.push(line);
            i++;
        }

        flushParagraph(paragraph);
        return html.join('\n');
    }

    /** 渲染进指定元素。 */
    function renderInto(element, markdown, options) {
        if (!element) return;
        element.innerHTML = render(markdown, options);
        element.classList.add('md');
    }

    /** 去掉 Markdown 标记，用于摘要 / 分享描述。 */
    function toPlainText(markdown, maxLength = 160) {
        const text = String(markdown ?? '')
            .replace(/```[\s\S]*?```/g, ' ')
            .replace(/`([^`]+)`/g, '$1')
            .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
            .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
            .replace(/^\s*#{1,6}\s+/gm, '')
            .replace(/^\s*>\s?/gm, '')
            .replace(/^\s*([-*+]|\d+\.)\s+/gm, '')
            .replace(/[*_~]/g, '')
            .replace(/\s+/g, ' ')
            .trim();
        if (text.length <= maxLength) return text;
        return `${text.slice(0, maxLength - 1)}…`;
    }

    global.HamKit = global.HamKit || {};
    global.HamKit.md = { render, renderInto, toPlainText, escapeHtml, safeUrl };
})(window);
