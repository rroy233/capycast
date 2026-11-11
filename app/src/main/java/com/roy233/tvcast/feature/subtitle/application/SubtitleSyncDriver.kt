package com.roy233.tvcast.feature.subtitle.application

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 字幕同步驱动器。
 *
 * 以固定间隔读取播放器位置并交给 [SubtitleController]；播放暂停时位置不变，因此不会产生额外界面更新。
 */
class SubtitleSyncDriver(
    private val scope: CoroutineScope,
    private val positionMs: () -> Long,
    private val onPosition: (Long) -> Unit,
    private val intervalMs: Long = SYNC_INTERVAL_MS,
) {
    private var job: Job? = null

    /** 启动周期采样；重复调用不会产生第二个任务。 */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                onPosition(positionMs())
            }
        }
    }

    /** 停止周期采样。 */
    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** 播放中的采样间隔，单位毫秒。 */
        const val SYNC_INTERVAL_MS = 100L
    }
}
