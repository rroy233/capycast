package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.core.playback.PlaybackBackend
import com.roy233.tvcast.core.playback.PlaybackEvent
import com.roy233.tvcast.core.playback.PlaybackRequest

/** 播放器事件所需的会话、历史、弹幕与展示命令。 */
interface PlaybackEventHost {
    fun activeBackend(): PlaybackBackend
    fun currentPlaybackRequest(): PlaybackRequest?
    fun consumePendingSeekMs(): Long
    fun playerPositionMs(): Long
    fun playerDurationMs(): Long
    fun playerStateSnapshot(): String
    fun runOnMain(action: () -> Unit)
    fun vodOnPlaying()
    fun observeVodBuffering(buffering: Boolean)
    fun handleVodFailure(reason: String, preferCodecFallback: Boolean = false): Boolean
    fun onLivePlaying()
    fun onLivePaused()
    fun onLiveBuffering(buffering: Boolean): Boolean
    fun handleLiveFailure(reason: String)
    fun clearLiveSession(reason: String)
    fun finishHistory(reason: String, ended: Boolean = false)
    fun historyOnPlaying()
    fun historyOnPaused()
    fun setCastEnded()
    fun setRetryFor(request: PlaybackRequest)
    fun updateMedia3Playing(seek: Long)
    fun updateVlcPlaying(seek: Long)
    fun updatePaused()
    fun updateStopped()
    fun updateBuffering(buffering: Boolean)
    fun updateEnded()
    fun updateVideoSize(width: Int, height: Int)
    fun refreshVideoSize()
    fun vodDanmakuPlaying(seek: Long)
    fun vodDanmakuPaused()
    fun vodDanmakuBuffering(buffering: Boolean)
    fun liveDanmakuClose()
    fun vodDanmakuClose()
    fun reportPlaybackError(title: String, message: String, code: String, canRetry: Boolean, keepVideo: Boolean)
}

/** 统一处理 VLC 与 Media3 事件，保持各后端原有主线程与清理时序。 */
class PlaybackEventRouter(private val host: PlaybackEventHost) {
    fun dispatch(backend: PlaybackBackend, event: PlaybackEvent) {
        if (host.activeBackend() != backend) return
        when (backend) {
            PlaybackBackend.MEDIA3 -> media3(event)
            PlaybackBackend.VLC -> vlc(event)
        }
    }

    private fun media3(event: PlaybackEvent) {
        when (event) {
            PlaybackEvent.Playing -> {
                host.vodOnPlaying()
                val seek = host.consumePendingSeekMs()
                host.updateMedia3Playing(seek)
                host.historyOnPlaying()
                host.vodDanmakuPlaying(seek)
            }
            PlaybackEvent.Paused -> {
                host.updatePaused()
                host.historyOnPaused()
                host.vodDanmakuPaused()
            }
            PlaybackEvent.Stopped -> Unit
            PlaybackEvent.Ended -> end("media3-ended", "media3-ended")
            is PlaybackEvent.Buffering -> {
                host.updateBuffering(event.isBuffering)
                host.observeVodBuffering(event.isBuffering)
                host.vodDanmakuBuffering(event.isBuffering)
            }
            is PlaybackEvent.Failed -> media3Failure(event)
            is PlaybackEvent.VideoSizeChanged -> host.updateVideoSize(event.width, event.height)
        }
    }

