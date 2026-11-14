package com.example.hamkit.ui.component.markdown

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Markdown 消毒测试（M-2，自 miuix 45cc65f3 移植）。 */
class MarkdownSanitizerTest {

    private val parser: Parser = Parser.builder().build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder().build()

    private fun render(markdown: String): String {
        val ast = parser.parse(markdown)
        MarkdownSanitizer.sanitize(ast)
        return renderer.render(ast)
    }

    @Test
    fun `strips script tags from raw html`() {
        val html = render("前文\n\n<script>alert(1)</script>\n\n后文")
        assertFalse(html.contains("<script"))
        assertTrue(html.contains("alert(1)")) // 降级为转义文本，内容可见但不可执行
    }

    @Test
    fun `strips inline html event handlers`() {
        val html = render("hello <img src=x onerror=alert(1)> world")
        // HTML 标签降级为转义纯文本：不再有可被浏览器解析的 <img 标签形态，
        // 字面量会以 &lt;img …&gt; 形式出现（onerror 仅作为无害文本可见）
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("<p>hello <img"))
    }

    @Test
    fun `blocks javascript scheme links`() {
        val html = render("[点我](javascript:alert(1))")
        assertFalse(html.contains("href=\"javascript:"))
        assertTrue(html.contains("<a")) // 链接仍在，但 destination 为空
    }

    @Test
    fun `blocks data scheme images`() {
        val html = render("![x](data:text/html;base64,PHNjcmlwdD4)")
        assertFalse(html.contains("src=\"data:text/html"))
    }

    @Test
    fun `allows http https mailto and relative links`() {
        val html = render(
            "[a](https://example.com) [b](http://example.com) " +
                "[c](mailto:a@b.c) [d](/relative) [e](#anchor)",
        )
        assertTrue(html.contains("https://example.com"))
        assertTrue(html.contains("http://example.com"))
        assertTrue(html.contains("mailto:a@b.c"))
        assertTrue(html.contains("/relative"))
        assertTrue(html.contains("#anchor"))
    }

    @Test
    fun `keeps normal markdown rendering`() {
        val html = render("# 标题\n\n**粗体** 正文")
        assertTrue(html.contains("<h1>"))
        assertTrue(html.contains("<strong>"))
    }
}
