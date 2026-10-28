package com.roy233.tvcast.ui.tv.settings

import androidx.compose.ui.text.AnnotatedString

/** 更新日志的块类型。 */
internal enum class ReleaseNoteKind { HEADING, BULLET, ORDERED, QUOTE, CODE, DIVIDER, TEXT }

/** 更新日志中的一段可聚焦内容。 */
internal data class ReleaseNote(
    val kind: ReleaseNoteKind,
    val text: AnnotatedString = AnnotatedString(""),
    val prefix: String = "",
    val level: Int = 0,
)

/** 将更新日志 Markdown 解析为可逐段聚焦的块。 */
internal fun parseReleaseNotes(markdown: String): List<ReleaseNote> {
    val blocks = mutableListOf<ReleaseNote>()
    val code = mutableListOf<String>()
    var inCode = false
    markdown.lineSequence().forEach { raw ->
        val line = raw.trimEnd()
        when {
            line.startsWith("```") -> {
                if (inCode) {
                    blocks += ReleaseNote(ReleaseNoteKind.CODE, AnnotatedString(code.joinToString("\n")))
                    code.clear()
                }
                inCode = !inCode
            }

            inCode -> code += line
            line.isBlank() -> Unit
            line == "---" || line == "***" || line == "___" ->
                blocks += ReleaseNote(ReleaseNoteKind.DIVIDER)

            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length
                blocks += ReleaseNote(
                    kind = ReleaseNoteKind.HEADING,
                    text = renderInlineMarkdown(line.drop(level).trim()),
                    level = level,
                )
            }

            line.startsWith("- ") || line.startsWith("* ") -> blocks += ReleaseNote(
                kind = ReleaseNoteKind.BULLET,
                text = renderInlineMarkdown(line.drop(2)),
            )

            ORDERED_LIST_PATTERN.containsMatchIn(line) -> {
                val match = requireNotNull(ORDERED_LIST_PATTERN.find(line))
                blocks += ReleaseNote(
                    kind = ReleaseNoteKind.ORDERED,
                    text = renderInlineMarkdown(line.drop(match.value.length)),
                    prefix = match.value.trim(),
                )
            }

            line.startsWith("> ") -> blocks += ReleaseNote(
                kind = ReleaseNoteKind.QUOTE,
                text = renderInlineMarkdown(line.drop(2)),
            )

            else -> blocks += ReleaseNote(
                kind = ReleaseNoteKind.TEXT,
                text = renderInlineMarkdown(line),
            )
        }
    }
    if (code.isNotEmpty()) {
        blocks += ReleaseNote(ReleaseNoteKind.CODE, AnnotatedString(code.joinToString("\n")))
    }
    return blocks
}


/** 有序列表项的 Markdown 前缀。 */
private val ORDERED_LIST_PATTERN = Regex("^\\d+\\.\\s+")

/** 提取更新日志首行作为入口摘要。 */
internal fun releaseNotesPreview(notes: String): String = notes.lineSequence()
    .map { line -> line.trim().trimStart('#', '*', '-', '>', ' ') }
    .firstOrNull { it.isNotBlank() && !it.startsWith("```") }
    .orEmpty()
    .take(90)

/** 显示带标题的更新设置分组。 */