    private fun media3Failure(error: PlaybackEvent.Failed) {
        val decoderFailure = error.code.contains("DECODING", ignoreCase = true) ||
            error.code.contains("RENDERER", ignoreCase = true) ||
            error.code.contains("CODEC", ignoreCase = true)
        if (host.handleVodFailure("media3-player-error", preferCodecFallback = decoderFailure)) return
        host.finishHistory("media3-error")
        val info = host.currentPlaybackRequest()
        if (info != null) host.setRetryFor(info)
        val licenseFailure = error.code.contains("DRM", ignoreCase = true) ||
            error.code.contains("LICENSE", ignoreCase = true)
        val code = when {
            info?.isDrm != true -> "DOLBY_VISION_PLAYBACK_FAILED"
            licenseFailure -> "WIDEVINE_LICENSE_FAILED"
            else -> "WIDEVINE_PLAYBACK_FAILED"
        }
        val title = if (info?.isDrm == true) "受保护内容播放失败" else "杜比视界播放失败"
        LogHub.e(TAG, "Media3 playback failed drm=${info?.isDrm == true} code=${error.code} message=${error.message}")
        PlaybackDiagnostics.fail(code = code, message = title, detail = "error=${error.code} message=${error.message}")
        host.reportPlaybackError(
            title = title,
            message = if (info?.isDrm == true) "Widevine 授权或设备安全解码器不可用，请查看诊断日志。" else "当前设备、系统或 HDMI 链路无法播放此杜比视界码流。",
            code = code,
            canRetry = info != null,
            keepVideo = true,
        )
        host.vodDanmakuClose()
    }

    private fun vlc(event: PlaybackEvent) {
        when (event) {
            PlaybackEvent.Playing -> {
                val seek = host.consumePendingSeekMs()
                LogHub.i(TAG, "ui playing initialPosition=${seek}ms")
                host.runOnMain {
                    host.vodOnPlaying()
                    host.updateVlcPlaying(seek)
                    host.refreshVideoSize()
                    if (host.currentPlaybackRequest()?.isLive == true) host.onLivePlaying()
                    else host.vodDanmakuPlaying(seek)
                    host.historyOnPlaying()
                }
            }
            PlaybackEvent.Paused -> {
                LogHub.d(TAG, "ui paused")
                host.runOnMain {
                    host.updatePaused()
                    host.historyOnPaused()
                    if (host.currentPlaybackRequest()?.isLive == true) host.onLivePaused()
                    else host.vodDanmakuPaused()
                }
            }
            PlaybackEvent.Stopped -> {
                LogHub.d(TAG, "ui stopped")
                host.runOnMain {
                    host.updateStopped()
                    host.vodDanmakuClose()
                    host.liveDanmakuClose()
                }
            }
            PlaybackEvent.Ended -> {
                LogHub.i(TAG, "ui end reached")
                host.finishHistory("vlc-ended", ended = true)
                host.clearLiveSession("vlc-end-reached")
                host.setCastEnded()
                host.runOnMain {
                    host.vodDanmakuClose()
                    host.liveDanmakuClose()
                    host.updateEnded()
                }
            }
            is PlaybackEvent.Failed -> vlcFailure()
            is PlaybackEvent.Buffering -> host.runOnMain {
                val buffering = event.isBuffering
                host.updateBuffering(buffering)
                if (host.currentPlaybackRequest()?.isLive == true && host.onLiveBuffering(buffering)) {
                    host.handleLiveFailure("two-consecutive-rebuffers")
                }
                if (host.currentPlaybackRequest()?.isLive != true) host.observeVodBuffering(buffering)
                if (host.currentPlaybackRequest()?.isLive != true) host.vodDanmakuBuffering(buffering)
            }
            is PlaybackEvent.VideoSizeChanged -> Unit
        }
    }

    private fun vlcFailure() {
        val snapshot = runCatching { host.playerStateSnapshot() }.getOrDefault("(snapshot unavailable)")
        LogHub.e(TAG, "ui playback failed code=PLAYBACK_FAILED snapshot=$snapshot")
        host.finishHistory("vlc-error")
        val info = host.currentPlaybackRequest()
        if (info?.isLive == true) {
            host.handleLiveFailure("player-error")
            return
        }
        if (info != null && host.handleVodFailure("player-error")) return
        if (info != null) host.setRetryFor(info)
        host.reportPlaybackError(
            title = "播放失败",
            message = "设备解码能力不足或网络异常。",
            code = "PLAYBACK_FAILED",
            canRetry = info != null,
            keepVideo = true,
        )
        host.vodDanmakuClose()
        host.liveDanmakuClose()
    }

    private fun end(historyReason: String, liveReason: String) {
        host.finishHistory(historyReason, ended = true)
        host.clearLiveSession(liveReason)
        host.setCastEnded()
        host.vodDanmakuClose()
        host.updateEnded()
    }

    private companion object { const val TAG = "SimpleBili" }
}
