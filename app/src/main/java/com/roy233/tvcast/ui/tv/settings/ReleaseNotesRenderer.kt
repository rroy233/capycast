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
import com.roy233.tvcast.feature.update.UpdateChannel
import com.roy233.tvcast.feature.update.UpdateStatus
import com.roy233.tvcast.feature.update.UpdateUiState
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.tv.home.WhiteButton
import com.roy233.tvcast.ui.tv.tvFocusRegion
import kotlinx.coroutines.launch

@Composable
internal fun ReleaseNoteBlock(block: ReleaseNote, focusRequester: FocusRequester?) {
    if (block.kind == ReleaseNoteKind.DIVIDER) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.16f)),
        )
        return
    }
    var focused by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val scrollStep = with(LocalDensity.current) { 48.dp.roundToPx() }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 96.dp)
            .verticalScroll(scrollState)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        if (!scrollState.canScrollForward) return@onPreviewKeyEvent false
                        coroutineScope.launch {
                            scrollState.scrollTo(
                                (scrollState.value + scrollStep).coerceAtMost(scrollState.maxValue),
                            )
                        }
                        true
                    }

                    Key.DirectionUp -> {
                        if (!scrollState.canScrollBackward) return@onPreviewKeyEvent false
                        coroutineScope.launch {
                            scrollState.scrollTo((scrollState.value - scrollStep).coerceAtLeast(0))
                        }
                        true
                    }

                    else -> false
                }
            }
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (block.kind) {
            ReleaseNoteKind.BULLET -> {
                BasicText("•", style = TvType.body.copy(color = TvColors.pink))
                BasicText(
                    text = block.text,
                    style = TvType.body.copy(color = TvColors.textSecondary),
                    modifier = Modifier.weight(1f),
                )
            }

            ReleaseNoteKind.ORDERED -> {
                BasicText(block.prefix, style = TvType.body.copy(color = TvColors.pink))
                BasicText(
                    text = block.text,
                    style = TvType.body.copy(color = TvColors.textSecondary),
                    modifier = Modifier.weight(1f),
                )
            }

            ReleaseNoteKind.CODE -> BasicText(
                text = block.text,
                style = TvType.caption.copy(
                    color = TvColors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .padding(10.dp),
            )

            ReleaseNoteKind.QUOTE -> BasicText(
                text = block.text,
                style = TvType.body.copy(
                    color = TvColors.textSecondary,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(10.dp),
            )

            ReleaseNoteKind.HEADING -> BasicText(
                text = block.text,
                style = TvType.body.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = if (block.level == 1) 25.sp else 21.sp,
                ),
            )

            ReleaseNoteKind.TEXT -> BasicText(
                text = block.text,
                style = TvType.body.copy(color = TvColors.textSecondary),
            )

            ReleaseNoteKind.DIVIDER -> Unit
        }
    }
}

/** 将 Markdown 行中的粗体和行内代码转换为带样式文本。 */
internal fun renderInlineMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < source.length) {
        when {
            source.startsWith("**", index) -> {
                val end = source.indexOf("**", index + 2)
                if (end > index + 2) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(source.substring(index + 2, end))
                    pop()
                    index = end + 2
                } else {
                    append(source[index++])
                }
            }

            source[index] == '`' -> {
                val end = source.indexOf('`', index + 1)
                if (end > index + 1) {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = TvColors.pink))
                    append(source.substring(index + 1, end))
                    pop()
                    index = end + 1
                } else {
                    append(source[index++])
                }
            }

            source[index] == '[' -> {
                val labelEnd = source.indexOf("](", index + 1)
                val urlEnd = if (labelEnd >= 0) source.indexOf(')', labelEnd + 2) else -1
                if (labelEnd > index + 1 && urlEnd > labelEnd + 2) {
                    pushStyle(
                        SpanStyle(
                            color = TvColors.pink,
                            textDecoration = TextDecoration.Underline,
                        )
                    )
                    append(source.substring(index + 1, labelEnd))
                    pop()
                    append(" (${source.substring(labelEnd + 2, urlEnd)})")
                    index = urlEnd + 1
                } else {
                    append(source[index++])
                }
            }

            else -> append(source[index++])
        }
    }
}

/** 返回更新状态对应的标题。 */
