package com.roy233.tvcast.ui.tv.player

import android.os.SystemClock
import android.graphics.Bitmap
import com.roy233.tvcast.R
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.model.PanelKind
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.res.vectorResource
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 显示投屏准备阶段的加载界面。 */
@Composable
internal fun LoadingScreen() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(900)
        visible = true
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.black),
        contentAlignment = Alignment.Center,
    ) {
        if (visible) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LoadingSpinner()
                BasicText(
                    text = "正在准备投屏…",
                    style = TvType.caption.copy(color = TvColors.textSecondary),
                )
            }
        }
    }
}

/**
 * 显示播放画面、标题、进度和播放控制项。
 *
 * @param state 当前播放状态。
 * @param actions 播放控制回调。
 * @param enabled 是否允许用户操作控制项。
 */
@Composable
internal fun PlayingScreen(
    state: TvUiState,
    actions: TvActions,
    enabled: Boolean,
    seekAccelerator: PlaybackSeekAccelerator,
) {
    val playFocus = remember { FocusRequester() }
    val progressFocus = remember { FocusRequester() }
    val videoFocus = remember { FocusRequester() }
    val modal = state.panel != null || state.error != null || state.stopCastConfirm
    val displayedPositionMs = state.seekPreviewPositionMs ?: state.positionMs
    val seekingState = rememberUpdatedState(state.seekPreviewPositionMs != null)
    val actionsState = rememberUpdatedState(actions)

    LaunchedEffect(state.hudVisible, modal, enabled) {
        if (!enabled || modal) return@LaunchedEffect
        if (state.hudVisible) {
            delay(80)
            runCatching { playFocus.requestFocus() }
        } else {
            runCatching { videoFocus.requestFocus() }
        }
    }

    LaunchedEffect(state.seekPreviewPositionMs) {
        if (state.seekPreviewPositionMs != null) {
            delay(SEEK_CONFIRM_DELAY_MS)
            actions.onConfirmSeek()
            seekAccelerator.reset()
        } else {
            seekAccelerator.reset()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(videoFocus)
                .focusable(enabled && !state.hudVisible)
                .playbackTouchControls(
                    enabled = enabled && !state.hudVisible,
                    isSeeking = { seekingState.value },
                    seekAccelerator = seekAccelerator,
                    onPreviewSeekBy = { actionsState.value.onPreviewSeekBy(it) },
                    onConfirmSeek = { actionsState.value.onConfirmSeek() },
                    onShowHub = { actionsState.value.onShowHud() },
                )
        )

        if (state.isBuffering) {
            LoadingSpinner(Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
            visible = state.hudVisible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(220)),
        ) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent)
                            )
                        )
                        .padding(horizontal = 48.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        text = state.castTitle.ifBlank { "正在播放" },
                        style = TvType.bodyMedium.copy(fontSize = 24.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.qualityLabel.isNotBlank()) {
                        GlassPill(
                            text = buildString {
                                append(state.qualityLabel)
                                if (state.qualitySource.isNotBlank()) {
                                    append(" · ")
                                    append(state.qualitySource)
                                }
                            },
                            emphasized = true,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = 760.dp)
                        .fillMaxWidth(0.66f)
                        .padding(bottom = 26.dp),
                ) {
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 148.dp, max = 160.dp),
                        shape = RoundedCornerShape(28.dp),
                        tint = Color(0x59000000),
                        borderAlpha = 0.36f,
                        highlightAlpha = 0.40f,
                        shadow = 36.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            SeekableProgressBar(
                                positionMs = displayedPositionMs,
                                durationMs = state.durationMs,
                                isSeeking = state.seekPreviewPositionMs != null,
                                focusRequester = progressFocus,
                                playFocusRequester = playFocus,
                                seekAccelerator = seekAccelerator,
                                onPreviewSeekBy = actions.onPreviewSeekBy,
                                onConfirmSeek = actions.onConfirmSeek,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicText(
                                    text = "${formatTime(displayedPositionMs)}  /  ${formatTime(state.durationMs)}",
                                    style = TvType.caption.copy(
                                        color = TvColors.textSecondary,
                                        fontSize = 14.sp,
                                    ),
                                )
                                Spacer(Modifier.weight(1f))
                            }
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PrimaryPlayButton(
                                    isPlaying = state.isPlaying,
                                    onClick = actions.onPlayPause,
                                    modifier = Modifier.focusProperties { up = progressFocus },
                                    size = 64.dp,
                                    focusRequester = playFocus,
                                )
                                Spacer(Modifier.weight(1f))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    GlassIconButton(
                                        icon = Icons.Rounded.HighQuality,
                                        contentDescription = "清晰度 ${state.qualityLabel}",
                                        onClick = { actions.onOpenPanel(PanelKind.QUALITY) },
                                        enabled = enabled,
                                        size = 56.dp,
                                    )
                                    GlassIconButton(
                                        icon = ImageVector.vectorResource(R.drawable.ic_danmu),
                                        contentDescription = if (state.danmakuEnabled) "关闭弹幕" else "开启弹幕",
                                        onClick = actions.onToggleDanmaku,
                                        enabled = enabled,
                                        selected = state.danmakuEnabled,
                                        size = 56.dp,
                                    )
                                    GlassIconButton(
                                        icon = Icons.Rounded.Settings,
                                        contentDescription = "播放设置",
                                        onClick = { actions.onOpenPanel(PanelKind.ROOT) },
                                        enabled = enabled,
                                        size = 56.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.seekPreviewPositionMs != null && !state.hudVisible,
            enter = fadeIn(tween(160)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { it / 3 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp),
        ) {
            SeekPreviewOverlay(
                positionMs = displayedPositionMs,
                durationMs = state.durationMs,
            )
        }
    }
}

