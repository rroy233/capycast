package com.roy233.tvcast.platform.player

import androidx.media3.common.util.UnstableApi
import com.roy233.tvcast.core.playback.PlaybackBackend
import com.roy233.tvcast.core.playback.PlaybackEngine
import com.roy233.tvcast.core.playback.PlaybackRequest

/**
 * 将 Media3 播放器门面适配为统一播放契约。
 *
 * Media3 播放器按需创建；尚未创建时各读取方法返回空值语义。
 */
@UnstableApi
class Media3PlaybackEngineAdapter(
    private val engine: () -> Media3PlayerEngine?,
) : PlaybackEngine {

    override val backend: PlaybackBackend = PlaybackBackend.MEDIA3

    override fun open(request: PlaybackRequest, seekToMs: Long): Boolean {
        engine()?.open(request, seekToMs) ?: return false
        return true
    }

    override fun release() = engine()?.release() ?: Unit

    override fun positionMs(): Long = engine()?.positionMs() ?: 0L

    override fun durationMs(): Long = engine()?.durationMs() ?: 0L

    override fun isPlaying(): Boolean = engine()?.isPlaying() ?: false

    override fun volume(fallback: Int): Int = engine()?.volume() ?: fallback

    override fun seekTo(positionMs: Long, reason: String): Long =
        engine()?.seekTo(positionMs) ?: positionMs

    override fun setPaused(paused: Boolean, reason: String) {
        engine()?.setPaused(paused)
    }

    override fun setPlaybackRate(rate: Float, reason: String): Float =
        engine()?.setPlaybackRate(rate) ?: rate

    override fun setVolume(volume: Int, reason: String) {
        engine()?.setVolume(volume)
    }

    override fun stop(reason: String) {
        engine()?.stop()
    }
}
