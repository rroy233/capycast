package com.roy233.tvcast.ui.tv.player

import android.graphics.Bitmap
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.model.AudioOption
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
internal fun PlayingScreen(state: TvUiState, actions: TvActions, enabled: Boolean) {
    val playFocus = remember { FocusRequester() }
    val videoFocus = remember { FocusRequester() }
    val modal = state.panel != null || state.error != null || state.exitConfirm

    LaunchedEffect(state.hudVisible, modal, enabled) {
        if (!enabled || modal) return@LaunchedEffect
        if (state.hudVisible) {
            delay(80)
            runCatching { playFocus.requestFocus() }
        } else {
            runCatching { videoFocus.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(videoFocus)
                .focusable(enabled && !state.hudVisible)
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
                            TvProgressBar(
                                positionMs = state.positionMs,
                                durationMs = state.durationMs,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicText(
                                    text = "${formatTime(state.positionMs)}  /  ${formatTime(state.durationMs)}",
                                    style = TvType.caption.copy(
                                        color = TvColors.textSecondary,
                                        fontSize = 14.sp,
                                    ),
                                )
                                Spacer(Modifier.weight(1f))
                                BasicText(
                                    text = state.audioLabel,
                                    style = TvType.caption.copy(
                                        color = TvColors.textTertiary,
                                        fontSize = 14.sp,
                                    ),
                                    maxLines = 1,
                                )
                            }
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                            ) {
                                PrimaryPlayButton(
                                    isPlaying = state.isPlaying,
                                    onClick = actions.onPlayPause,
                                    size = 64.dp,
                                    focusRequester = playFocus,
                                )
                                GlassIconButton(
                                    icon = Icons.Rounded.Replay10,
                                    contentDescription = "快退 10 秒",
                                    onClick = { actions.onSeekBy(-10_000L) },
                                    enabled = enabled,
                                    size = 56.dp,
                                )
                                GlassIconButton(
                                    icon = Icons.Rounded.Forward10,
                                    contentDescription = "快进 10 秒",
                                    onClick = { actions.onSeekBy(10_000L) },
                                    enabled = enabled,
                                    size = 56.dp,
                                )
                                GlassIconButton(
                                    icon = Icons.Rounded.HighQuality,
                                    contentDescription = "清晰度 ${state.qualityLabel}",
                                    onClick = { actions.onOpenPanel(PanelKind.QUALITY) },
                                    enabled = enabled,
                                    size = 56.dp,
                                )
                                GlassIconButton(
                                    icon = Icons.Rounded.Audiotrack,
                                    contentDescription = "音轨 ${state.audioLabel}",
                                    onClick = { actions.onOpenPanel(PanelKind.AUDIO) },
                                    enabled = enabled,
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
}

/** 绘制当前播放位置与媒体时长对应的进度条。 */
@Composable
private fun TvProgressBar(positionMs: Long, durationMs: Long) {
    val progress = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
    ) {
        val trackHeight = 4.dp.toPx()
        val y = (size.height - trackHeight) / 2f
        drawRoundRect(
            color = Color.White.copy(alpha = 0.18f),
            topLeft = Offset(0f, y),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
        )
        val filled = size.width * progress
        drawRoundRect(
            color = Color.White.copy(alpha = 0.92f),
            topLeft = Offset(0f, y),
            size = Size(filled, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
        )
        drawCircle(
            color = Color.White,
            radius = 4.dp.toPx(),
            center = Offset(filled, size.height / 2f),
        )
    }
}

