package com.roy233.tvcast.feature.playback.domain

import com.roy233.tvcast.core.playback.PlaybackEvent

/** 比较连续的解码器计数窗口；失效样本不会跨暂停或切换累积。 */
class VodFrameDropMonitor {
    data class Window(
        val frames: Long,
        val dropped: Long,
        val severe: Boolean,
        val sustained: Boolean,
    )

    private var start: PlaybackEvent.VideoFrameSample? = null
    private var last: PlaybackEvent.VideoFrameSample? = null
    private var severeWindows = 0

    fun reset() {
        start = null
        last = null
        severeWindows = 0
    }

    fun observe(sample: PlaybackEvent.VideoFrameSample): Window? {
        if (!sample.valid) {
            reset()
            return null
        }
        val previous = last
        if (
            previous != null &&
                (sample.timestampMs <= previous.timestampMs ||
                    sample.rendered < previous.rendered ||
                    sample.dropped < previous.dropped ||
                    sample.timestampMs - previous.timestampMs > MAX_SAMPLE_GAP_MS)
        ) {
            reset()
        }
        last = sample
        val initial =
            start
                ?: run {
                    start = sample
                    return null
                }
        if (sample.timestampMs - initial.timestampMs < WINDOW_MS) return null
        val rendered = sample.rendered.toLong() - initial.rendered
        val dropped = sample.dropped.toLong() - initial.dropped
        val frames = rendered + dropped
        val severe = frames >= MIN_FRAMES && dropped * 100 >= frames * DROP_PERCENT
        severeWindows = if (severe) severeWindows + 1 else 0
        start = sample
        return Window(frames, dropped, severe, severeWindows >= REQUIRED_WINDOWS)
    }

    private companion object {
        const val WINDOW_MS = 10_000L
        const val MAX_SAMPLE_GAP_MS = 2_000L
        const val MIN_FRAMES = 100L
        const val DROP_PERCENT = 10L
        const val REQUIRED_WINDOWS = 2
    }
}