/** 绘制当前播放位置与媒体时长对应的进度条。 */
@Composable
private fun TvProgressBar(positionMs: Long, durationMs: Long, focused: Boolean) {
    val progress = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(if (focused) 18.dp else 8.dp)
    ) {
        val trackHeight = if (focused) 7.dp.toPx() else 4.dp.toPx()
        val y = (size.height - trackHeight) / 2f
        drawRoundRect(
            color = Color.White.copy(alpha = if (focused) 0.32f else 0.18f),
            topLeft = Offset(0f, y),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
        )
        val filled = size.width * progress
        drawRoundRect(
            color = if (focused) TvColors.pink else Color.White.copy(alpha = 0.92f),
            topLeft = Offset(0f, y),
            size = Size(filled, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
        )
        drawCircle(
            color = Color.White,
            radius = if (focused) 7.dp.toPx() else 4.dp.toPx(),
            center = Offset(filled, size.height / 2f),
        )
    }
}

/** Hub 中可获得焦点的进度条；与全屏快捷调整复用同一套预览回调。 */
@Composable
private fun SeekableProgressBar(
    positionMs: Long,
    durationMs: Long,
    isSeeking: Boolean,
    focusRequester: FocusRequester,
    playFocusRequester: FocusRequester,
    seekAccelerator: PlaybackSeekAccelerator,
    onPreviewSeekBy: (Long) -> Unit,
    onConfirmSeek: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .focusRequester(focusRequester)
            .focusProperties { down = playFocusRequester }
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                handlePlaybackSeekKey(
                    event = event,
                    isSeeking = isSeeking,
                    seekAccelerator = seekAccelerator,
                    onPreviewSeekBy = onPreviewSeekBy,
                    onConfirmSeek = onConfirmSeek,
                )
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        TvProgressBar(positionMs, durationMs, focused)
    }
}

