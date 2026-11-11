package com.roy233.tvcast.feature.danmaku.application

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.feature.danmaku.domain.DanmakuItem
import com.roy233.tvcast.feature.danmaku.domain.DanmakuRenderer
import com.roy233.tvcast.feature.danmaku.domain.DanmakuRepository
import com.roy233.tvcast.feature.danmaku.domain.DanmakuSegmentKey
import com.roy233.tvcast.feature.danmaku.domain.DanmakuSettings
import com.roy233.tvcast.feature.danmaku.domain.MediaClock
import com.roy233.tvcast.feature.danmaku.domain.VodDanmakuContent
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 弹幕会话控制器。
 *
 * 负责把播放器的播放/暂停/seek/时间采样接到 [MediaClock] 和 [DanmakuRenderer]， 并按分段加载弹幕。所有公开方法都应在主线程调用。
 */
class DanmakuController(
    private val scope: CoroutineScope,
    private val renderer: DanmakuRenderer,
    private val clock: MediaClock = MediaClock(),
    private val repository: DanmakuRepository,
) {

    /** 一次点播弹幕会话，持有其代次、内容身份与加载作业。 */
    private data class VodDanmakuSession(
        val generation: Long,
        val content: VodDanmakuContent,
        var viewJob: Job? = null,
        var segmentJob: Job? = null,
    )

    private var activeSession: VodDanmakuSession? = null
    private var lastGeneration = 0L
    private var segmentSizeMs = DEFAULT_SEGMENT_MS
    private var totalSegments = 0
    private var currentIndex = -1
    private var currentItems: List<DanmakuItem> = emptyList()
    private var settings = DanmakuSettings()
    private var bufferingJob: Job? = null
    private var loadingIndex = -1
    private var lastPlayerPositionMs = 0L
    private var userPaused = false
    private var buffering = false
    private var bufferingPaused = false
    private var seekInProgress = false
    private var seekTargetMs = 0L
    /** 播放器采样计数，用于低频输出渲染自检。 */
    private var tickCount = 0L

    /** 设置视频内容区高度，用于字号换算。 */
    fun setContentHeight(heightPx: Int) {
        renderer.setContentHeight(heightPx)
    }

    /** 视图完成布局后调用，初始化 DFM。 */
    fun prepare() {
        renderer.prepare()
    }

    /**
     * 替换为新的弹幕会话：取消旧会话的作业，并用新的代次与内容身份重新加载。
     *
     * @param content 数据身份。
     * @param durationMs 视频总时长。
     * @param positionMs 当前播放位置。
     */
    fun open(content: VodDanmakuContent, durationMs: Long, positionMs: Long) {
        retireSession()
        val session = VodDanmakuSession(generation = ++lastGeneration, content = content)
        activeSession = session
        currentIndex = -1
        currentItems = emptyList()
        totalSegments = 0
        segmentSizeMs = DEFAULT_SEGMENT_MS
        loadingIndex = -1
        lastPlayerPositionMs = positionMs.coerceAtLeast(0L)
        userPaused = false
        buffering = false
        bufferingPaused = false
        seekInProgress = false
        cancelBuffering()
        renderer.clear()
        clock.reset()
        clock.durationMs = durationMs.coerceAtLeast(0L)
        // 播放器尚未确认 Playing 前，弹幕必须停在起播位置；否则网络起播
        // 期间弹幕会先于视频推进，随后看起来像从半屏提前消失。
        clock.pause(lastPlayerPositionMs)
        // DFM 的时间轴自 prepare 起按墙钟推进；换会话不重置就会领先媒体时钟数秒，
        // 之后提交的弹幕会按错误时间轴判过期，表现为整段不显示。
        renderer.seekTo(lastPlayerPositionMs)
        LogHub.i(
            TAG,
            "session open ${sessionLabel(session)} position=${lastPlayerPositionMs}ms " +
                describe(),
        )
        publishDiagnostics()
        // DFM 必须在视频实际内容区完成布局后才可 prepare。这里不能抢先初始化，
        // 否则它会按全屏宽度计算滚动距离，随后内容区收窄会造成弹幕中途出屏。
        loadView(session, lastPlayerPositionMs)
    }

    /** 关闭会话并清空屏幕。 */
    fun close() {
        val session = activeSession
        retireSession()
        if (session != null) LogHub.i(TAG, "session close ${sessionLabel(session)}")
        cancelBuffering()
        loadingIndex = -1
        userPaused = false
        buffering = false
        bufferingPaused = false
        seekInProgress = false
        currentIndex = -1
        currentItems = emptyList()
        renderer.clear()
        clock.reset()
    }

    /** 播放器开始/恢复播放。 */
    fun onPlaying(positionMs: Long) {
        if (activeSession == null) return
        cancelBuffering()
        buffering = false
        bufferingPaused = false
        userPaused = false
        val position = positionMs.coerceAtLeast(0L)
        lastPlayerPositionMs = position
        if (seekInProgress && !isSeekPosition(position)) return
        seekInProgress = false
        clock.rebase(position)
        renderer.resume()
        LogHub.d(TAG, "playing position=$position " + describe())
        publishDiagnostics()
    }

    /** 播放器暂停。 */
    fun onPaused(positionMs: Long) {
        if (activeSession == null) return
        cancelBuffering()
        buffering = false
        bufferingPaused = false
        userPaused = true
        seekInProgress = false
        lastPlayerPositionMs = positionMs.coerceAtLeast(0L)
        clock.pause(lastPlayerPositionMs)
        renderer.pause()
        LogHub.d(TAG, "paused position=$lastPlayerPositionMs " + describe())
        publishDiagnostics()
    }

    /**
     * 播放器缓冲。
     *
     * 起播和网络抖动会短暂交替上报缓冲状态。只有缓冲持续一段时间才冻结弹幕，避免每个瞬态事件都让 DFM pause/resume 一次。
     */
    fun onBuffering(buffering: Boolean, positionMs: Long) {
        if (activeSession == null || userPaused) return
        lastPlayerPositionMs = positionMs.coerceAtLeast(0L)
        if (buffering) {
            if (this.buffering) return
            this.buffering = true
            LogHub.d(TAG, "buffering start position=$lastPlayerPositionMs " + describe())
            bufferingJob = scope.launch {
                delay(BUFFERING_PAUSE_DEBOUNCE_MS)
                if (activeSession == null || userPaused || !this@DanmakuController.buffering)
                    return@launch
                bufferingPaused = true
                LogHub.d(TAG, "buffering freeze position=$lastPlayerPositionMs " + describe())
                publishDiagnostics()
                // 缓冲期间应以播放器最后确认的位置冻结，不能使用预测时钟位置。
                clock.pause(lastPlayerPositionMs)
                renderer.pause()
            }
        } else {
            this.buffering = false
            cancelBuffering()
            LogHub.d(
                TAG,
                "buffering end position=$lastPlayerPositionMs resumed=$bufferingPaused " +
                    describe(),
            )
            if (bufferingPaused) {
                bufferingPaused = false
                clock.rebase(lastPlayerPositionMs)
                renderer.resume()
            }
        }
    }

    /** 播放器时间采样；由 ticker 或 TimeChanged 调用。 */
    fun onPlayerTime(positionMs: Long) {
        if (activeSession == null) return
        lastPlayerPositionMs = positionMs.coerceAtLeast(0L)
        tickCount++
        if (tickCount % SAMPLE_EVERY_TICKS == 0L) {
            LogHub.d(TAG, "sample tick=${lastPlayerPositionMs}ms " + describe())
            publishDiagnostics()
        }
        if (seekInProgress) {
            if (!isSeekPosition(lastPlayerPositionMs)) return
            seekInProgress = false
            if (!userPaused && !bufferingPaused) {
                clock.rebase(lastPlayerPositionMs)
                renderer.resume()
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
            else ->
                LogHub.d(
                    TAG,
                    "ignore unexpected segment jump current=$currentIndex next=$index; awaiting seek",
                )
        }
    }

    /** 将弹幕媒体时钟切换到与播放器一致的速度，保持当前时间连续。 */
    fun onPlaybackRateChanged(rate: Float) {
        if (activeSession == null) return
        clock.setRate(rate)
        LogHub.d(TAG, "rate=$rate " + describe())
    }

    /** 用户 seek 到指定位置。 */
    fun onSeek(positionMs: Long) {
        if (activeSession == null) return
        val target = positionMs.coerceAtLeast(0L)
        lastPlayerPositionMs = target
        seekTargetMs = target
        seekInProgress = true
        buffering = false
        bufferingPaused = false
        cancelBuffering()
        // 在收到目标位置的播放器采样前保持冻结，避免旧位置和目标位置交替绘制。
        clock.pause(target)
        renderer.pause()
        renderer.seekTo(target)
        LogHub.d(TAG, "seek target=$target " + describe())
        publishDiagnostics()
        loadSegment(indexOf(target), replace = true, force = true)
    }

    /** 应用显示设置；AI 档位变化时重建当前分段。 */
    fun applySettings(newSettings: DanmakuSettings) {
        val aiChanged = newSettings.aiShield != settings.aiShield
        val enabledChanged = newSettings.enabled != settings.enabled
        settings = newSettings
        renderer.applySettings(newSettings)
        if (aiChanged || enabledChanged) {
            renderer.replaceSegment(currentItems, settings.aiShield)
        }
    }

    /** 释放 DFM 资源；Activity 销毁时调用。 */
    fun release() {
        close()
        renderer.release()
    }

    /** 取消当前会话的作业并撤销会话归属。 */
    private fun retireSession() {
        val session = activeSession ?: return
        activeSession = null
        session.viewJob?.cancel()
        session.segmentJob?.cancel()
        session.viewJob = null
        session.segmentJob = null
        LogHub.i(TAG, "session retire ${sessionLabel(session)}")
    }

    private fun loadView(session: VodDanmakuSession, positionMs: Long) {
        session.viewJob?.cancel()
        val content = session.content
        session.viewJob = scope.launch {
            val view =
                try {
                    repository.view(content)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LogHub.w(
                        TAG,
                        "view failed ${sessionLabel(session)} accepted=false: ${failure.message}",
                    )
                    return@launch
                }
            if (activeSession !== session) {
                LogHub.i(TAG, "view discarded ${sessionLabel(session)} accepted=false")
                return@launch
            }
            LogHub.d(
                TAG,
                "view accepted ${sessionLabel(session)} state=${view.state} " +
                    "segments=${view.segConfig?.total ?: 0}",
            )
            if (view.state == CLOSED_STATE) {
                LogHub.i(TAG, "danmaku closed by uploader ${sessionLabel(session)}")
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
        val session = activeSession ?: return
        if (index <= 0) return
        if (totalSegments > 0 && index > totalSegments) return
        if (!replace && index == currentIndex) return
        if (!force && index == loadingIndex && session.segmentJob?.isActive == true) return

        val key = DanmakuSegmentKey(session.content, index)
        session.segmentJob?.cancel()
        loadingIndex = index
        session.segmentJob = scope.launch {
            val segment =
                try {
                    repository.segment(key)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LogHub.w(
                        TAG,
                        "segment failed ${sessionLabel(session)} index=$index " +
                            "accepted=false: ${failure.message}",
                    )
                    return@launch
                }
            if (activeSession !== session) {
                LogHub.i(
                    TAG,
                    "segment discarded ${sessionLabel(session)} index=$index " +
                        "accepted=false reason=stale-session",
                )
                return@launch
            }
            if (index != loadingIndex) {
                LogHub.i(
                    TAG,
                    "segment discarded ${sessionLabel(session)} index=$index " +
                        "accepted=false reason=superseded",
                )
                return@launch
            }
            if (!force && index == currentIndex) {
                LogHub.d(
                    TAG,
                    "segment discarded ${sessionLabel(session)} index=$index " +
                        "accepted=false reason=duplicate",
                )
                return@launch
            }
            currentIndex = index
            loadingIndex = -1
            currentItems = segment.items
            LogHub.d(
                TAG,
                "segment accepted ${sessionLabel(session)} index=$index " +
                    "items=${segment.items.size} replace=$replace " +
                    describe(),
            )
            if (replace) {
                renderer.replaceSegment(segment.items, settings.aiShield)
            } else {
                renderer.addSegment(segment.items, settings.aiShield)
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

    /** 弹幕会话自检：时钟、播放器位置、分段状态与渲染器快照。 */
    private fun describe(): String =
        "generation=${activeSession?.generation ?: 0} clock=${clock.nowMs()} " +
            "frozen=${clock.isFrozen} player=${lastPlayerPositionMs}ms " +
            "index=$currentIndex loading=$loadingIndex items=${currentItems.size} " +
            "bufferingPaused=$bufferingPaused userPaused=$userPaused " +
            "seekInProgress=$seekInProgress renderer=[${renderer.renderDiagnostics()}]"

    /** 把弹幕自检写入 /playback，用于判断是数据、时钟还是渲染停滞。 */
    private fun publishDiagnostics() {
        PlaybackDiagnostics.attribute("danmaku.clock", clock.nowMs().toString())
        PlaybackDiagnostics.attribute("danmaku.player", lastPlayerPositionMs.toString())
        PlaybackDiagnostics.attribute("danmaku.frozen", clock.isFrozen.toString())
        PlaybackDiagnostics.attribute("danmaku.index", currentIndex.toString())
        PlaybackDiagnostics.attribute("danmaku.items", currentItems.size.toString())
        PlaybackDiagnostics.attribute("danmaku.bufferingPaused", bufferingPaused.toString())
        PlaybackDiagnostics.attribute("danmaku.renderer", renderer.renderDiagnostics())
    }

    /** 诊断日志前缀：会话代次与内容身份。 */
    private fun sessionLabel(session: VodDanmakuSession): String =
        "generation=${session.generation} aid=${session.content.aid} cid=${session.content.cid}"

    private companion object {
        const val TAG = "DanmakuController"
        const val DEFAULT_SEGMENT_MS = 360_000L
        const val CLOSED_STATE = 1
        const val BUFFERING_PAUSE_DEBOUNCE_MS = 750L
        const val SEEK_CONFIRM_TOLERANCE_MS = 750L
        /** 每 4 次播放器采样（约 2 秒）输出一次渲染自检。 */
        const val SAMPLE_EVERY_TICKS = 4L
    }
}
