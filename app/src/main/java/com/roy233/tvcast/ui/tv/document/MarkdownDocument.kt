package com.roy233.tvcast.ui.tv.document

import androidx.compose.ui.text.AnnotatedString

/** 电视端长文档的结构块类型。 */
internal enum class MarkdownBlockKind {
    HEADING,
    BULLET,
    ORDERED,
    QUOTE,
    CODE,
    DIVIDER,
    TEXT,
}

/** 可由电视端文档阅读器逐段显示与聚焦的一段 Markdown 内容。 */
internal data class MarkdownBlock(
    val kind: MarkdownBlockKind,
    val text: AnnotatedString = AnnotatedString(""),
    val prefix: String = "",
    val level: Int = 0,
)

/** 将 Markdown 解析为适合电视端逐段翻阅的结构块。 */
internal fun parseMarkdownDocument(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val code = mutableListOf<String>()
    var inCode = false
    markdown.lineSequence().forEach { raw ->
        val line = raw.trimEnd()
        when {
            line.startsWith("```") -> {
                if (inCode) {
                    blocks +=
                        MarkdownBlock(
                            MarkdownBlockKind.CODE,
                            AnnotatedString(code.joinToString("\n")),
                        )
                    code.clear()
                }
                inCode = !inCode
            }

            inCode -> code += line
            line.isBlank() -> Unit
            line == "---" || line == "***" || line == "___" ->
                blocks += MarkdownBlock(MarkdownBlockKind.DIVIDER)

            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length
                blocks +=
                    MarkdownBlock(
                        kind = MarkdownBlockKind.HEADING,
                        text = renderInlineMarkdown(line.drop(level).trim()),
                        level = level,
                    )
            }

            line.startsWith("- ") || line.startsWith("* ") ->
                blocks +=
                    MarkdownBlock(
                        kind = MarkdownBlockKind.BULLET,
                        text = renderInlineMarkdown(line.drop(2)),
                    )

            ORDERED_LIST_PATTERN.containsMatchIn(line) -> {
                val match = requireNotNull(ORDERED_LIST_PATTERN.find(line))
                blocks +=
                    MarkdownBlock(
                        kind = MarkdownBlockKind.ORDERED,
                        text = renderInlineMarkdown(line.drop(match.value.length)),
                        prefix = match.value.trim(),
                    )
            }

            line.startsWith("> ") ->
                blocks +=
                    MarkdownBlock(
                        kind = MarkdownBlockKind.QUOTE,
                        text = renderInlineMarkdown(line.drop(2)),
                    )

            else ->
                blocks +=
                    MarkdownBlock(
                        kind = MarkdownBlockKind.TEXT,
                        text = renderInlineMarkdown(line),
                    )
        }
    }
    if (code.isNotEmpty()) {
        blocks += MarkdownBlock(MarkdownBlockKind.CODE, AnnotatedString(code.joinToString("\n")))
    }
    return blocks
}

/** 提取 Markdown 首行作为列表入口摘要。 */
internal fun markdownDocumentPreview(markdown: String): String =
    markdown
        .lineSequence()
        .map { line -> line.trim().trimStart('#', '*', '-', '>', ' ') }
        .firstOrNull { it.isNotBlank() && !it.startsWith("```") }
        .orEmpty()
        .take(90)

/** 有序列表项的 Markdown 前缀。 */
private val ORDERED_LIST_PATTERN = Regex("^\\d+\\.\\s+")
