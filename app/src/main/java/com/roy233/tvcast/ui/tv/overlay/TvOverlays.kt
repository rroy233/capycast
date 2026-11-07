package com.roy233.tvcast.ui.tv.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roy233.tvcast.feature.danmaku.domain.AiShieldLevel
import com.roy233.tvcast.feature.playback.presentation.PlaybackSpeed
import com.roy233.tvcast.ui.designsystem.*
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.tv.FocusDiagnostics
import com.roy233.tvcast.ui.tv.RequestFocusWhenReady
import com.roy233.tvcast.ui.tv.home.WhiteButton
import com.roy233.tvcast.ui.tv.rememberFocusTarget
import com.roy233.tvcast.ui.tv.tvModalFocusRegion
import kotlin.math.roundToInt

/**
 * 显示投屏帮助模态层。
 *
 * @param state 当前界面状态。
 * @param actions 帮助层可执行的操作回调。
 */
@Composable
internal fun HelpSheet(state: TvUiState, actions: TvActions) {
    val focus = rememberFocusTarget()
    RequestFocusWhenReady(activation = state.helpVisible, target = focus)

    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.68f))
                .tvModalFocusRegion()
                .pointerInput(Unit) { detectTapGestures { actions.onCloseHelp() } },
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(0.76f).pointerInput(Unit) {},
            material = GlassMaterial.Panel,
            size = GlassSize.Large,
        ) {
            Column(
                Modifier.padding(horizontal = 30.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BasicText("如何投屏", style = TvType.section.copy(fontSize = 30.sp))
                HelpStep("1", "手机打开 B 站")
                HelpStep("2", "播放页点右上角 TV")
                HelpStep("3", "选择「${state.deviceName}」")
                Spacer(Modifier.height(4.dp))
                WhiteButton(
                    text = "知道了",
                    onClick = actions.onCloseHelp,
                    focusTarget = focus,
                    compact = true,
                )
            }
        }
    }
}

/** 显示帮助步骤编号和说明文本。 */
@Composable
private fun HelpStep(number: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BasicText(
            text = number,
            style = TvType.bodyMedium.copy(color = TvColors.accent, fontSize = 20.sp),
        )
        BasicText(
            text = text,
            style = TvType.body.copy(color = TvColors.textSecondary, fontSize = 21.sp),
        )
    }
}

/**
 * 显示播放设置、清晰度和音轨选择面板。
 *
 * @param state 当前播放状态。
 * @param actions 面板可执行的操作回调。
 */
