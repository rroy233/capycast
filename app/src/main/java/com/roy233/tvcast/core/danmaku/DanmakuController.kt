package com.roy233.tvcast.core.danmaku

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.data.bili.danmaku.DanmakuItem
import com.roy233.tvcast.data.bili.danmaku.DanmakuRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 弹幕会话控制器。
 *
 * 负责把播放器的播放/暂停/seek/时间采样接到 [MediaClock] 和 [DfmDanmakuEngine]，
 * 并按分段加载弹幕。所有公开方法都应在主线程调用。
 */
class DanmakuController(
    private val scope: CoroutineScope,
    private val engine: DfmDanmakuEngine,
    private val clock: MediaClock = MediaClock(),
    private val repository: DanmakuRepository = DanmakuRepository(),
) {

    private var aid = 0L
    private var cid = 0L
    private var segmentSizeMs = DEFAULT_SEGMENT_MS
    private var totalSegments = 0
    private var currentIndex = -1
    private var currentItems: List<DanmakuItem> = emptyList()
    private var settings = DanmakuSettings()
    private var viewJob: Job? = null
    private var segmentJob: Job? = null
    private var closed = false

    /** 设置视频内容区高度，用于字号换算。 */
    fun setContentHeight(heightPx: Int) {
        engine.setContentHeight(heightPx)
    }

    /** 视图完成布局后调用，初始化 DFM。 */
    fun prepare() {
        engine.prepare()
    }

    /**
     * 打开一个视频的弹幕会话。
     *
     * @param aid 稿件 avid。
     * @param cid 视频 cid。
     * @param durationMs 视频总时长。
     * @param positionMs 当前播放位置。
     */
    fun open(aid: Long, cid: Long, durationMs: Long, positionMs: Long) {
        this.aid = aid
        this.cid = cid
        closed = false
        currentIndex = -1
        currentItems = emptyList()
        totalSegments = 0
        segmentSizeMs = DEFAULT_SEGMENT_MS
        engine.clear()
        clock.reset()
        clock.durationMs = durationMs.coerceAtLeast(0L)
        clock.start(positionMs.coerceAtLeast(0L))
        engine.prepare()
        loadView(positionMs)
    }

    /** 关闭会话并清空屏幕。 */
    fun close() {
        closed = true
        viewJob?.cancel()
        viewJob = null
        segmentJob?.cancel()
        segmentJob = null
        currentIndex = -1
        currentItems = emptyList()
        engine.clear()
        repository.clear()
        clock.reset()
    }

    /** 播放器开始/恢复播放。 */
    fun onPlaying(positionMs: Long) {
        if (closed) return
        clock.rebase(positionMs)
        engine.resume()
    }

    /** 播放器暂停。 */
    fun onPaused(positionMs: Long) {
        if (closed) return
        clock.pause(positionMs)
        engine.pause()
    }

    /** 播放器缓冲。 */
    fun onBuffering(buffering: Boolean, positionMs: Long) {
        if (closed) return
        if (buffering) {
            clock.pause(positionMs)
            engine.pause()
        } else {
            onPlaying(positionMs)
        }
    }

    /**
     * 播放器时间采样；由 ticker 或 TimeChanged 调用。
     */
    fun onPlayerTime(positionMs: Long) {
        if (closed || clock.isFrozen) return
        val predicted = clock.nowMs()
        clock.onPlayerSample(positionMs)
        val jumped = kotlin.math.abs(positionMs - predicted) > 1_000L
        if (jumped) {
            engine.seekTo(positionMs)
            loadSegment(indexOf(positionMs), replace = true)
            return
        }
        val index = indexOf(positionMs)
        if (index != currentIndex) {
            loadSegment(index, replace = index < currentIndex || index > currentIndex + 1)
        }
    }

    /** 用户 seek 到指定位置。 */
    fun onSeek(positionMs: Long) {
        if (closed) return
        clock.seek(positionMs)
        engine.seekTo(positionMs)
        loadSegment(indexOf(positionMs), replace = true)
    }

    /** 应用显示设置；AI 档位变化时重建当前分段。 */
    fun applySettings(newSettings: DanmakuSettings) {
        val aiChanged = newSettings.aiShield != settings.aiShield
        val enabledChanged = newSettings.enabled != settings.enabled
        settings = newSettings
        engine.applySettings(newSettings)
        if (aiChanged || enabledChanged) {
            engine.replaceSegment(currentItems, settings.aiShield)
        }
    }

    /** 释放 DFM 资源；Activity 销毁时调用。 */
    fun release() {
        close()
        engine.release()
    }

    private fun loadView(positionMs: Long) {
        viewJob?.cancel()
        viewJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.view(cid) }
            }
            val view = result.getOrElse { failure ->
                LogHub.w(TAG, "danmaku view failed cid=$cid: ${failure.message}")
                return@launch
            }
            if (closed) return@launch
            if (view.state == CLOSED_STATE) {
                LogHub.i(TAG, "danmaku closed by uploader cid=$cid")
                return@launch
            }
            view.segConfig?.let { config ->
                if (config.pageSizeMs > 0) segmentSizeMs = config.pageSizeMs
                totalSegments = config.total
            }
            loadSegment(indexOf(positionMs), replace = true)
        }
    }

    private fun loadSegment(index: Int, replace: Boolean) {
        if (closed) return
        if (index <= 0) return
        if (totalSegments > 0 && index > totalSegments) return
        if (!replace && index == currentIndex) return

        val requestedIndex = index
        segmentJob?.cancel()
        segmentJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.segment(aid, cid, requestedIndex) }
            }
            val segment = result.getOrElse { failure ->
                LogHub.w(
                    TAG,
                    "danmaku segment failed cid=$cid index=$requestedIndex: ${failure.message}",
                )
                return@launch
            }
            if (closed || requestedIndex == currentIndex) return@launch
            currentIndex = requestedIndex
            currentItems = segment.items
            if (replace) {
                engine.replaceSegment(segment.items, settings.aiShield)
            } else {
                engine.addSegment(segment.items, settings.aiShield)
            }
        }
    }

    private fun indexOf(positionMs: Long): Int {
        return ((positionMs.coerceAtLeast(0L)) / segmentSizeMs).toInt() + 1
    }

    private companion object {
        const val TAG = "DanmakuController"
        const val DEFAULT_SEGMENT_MS = 360_000L
        const val CLOSED_STATE = 1
    }
}
