package com.roy233.tvcast.platform.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.data.bili.BiliApi
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * VLC 播放引擎门面，负责生命周期、播放控制和事件分发。
 *
 * 播放链路的其他职责已经拆分到独立协作对象：
 * - [VlcRuntimeLogger]：libVLC 启动参数和运行时能力；
 * - [VlcMediaFactory]：媒体构建、媒体选项和候选轨道；
 * - [VlcEventRecorder]：播放器事件时间线；
 * - [VlcPlaybackMonitor]：启动、画面和卡顿看门狗；
 * - [VlcPlaybackReporter]：轨道、失败和原生日志报告；
 * - [VlcPlaybackState]：一次播放会话的共享状态。
 *
 * @param context 用于创建 VLC 运行时的上下文。
 * @param videoLayout 播放视频所使用的布局。
 * @param onEvent VLC 播放器事件回调。
 */
class VlcPlayerEngine(
    context: Context,
    private val videoLayout: VLCVideoLayout,
    private val onEvent: (MediaPlayer.Event) -> Unit,
) {

    /** 应用上下文。 */
    private val appContext = context.applicationContext
    /** 用于执行播放监控任务的主线程处理器。 */
    private val handler = Handler(Looper.getMainLooper())
    /** libVLC 原生日志文件。 */
    private val nativeLogFile = PlaybackDiagnostics.vlcLogFile(appContext)
    /** libVLC 启动参数。 */
    private val libVlcOptions = VlcRuntimeLogger.options(nativeLogFile)
    /** VLC 原生运行时实例。 */
    private val libVlc = LibVLC(appContext, libVlcOptions)

    /** 对外提供的 VLC 播放器实例。 */
    val player = MediaPlayer(libVlc)

    /** 一次播放会话的共享状态。 */
    private val state = VlcPlaybackState()
    /** 播放状态、轨道和原生日志报告器。 */
    private val reporter = VlcPlaybackReporter(player, state, videoLayout)
    /** 周期看门狗。 */
    private val monitor = VlcPlaybackMonitor(player, state, reporter, handler)
    /** 播放器事件记录器。 */
    private val eventRecorder = VlcEventRecorder(player, state, monitor, reporter)
    /** 媒体构建器。 */
    private val mediaFactory = VlcMediaFactory(libVlc)

    /** 将播放器绑定到视频布局并注册事件监听器。 */
    init {
        VlcRuntimeLogger.logStartup(
            appContext,
            player,
            videoLayout,
            nativeLogFile,
            libVlcOptions,
        )
        runCatching {
            player.attachViews(videoLayout, null, false, false)
            state.viewsAttached = true
            LogHub.i(TAG, "video surface attached size=${reporter.viewSize()}")
        }.onFailure {
            LogHub.e(TAG, "attachViews failed", it, stack = true)
            PlaybackDiagnostics.runtime("views.attached", "false")
        }
        player.setEventListener { event -> dispatchEvent(event) }
    }

    /**
     * 打开播放信息对应的媒体并开始播放。
     *
     * @param info 已解析的播放信息。
     * @param seekToMs 开始播放前需要跳转的位置，单位为毫秒。
     * @return 是否已经成功发起播放。
     */
    fun open(info: BiliApi.PlayInfo, seekToMs: Long): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        monitor.stop()
        state.reset(startedAt)
        var createdMedia: Media? = null
        return try {
            PlaybackDiagnostics.ensureSession("player-open")
            PlaybackDiagnostics.attribute(
                "player.viewsAttached",
                state.viewsAttached.toString(),
            )
            val media = mediaFactory.create(info, seekToMs)
            createdMedia = media
            val previous = state.currentMedia
            state.currentMedia = media
            player.media = media
            previous?.let { runCatching { it.release() } }
            player.play()
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            LogHub.i(
                TAG,
                "play invoked elapsed=${elapsed}ms state=${reporter.playerStateName()}",
            )
            PlaybackDiagnostics.event("Open", "play invoked elapsed=${elapsed}ms")
            monitor.start()
            true
        } catch (t: Throwable) {
            LogHub.e(TAG, "open failed: ${LogRedaction.reason(t)}", t, stack = true)
            runCatching { player.stop() }
            val assigned = createdMedia != null && state.currentMedia === createdMedia
            state.currentMedia = null
            createdMedia?.let { media -> runCatching { media.release() } }
            if (assigned) {
                LogHub.i(TAG, "released failed media after open error")
            }
            PlaybackDiagnostics.fail(
                code = "PLAYER_OPEN_FAILED",
                message = "打开播放器失败",
                detail = "reason=${LogRedaction.reason(t)} ${reporter.failureSnapshot()}",
            )
            reporter.logNativeLogTail("open-failed", 40)
            monitor.stop()
            false
        }
    }

    /**
     * 暂停或继续播放并记录调用结果。
     *
     * @param paused 是否暂停。
     * @param reason 调用来源。
     */
    fun setPaused(paused: Boolean, reason: String) {
        val beforePlaying = runCatching { player.isPlaying }.getOrDefault(false)
        val beforeState = reporter.playerStateName()
        val result = runCatching { if (paused) player.pause() else player.play() }
        val afterPlaying = runCatching { player.isPlaying }.getOrDefault(false)
        LogHub.i(
            TAG,
            "control pause=$paused reason=$reason " +
                "before=$beforeState/$beforePlaying " +
                "after=${reporter.playerStateName()}/$afterPlaying " +
                "call=${result.isSuccess}",
        )
        result.onFailure {
            LogHub.e(TAG, "control pause=$paused failed reason=$reason", it, stack = true)
        }
        PlaybackDiagnostics.event(
            "Control",
            "pause=$paused reason=$reason " +
                "beforePlaying=$beforePlaying afterPlaying=$afterPlaying",
        )
        state.lastProgressAt = SystemClock.elapsedRealtime()
    }

    /**
     * 跳转到指定播放位置并记录请求值和实际值。
     *
     * @param positionMs 目标位置，单位为毫秒。
     * @param reason 调用来源。
     * @return 跳转后立即读取到的播放位置。
     */
    fun seekTo(positionMs: Long, reason: String): Long {
        val beforeTime = runCatching { player.time }.getOrDefault(-1L)
        val length = runCatching { player.length }.getOrDefault(-1L)
        val target = positionMs.coerceAtLeast(0L)
        val result = runCatching { player.time = target }
        val afterTime = runCatching { player.time }.getOrDefault(-1L)
        LogHub.i(
            TAG,
            "control seek reason=$reason from=${beforeTime}ms to=${target}ms " +
                "after=${afterTime}ms length=${length}ms call=${result.isSuccess}",
        )
        result.onFailure {
            LogHub.e(TAG, "seek failed reason=$reason target=${target}ms", it, stack = true)
        }
        PlaybackDiagnostics.event(
            "Control",
            "seek reason=$reason target=${target}ms after=${afterTime}ms length=${length}ms",
        )
        state.lastPositionMs = afterTime
        state.lastProgressAt = SystemClock.elapsedRealtime()
        return afterTime
    }

    /**
     * 设置播放器音量并记录请求值和实际值。
     *
     * @param volume 目标音量，范围 0 到 100。
     * @param reason 调用来源。
     */
    fun setPlayerVolume(volume: Int, reason: String) {
        val target = volume.coerceIn(0, 100)
        val before = runCatching { player.volume }.getOrDefault(-1)
        val result = runCatching { player.volume = target }
        val after = runCatching { player.volume }.getOrDefault(-1)
        LogHub.i(
            TAG,
            "control volume reason=$reason requested=$target before=$before after=$after " +
                "call=${result.isSuccess}",
        )
        result.onFailure {
            LogHub.e(TAG, "volume failed reason=$reason target=$target", it, stack = true)
        }
        PlaybackDiagnostics.event(
            "Control",
            "volume reason=$reason requested=$target after=$after",
        )
    }

    /**
     * 停止播放并结束当前诊断会话。
     *
     * @param reason 调用来源。
     */
    fun stopPlayback(reason: String) {
        LogHub.i(
            TAG,
            "stop reason=$reason state=${reporter.playerStateName()} " +
                "time=${runCatching { player.time }.getOrDefault(-1L)}ms " +
                "playing=${runCatching { player.isPlaying }.getOrDefault(false)}",
        )
        monitor.stop()
        val result = runCatching { player.stop() }
        result.onFailure {
            LogHub.e(TAG, "stop failed reason=$reason", it, stack = true)
        }
        PlaybackDiagnostics.event("Control", "stop reason=$reason success=${result.isSuccess}")
        monitor.sampleStats(force = true)
        PlaybackDiagnostics.closeSession("stopped:$reason")
    }

    /**
     * 主动输出一次播放状态、轨道和统计快照。
     *
     * @param reason 触发原因。
     */
    fun refreshSnapshot(reason: String) {
        LogHub.i(TAG, "snapshot reason=$reason ${reporter.failureSnapshot()}")
        reporter.logTrackSnapshot("manual-$reason")
        monitor.sampleStats(force = true)
        reporter.logNativeLogTail("manual-$reason", 20)
    }

    /**
     * 返回当前播放器状态的单行快照。
     *
     * @return 包含播放器状态、媒体和统计摘要的文本。
     */
    fun stateSnapshot(): String = reporter.failureSnapshot()

    /** 停止播放并释放播放器及原生运行时资源。 */
    fun release() {
        monitor.stop()
        LogHub.i(TAG, "release ${reporter.failureSnapshot()}")
        PlaybackDiagnostics.event("Engine", "release")
        PlaybackDiagnostics.closeSession("engine-release")
        runCatching { player.stop() }.onFailure {
            LogHub.e(TAG, "release stop failed", it)
        }
        runCatching { player.detachViews() }.onFailure {
            LogHub.e(TAG, "release detachViews failed", it)
        }
        runCatching { player.release() }.onFailure {
            LogHub.e(TAG, "release player failed", it)
        }
        state.currentMedia?.let { media ->
            runCatching { media.release() }.onFailure {
                LogHub.e(TAG, "release media failed", it)
            }
        }
        state.currentMedia = null
        runCatching { libVlc.release() }.onFailure {
            LogHub.e(TAG, "release libvlc failed", it)
        }
        LogHub.i(TAG, "released nativeLogBytes=${PlaybackDiagnostics.vlcLogBytes()}")
    }

    /**
     * 将播放器事件分发给诊断记录和界面回调。
     *
     * @param event libVLC 播放器事件。
     */
    private fun dispatchEvent(event: MediaPlayer.Event) {
        runCatching { eventRecorder.record(event) }
            .onFailure {
                LogHub.e(TAG, "record event failed type=${event.type}", it, stack = true)
            }
        runCatching { onEvent(event) }
            .onFailure {
                LogHub.e(TAG, "ui event handler failed type=${event.type}", it, stack = true)
            }
    }

    /** 日志标签。 */
    private companion object {
        const val TAG = VLC_PLAYER_TAG
    }
}
