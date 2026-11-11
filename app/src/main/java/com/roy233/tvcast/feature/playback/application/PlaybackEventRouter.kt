package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.core.diagnostics.PlaybackEndReason
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

    fun updatePlaying(seek: Long)

    fun updatePaused()

    fun updateStopped()

    fun updateBuffering(buffering: Boolean)

    fun updateEnded()

    fun updateVideoSize(width: Int, height: Int)

    fun vodDanmakuPlaying(seek: Long)

    fun vodDanmakuPaused()

    fun vodDanmakuBuffering(buffering: Boolean)

    fun liveDanmakuClose()

    fun vodDanmakuClose()

    fun vodSubtitleClose()

    fun reportPlaybackError(
        title: String,
        message: String,
        code: String,
        canRetry: Boolean,
        keepVideo: Boolean,
    )

    fun usageOnPlaying()

    fun usageOnPaused()

    fun finishUsage(reason: PlaybackEndReason)
}

/** 将唯一 Media3 后端的事件映射到既有 VOD、直播、历史与弹幕会话。 */
class PlaybackEventRouter(private val host: PlaybackEventHost) {
    fun dispatch(backend: PlaybackBackend, event: PlaybackEvent) {
        if (backend != PlaybackBackend.MEDIA3 || host.activeBackend() != backend) return
        when (event) {
            PlaybackEvent.Playing -> playing()
            PlaybackEvent.Paused -> paused()
            PlaybackEvent.Stopped -> stopped()
            PlaybackEvent.Ended -> end()
            is PlaybackEvent.Buffering -> buffering(event.isBuffering)
            is PlaybackEvent.Failed -> failed(event)
            is PlaybackEvent.VideoSizeChanged ->
                host.runOnMain {
                    host.updateVideoSize(event.width, event.height)
                }
        }
    }

    private fun playing() {
        val seek = host.consumePendingSeekMs()
        LogHub.d(
            TAG,
            "event playing seek=$seek live=${host.currentPlaybackRequest()?.isLive == true}",
        )
        host.runOnMain {
            val live = host.currentPlaybackRequest()?.isLive == true
            host.updatePlaying(seek)
            if (live) host.onLivePlaying()
            else {
                host.vodOnPlaying()
                host.vodDanmakuPlaying(seek)
            }
            host.historyOnPlaying()
            host.usageOnPlaying()
        }
    }

    private fun paused() {
        LogHub.d(TAG, "event paused live=${host.currentPlaybackRequest()?.isLive == true}")
        host.runOnMain {
            val live = host.currentPlaybackRequest()?.isLive == true
            host.updatePaused()
            host.historyOnPaused()
            if (live) host.onLivePaused() else host.vodDanmakuPaused()
            host.usageOnPaused()
        }
    }

    private fun stopped() {
        host.runOnMain {
            host.updateStopped()
            host.vodDanmakuClose()
            host.vodSubtitleClose()
            host.liveDanmakuClose()
            host.finishUsage(PlaybackEndReason.USER_STOPPED)
        }
    }

    private fun buffering(buffering: Boolean) {
        LogHub.d(
            TAG,
            "event buffering=$buffering live=${host.currentPlaybackRequest()?.isLive == true}",
        )
        host.runOnMain {
            val live = host.currentPlaybackRequest()?.isLive == true
            host.updateBuffering(buffering)
            if (live) {
                if (host.onLiveBuffering(buffering)) {
                    host.handleLiveFailure("two-consecutive-rebuffers")
                }
            } else {
                host.observeVodBuffering(buffering)
                host.vodDanmakuBuffering(buffering)
            }
        }
    }

    private fun end() {
        host.finishHistory("media3-ended", ended = true)
        host.clearLiveSession("media3-end-reached")
        host.setCastEnded()
        host.finishUsage(PlaybackEndReason.COMPLETED)
        host.runOnMain {
            host.vodDanmakuClose()
            host.vodSubtitleClose()
            host.liveDanmakuClose()
            host.updateEnded()
        }
    }

    private fun failed(error: PlaybackEvent.Failed) {
        val info = host.currentPlaybackRequest()
        val snapshot = host.playerStateSnapshot()
        LogHub.e(
            TAG,
            "Media3 playback failed live=${info?.isLive == true} code=${error.code} " +
                "message=${error.message} snapshot=$snapshot",
        )
        if (info?.isLive == true) {
            host.finishHistory("media3-live-error")
            host.handleLiveFailure("player-error")
            return
        }
        val decoderFailure =
            error.code.contains("DECODING", ignoreCase = true) ||
                error.code.contains("RENDERER", ignoreCase = true) ||
                error.code.contains("CODEC", ignoreCase = true)
        if (host.handleVodFailure("media3-player-error", preferCodecFallback = decoderFailure))
            return
        host.finishHistory("media3-error")
        if (info != null) host.setRetryFor(info)
        val title =
            when {
                info?.isDrm == true -> "受保护内容播放失败"
                info?.video?.isDolbyVision == true -> "杜比视界播放失败"
                info?.video?.isHdr == true -> "HDR 播放失败"
                else -> "播放失败"
            }
        val code =
            when {
                info?.isDrm == true -> "WIDEVINE_PLAYBACK_FAILED"
                info?.video?.isDolbyVision == true -> "DOLBY_VISION_PLAYBACK_FAILED"
                info?.video?.isHdr == true -> "HDR_PLAYBACK_FAILED"
                else -> "PLAYBACK_FAILED"
            }
        PlaybackDiagnostics.fail(
            code = code,
            message = title,
            detail = "error=${error.code} message=${error.message}",
        )
        host.reportPlaybackError(
            title = title,
            message = "设备解码能力不足或网络异常，请查看诊断日志。",
            code = code,
            canRetry = info != null,
            keepVideo = true,
        )
        host.vodDanmakuClose()
        host.vodSubtitleClose()
        host.finishUsage(PlaybackEndReason.ERROR)
    }

    private companion object {
        const val TAG = "SimpleBili"
    }
}
