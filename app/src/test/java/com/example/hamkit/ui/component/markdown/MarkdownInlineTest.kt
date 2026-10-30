package com.example.hamkit.ui.component.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Markdown inline 渲染的纯函数测试（不依赖 Compose UI）。 */
class MarkdownInlineTest {

    private val palette = MarkdownPalette(
        text = Color.Black,
        secondary = Color.Gray,
        primary = Color.Blue,
        codeBackground = Color.LightGray,
        divider = Color.Gray,
    )

    private fun inline(markdown: String): androidx.compose.ui.text.AnnotatedString {
        val parser = Parser.builder().build()
        val document = parser.parse(markdown)
        val paragraph = firstBlock(document) as Paragraph
        return buildMarkdownInline(paragraph, palette)
    }

    private fun firstBlock(node: Node): Node {
        var child = node.firstChild
            ?: error("no child")
        while (child.next != null && child !is Paragraph) {
            child = child.next
        }
        return child
    }

    @Test
    fun `plain text`() {
        assertEquals("hello", inline("hello").text)
    }

    @Test
    fun `bold and italic styles`() {
        val annotated = inline("**bold** and *italic*")
        assertEquals("bold and italic", annotated.text)
        assertTrue(annotated.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(annotated.spanStyles.any { it.item.fontStyle != null })
    }

    @Test
    fun `inline code uses monospace`() {
        val annotated = inline("run `adb logcat` now")
        assertEquals("run adb logcat now", annotated.text)
        assertTrue(annotated.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `link keeps text and url annotation`() {
        val annotated = inline("see [HamKit](https://hamkit.click)")
        assertEquals("see HamKit", annotated.text)
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isNotEmpty())
    }

    @Test
    fun `hard line break is preserved`() {
        // Markdown 中行尾两个空格才是 hard break；单个换行是 soft break（渲染为空格）
        val annotated = inline("line1  \nline2")
        assertEquals("line1\nline2", annotated.text)
    }
}
