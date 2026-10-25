package com.roy233.tvcast.ui.tv.player

import android.os.SystemClock
import android.graphics.Bitmap
import com.roy233.tvcast.R
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.ui.model.PlaybackSpeed
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

/** 绘制当前播放位置与媒体时长对应的进度条。 */
@Composable
internal fun TvProgressBar(positionMs: Long, durationMs: Long, focused: Boolean) {
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
internal fun SeekableProgressBar(
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
internal fun SeekPreviewOverlay(positionMs: Long, durationMs: Long) {
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

internal fun progressPercent(positionMs: Long, durationMs: Long): Int =
    if (durationMs > 0L) ((positionMs * 100L) / durationMs).toInt().coerceIn(0, 100) else 0

