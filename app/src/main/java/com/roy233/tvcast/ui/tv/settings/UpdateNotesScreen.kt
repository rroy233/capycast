package com.roy233.tvcast.ui.tv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.model.UiFocusAnchor
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvDimens
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.tv.FocusBinding
import com.roy233.tvcast.ui.tv.RestoreFocus
import com.roy233.tvcast.ui.tv.home.WhiteButton
import com.roy233.tvcast.ui.tv.rememberFocusTarget
import com.roy233.tvcast.ui.tv.tvFocusRegion

/** 显示可逐段聚焦翻阅的完整更新日志。 */
@Composable
internal fun UpdateNotesScreen(state: TvUiState, actions: TvActions) {
    val available = state.update.availableUpdate
    val notes = available?.releaseNotes.orEmpty()
    val blocks = remember(notes) { parseReleaseNotes(notes) }
    val firstFocus = rememberFocusTarget()
    val backFocus = rememberFocusTarget()
    val listState = rememberLazyListState()
    val firstFocusable = blocks.indexOfFirst { it.kind != ReleaseNoteKind.DIVIDER }
    RestoreFocus(
        request = state.focusRequest,
        listState = listState,
        bindings =
            listOf(
                FocusBinding(
                    anchor = UiFocusAnchor.SETTINGS_UPDATE_NOTES_INITIAL,
                    itemIndex = if (firstFocusable >= 0) firstFocusable + 1 else 0,
                    target = if (firstFocusable >= 0) firstFocus else backFocus,
                ),
            ),
        contentKey = firstFocusable,
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier =
                Modifier.width((maxWidth - 160.dp).coerceAtMost(TvDimens.pageMaxWidth))
                    .fillMaxHeight()
                    .align(Alignment.Center)
                    .padding(vertical = 52.dp)
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
                                style =
                                    TvType.caption.copy(
                                        color = TvColors.textSecondary,
                                        fontSize = 18.sp,
                                    ),
                            )
                        }
                    }
                    WhiteButton(
                        text = "返回",
                        onClick = actions.onNavigateSettingsBack,
                        focusTarget = backFocus,
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
                            focusTarget = if (index == firstFocusable) firstFocus else null,
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
}
