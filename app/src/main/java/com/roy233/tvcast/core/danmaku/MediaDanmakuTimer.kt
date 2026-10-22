package com.roy233.tvcast.core.danmaku

import master.flame.danmaku.danmaku.model.DanmakuTimer

/**
 * 把 DFM 的渲染时钟接到 [MediaClock] 上。
 *
 * DFM 有两条更新路径：
 * - 正常渲染调用 [add]；
 * - 等待/首帧/seek 时调用 [update]。
 *
 * 两条都必须写入媒体时间，否则墙钟会从 [update] 泄漏进来，导致渲染时间
 * 跑到播放器前面（实测 t=4.5s 而播放位置只有 1.6s）。
 *
 * @param clock 媒体时钟。
 */
class MediaDanmakuTimer(
    private val clock: MediaClock,
) : DanmakuTimer() {

    override fun add(mills: Long): Long {
        return super.update(clock.nowMs())
    }

    override fun update(currMillisecond: Long): Long {
        return super.update(clock.nowMs())
    }
}
