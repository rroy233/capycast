package com.roy233.tvcast.ui.tv.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** 遥控器方向键对应的进度调整方向。 */
internal enum class PlaybackSeekDirection { BACKWARD, FORWARD }

/**
 * 根据同一次长按持续时间计算进度调整步长。
 *
 * 初始步长为 10 秒，之后每秒提升为前一档的 1.5 倍，最大限制为两分钟。
 * 该节奏与 SmartTube 的长按加速逻辑一致，同时能避免超长视频中一次跳转过远。
 */
internal class PlaybackSeekAccelerator(
    private val baseIncrementMs: Long = BASE_INCREMENT_MS,
    private val accelerationPeriodMs: Long = ACCELERATION_PERIOD_MS,
    private val accelerationFactor: Double = ACCELERATION_FACTOR,
    private val maxIncrementMs: Long = MAX_INCREMENT_MS,
) {
    private var activeDirection: PlaybackSeekDirection? = null
    private var startedAtMs = 0L

    /** 返回本次按键应调整的有符号毫秒数。 */
    fun nextIncrement(
        direction: PlaybackSeekDirection,
        eventTimeMs: Long,
        repeatCount: Int,
    ): Long {
        if (repeatCount == 0 || activeDirection != direction || eventTimeMs < startedAtMs) {
            activeDirection = direction
            startedAtMs = eventTimeMs
        }
        val elapsedMs = (eventTimeMs - startedAtMs).coerceAtLeast(0L)
        val accelerationLevel = (elapsedMs / accelerationPeriodMs).toInt()
        val increment = (baseIncrementMs * accelerationFactor.pow(accelerationLevel))
            .toLong()
            .coerceAtMost(maxIncrementMs)
        return if (direction == PlaybackSeekDirection.FORWARD) increment else -increment
    }

    /** 在确认、超时或离开进度调整后清除长按状态。 */
    fun reset() {
        activeDirection = null
        startedAtMs = 0L
    }

    private fun Double.pow(exponent: Int): Double {
        var result = 1.0
        repeat(exponent) { result *= this }
        return result
    }

    private companion object {
        const val BASE_INCREMENT_MS = 10_000L
        const val ACCELERATION_PERIOD_MS = 1_000L
        const val ACCELERATION_FACTOR = 1.5
        const val MAX_INCREMENT_MS = 120_000L
    }
}

/**
 * 处理进度调整会话中的遥控器按键。
 *
 * 全屏快捷调整与 Hub 进度条共同使用此入口，保证二者的加速、预览和确认语义一致。
 */
internal fun handlePlaybackSeekKey(
    event: KeyEvent,
    isSeeking: Boolean,
    seekAccelerator: PlaybackSeekAccelerator,
    onPreviewSeekBy: (Long) -> Unit,
    onConfirmSeek: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    when (event.key) {
        Key.DirectionLeft -> {
            onPreviewSeekBy(
                seekAccelerator.nextIncrement(
                    PlaybackSeekDirection.BACKWARD,
                    event.nativeKeyEvent.eventTime,
                    event.nativeKeyEvent.repeatCount,
                ),
            )
            return true
        }

        Key.DirectionRight -> {
            onPreviewSeekBy(
                seekAccelerator.nextIncrement(
                    PlaybackSeekDirection.FORWARD,
                    event.nativeKeyEvent.eventTime,
                    event.nativeKeyEvent.repeatCount,
                ),
            )
            return true
        }

        Key.DirectionCenter,
        Key.Enter -> if (isSeeking) {
            onConfirmSeek()
            seekAccelerator.reset()
            return true
        }

        else -> Unit
    }
    return false
}
