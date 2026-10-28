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

/** 显示可逐段聚焦翻阅的完整更新日志。 */
@Composable
internal fun UpdateNotesScreen(state: TvUiState, actions: TvActions) {
    val available = state.update.availableUpdate
    val notes = available?.releaseNotes.orEmpty()
    val blocks = remember(notes) { parseReleaseNotes(notes) }
    val firstFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val firstFocusable = blocks.indexOfFirst { it.kind != ReleaseNoteKind.DIVIDER }
    LaunchedEffect(firstFocusable) {
        runCatching {
            if (firstFocusable >= 0) firstFocus.requestFocus() else backFocus.requestFocus()
        }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 80.dp, vertical = 52.dp)
            .tvFocusRegion(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        text = "更新日志",
                        style = TvType.title.copy(fontSize = 48.sp),
                    )
                    available?.let {
                        BasicText(
                            text = "v${it.versionName}",
                            style = TvType.caption.copy(
                                color = TvColors.textSecondary,
                                fontSize = 18.sp,
                            ),
                        )
                    }
                }
                WhiteButton(
                    text = "返回",
                    onClick = actions.onCloseUpdateNotes,
                    focusRequester = backFocus,
                    compact = true,
                )
            }
            Spacer(Modifier.height(18.dp))
        }
        if (blocks.isEmpty()) {
            item {
                BasicText(
                    text = "此版本未提供更新说明。",
                    style = TvType.body.copy(color = TvColors.textSecondary),
                )
            }
        } else {
            blocks.forEachIndexed { index, block ->
                item(key = index) {
                    ReleaseNoteBlock(
                        block = block,
                        focusRequester = if (index == firstFocusable) firstFocus else null,
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(20.dp))
            BasicText(
                text = "按返回键回到更新页面",
                style = TvType.caption.copy(color = TvColors.textTertiary, fontSize = 15.sp),
            )
        }
    }
}
