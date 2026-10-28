package com.roy233.tvcast.feature.danmaku.domain

import kotlin.math.abs

/**
 * 以播放器时间为锚点的媒体时钟。
 *
 * 位置是「锚点媒体时间 + 经过的墙钟时间 × 倍速」的纯函数，不做逐帧累加，
 * 因此暂停、seek、倍速切换都不会累积误差。
 *
 * @param nanoTime 单调时钟；测试可注入假实现。
 */
class MediaClock(
    private val nanoTime: () -> Long = { System.nanoTime() },
) {

    private var anchorMediaMs = 0L
    private var anchorNanos = 0L
    private var rate = 1f
    private var frozen = true

    /** 视频总时长；0 表示未知。 */
    var durationMs: Long = 0L

    /** 当前是否处于暂停/冻结状态。 */
    val isFrozen: Boolean
        get() = frozen

    /** 当前媒体时间，单位毫秒。 */
    fun nowMs(): Long {
        if (frozen) return anchorMediaMs
        val elapsedMs = (nanoTime() - anchorNanos) / 1_000_000.0
        return clamp((anchorMediaMs + elapsedMs * rate).toLong())
    }

    /** 从指定位置开始播放。 */
    fun start(mediaMs: Long) {
        rebase(mediaMs)
    }

    /** 以播放器位置重新锚定，并进入播放状态。 */
    fun rebase(mediaMs: Long) {
        anchorMediaMs = clamp(mediaMs)
        anchorNanos = nanoTime()
        frozen = false
    }

    /** 冻结在指定位置。 */
    fun pause(mediaMs: Long) {
        anchorMediaMs = clamp(mediaMs)
        frozen = true
    }

    /** 跳转到指定位置；保持当前播放/暂停状态。 */
    fun seek(mediaMs: Long) {
        anchorMediaMs = clamp(mediaMs)
        anchorNanos = nanoTime()
    }

    /** 更新倍速，保持当前媒体时间连续。 */
    fun setRate(newRate: Float) {
        anchorMediaMs = nowMs()
        anchorNanos = nanoTime()
        rate = newRate.coerceIn(0.25f, 4f)
    }

    /**
     * 喂入播放器的低频进度采样。
     *
     * 渲染时钟本身由单调时钟推进。播放器进度只负责抑制长期漂移：小误差忽略，
     * 中等误差逐步收敛，只有明显断层才重锚。调用方不能把返回值当作 seek，
     * 因为正常起播和网络波动也可能带来较大的采样差。
     *
     * @return true 表示发生硬重锚，便于记录诊断信息。
     */
    fun onPlayerSample(mediaMs: Long): Boolean {
        if (frozen) {
            return false
        }
        val predicted = nowMs()
        val error = mediaMs - predicted
        val absError = abs(error)
        if (absError <= IGNORE_THRESHOLD_MS) return false
        if (absError >= HARD_RESYNC_THRESHOLD_MS) {
            rebase(mediaMs)
            return true
        }
        // 以当前预测位置为基准重设锚点，保持时钟单调前进；若从旧锚点加误差，
        // 每次采样都会把已走过的墙钟时间丢掉，表现为反向抖动。
        anchorMediaMs = clamp(predicted + (error * SLEW_FACTOR).toLong())
        anchorNanos = nanoTime()
        return false
    }

    /** 重置为初始状态。 */
    fun reset() {
        anchorMediaMs = 0L
        anchorNanos = nanoTime()
        rate = 1f
        frozen = true
        durationMs = 0L
    }

    private fun clamp(value: Long): Long {
        return if (durationMs > 0) value.coerceIn(0L, durationMs) else value.coerceAtLeast(0L)
    }

    companion object {
        /** 小于该误差的进度采样波动不校正，单位毫秒。 */
        const val IGNORE_THRESHOLD_MS = 80L

        /** 只有超过该误差才立即重锚，单位毫秒。 */
        const val HARD_RESYNC_THRESHOLD_MS = 1_500L

        /** 中等误差每次采样修正的比例。 */
        const val SLEW_FACTOR = 0.05f
    }
}
