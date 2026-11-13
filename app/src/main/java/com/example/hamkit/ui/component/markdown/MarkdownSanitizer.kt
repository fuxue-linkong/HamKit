package com.example.hamkit.ui.component.markdown

import org.commonmark.node.BlockQuote
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text

/**
 * Markdown/HTML 渲染前的节点级消毒（2026-11 安全审计新增，对应 M-2）。
 *
 * 背景：commonmark 的 [HtmlRenderer] 会**原样输出**文档里的原始 HTML
 * （HtmlBlock / HtmlInline），而本组件的 WebView 开着 JavaScript；
 * AI 模型输出属于不可信输入，一次提示注入就可能让 `<script>`、`<img onerror=…>`
 * 落进 WebView 执行。消毒在解析后的 AST 上做，而不是渲染后的字符串，
 * 避免和 HTML 转义时序互相踩。
 *
 * 规则：
 *  1. HtmlBlock / HtmlInline：内容降级为转义纯文本（保留可见性，去掉可执行性）；
 *  2. Link / Image（Image 继承自 Link）的 destination：scheme 白名单
 *     （http / https / mailto / 相对路径 / 锚点），`javascript:`、`data:` 等
 *     一律置空（链接仍在但点击无动作，图片仍走 alt 占位）。
 */
object MarkdownSanitizer {

    /** 允许的 URL scheme；空串表示相对路径/锚点。 */
    private val ALLOWED_SCHEMES = setOf("http", "https", "mailto")

    /** 对外入口：解析后、渲染前调用。返回处理后的根节点（原地修改）。 */
    fun sanitize(root: Node): Node {
        var node: Node? = root.firstChild
        while (node != null) {
            val current = node
            node = current.next
            sanitizeNode(current)
        }
        return root
    }

    private fun sanitizeNode(node: Node) {
        when (node) {
            is HtmlBlock -> demoteToText(node, node.literal)
            is HtmlInline -> demoteToText(node, node.literal)
            is Link -> {
                node.destination = safeDestination(node.destination)
                sanitizeChildren(node)
            }
            else -> sanitizeChildren(node)
        }
    }

    private fun sanitizeChildren(node: Node) {
        var child: Node? = node.firstChild
        while (child != null) {
            val current = child
            child = current.next
            sanitizeNode(current)
        }
    }

    /** 把原始 HTML 节点替换为纯文本节点（内容原样保留，渲染时会被 HTML 转义）。 */
    private fun demoteToText(node: Node, literal: String) {
        val text = Text(literal)
        node.insertBefore(text)
        node.unlink()
    }

    /** scheme 白名单校验；不允许的 destination 一律置空。 */
    private fun safeDestination(destination: String): String {
        val trimmed = destination.trim()
        if (trimmed.isEmpty()) return trimmed
        // 无 scheme（相对路径 / 锚点 / 查询串）：直接放行
        val schemeEnd = trimmed.indexOf(':')
        if (schemeEnd < 0) return trimmed
        val scheme = trimmed.take(schemeEnd).lowercase()
        // scheme 里只该出现字母数字 + - . ；含 '/' 或非法字符说明是路径冒号，放行
        if (!scheme.matches(Regex("[a-z][a-z0-9+.-]*"))) return trimmed
        return if (scheme in ALLOWED_SCHEMES) trimmed else ""
    }
}
