package com.roy233.tvcast.ui.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
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
import com.roy233.tvcast.ui.designsystem.FocusableRow
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.tv.home.WhiteButton
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
    val checkFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        checkFocus.requestFocus()
    }
    val canCheck = update.status != UpdateStatus.CHECKING &&
        update.status != UpdateStatus.DOWNLOADING &&
        update.status != UpdateStatus.VERIFYING
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 80.dp, vertical = 52.dp)
            .tvFocusRegion(),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = TvColors.pink,
                )
                Spacer(Modifier.width(14.dp))
                BasicText(
                    text = "应用更新",
                    style = TvType.title.copy(fontSize = 48.sp),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(26.dp))
        }

        item {
            UpdateStatusCard(update)
            Spacer(Modifier.height(22.dp))
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().tvFocusRegion(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                BasicText(
                    text = "当前版本 ${state.version}",
                    style = TvType.caption.copy(color = TvColors.textSecondary, fontSize = 18.sp),
                    modifier = Modifier.weight(1f),
                )
                WhiteButton(
                    text = if (canCheck) "检查更新" else "更新进行中",
                    onClick = { if (canCheck) actions.onCheckForUpdates() },
                    focusRequester = checkFocus,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        item { UpdateAction(update, actions) }

        item {
            update.availableUpdate?.let { available ->
                UpdateSection("版本 ${available.versionName}") {
                    BasicText(
                        text = "${formatBytes(available.sizeBytes)} · GitHub Release",
                        style = TvType.caption.copy(color = TvColors.textTertiary, fontSize = 17.sp),
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                    if (available.releaseNotes.isBlank()) {
                        BasicText(
                            text = "此版本未提供更新说明。",
                            style = TvType.body.copy(color = TvColors.textSecondary),
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                        )
                    } else {
                        MarkdownReleaseNotes(available.releaseNotes)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            UpdateChoiceGroup("更新通道") {
                UpdateOptionTile(
                    title = "正式版",
                    description = "推荐日常使用",
                    selected = update.channel == UpdateChannel.STABLE,
                    onClick = { actions.onSelectUpdateChannel(UpdateChannel.STABLE) },
                    modifier = Modifier.weight(1f),
                )
                UpdateOptionTile(
                    title = "Beta 版",
                    description = "公开测试版本",
                    selected = update.channel == UpdateChannel.BETA,
                    onClick = { actions.onSelectUpdateChannel(UpdateChannel.BETA) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        item {
            UpdateChoiceGroup("自动检查更新") {
                UpdateOptionTile(
                    title = "开启",
                    description = "启动应用时自动检查",
                    selected = update.automaticCheck,
                    onClick = { actions.onSetAutoUpdateCheck(true) },
                    modifier = Modifier.weight(1f),
                )
                UpdateOptionTile(
                    title = "关闭",
                    description = "仅在手动操作时检查",
                    selected = !update.automaticCheck,
                    onClick = { actions.onSetAutoUpdateCheck(false) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(18.dp))
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
        style = TvType.caption.copy(
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
        modifier = modifier
            .height(96.dp)
            .clip(shape)
            .background(
                when {
                    focused && selected -> TvColors.pink.copy(alpha = 0.30f)
                    focused -> Color.White.copy(alpha = 0.16f)
                    selected -> TvColors.pink.copy(alpha = 0.16f)
                    else -> Color.White.copy(alpha = 0.07f)
                }
            )
            .border(
                width = 2.dp,
                color = when {
                    focused -> TvColors.pink
                    selected -> TvColors.pink.copy(alpha = 0.72f)
                    else -> Color.White.copy(alpha = 0.08f)
                },
                shape = shape,
            )
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
                    tint = TvColors.pink,
                )
            }
        }
        BasicText(
            text = description,
            style = TvType.caption.copy(color = TvColors.textSecondary, fontSize = 15.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            BasicText(
                text = "已选中",
                style = TvType.caption.copy(color = TvColors.pink, fontSize = 14.sp),
            )
        }
    }
}

/** 显示更新流程的状态卡片和下载进度。 */
@Composable
private fun UpdateStatusCard(update: UpdateUiState) {
    val fraction = if (update.totalBytes > 0L) {
        (update.downloadedBytes.toFloat() / update.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.09f))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            text = updateStatusTitle(update.status),
            style = TvType.body.copy(fontWeight = FontWeight.SemiBold),
        )
        update.message?.let { message ->
            BasicText(
                text = message,
                style = TvType.caption.copy(color = TvColors.textSecondary, fontSize = 17.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (update.status == UpdateStatus.DOWNLOADING) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.16f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(7.dp)
                        .background(TvColors.pink),
                )
            }
            BasicText(
                text = "${formatBytes(update.downloadedBytes)} / ${formatBytes(update.totalBytes)} · ${(fraction * 100).toInt()}%",
                style = TvType.caption.copy(color = TvColors.textTertiary, fontSize = 16.sp),
            )
        }
    }
}

/** 根据更新状态显示可执行操作。 */
@Composable
private fun UpdateAction(update: UpdateUiState, actions: TvActions) {
    when (update.status) {
        UpdateStatus.AVAILABLE,
        UpdateStatus.FAILED -> if (update.availableUpdate != null) {
            UpdateSection("更新操作") {
                FocusableRow(
                    title = "下载更新",
                    value = update.availableUpdate.versionName,
                    onClick = actions.onDownloadUpdate,
                )
            }
        }

        UpdateStatus.DOWNLOADING,
        UpdateStatus.VERIFYING -> UpdateSection("更新操作") {
            FocusableRow(
                title = "取消下载",
                onClick = actions.onCancelUpdateDownload,
                destructive = true,
                showChevron = false,
            )
        }

        UpdateStatus.READY_TO_INSTALL -> UpdateSection("更新操作") {
            FocusableRow(
                title = "安装更新",
                value = update.availableUpdate?.versionName,
                onClick = actions.onInstallUpdate,
            )
        }

        else -> Unit
    }
}

/** 显示带标题的更新设置分组。 */
@Composable
private fun UpdateSection(title: String, content: @Composable () -> Unit) {
    BasicText(
        text = title,
        style = TvType.caption.copy(
            color = TvColors.textTertiary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
        ),
        modifier = Modifier.padding(top = 10.dp, bottom = 7.dp, start = 2.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.035f)),
    ) {
        content()
    }
}

/** 渲染 GitHub Release 常用的标题、列表、代码和粗体 Markdown 语法。 */
@Composable
private fun MarkdownReleaseNotes(markdown: String) {
    Column(
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        var inCodeBlock = false
        markdown.lineSequence().forEach { line ->
            when {
                line.startsWith("```") -> inCodeBlock = !inCodeBlock
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                line == "---" || line == "***" || line == "___" -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.16f)),
                )
                inCodeBlock -> BasicText(
                    text = line,
                    style = TvType.caption.copy(
                        color = TvColors.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.25f))
                        .padding(8.dp),
                )

                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    BasicText(
                        text = renderInlineMarkdown(line.drop(level).trim()),
                        style = TvType.body.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (level == 1) 25.sp else 21.sp,
                        ),
                    )
                }

                line.startsWith("- ") || line.startsWith("* ") -> Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicText("•", style = TvType.body.copy(color = TvColors.pink))
                    BasicText(
                        text = renderInlineMarkdown(line.drop(2)),
                        style = TvType.body.copy(color = TvColors.textSecondary),
                        modifier = Modifier.weight(1f),
                    )
                }

                ORDERED_LIST_PATTERN.containsMatchIn(line) -> Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val match = requireNotNull(ORDERED_LIST_PATTERN.find(line))
                    BasicText(
                        text = match.value.trim(),
                        style = TvType.body.copy(color = TvColors.pink),
                    )
                    BasicText(
                        text = renderInlineMarkdown(line.drop(match.value.length)),
                        style = TvType.body.copy(color = TvColors.textSecondary),
                        modifier = Modifier.weight(1f),
                    )
                }

                line.startsWith("> ") -> BasicText(
                    text = renderInlineMarkdown(line.drop(2)),
                    style = TvType.body.copy(
                        color = TvColors.textSecondary,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(10.dp),
                )

                else -> BasicText(
                    text = renderInlineMarkdown(line),
                    style = TvType.body.copy(color = TvColors.textSecondary),
                )
            }
        }
    }
}

/** 将 Markdown 行中的粗体和行内代码转换为带样式文本。 */
private fun renderInlineMarkdown(source: String): AnnotatedString = buildAnnotatedString {
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
private fun updateStatusTitle(status: UpdateStatus): String = when (status) {
    UpdateStatus.IDLE -> "选择通道后检查更新"
    UpdateStatus.CHECKING -> "正在检查 GitHub Release"
    UpdateStatus.UP_TO_DATE -> "当前已是最新版本"
    UpdateStatus.AVAILABLE -> "发现可用更新"
    UpdateStatus.DOWNLOADING -> "正在下载更新"
    UpdateStatus.VERIFYING -> "正在校验安装包"
    UpdateStatus.READY_TO_INSTALL -> "更新安装包已准备完成"
    UpdateStatus.FAILED -> "更新操作未完成"
}

/** 将字节数格式化为便于阅读的文件大小。 */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${"%.1f".format(bytes / 1024f)} KB"
    else -> "${"%.1f".format(bytes / (1024f * 1024f))} MB"
}

/** 有序列表项的 Markdown 前缀。 */
private val ORDERED_LIST_PATTERN = Regex("^\\d+\\.\\s+")
