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

internal const val SEEK_CONFIRM_DELAY_MS = 1_200L
internal const val TOUCH_SWIPE_THRESHOLD_PX = 36f
internal const val TOUCH_SEEK_REPEAT_DELAY_MS = 250L

/**
 * 为触摸设备提供与遥控器一致的播放控制：轻触唤出/确认 Hub，横滑等效于左右键。
 *
 * 识别到横滑后会持续重复同一方向的进度调整，直到手指抬起；步长交给
 * [PlaybackSeekAccelerator] 统一计算，因此触摸长按和遥控器长按遵循同一加速曲线。
 * 直播等不支持定位的内容传入 [seekEnabled] = false，此时横滑不触发进度调整，
 * 但轻触仍会唤出 Hub。
 */
internal fun Modifier.playbackTouchControls(
    enabled: Boolean,
    seekEnabled: Boolean,
    isSeeking: () -> Boolean,
    seekAccelerator: PlaybackSeekAccelerator,
    onPreviewSeekBy: (Long) -> Unit,
    onConfirmSeek: () -> Unit,
    onShowHub: () -> Unit,
): Modifier = if (!enabled) {
    this
} else {
    pointerInput(seekAccelerator, seekEnabled) {
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
                        if (seekEnabled &&
                            direction == null &&
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
