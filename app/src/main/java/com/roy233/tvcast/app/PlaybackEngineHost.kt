package com.roy233.tvcast.app

import android.content.Context
import android.view.Surface
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.playback.LivePlaybackTelemetry
import com.roy233.tvcast.core.playback.PlaybackBackend
import com.roy233.tvcast.core.playback.PlaybackEngine
import com.roy233.tvcast.core.playback.PlaybackEvent
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.core.playback.PlaybackSnapshot
import com.roy233.tvcast.platform.player.Media3PlaybackEngineAdapter
import com.roy233.tvcast.platform.player.Media3PlayerEngine

/** 管理唯一 Media3 播放器的生命周期、输出 Surface 与 HDR 窗口状态。 */
@UnstableApi
class PlaybackEngineHost(
    context: Context,
    private val playerView: PlayerView,
    private val viewportHost: VideoViewportHost,
    private val onEvent: (PlaybackBackend, PlaybackEvent) -> Unit,
    private val onHdrWindowChanged: (Boolean) -> Unit,
) {
    private val media3 =
        Media3PlayerEngine(
            context = context,
            playerView = playerView,
            onEvent = { event ->
                if (event is PlaybackEvent.VideoSizeChanged) {
                    viewportHost.setMedia3ContentAspect(event.width, event.height)
                }
                onEvent(PlaybackBackend.MEDIA3, event)
            },
        )
    private val engine: PlaybackEngine = Media3PlaybackEngineAdapter { media3 }

    val backend: PlaybackBackend = PlaybackBackend.MEDIA3

    fun open(request: PlaybackRequest, seekToMs: Long): Boolean {
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
        media3.setVideoOutput(output)
        viewportHost.showPlaybackCompensation(compensated)
        return engine.open(request, seekToMs)
    }

    fun snapshot(fallbackVolume: Int): PlaybackSnapshot = engine.snapshot(fallbackVolume)

    fun seekTo(positionMs: Long, reason: String): Long = engine.seekTo(positionMs, reason)

    fun setPaused(paused: Boolean, reason: String) = engine.setPaused(paused, reason)

    fun setPlaybackRate(rate: Float, reason: String): Float = engine.setPlaybackRate(rate, reason)

    fun setVolume(volume: Int, reason: String) = engine.setVolume(volume, reason)

    fun stop(reason: String) {
        engine.stop(reason)
        viewportHost.showPlaybackCompensation(false)
    }

    fun isSeekable(): Boolean = engine.isSeekable()

    fun stateSnapshot(): String = media3.stateSnapshot()

    fun videoSize(): Pair<Int, Int>? = media3.videoSize()

    fun liveTelemetry(): LivePlaybackTelemetry = media3.liveTelemetry()

    fun release() {
        engine.release()
        viewportHost.showPlaybackCompensation(false)
    }
}
