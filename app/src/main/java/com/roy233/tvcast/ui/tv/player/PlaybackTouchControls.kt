package com.roy233.tvcast.ui.tv.player

import android.os.SystemClock
import com.roy233.tvcast.ui.designsystem.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
