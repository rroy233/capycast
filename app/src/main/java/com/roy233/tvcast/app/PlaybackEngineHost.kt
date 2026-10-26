package com.roy233.tvcast.app

import android.content.Context
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.roy233.tvcast.core.playback.LivePlaybackTelemetry
import com.roy233.tvcast.core.playback.PlaybackBackend
import com.roy233.tvcast.core.playback.PlaybackBackendSelector
import com.roy233.tvcast.core.playback.PlaybackEngine
import com.roy233.tvcast.core.playback.PlaybackEvent
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.core.playback.PlaybackSnapshot
import com.roy233.tvcast.platform.player.Media3PlaybackEngineAdapter
import com.roy233.tvcast.platform.player.Media3PlayerEngine
import com.roy233.tvcast.platform.player.VlcPlaybackEngineAdapter
import com.roy233.tvcast.platform.player.VlcPlayerEngine
import org.videolan.libvlc.util.VLCVideoLayout

/** 管理播放器后端的创建、选择、切换和释放。 */
@UnstableApi
class PlaybackEngineHost(
    context: Context,
    private val vlcView: VLCVideoLayout,
    private val media3View: PlayerView,
    private val onEvent: (PlaybackBackend, PlaybackEvent) -> Unit,
    private val onHdrWindowChanged: (Boolean) -> Unit,
) {
    private val vlc = VlcPlayerEngine(context, vlcView) { event ->
        onEvent(PlaybackBackend.VLC, event)
    }
    private val vlcEngine = VlcPlaybackEngineAdapter(vlc)
    private var media3: Media3PlayerEngine? = null
    private val media3Engine = Media3PlaybackEngineAdapter { media3 }

    var backend: PlaybackBackend = PlaybackBackend.VLC
        private set

    fun open(request: PlaybackRequest, seekToMs: Long): Boolean {
        backend = PlaybackBackendSelector.select(request)
        return when (backend) {
            PlaybackBackend.MEDIA3 -> {
                onHdrWindowChanged(request.video.isHdr || request.video.isDolbyVision)
                vlc.stopPlayback(PlaybackBackendSelector.switchReason(request))
                vlcView.visibility = View.GONE
                media3View.visibility = View.VISIBLE
                media3Engine()
                media3Engine.open(request, seekToMs)
            }
            PlaybackBackend.VLC -> {
                media3?.stop()
                onHdrWindowChanged(false)
                media3View.visibility = View.GONE
                vlcView.visibility = View.VISIBLE
                vlcEngine.open(request, seekToMs)
            }
        }
    }

    fun snapshot(fallbackVolume: Int): PlaybackSnapshot = activeEngine().snapshot(fallbackVolume)

    fun seekTo(positionMs: Long, reason: String): Long = activeEngine().seekTo(positionMs, reason)

    fun setPaused(paused: Boolean, reason: String) = activeEngine().setPaused(paused, reason)

    fun setPlaybackRate(rate: Float, reason: String): Float =
        activeEngine().setPlaybackRate(rate, reason)

    fun setVolume(volume: Int, reason: String) = activeEngine().setVolume(volume, reason)

    fun stop(reason: String) = activeEngine().stop(reason)

    fun isVlcSeekable(): Boolean = runCatching { vlc.player.isSeekable }.getOrDefault(false)

    fun stateSnapshot(): String = runCatching { vlc.stateSnapshot() }.getOrDefault("")

    fun videoSize(): Pair<Int, Int>? = vlc.videoSize()

    fun liveTelemetry(): LivePlaybackTelemetry? {
        if (backend != PlaybackBackend.VLC) return null
        return vlc.liveProgressSnapshot().let {
            LivePlaybackTelemetry(
                positionMs = it.positionMs,
                readBytes = it.readBytes,
                demuxReadBytes = it.demuxReadBytes,
                decodedVideoFrames = it.decodedVideoFrames,
                displayedPictures = it.displayedPictures,
            )
        }
    }

    fun release() {
        media3Engine.release()
        vlcEngine.release()
    }

    private fun activeEngine(): PlaybackEngine = when (backend) {
        PlaybackBackend.VLC -> vlcEngine
        PlaybackBackend.MEDIA3 -> media3Engine
    }

    private fun media3Engine(): Media3PlayerEngine = media3 ?: Media3PlayerEngine(
        context = vlcView.context,
        playerView = media3View,
        onEvent = { event -> onEvent(PlaybackBackend.MEDIA3, event) },
    ).also { media3 = it }
}
