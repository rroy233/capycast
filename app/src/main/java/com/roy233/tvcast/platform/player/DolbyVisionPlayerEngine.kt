package com.roy233.tvcast.platform.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.data.bili.BiliApi

/**
 * 杜比视界专用的 Media3 播放器。
 *
 * Media3 将 fMP4 中的 Dolby Vision 配置、色彩信息和 profile 交给 Android MediaCodec，
 * 并通过 SurfaceView 直出，避免 libVLC 的 RGB 视频输出路径丢失 10-bit HDR 信息。
 */
class DolbyVisionPlayerEngine(
    context: Context,
    playerView: PlayerView,
    private val events: Events,
) {

    /** 向宿主报告播放器状态，保持 Activity 的 UI 与弹幕生命周期统一。 */
    interface Events {
        fun onPlaying()
        fun onPaused()
        fun onBuffering(buffering: Boolean)
        fun onEnded()
        fun onError(error: PlaybackException)
        fun onVideoSizeChanged(width: Int, height: Int)
    }

    private val player = ExoPlayer.Builder(context.applicationContext).build().also { instance ->
        playerView.player = instance
        instance.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> events.onBuffering(true)
                    Player.STATE_READY -> {
                        events.onBuffering(false)
                        if (instance.playWhenReady) events.onPlaying()
                    }
                    Player.STATE_ENDED -> events.onEnded()
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) events.onPlaying() else events.onPaused()
            }

            override fun onPlayerError(error: PlaybackException) = events.onError(error)

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                events.onVideoSizeChanged(videoSize.width, videoSize.height)
            }
        })
    }

    /** 打开分离的视频、音频 DASH 表示层，并让 Media3 负责 Dolby Vision 解码。 */
    fun open(info: BiliApi.PlayInfo, seekToMs: Long) {
        require(info.video.isDolbyVision) { "Dolby Vision engine requires a Dolby Vision track" }
        val video = progressiveSource(info.video)
        val mediaSource = info.audio?.takeIf { it.url.isNotBlank() }
            ?.let { MergingMediaSource(video, progressiveSource(it)) }
            ?: video
        PlaybackDiagnostics.ensureSession("dolby-vision-open")
        PlaybackDiagnostics.state("opening")
        PlaybackDiagnostics.attribute("player.backend", "media3")
        PlaybackDiagnostics.attribute("stream.dynamicRange", "dolbyVision")
        PlaybackDiagnostics.attribute("stream.codecString", info.video.codecs)
        PlaybackDiagnostics.attribute("stream.mimeType", info.video.mimeType)
        PlaybackDiagnostics.attribute("stream.audioCodecString", info.audio?.codecs.orEmpty())
        LogHub.i(
            TAG,
            "open Dolby Vision qn=${info.video.id} codecs=${info.video.codecs} " +
                "video=${LogRedaction.url(info.video.url)} audio=${LogRedaction.url(info.audio?.url)}",
        )
        player.setMediaSource(mediaSource)
        player.seekTo(seekToMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = true
    }

    /** 暂停或恢复。 */
    fun setPaused(paused: Boolean) {
        if (paused) player.pause() else player.play()
    }

    /** 定位，返回已经限幅后的目标位置。 */
    fun seekTo(positionMs: Long): Long {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val target = if (duration == null) positionMs.coerceAtLeast(0L) else positionMs.coerceIn(0L, duration)
        player.seekTo(target)
        return target
    }

    /** 设置播放速率。 */
    fun setPlaybackRate(rate: Float): Float {
        val target = rate.coerceIn(MIN_PLAYBACK_RATE, MAX_PLAYBACK_RATE)
        player.setPlaybackSpeed(target)
        return target
    }

    /** 设置音量，沿用界面的 0..100 标度。 */
    fun setVolume(volume: Int) {
        player.volume = volume.coerceIn(0, 100) / 100f
    }

    fun stop() = player.stop()
    fun release() = player.release()
    fun positionMs(): Long = player.currentPosition.coerceAtLeast(0L)
    fun durationMs(): Long = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
    fun isPlaying(): Boolean = player.isPlaying
    fun volume(): Int = (player.volume * 100).toInt().coerceIn(0, 100)

    private fun progressiveSource(track: BiliApi.Track): MediaSource {
        val headers = mapOf(
            "Referer" to "https://www.bilibili.com/",
            "User-Agent" to BiliApi.UA,
        )
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(BiliApi.UA)
            .setDefaultRequestProperties(headers)
        val item = MediaItem.Builder()
            .setUri(track.url)
            .setMimeType(track.mimeType.ifBlank { null })
            .build()
        return ProgressiveMediaSource.Factory(factory).createMediaSource(item)
    }

    private companion object {
        const val TAG = "DolbyVisionPlayer"
        const val MIN_PLAYBACK_RATE = 0.25f
        const val MAX_PLAYBACK_RATE = 4f
    }
}
