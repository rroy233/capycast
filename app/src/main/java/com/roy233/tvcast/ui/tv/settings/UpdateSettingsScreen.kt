package com.roy233.tvcast.ui.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roy233.tvcast.R
import com.roy233.tvcast.feature.update.UpdateStatus
import com.roy233.tvcast.feature.update.UpdateUiState
import com.roy233.tvcast.feature.update.model.UpdateChannel
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.model.UiFocusAnchor
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvDimens
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.tv.FocusBinding
import com.roy233.tvcast.ui.tv.FocusTarget
import com.roy233.tvcast.ui.tv.RestoreFocus
import com.roy233.tvcast.ui.tv.document.markdownDocumentPreview
import com.roy233.tvcast.ui.tv.focusTarget
import com.roy233.tvcast.ui.tv.focusTrace
import com.roy233.tvcast.ui.tv.home.WhiteButton
import com.roy233.tvcast.ui.tv.rememberFocusTarget
import com.roy233.tvcast.ui.tv.tvFocusRegion

/**
 * 显示更新通道、检查开关、发布说明和下载安装操作。
 *
 * @param state 当前电视界面状态。
 * @param actions 更新页面可执行的操作回调。
 */
@Composable
internal fun UpdateSettingsScreen(state: TvUiState, actions: TvActions) {
    val update = state.update
    val releaseNotes = releaseNotesText(update.availableUpdate?.releaseNotes.orEmpty())
    val backFocus = rememberFocusTarget()
    val releaseNotesFocus = rememberFocusTarget()
    val primaryActionFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val focusBindings = buildList {
        add(
            FocusBinding(
                anchor = UiFocusAnchor.SETTINGS_UPDATE_BACK,
                itemIndex = UPDATE_HEADER_ITEM_INDEX,
                target = backFocus,
            ),
        )
        if (releaseNotes.isNotBlank()) {
            add(
                FocusBinding(
                    anchor = UiFocusAnchor.SETTINGS_UPDATE_RELEASE_NOTES,
                    itemIndex = UPDATE_RELEASE_NOTES_ITEM_INDEX,
                    target = releaseNotesFocus,
                ),
            )
        }
    }
    RestoreFocus(
        request = state.focusRequest,
        listState = listState,
        bindings = focusBindings,
        // 更新日志入口只在有说明时存在，条目数量变化后需要重新取回焦点。
        contentKey = focusBindings.size,
    )
    val progress =
        if (update.totalBytes > 0L) {
            (update.downloadedBytes.toFloat() / update.totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    val primaryActionText =
        when (update.status) {
            UpdateStatus.CHECKING -> stringResource(R.string.update_checking)
            UpdateStatus.AVAILABLE -> stringResource(R.string.update_action_download)
            UpdateStatus.DOWNLOADING ->
                stringResource(R.string.update_downloading_progress, (progress * 100).toInt())
            UpdateStatus.VERIFYING -> stringResource(R.string.update_verifying)
            UpdateStatus.READY_TO_INSTALL -> stringResource(R.string.update_action_install)
            UpdateStatus.FAILED ->
                when {
                    update.availableUpdate == null ->
                        stringResource(R.string.update_action_retry_check)
                    update.downloadedApkPath != null ->
                        stringResource(R.string.update_action_install)
                    else -> stringResource(R.string.update_action_redownload)
                }
            UpdateStatus.IDLE,
            UpdateStatus.UP_TO_DATE -> stringResource(R.string.settings_check_update)
        }
    val primaryAction: () -> Unit =
        when (update.status) {
            UpdateStatus.AVAILABLE -> actions.onDownloadUpdate
            UpdateStatus.READY_TO_INSTALL -> actions.onInstallUpdate
            UpdateStatus.FAILED ->
                when {
                    update.availableUpdate == null -> actions.onCheckForUpdates
                    update.downloadedApkPath != null -> actions.onInstallUpdate
                    else -> actions.onDownloadUpdate
                }
            UpdateStatus.DOWNLOADING -> actions.onCancelUpdateDownload
            UpdateStatus.VERIFYING,
            UpdateStatus.CHECKING -> ({})
            UpdateStatus.IDLE,
            UpdateStatus.UP_TO_DATE -> actions.onCheckForUpdates
        }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier =
                Modifier.width((maxWidth - 160.dp).coerceAtMost(TvDimens.pageMaxWidth))
                    .fillMaxHeight()
                    .align(Alignment.Center)
                    .padding(vertical = 52.dp)
                    .tvFocusRegion(),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SystemUpdate,
                            contentDescription = null,
                            tint = TvColors.accent,
                        )
                        Spacer(Modifier.width(14.dp))
                        BasicText(
                            text = stringResource(R.string.update_title),
                            style = TvType.title.copy(fontSize = 48.sp),
                        )
                    }
                    WhiteButton(
                        text = stringResource(R.string.common_back),
                        onClick = actions.onNavigateSettingsBack,
                        focusTarget = backFocus,
                        compact = true,
                    )
                }
                Spacer(Modifier.height(26.dp))
            }

            item {
                UpdateStatusCard(update, state.version, releaseNotes)
                Spacer(Modifier.height(22.dp))
            }

            if (releaseNotes.isNotBlank()) {
                item {
                    ReleaseNotesEntry(
                        notes = releaseNotes,
                        onClick = actions.onOpenUpdateNotes,
                        focusTarget = releaseNotesFocus,
                    )
                    Spacer(Modifier.height(14.dp))
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().tvFocusRegion(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    BasicText(
                        text = stringResource(R.string.update_current_version, state.version),
                        style =
                            TvType.caption.copy(color = TvColors.textSecondary, fontSize = 18.sp),
                        modifier = Modifier.weight(1f),
                    )
                    WhiteButton(
                        text = primaryActionText,
                        onClick = primaryAction,
                        focusRequester = primaryActionFocus,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            item {
                UpdateChoiceGroup(stringResource(R.string.update_channel)) {
                    UpdateOptionTile(
                        title = stringResource(R.string.update_channel_release),
                        description = stringResource(R.string.update_channel_release_desc),
                        selected = update.channel == UpdateChannel.STABLE,
                        onClick = { actions.onSelectUpdateChannel(UpdateChannel.STABLE) },
                        modifier = Modifier.weight(1f),
                    )
                    UpdateOptionTile(
                        title = stringResource(R.string.update_channel_beta),
                        description = stringResource(R.string.update_channel_beta_desc),
                        selected = update.channel == UpdateChannel.BETA,
                        onClick = { actions.onSelectUpdateChannel(UpdateChannel.BETA) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            item {
                SettingsSection(stringResource(R.string.update_auto_check)) {
                    SettingsToggleRow(
                        title = stringResource(R.string.update_auto_check_desc),
                        checked = update.automaticCheck,
                        onCheckedChange = actions.onSetAutoUpdateCheck,
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

/** 显示一项更新设置及其并排的可选值。 */
@Composable
private fun UpdateChoiceGroup(
    title: String,
    content: @Composable RowScope.() -> Unit,
) {
    BasicText(
        text = title,
        style =
            TvType.caption.copy(
                color = TvColors.textTertiary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
            ),
        modifier = Modifier.padding(bottom = 7.dp, start = 2.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth().tvFocusRegion(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** 显示单个可直接选中的更新偏好选项。 */
@Composable
private fun UpdateOptionTile(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused = interaction.collectIsFocusedAsState().value
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier =
            modifier
                .height(96.dp)
                .clip(shape)
                .background(
                    when {
                        focused && selected -> TvColors.accent.copy(alpha = 0.30f)
                        focused -> Color.White.copy(alpha = 0.16f)
                        selected -> TvColors.accent.copy(alpha = 0.16f)
                        else -> Color.White.copy(alpha = 0.07f)
                    },
                )
                .border(
                    width = 2.dp,
                    color =
                        when {
                            focused -> TvColors.accent
                            selected -> TvColors.accent.copy(alpha = 0.72f)
                            else -> Color.White.copy(alpha = 0.08f)
                        },
                    shape = shape,
                )
                .focusTrace("tile:$title")
                .selectable(
                    selected = selected,
                    interactionSource = interaction,
                    indication = null,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = title,
                style = TvType.body.copy(fontSize = 23.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = TvColors.accent,
                )
            }
        }
        BasicText(
            text = description,
            style = TvType.caption.copy(color = TvColors.textSecondary, fontSize = 15.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 在统一信息卡中呈现更新阶段、版本摘要、更新日志和下载进度。 */
@Composable
private fun UpdateStatusCard(update: UpdateUiState, currentVersion: String, releaseNotes: String) {
    val interaction = remember { MutableInteractionSource() }
    val focused = interaction.collectIsFocusedAsState().value
    val accent = updateAccent(update.status)
    val fraction =
        if (update.totalBytes > 0L) {
            (update.downloadedBytes.toFloat() / update.totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(max = 240.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(accent.copy(alpha = if (focused) 0.17f else 0.10f))
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) TvColors.accent else accent.copy(alpha = 0.38f),
                    shape = RoundedCornerShape(18.dp),
                )
                .focusable(interactionSource = interaction)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.width(9.dp).height(9.dp).clip(RoundedCornerShape(50)).background(accent),
            )
            BasicText(
                text = updateStatusTitle(update.status),
                style = TvType.body.copy(color = accent, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            if (update.status == UpdateStatus.DOWNLOADING) {
                BasicText(
                    text = "${(fraction * 100).toInt()}%",
                    style =
                        TvType.caption.copy(
                            color = accent,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                )
            }
        }

        update.availableUpdate?.let { available ->
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BasicText(
                    text = "v${available.versionName}",
                    style = TvType.title.copy(color = TvColors.textPrimary, fontSize = 34.sp),
                )
                BasicText(
                    text =
                        "${formatBytes(available.sizeBytes)} · ${stringResource(R.string.update_current_version, currentVersion)}",
                    style = TvType.caption.copy(color = TvColors.textTertiary, fontSize = 16.sp),
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
            BasicText(
                text =
                    when (update.status) {
                        UpdateStatus.DOWNLOADING -> stringResource(R.string.update_getting_package)
                        UpdateStatus.VERIFYING -> stringResource(R.string.update_verifying_package)
                        UpdateStatus.READY_TO_INSTALL -> stringResource(R.string.update_verified)
                        UpdateStatus.FAILED ->
                            update.message ?: stringResource(R.string.update_retry_message)
                        else -> stringResource(R.string.update_ready_message)
                    },
                style = TvType.caption.copy(color = TvColors.textSecondary, fontSize = 16.sp),
            )
            if (releaseNotes.isBlank()) {
                BasicText(
                    text = stringResource(R.string.update_notes_empty),
                    style = TvType.body.copy(color = TvColors.textSecondary, fontSize = 17.sp),
                )
            }
        }
            ?: update.message?.let { message ->
                BasicText(
                    text = message,
                    style = TvType.caption.copy(color = TvColors.textSecondary, fontSize = 17.sp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

        if (update.status == UpdateStatus.DOWNLOADING) {
            LinearProgressIndicator(
                progress = fraction,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
                color = accent,
                backgroundColor = Color.White.copy(alpha = 0.16f),
            )
            BasicText(
                text =
                    "${formatBytes(update.downloadedBytes)} / ${formatBytes(update.totalBytes)} · ${(fraction * 100).toInt()}%",
                style = TvType.caption.copy(color = TvColors.textSecondary, fontSize = 16.sp),
            )
        }
    }
}

/** 显示可聚焦的更新日志入口。 */
@Composable
private fun ReleaseNotesEntry(
    notes: String,
    onClick: () -> Unit,
    focusTarget: FocusTarget,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused = interaction.collectIsFocusedAsState().value
    val shape = RoundedCornerShape(16.dp)
    val preview = markdownDocumentPreview(notes)
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .focusTarget(focusTarget)
                .clip(shape)
                .background(
                    if (focused) Color.White.copy(alpha = 0.14f)
                    else Color.White.copy(alpha = 0.06f),
                )
                .border(
                    width = 2.dp,
                    color = if (focused) TvColors.accent else Color.White.copy(alpha = 0.08f),
                    shape = shape,
                )
                .focusTrace("row:更新日志")
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Description,
            contentDescription = null,
            tint = if (focused) TvColors.accent else TvColors.textSecondary,
            modifier = Modifier.size(24.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BasicText(
                text = stringResource(R.string.update_view_notes),
                style = TvType.body.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium),
            )
            if (preview.isNotBlank()) {
                BasicText(
                    text = preview,
                    style = TvType.caption.copy(color = TvColors.textSecondary, fontSize = 15.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = if (focused) TvColors.accent else TvColors.textTertiary,
        )
    }
}

/** 显示带标题的更新设置分组。 */
@Composable
private fun UpdateSection(title: String, content: @Composable () -> Unit) {
    BasicText(
        text = title,
        style =
            TvType.caption.copy(
                color = TvColors.textTertiary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            ),
        modifier = Modifier.padding(top = 10.dp, bottom = 7.dp, start = 2.dp),
    )
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.035f)),
    ) {
        content()
    }
}

@Composable
private fun updateStatusTitle(status: UpdateStatus): String =
    when (status) {
        UpdateStatus.IDLE -> stringResource(R.string.update_status_idle)
        UpdateStatus.CHECKING -> stringResource(R.string.update_status_checking)
        UpdateStatus.UP_TO_DATE -> stringResource(R.string.update_status_latest)
        UpdateStatus.AVAILABLE -> stringResource(R.string.update_status_available)
        UpdateStatus.DOWNLOADING -> stringResource(R.string.update_status_downloading)
        UpdateStatus.VERIFYING -> stringResource(R.string.update_status_verifying)
        UpdateStatus.READY_TO_INSTALL -> stringResource(R.string.update_status_ready)
        UpdateStatus.FAILED -> stringResource(R.string.update_status_failed)
    }

/** 用颜色区分检查、下载、校验、就绪和失败阶段。 */
private fun updateAccent(status: UpdateStatus): Color =
    when (status) {
        UpdateStatus.IDLE -> TvColors.blue
        UpdateStatus.CHECKING -> TvColors.blue
        UpdateStatus.UP_TO_DATE -> TvColors.success
        UpdateStatus.AVAILABLE -> TvColors.accent
        UpdateStatus.DOWNLOADING -> TvColors.violet
        UpdateStatus.VERIFYING -> TvColors.warning
        UpdateStatus.READY_TO_INSTALL -> TvColors.success
        UpdateStatus.FAILED -> TvColors.error
    }

/** 将字节数格式化为便于阅读的文件大小。 */
private fun formatBytes(bytes: Long): String =
    when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${"%.1f".format(bytes / 1024f)} KB"
        else -> "${"%.1f".format(bytes / (1024f * 1024f))} MB"
    }

/** 更新页懒列表的条目下标：标题、状态卡、更新日志入口。 */
private const val UPDATE_HEADER_ITEM_INDEX = 0
private const val UPDATE_RELEASE_NOTES_ITEM_INDEX = 2
