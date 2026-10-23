package com.roy233.tvcast.core.danmaku

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.data.bili.danmaku.DanmakuItem
import com.roy233.tvcast.data.bili.danmaku.DanmakuRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

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
    private var bufferingJob: Job? = null
    private var loadingIndex = -1
    private var sessionId = 0L
    private var lastPlayerPositionMs = 0L
    private var userPaused = false
    private var buffering = false
    private var bufferingPaused = false
    private var seekInProgress = false
    private var seekTargetMs = 0L
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
        sessionId++
        loadingIndex = -1
        lastPlayerPositionMs = positionMs.coerceAtLeast(0L)
        userPaused = false
        buffering = false
        bufferingPaused = false
        seekInProgress = false
        cancelBuffering()
        engine.clear()
        clock.reset()
        clock.durationMs = durationMs.coerceAtLeast(0L)
        // 播放器尚未确认 Playing 前，弹幕必须停在起播位置；否则网络起播
        // 期间弹幕会先于视频推进，随后看起来像从半屏提前消失。
        clock.pause(lastPlayerPositionMs)
        // DFM 必须在视频实际内容区完成布局后才可 prepare。这里不能抢先初始化，
        // 否则它会按全屏宽度计算滚动距离，随后内容区收窄会造成弹幕中途出屏。
        loadView(lastPlayerPositionMs, sessionId)
    }

    /** 关闭会话并清空屏幕。 */
    fun close() {
        closed = true
        viewJob?.cancel()
        viewJob = null
        segmentJob?.cancel()
        segmentJob = null
        cancelBuffering()
        loadingIndex = -1
        userPaused = false
        buffering = false
        bufferingPaused = false
        seekInProgress = false
        currentIndex = -1
        currentItems = emptyList()
        engine.clear()
        repository.clear()
        clock.reset()
    }

    /** 播放器开始/恢复播放。 */
    fun onPlaying(positionMs: Long) {
        if (closed) return
        cancelBuffering()
        buffering = false
        bufferingPaused = false
        userPaused = false
        val position = positionMs.coerceAtLeast(0L)
        lastPlayerPositionMs = position
        if (seekInProgress && !isSeekPosition(position)) return
        seekInProgress = false
        clock.rebase(position)
        engine.resume()
    }

    /** 播放器暂停。 */
    fun onPaused(positionMs: Long) {
        if (closed) return
        cancelBuffering()
        buffering = false
        bufferingPaused = false
        userPaused = true
        seekInProgress = false
        lastPlayerPositionMs = positionMs.coerceAtLeast(0L)
        clock.pause(lastPlayerPositionMs)
        engine.pause()
    }

    /**
     * 播放器缓冲。
     *
     * VLC 起播和网络抖动会短暂交替上报缓冲状态。只有缓冲持续一段时间才冻结
     * 弹幕，避免每个瞬态事件都让 DFM pause/resume 一次。
     */
    fun onBuffering(buffering: Boolean, positionMs: Long) {
        if (closed || userPaused) return
        lastPlayerPositionMs = positionMs.coerceAtLeast(0L)
        if (buffering) {
            if (this.buffering) return
            this.buffering = true
            bufferingJob = scope.launch {
                delay(BUFFERING_PAUSE_DEBOUNCE_MS)
                if (closed || userPaused || !this@DanmakuController.buffering) return@launch
                bufferingPaused = true
                // 缓冲期间应以播放器最后确认的位置冻结，不能使用预测时钟位置。
                clock.pause(lastPlayerPositionMs)
                engine.pause()
            }
        } else {
            this.buffering = false
            cancelBuffering()
            if (bufferingPaused) {
                bufferingPaused = false
                clock.rebase(lastPlayerPositionMs)
                engine.resume()
            }
        }
    }

    /**
     * 播放器时间采样；由 ticker 或 TimeChanged 调用。
     */
    fun onPlayerTime(positionMs: Long) {
        if (closed) return
        lastPlayerPositionMs = positionMs.coerceAtLeast(0L)
        if (seekInProgress) {
            if (!isSeekPosition(lastPlayerPositionMs)) return
            seekInProgress = false
            if (!userPaused && !bufferingPaused) {
                clock.rebase(lastPlayerPositionMs)
                engine.resume()
            }
        } else if (!clock.isFrozen) {
            val hardResync = clock.onPlayerSample(lastPlayerPositionMs)
            if (hardResync) {
                LogHub.d(TAG, "clock hard resync at=${lastPlayerPositionMs}ms")
            }
        }
        val index = indexOf(lastPlayerPositionMs)
        when {
            currentIndex < 0 -> loadSegment(index, replace = true)
            index == currentIndex + 1 -> loadSegment(index, replace = false)
            index == currentIndex -> Unit
            else -> LogHub.d(
                TAG,
                "ignore unexpected segment jump current=$currentIndex next=$index; awaiting seek",
            )
        }
    }

    /** 将弹幕媒体时钟切换到与播放器一致的速度，保持当前时间连续。 */
    fun onPlaybackRateChanged(rate: Float) {
        if (closed) return
        clock.setRate(rate)
    }

    /** 用户 seek 到指定位置。 */
    fun onSeek(positionMs: Long) {
        if (closed) return
        val target = positionMs.coerceAtLeast(0L)
        lastPlayerPositionMs = target
        seekTargetMs = target
        seekInProgress = true
        buffering = false
        bufferingPaused = false
        cancelBuffering()
        // 在收到目标位置的播放器采样前保持冻结，避免旧位置和目标位置交替绘制。
        clock.pause(target)
        engine.pause()
        engine.seekTo(target)
        loadSegment(indexOf(target), replace = true, force = true)
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

    private fun loadView(positionMs: Long, requestSession: Long) {
        viewJob?.cancel()
        viewJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.view(cid) }
            }
            val view = result.getOrElse { failure ->
                LogHub.w(TAG, "danmaku view failed cid=$cid: ${failure.message}")
                return@launch
            }
            if (closed || requestSession != sessionId) return@launch
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

    private fun loadSegment(index: Int, replace: Boolean, force: Boolean = false) {
        if (closed) return
        if (index <= 0) return
        if (totalSegments > 0 && index > totalSegments) return
        if (!replace && index == currentIndex) return
        if (!force && index == loadingIndex && segmentJob?.isActive == true) return

        val requestedIndex = index
        val requestSession = sessionId
        segmentJob?.cancel()
        loadingIndex = requestedIndex
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
            if (closed || requestSession != sessionId || requestedIndex != loadingIndex) return@launch
            if (!force && requestedIndex == currentIndex) return@launch
            currentIndex = requestedIndex
            loadingIndex = -1
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

    private fun isSeekPosition(positionMs: Long): Boolean {
        return abs(positionMs - seekTargetMs) <= SEEK_CONFIRM_TOLERANCE_MS
    }

    private fun cancelBuffering() {
        bufferingJob?.cancel()
        bufferingJob = null
    }

    private companion object {
        const val TAG = "DanmakuController"
        const val DEFAULT_SEGMENT_MS = 360_000L
        const val CLOSED_STATE = 1
        const val BUFFERING_PAUSE_DEBOUNCE_MS = 750L
        const val SEEK_CONFIRM_TOLERANCE_MS = 750L
    }
}
