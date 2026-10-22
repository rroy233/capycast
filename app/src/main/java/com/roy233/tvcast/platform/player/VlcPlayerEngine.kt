package com.roy233.tvcast.platform.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
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
    /** VLC 原生运行时实例。播放器重建期间保持该实例，避免额外的 native 初始化抖动。 */
    private val libVlc = LibVLC(appContext, libVlcOptions)

    /** 当前 VLC 播放器实例；视频输出失败时会以新实例替换。 */
    private lateinit var internalPlayer: MediaPlayer

    /** 对外提供当前播放器实例，供活动页读取位置和轨道信息。 */
    val player: MediaPlayer
        get() = internalPlayer

    /** 一次播放会话的共享状态。 */
    private val state = VlcPlaybackState()
    /** 播放状态、轨道和原生日志报告器。 */
    private lateinit var reporter: VlcPlaybackReporter
    /** 周期看门狗。 */
    private lateinit var monitor: VlcPlaybackMonitor
    /** 播放器事件记录器。 */
    private lateinit var eventRecorder: VlcEventRecorder
    /** 媒体构建器。 */
    private val mediaFactory = VlcMediaFactory(libVlc)

    /** 最近一次外部播放请求；vout 重建后据此恢复原媒体与位置。 */
    private var lastOpenRequest: OpenRequest? = null

    /** Surface 未就绪时延迟执行的播放请求。 */
    private var pendingOpenRequest: OpenRequest? = null

    /** 延迟播放请求开始等待 Surface 的时间。 */
    private var surfaceWaitStartedAt: Long = 0L

    /** 当前会话已经执行的视频输出自动重建次数。 */
    private var voutRecoveryAttempts: Int = 0

    /** 已释放后忽略所有异步恢复任务。 */
    private var released: Boolean = false

    /** 防止同一故障由多个监控周期重复排队重建。 */
    private var recoveryQueued: Boolean = false

    /** 等待布局完成后重试已延迟的播放请求。 */
    private val surfaceOpenRunnable = Runnable { openWhenSurfaceReady("surface-wait") }

    /** 将播放器绑定到视频布局并注册事件监听器。 */
    init {
        createPlayer("initial")
        videoLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            openWhenSurfaceReady("layout-change")
        }
        videoLayout.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                LogHub.i(TAG, "video layout attached size=${reporter.viewSize()}")
                openWhenSurfaceReady("view-attached")
            }

            override fun onViewDetachedFromWindow(view: View) {
                LogHub.w(TAG, "video layout detached; future opens wait for a new Surface")
                state.viewsAttached = false
            }
        })
        videoLayout.post { openWhenSurfaceReady("initial-layout") }
    }

    /**
     * 打开播放信息对应的媒体并开始播放。
     *
     * @param info 已解析的播放信息。
     * @param seekToMs 开始播放前需要跳转的位置，单位为毫秒。
     * @return 是否已经成功发起播放。
     */
    fun open(info: BiliApi.PlayInfo, seekToMs: Long): Boolean {
        if (released) {
            LogHub.e(TAG, "open rejected after engine release")
            PlaybackDiagnostics.fail(
                code = "PLAYER_RELEASED",
                message = "播放器已经释放",
                detail = "open ignored after release",
            )
            return false
        }
        val request = OpenRequest(info, seekToMs.coerceAtLeast(0L))
        lastOpenRequest = request
        pendingOpenRequest = null
        voutRecoveryAttempts = 0
        return openOrWaitForSurface(request, "open")
    }

    /**
     * 在视频布局拥有有效尺寸后打开媒体。Surface 尚未就绪时保留请求并在布局回调中重试。
     */
    private fun openOrWaitForSurface(request: OpenRequest, reason: String): Boolean {
        if (!ensureViewsAttached(reason)) {
            pendingOpenRequest = request
            if (surfaceWaitStartedAt == 0L) {
                surfaceWaitStartedAt = SystemClock.elapsedRealtime()
                LogHub.i(
                    TAG,
                    "open deferred reason=$reason view=${reporter.viewSize()} " +
                        "attached=${videoLayout.isAttachedToWindow}",
                )
                PlaybackDiagnostics.event(
                    "Surface",
                    "open deferred reason=$reason view=${reporter.viewSize()}",
                )
            }
            handler.removeCallbacks(surfaceOpenRunnable)
            handler.postDelayed(surfaceOpenRunnable, SURFACE_RETRY_INTERVAL_MS)
            return true
        }
        pendingOpenRequest = null
        surfaceWaitStartedAt = 0L
        handler.removeCallbacks(surfaceOpenRunnable)
        return openPrepared(request)
    }

    /** 执行已经通过 Surface 就绪检查的媒体打开流程。 */
    private fun openPrepared(request: OpenRequest): Boolean {
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
            val media = mediaFactory.create(request.info, request.seekToMs)
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

    /** 创建播放器并注册当前实例独有的监控、事件记录与视频 Surface 绑定。 */
    private fun createPlayer(reason: String) {
        internalPlayer = MediaPlayer(libVlc)
        reporter = VlcPlaybackReporter(internalPlayer, state, videoLayout)
        monitor = VlcPlaybackMonitor(
            internalPlayer,
            state,
            reporter,
            handler,
            ::requestVoutRecovery,
        )
        eventRecorder = VlcEventRecorder(internalPlayer, state, monitor, reporter)
        internalPlayer.setEventListener { event -> dispatchEvent(event) }
        VlcRuntimeLogger.logStartup(
            appContext,
            internalPlayer,
            videoLayout,
            nativeLogFile,
            libVlcOptions,
        )
        state.viewsAttached = false
        LogHub.i(TAG, "player instance created reason=$reason view=${reporter.viewSize()}")
        PlaybackDiagnostics.event("PlayerInstance", "created reason=$reason view=${reporter.viewSize()}")
        ensureViewsAttached("player-created:$reason")
    }

    /**
     * 仅在布局已附着且拥有可用尺寸时绑定 libVLC 视频视图。
     *
     * VLCVideoLayout 在 Activity 的 onCreate 阶段通常为 0×0。等待布局就绪可避免
     * 首次投屏把播放器绑定到尚未创建完成的 Android Surface。
     */
    private fun ensureViewsAttached(reason: String): Boolean {
        if (state.viewsAttached) return true
        val ready = videoLayout.isAttachedToWindow && videoLayout.width > 0 && videoLayout.height > 0
        if (!ready) {
            LogHub.d(
                TAG,
                "video surface not ready reason=$reason view=${reporter.viewSize()} " +
                    "attached=${videoLayout.isAttachedToWindow}",
            )
            return false
        }
        return runCatching {
            internalPlayer.attachViews(videoLayout, null, false, false)
            state.viewsAttached = true
            LogHub.i(TAG, "video surface attached reason=$reason size=${reporter.viewSize()}")
            PlaybackDiagnostics.event(
                "Surface",
                "attached reason=$reason view=${reporter.viewSize()}",
            )
            true
        }.getOrElse {
            LogHub.e(TAG, "attachViews failed reason=$reason", it, stack = true)
            PlaybackDiagnostics.runtime("views.attached", "false")
            PlaybackDiagnostics.event(
                "Surface",
                "attach failed reason=$reason detail=${LogRedaction.reason(it)}",
            )
            false
        }
    }

    /** Surface 就绪回调：开始已延迟的播放，或记录等待超时。 */
    private fun openWhenSurfaceReady(reason: String) {
        if (released) return
        val request = pendingOpenRequest ?: return
        if (ensureViewsAttached(reason)) {
            pendingOpenRequest = null
            surfaceWaitStartedAt = 0L
            handler.removeCallbacks(surfaceOpenRunnable)
            LogHub.i(TAG, "deferred open resumed reason=$reason view=${reporter.viewSize()}")
            PlaybackDiagnostics.event("Surface", "deferred open resumed reason=$reason")
            openPrepared(request)
            return
        }
        val waited = SystemClock.elapsedRealtime() - surfaceWaitStartedAt
        if (surfaceWaitStartedAt > 0L && waited >= VLC_SURFACE_READY_TIMEOUT_MS) {
            pendingOpenRequest = null
            LogHub.e(TAG, "surface not ready after ${waited}ms; deferred open cancelled")
            PlaybackDiagnostics.fail(
                code = "PLAYER_SURFACE_UNAVAILABLE",
                message = "视频渲染 Surface 未就绪",
                detail = "waited=${waited}ms view=${reporter.viewSize()} attached=${videoLayout.isAttachedToWindow}",
            )
            return
        }
        handler.removeCallbacks(surfaceOpenRunnable)
        handler.postDelayed(surfaceOpenRunnable, SURFACE_RETRY_INTERVAL_MS)
    }

    /**
     * 在已开始播放、但没有有效视频输出时销毁并重建 MediaPlayer，再恢复媒体和位置。
     *
     * 回调从监控器到达时不直接触碰播放器线程；统一切回主线程，防止旧实例的终止事件
     * 与新实例媒体设置交错。
     */
    private fun requestVoutRecovery(reason: String) {
        if (released || recoveryQueued) return
        recoveryQueued = true
        handler.post {
            recoveryQueued = false
            val request = lastOpenRequest
            if (released || request == null || !state.active) {
                LogHub.w(TAG, "vout recovery skipped reason=$reason active=${state.active}")
                return@post
            }
            if (voutRecoveryAttempts >= VLC_MAX_VOUT_RECOVERY_ATTEMPTS) {
                LogHub.e(
                    TAG,
                    "vout recovery exhausted attempts=$voutRecoveryAttempts reason=$reason " +
                        reporter.failureSnapshot(),
                )
                PlaybackDiagnostics.fail(
                    code = "VLC_VOUT_RECOVERY_EXHAUSTED",
                    message = "视频输出重建后仍未恢复",
                    detail = "attempts=$voutRecoveryAttempts reason=$reason ${reporter.failureSnapshot()}",
                )
                return@post
            }
            val position = runCatching { internalPlayer.time }.getOrDefault(-1L)
                .takeIf { it >= 0L } ?: request.seekToMs
            voutRecoveryAttempts += 1
            LogHub.w(
                TAG,
                "vout recovery begin attempt=$voutRecoveryAttempts reason=$reason " +
                    "resume=${position}ms view=${reporter.viewSize()}",
            )
            PlaybackDiagnostics.event(
                "VoutRecovery",
                "begin attempt=$voutRecoveryAttempts reason=$reason resume=${position}ms",
            )
            releasePlayerInstance("vout-recovery")
            createPlayer("vout-recovery-$voutRecoveryAttempts")
            val recoveryRequest = request.copy(seekToMs = position)
            lastOpenRequest = recoveryRequest
            openOrWaitForSurface(recoveryRequest, "vout-recovery")
        }
    }

    /** 释放当前 MediaPlayer 与媒体对象；LibVLC 保留给下一实例复用。 */
    private fun releasePlayerInstance(reason: String) {
        monitor.stop()
        val oldPlayer = internalPlayer
        val oldMedia = state.currentMedia
        state.currentMedia = null
        state.viewsAttached = false
        runCatching { oldPlayer.stop() }.onFailure {
            LogHub.e(TAG, "player stop failed reason=$reason", it, stack = true)
        }
        runCatching { oldPlayer.detachViews() }.onFailure {
            LogHub.e(TAG, "detachViews failed reason=$reason", it, stack = true)
        }
        runCatching { oldPlayer.release() }.onFailure {
            LogHub.e(TAG, "player release failed reason=$reason", it, stack = true)
        }
        oldMedia?.let { media ->
            runCatching { media.release() }.onFailure {
                LogHub.e(TAG, "media release failed reason=$reason", it, stack = true)
            }
        }
        LogHub.i(TAG, "player instance released reason=$reason")
        PlaybackDiagnostics.event("PlayerInstance", "released reason=$reason")
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
        pendingOpenRequest = null
        surfaceWaitStartedAt = 0L
        handler.removeCallbacks(surfaceOpenRunnable)
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
        if (released) return
        released = true
        pendingOpenRequest = null
        handler.removeCallbacks(surfaceOpenRunnable)
        LogHub.i(TAG, "release ${reporter.failureSnapshot()}")
        PlaybackDiagnostics.event("Engine", "release")
        PlaybackDiagnostics.closeSession("engine-release")
        releasePlayerInstance("engine-release")
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
        const val SURFACE_RETRY_INTERVAL_MS = 100L
    }

    /** 一次可恢复的打开请求。 */
    private data class OpenRequest(
        val info: BiliApi.PlayInfo,
        val seekToMs: Long,
    )
}