@Composable
internal fun QuickPanel(state: TvUiState, actions: TvActions) {
    val closeFocus = rememberFocusTarget()
    val firstFocus = rememberFocusTarget()
    val selectedSpeedFocus = rememberFocusTarget()
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val panel = state.panel ?: return

    val preferredFocus = if (panel == PanelKind.SPEED) selectedSpeedFocus else firstFocus
    LaunchedEffect(
        panel,
        state.playbackSpeed,
        preferredFocus.isReady,
        closeFocus.isReady,
    ) {
        val initialFocus =
            when {
                preferredFocus.isReady -> preferredFocus
                closeFocus.isReady -> closeFocus
                else -> return@LaunchedEffect
            }
        if (panel == PanelKind.SPEED) {
            val selectedIndex =
                PlaybackSpeed.options
                    .indexOf(
                        PlaybackSpeed.normalize(state.playbackSpeed),
                    )
                    .coerceAtLeast(0)
            scrollState.scrollTo(
                with(density) { (QUICK_PANEL_ROW_PITCH * selectedIndex).roundToPx() },
            )
        } else {
            scrollState.scrollTo(0)
        }
        val focused = runCatching { initialFocus.requester.requestFocus() }.getOrDefault(false)
        FocusDiagnostics.transientFocusResult(focused)
    }

    Box(
        modifier =
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)).tvModalFocusRegion(),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val panelMaxHeight = (maxHeight * 0.88f).coerceAtMost(520.dp)
            GlassSurface(
                modifier =
                    Modifier.widthIn(max = 700.dp)
                        .fillMaxWidth(0.80f)
                        .heightIn(max = panelMaxHeight)
                        .align(Alignment.Center),
                material = GlassMaterial.Panel,
                size = GlassSize.Large,
            ) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            text =
                                when (panel) {
                                    PanelKind.ROOT -> "播放设置"
                                    PanelKind.QUALITY -> "清晰度"
                                    PanelKind.AUDIO -> "音轨"
                                    PanelKind.SUBTITLE -> "字幕"
                                    PanelKind.SPEED -> "播放速度"
                                    PanelKind.DANMAKU -> "弹幕设置"
                                    PanelKind.LIVE_QUALITY -> "直播画质"
                                },
                            style = TvType.section,
                            modifier = Modifier.weight(1f),
                        )
                        GlassIconButton(
                            icon = Icons.Rounded.Close,
                            contentDescription = "关闭",
                            onClick = actions.onClosePanel,
                            size = 48.dp,
                            focusTarget = closeFocus,
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        when (panel) {
                            PanelKind.ROOT -> {
                                if (state.isLive) {
                                    FocusableRow(
                                        title = "直播画质",
                                        value = state.qualityLabel.ifBlank { "自动" },
                                        onClick = { actions.onOpenPanel(PanelKind.LIVE_QUALITY) },
                                        focusTarget = firstFocus,
                                    )
                                    FocusableRow(
                                        title = "弹幕",
                                        value = if (state.danmakuEnabled) "开" else "关",
                                        selected = state.danmakuEnabled,
                                        onClick = actions.onToggleDanmaku,
                                    )
                                } else {
                                    FocusableRow(
                                        title = "清晰度",
                                        value = state.qualityLabel.ifBlank { "自动" },
                                        onClick = { actions.onOpenPanel(PanelKind.QUALITY) },
                                        focusTarget = firstFocus,
                                    )
                                    FocusableRow(
                                        title = "音轨",
                                        value = state.audioLabel.ifBlank { "自动" },
                                        onClick = { actions.onOpenPanel(PanelKind.AUDIO) },
                                    )
                                    FocusableRow(
                                        title = "字幕",
                                        value = state.subtitleSummary.ifBlank { "无字幕" },
                                        enabled = state.subtitleTracks.isNotEmpty(),
                                        onClick = { actions.onOpenPanel(PanelKind.SUBTITLE) },
                                    )
                                    FocusableRow(
                                        title = "播放速度",
                                        value = PlaybackSpeed.label(state.playbackSpeed),
                                        onClick = { actions.onOpenPanel(PanelKind.SPEED) },
                                    )
                                    FocusableRow(
                                        title = "弹幕设置",
                                        value =
                                            if (!state.danmakuEnabled) {
                                                "已关闭"
                                            } else {
                                                aiShieldLabel(state.danmakuAiShield)
                                            },
                                        onClick = { actions.onOpenPanel(PanelKind.DANMAKU) },
                                    )
                                }
                                if (!state.isLive) {
                                    BasicText(
                                        text = "aid ${state.aid} · cid ${state.cid}",
                                        style =
                                            TvType.caption.copy(
                                                color = TvColors.textTertiary,
                                                fontSize = 15.sp,
                                            ),
                                        modifier =
                                            Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                    )
                                }
                            }

                            PanelKind.LIVE_QUALITY -> {
                                state.liveQualities.forEachIndexed { index, option ->
                                    FocusableRow(
                                        title = option.label,
                                        value = if (option.qn == state.selectedQn) "当前" else null,
                                        selected = option.qn == state.selectedQn,
                                        onClick = { actions.onSelectQuality(option.qn) },
                                        focusTarget = if (index == 0) firstFocus else null,
                                    )
                                }
                            }

                            PanelKind.QUALITY -> {
                                state.tracks.forEachIndexed { index, track ->
                                    FocusableRow(
                                        title = track.label,
                                        value = track.detail,
                                        selected = track.qn == state.selectedQn,
                                        onClick = { actions.onSelectQuality(track.qn) },
                                        focusTarget = if (index == 0) firstFocus else null,
                                    )
                                }
                            }

                            PanelKind.AUDIO -> {
                                state.audios.forEachIndexed { index, audio ->
                                    FocusableRow(
                                        title = audio.label,
                                        value =
                                            if (audio.id == state.selectedAudioId) "当前" else null,
                                        selected = audio.id == state.selectedAudioId,
                                        onClick = { actions.onSelectAudio(audio.id) },
                                        focusTarget = if (index == 0) firstFocus else null,
                                    )
                                }
                            }

                            PanelKind.SUBTITLE -> {
                                FocusableRow(
                                    title = "关闭字幕",
                                    value = if (state.selectedSubtitleKey == null) "当前" else null,
                                    selected = state.selectedSubtitleKey == null,
                                    onClick = { actions.onSelectSubtitleTrack(null) },
                                    focusTarget = firstFocus,
                                )
                                state.subtitleTracks.forEach { option ->
                                    FocusableRow(
                                        title = option.label,
                                        value = if (option.selected) "当前" else option.detail,
                                        selected = option.selected,
                                        enabled = !option.locked,
                                        onClick = { actions.onSelectSubtitleTrack(option.key) },
                                    )
                                }
                            }

                            PanelKind.SPEED -> {
                                val selectedSpeed = PlaybackSpeed.normalize(state.playbackSpeed)
                                PlaybackSpeed.options.forEachIndexed { index, speed ->
                                    FocusableRow(
                                        title = PlaybackSpeed.label(speed),
                                        value = if (speed == 1f) "正常速度" else null,
                                        selected = speed == selectedSpeed,
                                        onClick = { actions.onSetPlaybackSpeed(speed) },
                                        focusTarget =
                                            if (speed == selectedSpeed) selectedSpeedFocus
                                            else null,
                                    )
                                }
                            }

                            PanelKind.DANMAKU -> {
                                FocusableRow(
                                    title = "弹幕",
                                    value = if (state.danmakuEnabled) "开" else "关",
                                    selected = state.danmakuEnabled,
                                    onClick = actions.onToggleDanmaku,
                                    focusTarget = firstFocus,
                                )
                                FocusableRow(
                                    title = "智能云屏蔽",
                                    value = aiShieldLabel(state.danmakuAiShield),
                                    onClick = {
                                        actions.onSetDanmakuAiShield(
                                            nextAiShield(state.danmakuAiShield),
                                        )
                                    },
                                )
                                FocusableRow(
                                    title = "显示区域",
                                    value = areaLabel(state.danmakuAreaRatio),
                                    onClick = {
                                        actions.onSetDanmakuArea(
                                            nextArea(state.danmakuAreaRatio),
                                        )
                                    },
                                )
                                FocusableRow(
                                    title = "同屏数量",
                                    value = maxOnScreenLabel(state.danmakuMaxOnScreen),
                                    onClick = {
                                        actions.onSetDanmakuMaxOnScreen(
                                            nextMaxOnScreen(state.danmakuMaxOnScreen),
                                        )
                                    },
                                )
                                FocusableRow(
                                    title = "不透明度",
                                    value = opacityLabel(state.danmakuOpacity),
                                    onClick = {
                                        actions.onSetDanmakuOpacity(
                                            nextOpacity(state.danmakuOpacity),
                                        )
                                    },
                                )
                                FocusableRow(
                                    title = "字号",
                                    value = fontScaleLabel(state.danmakuFontScale),
                                    onClick = {
                                        actions.onSetDanmakuFontScale(
                                            nextFontScale(state.danmakuFontScale),
                                        )
                                    },
                                )
                                FocusableRow(
                                    title = "速度",
                                    value = durationLabel(state.danmakuDurationSec),
                                    onClick = {
                                        actions.onSetDanmakuDuration(
                                            nextDuration(state.danmakuDurationSec),
                                        )
                                    },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    GlassButton(
                        text = "完成",
                        onClick = actions.onClosePanel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private val QUICK_PANEL_ROW_PITCH = 62.dp

private fun aiShieldLabel(level: AiShieldLevel): String =
    when (level) {
        AiShieldLevel.OFF -> "关"
        AiShieldLevel.LOW -> "低"
        AiShieldLevel.MEDIUM -> "中"
        AiShieldLevel.HIGH -> "高"
    }

private fun nextAiShield(level: AiShieldLevel): AiShieldLevel =
    when (level) {
        AiShieldLevel.OFF -> AiShieldLevel.LOW
        AiShieldLevel.LOW -> AiShieldLevel.MEDIUM
        AiShieldLevel.MEDIUM -> AiShieldLevel.HIGH
        AiShieldLevel.HIGH -> AiShieldLevel.OFF
    }

private fun areaLabel(ratio: Float): String =
    when {
        ratio <= 0.30f -> "1/4 屏"
        ratio <= 0.55f -> "半屏"
        ratio <= 0.80f -> "3/4 屏"
        else -> "全屏"
    }

private fun nextArea(ratio: Float): Float =
    when {
        ratio <= 0.30f -> 0.50f
        ratio <= 0.55f -> 0.75f
        ratio <= 0.80f -> 1.0f
        else -> 0.25f
    }

private fun maxOnScreenLabel(max: Int): String = if (max <= 0) "不限" else max.toString()

private fun nextMaxOnScreen(max: Int): Int =
    when (max) {
        30 -> 60
        60 -> 100
        100 -> 0
        else -> 30
    }

private fun opacityLabel(opacity: Float): String = "${(opacity * 100).roundToInt()}%"

private fun nextOpacity(opacity: Float): Float {
    val step = ((opacity * 100).roundToInt() + 20) / 20 * 20
    return if (step > 100) 20f / 100f else step / 100f
}

private fun fontScaleLabel(scale: Float): String =
    when {
        scale <= 0.80f -> "小"
        scale <= 1.05f -> "标准"
        scale <= 1.30f -> "大"
        else -> "超大"
    }

private fun nextFontScale(scale: Float): Float =
    when {
        scale <= 0.80f -> 1.0f
        scale <= 1.05f -> 1.25f
        scale <= 1.30f -> 1.5f
        else -> 0.75f
    }

private fun durationLabel(seconds: Float): String =
    when {
        seconds >= 10f -> "慢"
        seconds >= 7f -> "适中"
        else -> "快"
    }

private fun nextDuration(seconds: Float): Float =
    when {
        seconds >= 10f -> 8f
        seconds >= 7f -> 5f
        else -> 12f
    }
