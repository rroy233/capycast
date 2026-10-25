package com.roy233.tvcast.core.playback

/** 播放后端标识；仅用于诊断与能力区分，调用方不再按后端逐方法分支。 */
enum class PlaybackBackend { VLC, MEDIA3 }

/**
 * 播放器后端统一契约。
 *
 * VLC 与 Media3 各自实现该接口，界面与业务层通过它执行播放传输操作，
 * 不再直接引用具体播放器 SDK。首轮只覆盖现有被调用的操作，行为逐项与原实现一致。
 */
interface PlaybackEngine {

    /** 当前实现对应的后端标识。 */
    val backend: PlaybackBackend

    /** 当前播放位置，单位为毫秒。 */
    fun positionMs(): Long

    /** 当前媒体时长，单位为毫秒；未知时返回 0。 */
    fun durationMs(): Long

    /** 当前是否正在播放。 */
    fun isPlaying(): Boolean

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
