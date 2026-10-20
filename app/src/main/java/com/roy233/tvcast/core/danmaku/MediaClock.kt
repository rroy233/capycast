package com.roy233.tvcast.core.danmaku

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
     * 喂入一次播放器采样。
     *
     * 小误差忽略，避免弹幕随采样抖动；大误差视为 seek/漂移，重新锚定。
     *
     * @return true 表示发生了硬重锚，调用方应让 DFM 清屏重排。
     */
    fun onPlayerSample(mediaMs: Long): Boolean {
        if (frozen) {
            rebase(mediaMs)
            return true
        }
        val predicted = nowMs()
        if (abs(mediaMs - predicted) > RESYNC_THRESHOLD_MS) {
            rebase(mediaMs)
            return true
        }
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
        /** 超过该误差就认为需要重新锚定，单位毫秒。 */
        const val RESYNC_THRESHOLD_MS = 150L
    }
}
