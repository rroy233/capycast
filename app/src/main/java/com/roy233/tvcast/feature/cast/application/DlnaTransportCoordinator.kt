package com.roy233.tvcast.feature.cast.application

import com.roy233.tvcast.core.diagnostics.LogHub

/** DLNA 命令进入投屏会话后的播放与展示能力。 */
interface DlnaTransportHost {
    fun onCast(uri: String, title: String, longDescription: String)
    fun setPendingPause(paused: Boolean)
    fun engineAvailable(): Boolean
    fun setPaused(paused: Boolean, reason: String)
    fun stopCastFromDlna()
    fun currentIsLive(): Boolean
    fun currentCid(): Long?
    fun seekTo(positionMs: Long, reason: String)
    fun seekDanmaku(positionMs: Long)
    fun isVlcSeekable(): Boolean
    fun setVolume(volume: Int, reason: String)
    fun positionMs(): Long
    fun durationMs(): Long
    fun isPlaying(): Boolean
    fun volume(): Int
    fun onMainThread(action: () -> Unit)
    fun showDlnaPlayState(playing: Boolean)
    fun showDlnaSeekState(positionMs: Long)
    fun showDlnaVolumeState(volume: Int)
}

/** 协议适配器只向此端口传递语义命令。 */
interface DlnaCastCommands {
    fun cast(uri: String, title: String, longDescription: String)
    fun play()
    fun pause()
    fun stop()
    fun seek(positionMs: Long)
    fun isSeekable(): Boolean
    fun setVolume(volume: Int)
    fun positionMs(): Long
    fun durationMs(): Long
    fun isPlaying(): Boolean
    fun volume(): Int
}

/** DLNA 播放控制与当前投屏会话的交互。 */
class DlnaTransportCoordinator(private val host: DlnaTransportHost) : DlnaCastCommands {
    override fun cast(uri: String, title: String, longDescription: String) {
        host.onMainThread { host.onCast(uri, title, longDescription) }
    }

    override fun play() {
        host.setPendingPause(false)
        if (host.engineAvailable()) host.setPaused(false, "dlna-play")
        else LogHub.w(TAG, "dlna play ignored: player engine unavailable")
        host.onMainThread { host.showDlnaPlayState(true) }
    }

    override fun pause() {
        host.setPendingPause(true)
        if (host.engineAvailable()) host.setPaused(true, "dlna-pause")
        else LogHub.w(TAG, "dlna pause ignored: player engine unavailable")
        host.onMainThread { host.showDlnaPlayState(false) }
    }

    override fun stop() = host.stopCastFromDlna()

    override fun seek(positionMs: Long) {
        if (host.currentIsLive()) {
            LogHub.w(TAG, "dlna seek rejected for live room=${host.currentCid()}")
            return
        }
        if (host.engineAvailable()) host.seekTo(positionMs, "dlna-seek")
        else LogHub.w(TAG, "dlna seek ignored: player engine unavailable")
        host.onMainThread {
            val position = positionMs.coerceAtLeast(0L)
            host.showDlnaSeekState(position)
            host.seekDanmaku(position)
        }
    }

    override fun isSeekable(): Boolean = !host.currentIsLive() && host.isVlcSeekable()

    override fun setVolume(volume: Int) {
        val value = volume.coerceIn(0, 100)
        if (host.engineAvailable()) host.setVolume(value, "dlna-volume")
        else LogHub.w(TAG, "dlna volume ignored: player engine unavailable")
        host.onMainThread { host.showDlnaVolumeState(value) }
    }

    override fun positionMs(): Long = if (host.currentIsLive()) 0L else host.positionMs()
    override fun durationMs(): Long = if (host.currentIsLive()) 0L else host.durationMs()
    override fun isPlaying(): Boolean = host.isPlaying()
    override fun volume(): Int = host.volume()

    private companion object { const val TAG = "SimpleBili" }
}
