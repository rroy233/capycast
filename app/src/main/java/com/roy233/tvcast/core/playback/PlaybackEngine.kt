package com.roy233.tvcast.core.playback

/** 播放后端标识，用于诊断与能力区分。 */
enum class PlaybackBackend { VLC, MEDIA3 }

/**
 * 播放器后端统一契约。
 *
 * VLC 与 Media3 各自实现该接口，界面与业务层通过它执行播放传输操作，
 * 不直接引用具体播放器 SDK。
 */
interface PlaybackEngine {

    /** 当前实现对应的后端标识。 */
    val backend: PlaybackBackend

    /** 打开媒体并开始播放；返回是否已发起打开流程。 */
    fun open(request: PlaybackRequest, seekToMs: Long): Boolean

    /** 释放后端和它拥有的原生资源。 */
    fun release()

    /** 返回当前播放器状态的不可变快照。 */
    fun snapshot(fallbackVolume: Int): PlaybackSnapshot = PlaybackSnapshot(
        positionMs = positionMs(),
        durationMs = durationMs(),
        isPlaying = isPlaying(),
        volume = volume(fallbackVolume),
        backend = backend,
    )

    /** 当前播放位置，单位为毫秒。 */
    fun positionMs(): Long

    /** 当前媒体时长，单位为毫秒；未知时返回 0。 */
    fun durationMs(): Long

    /** 当前是否正在播放。 */
    fun isPlaying(): Boolean

    /** 当前媒体是否支持定位。 */
    fun isSeekable(): Boolean

    /** 当前音量；后端无法读取时返回调用方提供的回退值。 */
    fun volume(fallback: Int): Int

    /** 跳转到指定位置，返回实际生效的位置。 */
    fun seekTo(positionMs: Long, reason: String): Long

    /** 暂停或恢复播放。 */
    fun setPaused(paused: Boolean, reason: String)

    /** 设置播放速率，返回实际生效的速率。 */
    fun setPlaybackRate(rate: Float, reason: String): Float

    /** 设置音量，沿用界面的 0..100 标度。 */
    fun setVolume(volume: Int, reason: String)

    /** 停止当前播放。 */
    fun stop(reason: String)
}
