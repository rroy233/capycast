package com.roy233.tvcast.ui.tv.player

import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.designsystem.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

