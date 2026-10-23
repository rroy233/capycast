package com.roy233.tvcast.ui.tv.overlay

import com.roy233.tvcast.ui.tv.home.WhiteButton
import com.roy233.tvcast.ui.tv.tvModalFocusRegion

import android.graphics.Bitmap
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.model.AudioOption
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.ui.model.PlaybackSpeed
import com.roy233.tvcast.core.danmaku.AiShieldLevel
import kotlin.math.roundToInt
import com.roy233.tvcast.ui.model.RecentCast
import com.roy233.tvcast.ui.model.TrackOption
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvError
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.designsystem.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay

/**
 * 显示投屏帮助模态层。
 *
 * @param state 当前界面状态。
 * @param actions 帮助层可执行的操作回调。
 */
@Composable
internal fun HelpSheet(state: TvUiState, actions: TvActions) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { focus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.68f))
            .tvModalFocusRegion()
            .pointerInput(Unit) { detectTapGestures { actions.onCloseHelp() } },
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth(0.76f)
                .pointerInput(Unit) {},
            tint = Color(0xD90B0B0F),
            borderAlpha = 0.36f,
            highlightAlpha = 0.30f,
            shadow = 44.dp,
            useBackdrop = true,
            blurRadius = 36.dp,
            lensHeight = 12.dp,
            lensAmount = 18.dp,
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
                    focusRequester = focus,
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
            style = TvType.bodyMedium.copy(color = TvColors.pink, fontSize = 20.sp),
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
    val firstFocus = remember { FocusRequester() }
    val selectedSpeedFocus = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val panel = state.panel ?: return

    LaunchedEffect(panel, state.playbackSpeed) {
        delay(80)
        runCatching {
            if (panel == PanelKind.SPEED) {
                val selectedIndex = PlaybackSpeed.options.indexOf(
                    PlaybackSpeed.normalize(state.playbackSpeed),
                ).coerceAtLeast(0)
                scrollState.scrollTo(
                    with(density) { (QUICK_PANEL_ROW_PITCH * selectedIndex).roundToPx() },
                )
                selectedSpeedFocus.requestFocus()
            } else {
                scrollState.scrollTo(0)
                firstFocus.requestFocus()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .tvModalFocusRegion(),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val panelMaxHeight = (maxHeight * 0.88f).coerceAtMost(520.dp)
            GlassSurface(
                modifier = Modifier
                    .widthIn(max = 700.dp)
                    .fillMaxWidth(0.80f)
                    .heightIn(max = panelMaxHeight)
                    .align(Alignment.Center),
                tint = Color(0xF20B0B0F),
                borderAlpha = 0.30f,
                highlightAlpha = 0.24f,
                shadow = 38.dp,
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            text = when (panel) {
                                PanelKind.ROOT -> "播放设置"
                                PanelKind.QUALITY -> "清晰度"
                                PanelKind.AUDIO -> "音轨"
                                PanelKind.SPEED -> "播放速度"
                                PanelKind.DANMAKU -> "弹幕设置"
                            },
                            style = TvType.section,
                            modifier = Modifier.weight(1f),
                        )
                        GlassIconButton(
                            icon = Icons.Rounded.Close,
                            contentDescription = "关闭",
                            onClick = actions.onClosePanel,
                            size = 48.dp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        when (panel) {
                            PanelKind.ROOT -> {
                                if (state.isLive) {
                                    FocusableRow(
                                        title = "直播画质",
                                        value = state.qualityLabel.ifBlank { "自动" },
                                        onClick = {},
                                        focusRequester = firstFocus,
                                    )
                                    FocusableRow(
                                        title = "当前线路",
                                        value = state.liveCdn.ifBlank { "解析中" },
                                        onClick = {},
                                    )
                                    FocusableRow(
                                        title = "编码 / 格式 / 备用",
                                        value = "${state.qualityDetail} / ${state.liveFormat.uppercase()} / ${state.liveBackupCount}",
                                        onClick = {},
                                    )
                                } else {
                                    FocusableRow(
                                        title = "清晰度",
                                        value = state.qualityLabel.ifBlank { "自动" },
                                        onClick = { actions.onOpenPanel(PanelKind.QUALITY) },
                                        focusRequester = firstFocus,
                                    )
                                    FocusableRow(
                                        title = "音轨",
                                        value = state.audioLabel.ifBlank { "自动" },
                                        onClick = { actions.onOpenPanel(PanelKind.AUDIO) },
                                    )
                                    FocusableRow(
                                        title = "播放速度",
                                        value = PlaybackSpeed.label(state.playbackSpeed),
                                        onClick = { actions.onOpenPanel(PanelKind.SPEED) },
                                    )
                                    FocusableRow(
                                        title = "弹幕设置",
                                        value = if (!state.danmakuEnabled) {
                                            "已关闭"
                                        } else {
                                            aiShieldLabel(state.danmakuAiShield)
                                        },
                                        onClick = { actions.onOpenPanel(PanelKind.DANMAKU) },
                                    )
                                }
                                BasicText(
                                    text = if (state.isLive) "room ${state.cid}" else "aid ${state.aid} · cid ${state.cid}",
                                    style = TvType.caption.copy(
                                        color = TvColors.textTertiary,
                                        fontSize = 15.sp,
                                    ),
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                )
                            }

                            PanelKind.QUALITY -> {
                                state.tracks.forEachIndexed { index, track ->
                                    FocusableRow(
                                        title = track.label,
                                        value = track.detail,
                                        selected = track.qn == state.selectedQn,
                                        onClick = { actions.onSelectQuality(track.qn) },
                                        focusRequester = if (index == 0) firstFocus else null,
                                    )
                                }
                            }

                            PanelKind.AUDIO -> {
                                state.audios.forEachIndexed { index, audio ->
                                    FocusableRow(
                                        title = audio.label,
                                        value = if (audio.id == state.selectedAudioId) "当前" else null,
                                        selected = audio.id == state.selectedAudioId,
                                        onClick = { actions.onSelectAudio(audio.id) },
                                        focusRequester = if (index == 0) firstFocus else null,
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
                                        focusRequester = if (speed == selectedSpeed) selectedSpeedFocus else null,
                                    )
                                }
                            }

                            PanelKind.DANMAKU -> {
                                FocusableRow(
                                    title = "弹幕",
                                    value = if (state.danmakuEnabled) "开" else "关",
                                    selected = state.danmakuEnabled,
                                    onClick = actions.onToggleDanmaku,
                                    focusRequester = firstFocus,
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

private fun aiShieldLabel(level: AiShieldLevel): String = when (level) {
    AiShieldLevel.OFF -> "关"
    AiShieldLevel.LOW -> "低"
    AiShieldLevel.MEDIUM -> "中"
    AiShieldLevel.HIGH -> "高"
}

private fun nextAiShield(level: AiShieldLevel): AiShieldLevel = when (level) {
    AiShieldLevel.OFF -> AiShieldLevel.LOW
    AiShieldLevel.LOW -> AiShieldLevel.MEDIUM
    AiShieldLevel.MEDIUM -> AiShieldLevel.HIGH
    AiShieldLevel.HIGH -> AiShieldLevel.OFF
}

private fun areaLabel(ratio: Float): String = when {
    ratio <= 0.30f -> "1/4 屏"
    ratio <= 0.55f -> "半屏"
    ratio <= 0.80f -> "3/4 屏"
    else -> "全屏"
}

private fun nextArea(ratio: Float): Float = when {
    ratio <= 0.30f -> 0.50f
    ratio <= 0.55f -> 0.75f
    ratio <= 0.80f -> 1.0f
    else -> 0.25f
}

private fun maxOnScreenLabel(max: Int): String = if (max <= 0) "不限" else max.toString()

private fun nextMaxOnScreen(max: Int): Int = when (max) {
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

private fun fontScaleLabel(scale: Float): String = when {
    scale <= 0.80f -> "小"
    scale <= 1.05f -> "标准"
    scale <= 1.30f -> "大"
    else -> "超大"
}

private fun nextFontScale(scale: Float): Float = when {
    scale <= 0.80f -> 1.0f
    scale <= 1.05f -> 1.25f
    scale <= 1.30f -> 1.5f
    else -> 0.75f
}

private fun durationLabel(seconds: Float): String = when {
    seconds >= 10f -> "慢"
    seconds >= 7f -> "适中"
    else -> "快"
}

private fun nextDuration(seconds: Float): Float = when {
    seconds >= 10f -> 8f
    seconds >= 7f -> 5f
    else -> 12f
}

