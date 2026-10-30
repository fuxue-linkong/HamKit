package com.example.hamkit.ui.component.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownTextNode
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser


/** commonmark 的 Node 只有 firstChild/next，没有 Kotlin 的 children 列表。 */
private fun Node.childNodes(): List<Node> {
    val nodes = mutableListOf<Node>()
    var child = firstChild
    while (child != null) {
        nodes += child
        child = child.next
    }
    return nodes
}

/** Markdown 渲染配色（由调用方按 Miuix / Material 主题传入）。 */
data class MarkdownPalette(
    val text: Color,
    val secondary: Color,
    val primary: Color,
    val codeBackground: Color,
    val divider: Color,
)

/**
 * Compose 原生 Markdown 渲染（不依赖 WebView）。
 *
 * 聊天列表里一条回答一个实例：WebView 在 LazyColumn 中测量高度不可靠、重载成本高，
 * 因此这里用 commonmark 解析 + Compose 文本渲染，支持：
 * 标题、段落、粗体/斜体/删除线、行内代码、代码块、有序/无序列表、引用、分割线、链接。
 *
 * 表格暂降级为段落文本（P2 再做）。
 */
@Composable
fun MarkdownText(
    markdown: String,
    colors: MarkdownPalette,
    modifier: Modifier = Modifier,
) {
    val parser = remember {
        Parser.builder()
            .extensions(
                listOf(
                    TablesExtension.create(),
                    StrikethroughExtension.builder().requireTwoTildes(true).build(),
                    AutolinkExtension.create(),
                    TaskListItemsExtension.create(),
                )
            )
            .build()
    }
    val document = remember(markdown, parser) { parser.parse(markdown) }
    Column(modifier) {
        document.childNodes().forEach { child ->
            MarkdownBlock(child, colors)
        }
    }
}

@Composable
private fun MarkdownBlock(node: Node, colors: MarkdownPalette) {
    when (node) {
        is Heading -> {
            val size = when (node.level) {
                1 -> 20.sp
                2 -> 18.sp
                3 -> 16.sp
                else -> 14.sp
            }
            MarkdownInlineText(
                node = node,
                colors = colors,
                fontSize = size,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
        }

        is Paragraph -> MarkdownInlineText(
            node = node,
            colors = colors,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        is FencedCodeBlock -> CodeBlock(node.literal, colors)
        is IndentedCodeBlock -> CodeBlock(node.literal, colors)

        is BulletList -> MarkdownList(node, colors, ordered = false, start = 1)
        is OrderedList -> MarkdownList(node, colors, ordered = true, start = node.startNumber)

        is BlockQuote -> {
            Row(
                Modifier
                    .padding(vertical = 4.dp)
                    .height(IntrinsicSize.Min),
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(colors.primary, RoundedCornerShape(2.dp)),
                )
                Column(Modifier.padding(start = 10.dp)) {
                    node.childNodes().forEach { child -> MarkdownBlock(child, colors) }
                }
            }
        }

        is ThematicBreak -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .height(1.dp)
                .background(colors.divider),
        )

        // 表格等暂不支持：把子节点按普通块渲染，至少不丢文本
        is TableBlock -> {
            Column {
                node.childNodes().forEach { child -> MarkdownBlock(child, colors) }
            }
        }

        else -> node.childNodes().forEach { child -> MarkdownBlock(child, colors) }
    }
}

@Composable
private fun MarkdownInlineText(
    node: Node,
    colors: MarkdownPalette,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    fontWeight: FontWeight? = null,
) {
    val annotated = remember(node, colors) { buildMarkdownInline(node, colors) }
    Text(
        text = annotated,
        modifier = modifier.fillMaxWidth(),
        color = colors.text,
        fontSize = fontSize,
        lineHeight = (fontSize.value * 1.55f).sp,
        fontWeight = fontWeight,
    )
}

@Composable
private fun MarkdownList(
    node: Node,
    colors: MarkdownPalette,
    ordered: Boolean,
    start: Int,
) {
    Column(Modifier.padding(vertical = 2.dp)) {
        var index = start
        node.childNodes().filterIsInstance<ListItem>().forEach { item ->
            Row {
                Text(
                    text = if (ordered) "${index++}." else "•",
                    color = colors.secondary,
                    fontSize = 14.sp,
                    modifier = Modifier.width(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    item.childNodes().forEach { child -> MarkdownBlock(child, colors) }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, colors: MarkdownPalette) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(colors.codeBackground, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        Text(
            text = code.trimEnd(),
            color = colors.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

/**
 * 把 inline 节点（文本/粗体/斜体/代码/链接/删除线）拼成 AnnotatedString。
 * 纯函数，便于单测直接检查文本与样式。
 */
internal fun buildMarkdownInline(node: Node, colors: MarkdownPalette): AnnotatedString =
    buildAnnotatedString {
        fun visit(current: Node) {
            when (current) {
                is MarkdownTextNode -> append(current.literal)
                is Emphasis -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    current.childNodes().forEach(::visit)
                    pop()
                }

                is StrongEmphasis -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    current.childNodes().forEach(::visit)
                    pop()
                }

                is Strikethrough -> {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    current.childNodes().forEach(::visit)
                    pop()
                }

                is Code -> {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = colors.codeBackground,
                        )
                    )
                    append(current.literal)
                    pop()
                }

                is Link -> {
                    val pushed = runCatching {
                        pushLink(
                            LinkAnnotation.Url(
                                url = current.destination,
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = colors.primary,
                                        textDecoration = TextDecoration.Underline,
                                    )
                                ),
                            )
                        )
                    }.isSuccess
                    current.childNodes().forEach(::visit)
                    if (pushed) runCatching { pop() }
                }

                is SoftLineBreak -> append(' ')
                is HardLineBreak -> append('\n')
                else -> current.childNodes().forEach(::visit)
            }
        }
        node.childNodes().forEach(::visit)
    }
