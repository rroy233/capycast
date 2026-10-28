package com.roy233.tvcast.ui.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roy233.tvcast.feature.update.model.UpdateChannel
import com.roy233.tvcast.feature.update.UpdateStatus
import com.roy233.tvcast.feature.update.UpdateUiState
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.tv.home.WhiteButton
import com.roy233.tvcast.ui.tv.tvFocusRegion
import kotlinx.coroutines.launch

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
