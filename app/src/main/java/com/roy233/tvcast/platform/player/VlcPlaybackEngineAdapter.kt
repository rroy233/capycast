package com.roy233.tvcast.platform.player

import com.roy233.tvcast.core.playback.PlaybackBackend
import com.roy233.tvcast.core.playback.PlaybackEngine
import com.roy233.tvcast.core.playback.PlaybackRequest

/** 将 VLC 播放器门面适配为统一播放契约。 */
class VlcPlaybackEngineAdapter(
    private val engine: VlcPlayerEngine,
) : PlaybackEngine {

    override val backend: PlaybackBackend = PlaybackBackend.VLC

    override fun open(request: PlaybackRequest, seekToMs: Long): Boolean =
        engine.open(request, seekToMs)

    override fun release() = engine.release()

    override fun positionMs(): Long =
        runCatching { engine.player.time.coerceAtLeast(0L) }.getOrDefault(0L)

    override fun durationMs(): Long =
        runCatching { engine.player.length.coerceAtLeast(0L) }.getOrDefault(0L)

    override fun isPlaying(): Boolean =
        runCatching { engine.player.isPlaying }.getOrDefault(false)

    override fun volume(fallback: Int): Int =
        runCatching { engine.player.volume }.getOrDefault(fallback)

    override fun seekTo(positionMs: Long, reason: String): Long =
        engine.seekTo(positionMs, reason)

    override fun setPaused(paused: Boolean, reason: String) =
        engine.setPaused(paused, reason)

    override fun setPlaybackRate(rate: Float, reason: String): Float =
        engine.setPlaybackRate(rate, reason)

    override fun setVolume(volume: Int, reason: String) =
        engine.setPlayerVolume(volume, reason)

    override fun stop(reason: String) = engine.stopPlayback(reason)
}
