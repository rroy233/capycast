package com.roy233.tvcast.app

import android.content.Context
import android.view.Surface
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.lifecycle.ForegroundAvailability
import com.roy233.tvcast.core.playback.LivePlaybackTelemetry
import com.roy233.tvcast.core.playback.PlaybackBackend
import com.roy233.tvcast.core.playback.PlaybackEngine
import com.roy233.tvcast.core.playback.PlaybackEvent
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.core.playback.PlaybackSessionToken
import com.roy233.tvcast.core.playback.PlaybackSnapshot
import com.roy233.tvcast.core.playback.VideoTrackCapability
import com.roy233.tvcast.core.playback.VideoTrackSupport
import com.roy233.tvcast.platform.player.Media3PlaybackEngineAdapter
import com.roy233.tvcast.platform.player.Media3PlayerEngine

/** 管理唯一 Media3 播放器的生命周期、输出 Surface 与 HDR 窗口状态。 */
@UnstableApi
class PlaybackEngineHost(
    private val context: Context,
    private val playerView: PlayerView,
    private val viewportHost: VideoViewportHost,
    private val onEvent: (PlaybackSessionToken, PlaybackBackend, PlaybackEvent) -> Unit,
    private val onHdrWindowChanged: (Boolean) -> Unit,
    private val foreground: ForegroundAvailability,
) {
    /** 当前 Media3 打开代次；停止或替换期间保持为空，使同步产生的旧事件直接失效。 */
    private var eventToken: PlaybackSessionToken? = null

    private data class ActivePlayback(val request: PlaybackRequest, val token: PlaybackSessionToken)

    private data class SuspendedPlayback(val active: ActivePlayback, val snapshot: PlaybackSnapshot)

    private var activePlayback: ActivePlayback? = null
    private var suspendedPlayback: SuspendedPlayback? = null
    private var playerGeneration = 0L
    private var playbackRate = 1f
    private var savedVolume: Int? = null

    private fun createPlayer(): Media3PlayerEngine {
        val generation = ++playerGeneration
        return Media3PlayerEngine(
            context = context,
            playerView = playerView,
            onEvent = eventCallback@{ event ->
                    val token = eventToken ?: return@eventCallback
                    if (generation != playerGeneration) return@eventCallback
                    if (event is PlaybackEvent.VideoSizeChanged) {
                        viewportHost.setMedia3ContentAspect(event.width, event.height)
                    }
                    onEvent(token, PlaybackBackend.MEDIA3, event)
                },
        )
    }

    private var media3: Media3PlayerEngine? = createPlayer()
    private val engine: PlaybackEngine = Media3PlaybackEngineAdapter { media3 }

    val backend: PlaybackBackend = PlaybackBackend.MEDIA3

    val videoTrackCapability = VideoTrackCapability { track ->
        media3?.support(track) ?: VideoTrackSupport.UNKNOWN
    }

    fun sampleVideoFrames() {
        media3?.sampleVideoFrames()
    }

    fun open(
        request: PlaybackRequest,
        seekToMs: Long,
        token: PlaybackSessionToken,
        startPaused: Boolean = false,
    ): Boolean = openInternal(request, seekToMs, token, startPaused, restoring = false)

    private fun openInternal(
        request: PlaybackRequest,
        seekToMs: Long,
        token: PlaybackSessionToken,
        startPaused: Boolean,
        restoring: Boolean,
    ): Boolean {
        if (!foreground.isAvailable && !(restoring && foreground.isEligible)) return false
        val player = media3 ?: return false
        // Media3 替换旧媒体前先撤销 token，替换期间的同步回调不能冒充新尝试。
        eventToken = null
        val output: Surface? =
            if (request.isDrm || request.video.isHdr || request.video.isDolbyVision) {
                if (viewportHost.hasCompensation()) {
                    LogHub.w(
                        "VideoViewport",
                        "pixel compensation unavailable for protected or HDR output",
                    )
                }
                null
            } else {
                viewportHost.playbackDecoderSurface()
            }
        val compensated = output != null
        if (compensated) {
            viewportHost.setMedia3ContentAspect(request.video.width, request.video.height)
        } else {
            viewportHost.setMedia3ContentAspect(0, 0)
        }
        if (
            viewportHost.hasCompensation() &&
                !compensated &&
                !request.isDrm &&
                !request.video.isHdr &&
                !request.video.isDolbyVision
        ) {
            LogHub.e("VideoViewport", "pixel compensation unavailable; using direct output")
        }
        onHdrWindowChanged(request.video.isHdr || request.video.isDolbyVision)
        playerView.visibility = View.VISIBLE
        player.setVideoOutput(output)
        viewportHost.showPlaybackCompensation(compensated)
        val opened = engine.open(request, seekToMs, startPaused)
        if (opened) {
            eventToken = token
            activePlayback = ActivePlayback(request, token)
            suspendedPlayback = null
        }
        return opened
    }

    fun snapshot(fallbackVolume: Int): PlaybackSnapshot =
        suspendedPlayback?.snapshot ?: engine.snapshot(fallbackVolume)

    fun seekTo(positionMs: Long, reason: String): Long = engine.seekTo(positionMs, reason)

    fun setPaused(paused: Boolean, reason: String) {
        if (paused || foreground.isAvailable) engine.setPaused(paused, reason)
        else LogHub.i("PlaybackEngineHost", "resume rejected while unavailable reason=$reason")
    }

    /** 缓冲中暂停不会触发 isPlaying 变化，需要显式同步会话组件。 */
    fun pauseForBackground(reason: String) {
        val notifyPaused = engine.isPlayRequested() && !engine.snapshot(0).isPlaying
        setPaused(true, reason)
        if (notifyPaused) eventToken?.let(::publishInitialPaused)
    }

    fun setPlaybackRate(rate: Float, reason: String): Float =
        engine.setPlaybackRate(rate, reason).also { playbackRate = it }

    fun setVolume(volume: Int, reason: String) {
        engine.setVolume(volume, reason)
        savedVolume = volume
    }

    fun stop(reason: String) {
        eventToken = null
        activePlayback = null
        suspendedPlayback = null
        engine.stop(reason)
        viewportHost.showPlaybackCompensation(false)
    }

    fun isSeekable(): Boolean = engine.isSeekable()

    fun isPlayRequested(): Boolean = engine.isPlayRequested()

    /** 首次静默起播没有 isPlaying 变化，也要让业务组件进入暂停状态。 */
    fun publishInitialPaused(token: PlaybackSessionToken) {
        if (eventToken == token && !engine.isPlayRequested()) {
            onEvent(token, PlaybackBackend.MEDIA3, PlaybackEvent.Paused)
        }
    }

    fun stateSnapshot(): String = media3?.stateSnapshot() ?: "player suspended"

    fun videoSize(): Pair<Int, Int>? = media3?.videoSize()

    fun liveTelemetry(): LivePlaybackTelemetry? = media3?.liveTelemetry()

    /** 释放不可见播放器，同时保留会话身份和恢复位置。 */
    fun suspendForBackground(fallbackVolume: Int) {
        val player = media3 ?: return
        val active = activePlayback
        if (active != null) {
            suspendedPlayback =
                SuspendedPlayback(active, engine.snapshot(fallbackVolume).copy(isPlaying = false))
        }
        eventToken = null
        playerGeneration++
        try {
            player.release()
        } finally {
            media3 = null
            viewportHost.showPlaybackCompensation(false)
        }
    }

    /** 返回前台后以暂停状态重建后端，继续使用现有业务会话。 */
    fun restoreAfterBackground() {
        if (media3 != null || !foreground.isEligible) return
        val pending = suspendedPlayback
        media3 = createPlayer()
        if (pending != null) {
            try {
                check(
                    openInternal(
                        pending.active.request,
                        pending.snapshot.positionMs,
                        pending.active.token,
                        startPaused = true,
                        restoring = true,
                    ),
                )
                engine.setPlaybackRate(playbackRate, "foreground-restore")
                engine.setVolume(savedVolume ?: pending.snapshot.volume, "foreground-restore")
            } catch (failure: Throwable) {
                eventToken = null
                playerGeneration++
                runCatching { media3?.release() }
                    .onFailure { LogHub.e("PlaybackEngineHost", "restore cleanup failed", it) }
                media3 = null
                suspendedPlayback = pending
                throw failure
            }
        }
    }

    fun release() {
        eventToken = null
        activePlayback = null
        suspendedPlayback = null
        playerGeneration++
        engine.release()
        media3 = null
        viewportHost.showPlaybackCompensation(false)
    }
}