/** 未呼出 Hub 时显示的液态玻璃进度预览。 */
@Composable
private fun SeekPreviewOverlay(positionMs: Long, durationMs: Long) {
    GlassSurface(
        modifier = Modifier
            .widthIn(max = 580.dp)
            .fillMaxWidth(0.48f)
            .height(92.dp),
        shape = RoundedCornerShape(26.dp),
        tint = Color(0x68000000),
        borderAlpha = 0.46f,
        highlightAlpha = 0.52f,
        shadow = 28.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TvProgressBar(positionMs, durationMs, focused = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = formatTime(positionMs),
                    style = TvType.bodyMedium.copy(fontSize = 22.sp),
                )
                Spacer(Modifier.weight(1f))
                BasicText(
                    text = "${progressPercent(positionMs, durationMs)}%  ·  ${formatTime(durationMs)}",
                    style = TvType.caption.copy(color = TvColors.textSecondary, fontSize = 15.sp),
                )
            }
        }
    }
}

private fun progressPercent(positionMs: Long, durationMs: Long): Int =
    if (durationMs > 0L) ((positionMs * 100L) / durationMs).toInt().coerceIn(0, 100) else 0

private const val SEEK_CONFIRM_DELAY_MS = 1_200L
private const val TOUCH_SWIPE_THRESHOLD_PX = 36f
private const val TOUCH_SEEK_REPEAT_DELAY_MS = 250L

/**
 * 为触摸设备提供与遥控器一致的播放控制：轻触等效于确认，横滑等效于左右键。
 *
 * 识别到横滑后会持续重复同一方向的进度调整，直到手指抬起；步长交给
 * [PlaybackSeekAccelerator] 统一计算，因此触摸长按和遥控器长按遵循同一加速曲线。
 */
private fun Modifier.playbackTouchControls(
    enabled: Boolean,
    isSeeking: () -> Boolean,
    seekAccelerator: PlaybackSeekAccelerator,
    onPreviewSeekBy: (Long) -> Unit,
    onConfirmSeek: () -> Unit,
    onShowHub: () -> Unit,
): Modifier = if (!enabled) {
    this
} else {
    pointerInput(seekAccelerator) {
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown()
                var direction: PlaybackSeekDirection? = null
                var movedBeyondTapThreshold = false
                var repeatCount = 0
                var repeatJob: Job? = null

                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!movedBeyondTapThreshold) {
                                if (isSeeking()) onConfirmSeek() else onShowHub()
                            }
                            break
                        }

                        val horizontalDistance = change.position.x - down.position.x
                        val verticalDistance = change.position.y - down.position.y
                        if (kotlin.math.max(
                                kotlin.math.abs(horizontalDistance),
                                kotlin.math.abs(verticalDistance),
                            ) >= TOUCH_SWIPE_THRESHOLD_PX
                        ) {
                            movedBeyondTapThreshold = true
                        }
                        if (direction == null &&
                            kotlin.math.abs(horizontalDistance) >= TOUCH_SWIPE_THRESHOLD_PX &&
                            kotlin.math.abs(horizontalDistance) > kotlin.math.abs(verticalDistance)
                        ) {
                            val selectedDirection = if (horizontalDistance > 0f) {
                                PlaybackSeekDirection.FORWARD
                            } else {
                                PlaybackSeekDirection.BACKWARD
                            }
                            direction = selectedDirection
                            onPreviewSeekBy(
                                seekAccelerator.nextIncrement(
                                    direction = selectedDirection,
                                    eventTimeMs = SystemClock.uptimeMillis(),
                                    repeatCount = repeatCount++,
                                ),
                            )
                            repeatJob = launch {
                                delay(TOUCH_SEEK_REPEAT_DELAY_MS)
                                while (isActive) {
                                    onPreviewSeekBy(
                                        seekAccelerator.nextIncrement(
                                            direction = selectedDirection,
                                            eventTimeMs = SystemClock.uptimeMillis(),
                                            repeatCount = repeatCount++,
                                        ),
                                    )
                                    delay(TOUCH_SEEK_REPEAT_DELAY_MS)
                                }
                            }
                            change.consume()
                        }
                    }
                } finally {
                    repeatJob?.cancel()
                }
            }
        }
    }
}

