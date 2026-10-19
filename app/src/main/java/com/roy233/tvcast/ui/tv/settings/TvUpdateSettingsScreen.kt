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

/**
 * 显示更新通道、检查开关、发布说明和下载安装操作。
 *
 * @param state 当前电视界面状态。
 * @param actions 更新页面可执行的操作回调。
 */
@Composable
internal fun UpdateSettingsScreen(state: TvUiState, actions: TvActions) {
    val update = state.update
    val releaseNotes = update.availableUpdate?.releaseNotes.orEmpty()
    val headerFocus = remember { FocusRequester() }
    val primaryActionFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        listState.scrollToItem(0)
        headerFocus.requestFocus()
    }
    val progress = if (update.totalBytes > 0L) {
        (update.downloadedBytes.toFloat() / update.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val primaryActionText = when (update.status) {
        UpdateStatus.CHECKING -> "检查中…"
        UpdateStatus.AVAILABLE -> "下载"
        UpdateStatus.DOWNLOADING -> "下载中 ${(progress * 100).toInt()}%"
        UpdateStatus.VERIFYING -> "校验中…"
        UpdateStatus.READY_TO_INSTALL -> "安装"
        UpdateStatus.FAILED -> when {
            update.availableUpdate == null -> "重试检查"
            update.downloadedApkPath != null -> "安装"
            else -> "重新下载"
        }
        UpdateStatus.IDLE, UpdateStatus.UP_TO_DATE -> "检查更新"
    }
    val primaryAction: () -> Unit = when (update.status) {
        UpdateStatus.AVAILABLE -> actions.onDownloadUpdate
        UpdateStatus.READY_TO_INSTALL -> actions.onInstallUpdate
        UpdateStatus.FAILED -> when {
            update.availableUpdate == null -> actions.onCheckForUpdates
            update.downloadedApkPath != null -> actions.onInstallUpdate
            else -> actions.onDownloadUpdate
        }
        UpdateStatus.DOWNLOADING -> actions.onCancelUpdateDownload
        UpdateStatus.VERIFYING, UpdateStatus.CHECKING -> ({})
        UpdateStatus.IDLE, UpdateStatus.UP_TO_DATE -> actions.onCheckForUpdates
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 80.dp, vertical = 52.dp)
            .tvFocusRegion(),
    ) {
        item {
            val interaction = remember { MutableInteractionSource() }
            val focused = interaction.collectIsFocusedAsState().value
            val shape = RoundedCornerShape(14.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (focused) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                    .border(
                        width = 2.dp,
                        color = if (focused) TvColors.pink else Color.Transparent,
                        shape = shape,
                    )
                    .focusRequester(headerFocus)
                    .focusable(interactionSource = interaction)
                    .semantics(mergeDescendants = true) { }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
            UpdateStatusCard(update, state.version)
            Spacer(Modifier.height(22.dp))
        }

        if (releaseNotes.isNotBlank()) {
            item {
                ReleaseNotesEntry(releaseNotes, actions.onOpenUpdateNotes)
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
                    text = "当前版本 ${state.version}",
                    style = TvType.caption.copy(color = TvColors.textSecondary, fontSize = 18.sp),
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

/** 在统一信息卡中呈现更新阶段、版本摘要、更新日志和下载进度。 */
@Composable
private fun UpdateStatusCard(update: UpdateUiState, currentVersion: String) {
    val interaction = remember { MutableInteractionSource() }
    val focused = interaction.collectIsFocusedAsState().value
    val accent = updateAccent(update.status)
    val fraction = if (update.totalBytes > 0L) {
        (update.downloadedBytes.toFloat() / update.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = if (focused) 0.17f else 0.10f))
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) TvColors.pink else accent.copy(alpha = 0.38f),
                shape = RoundedCornerShape(18.dp),
            )
            .focusable(interactionSource = interaction)
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .width(9.dp)
                    .height(9.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accent),
            )
            BasicText(
                text = updateStatusTitle(update.status),
                style = TvType.body.copy(color = accent, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            if (update.status == UpdateStatus.DOWNLOADING) {
                BasicText(
                    text = "${(fraction * 100).toInt()}%",
                    style = TvType.caption.copy(color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold),
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
                    text = "${formatBytes(available.sizeBytes)} · 当前版本 $currentVersion",
                    style = TvType.caption.copy(color = TvColors.textTertiary, fontSize = 16.sp),
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
            BasicText(
                text = when (update.status) {
                    UpdateStatus.DOWNLOADING -> "正在获取安装包；按主按钮可取消下载。"
                    UpdateStatus.VERIFYING -> "正在校验安装包完整性…"
                    UpdateStatus.READY_TO_INSTALL -> "安装包已校验，可以安装。"
                    UpdateStatus.FAILED -> update.message ?: "上次操作失败，可重试下载。"
                    else -> "新版本已就绪，查看更新内容并开始下载。"
                },
                style = TvType.caption.copy(color = TvColors.textSecondary, fontSize = 16.sp),
            )
            if (available.releaseNotes.isBlank()) {
                BasicText(
                    text = "此版本未提供更新说明。",
                    style = TvType.body.copy(color = TvColors.textSecondary, fontSize = 17.sp),
                )
            }
        } ?: update.message?.let { message ->
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = accent,
                backgroundColor = Color.White.copy(alpha = 0.16f),
            )
            BasicText(
                text = "${formatBytes(update.downloadedBytes)} / ${formatBytes(update.totalBytes)} · ${(fraction * 100).toInt()}%",
                style = TvType.caption.copy(color = TvColors.textSecondary, fontSize = 16.sp),
            )
        }
    }
}

/** 显示可聚焦的更新日志入口。 */
@Composable
private fun ReleaseNotesEntry(notes: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused = interaction.collectIsFocusedAsState().value
    val shape = RoundedCornerShape(16.dp)
    val preview = releaseNotesPreview(notes)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (focused) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.06f)
            )
            .border(
                width = 2.dp,
                color = if (focused) TvColors.pink else Color.White.copy(alpha = 0.08f),
                shape = shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Description,
            contentDescription = null,
            tint = if (focused) TvColors.pink else TvColors.textSecondary,
            modifier = Modifier.size(24.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BasicText(
                text = "查看更新日志",
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
            tint = if (focused) TvColors.pink else TvColors.textTertiary,
        )
    }
}

/** 提取更新日志首行作为入口摘要。 */
private fun releaseNotesPreview(notes: String): String = notes.lineSequence()
    .map { line -> line.trim().trimStart('#', '*', '-', '>', ' ') }
    .firstOrNull { it.isNotBlank() && !it.startsWith("```") }
    .orEmpty()
    .take(90)

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

/** 显示一条可聚焦的更新日志内容。 */
@Composable
private fun ReleaseNoteBlock(block: ReleaseNote, focusRequester: FocusRequester?) {
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

/** 更新日志的块类型。 */
private enum class ReleaseNoteKind { HEADING, BULLET, ORDERED, QUOTE, CODE, DIVIDER, TEXT }

/** 更新日志中的一段可聚焦内容。 */
private data class ReleaseNote(
    val kind: ReleaseNoteKind,
    val text: AnnotatedString = AnnotatedString(""),
    val prefix: String = "",
    val level: Int = 0,
)

/** 将更新日志 Markdown 解析为可逐段聚焦的块。 */
private fun parseReleaseNotes(markdown: String): List<ReleaseNote> {
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
    UpdateStatus.CHECKING -> "正在检查更新"
    UpdateStatus.UP_TO_DATE -> "当前已是最新版本"
    UpdateStatus.AVAILABLE -> "发现可用更新"
    UpdateStatus.DOWNLOADING -> "正在下载新版本"
    UpdateStatus.VERIFYING -> "正在校验安装包"
    UpdateStatus.READY_TO_INSTALL -> "安装包已准备就绪"
    UpdateStatus.FAILED -> "更新操作未完成"
}

/** 用颜色区分检查、下载、校验、就绪和失败阶段。 */
private fun updateAccent(status: UpdateStatus): Color = when (status) {
    UpdateStatus.IDLE -> TvColors.blue
    UpdateStatus.CHECKING -> TvColors.blue
    UpdateStatus.UP_TO_DATE -> TvColors.success
    UpdateStatus.AVAILABLE -> TvColors.pink
    UpdateStatus.DOWNLOADING -> TvColors.violet
    UpdateStatus.VERIFYING -> TvColors.warning
    UpdateStatus.READY_TO_INSTALL -> TvColors.success
    UpdateStatus.FAILED -> TvColors.error
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
