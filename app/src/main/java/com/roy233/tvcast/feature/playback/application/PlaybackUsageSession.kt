package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.core.diagnostics.CompletionBucket
import com.roy233.tvcast.core.diagnostics.ContentKind
import com.roy233.tvcast.core.diagnostics.PlaybackEndReason
import com.roy233.tvcast.core.diagnostics.QualityTelemetry
import com.roy233.tvcast.core.diagnostics.UsageEvent

/** 以实际播放时间计算匿名播放会话，并保证每个会话只有一次首播和结束事件。 */
class PlaybackUsageSession(
    private val telemetry: QualityTelemetry,
    private val nowMs: () -> Long,
) {
    private var contentKind: ContentKind? = null
    private var started = false
    private var playingSinceMs: Long? = null
    private var activeDurationMs = 0L

    fun open(isLive: Boolean) {
        finish(PlaybackEndReason.REPLACED, positionMs = 0L, durationMs = 0L)
        contentKind = if (isLive) ContentKind.LIVE else ContentKind.VOD
    }

    fun onPlaying() {
        val kind = contentKind ?: return
        if (!started) {
            started = true
            telemetry.log(UsageEvent.PlaybackStarted(kind))
        }
        if (playingSinceMs == null) playingSinceMs = nowMs()
    }

    fun onPaused() = accumulate()

    fun finish(reason: PlaybackEndReason, positionMs: Long, durationMs: Long) {
        val kind = contentKind ?: return
        accumulate()
        if (started) {
            telemetry.log(
                UsageEvent.PlaybackSessionEnded(
                    contentKind = kind,
                    endReason = reason,
                    activeDurationSeconds = activeDurationMs / 1_000L,
                    completionBucket = completionBucket(positionMs, durationMs),
                ),
            )
        }
        contentKind = null
        started = false
        playingSinceMs = null
        activeDurationMs = 0L
    }

    private fun accumulate() {
        val startedAt = playingSinceMs ?: return
        activeDurationMs += (nowMs() - startedAt).coerceAtLeast(0L)
        playingSinceMs = null
    }

    private fun completionBucket(positionMs: Long, durationMs: Long): CompletionBucket {
        if (durationMs <= 0L) return CompletionBucket.UNKNOWN
        val percent = (positionMs.coerceAtLeast(0L) * 100L / durationMs).coerceAtMost(100L)
        return when {
            percent < 25 -> CompletionBucket.UNDER_25
            percent < 75 -> CompletionBucket.FROM_25_TO_74
            percent < 100 -> CompletionBucket.FROM_75_TO_99
            else -> CompletionBucket.COMPLETE
        }
    }
}
