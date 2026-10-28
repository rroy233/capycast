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
    fun isSeekable(): Boolean
    fun setVolume(volume: Int, reason: String)
    fun positionMs(): Long
    fun durationMs(): Long
    fun isPlaying(): Boolean
    fun volume(): Int
    fun onMainThread(action: () -> Unit)
    /** 同步协议查询必须在播放器所属线程读取状态。 */
    fun <T> readOnMainThread(action: () -> T): T
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
        host.onMainThread {
            host.setPendingPause(false)
            if (host.engineAvailable()) host.setPaused(false, "dlna-play")
            else LogHub.w(TAG, "dlna play ignored: player engine unavailable")
            host.showDlnaPlayState(true)
        }
    }

    override fun pause() {
        host.onMainThread {
            host.setPendingPause(true)
            if (host.engineAvailable()) host.setPaused(true, "dlna-pause")
            else LogHub.w(TAG, "dlna pause ignored: player engine unavailable")
            host.showDlnaPlayState(false)
        }
    }

    override fun stop() = host.onMainThread { host.stopCastFromDlna() }

    override fun seek(positionMs: Long) {
        host.onMainThread {
            if (host.currentIsLive()) {
                LogHub.w(TAG, "dlna seek rejected for live room=${host.currentCid()}")
                return@onMainThread
            }
            if (host.engineAvailable()) host.seekTo(positionMs, "dlna-seek")
            else LogHub.w(TAG, "dlna seek ignored: player engine unavailable")
            val position = positionMs.coerceAtLeast(0L)
            host.showDlnaSeekState(position)
            host.seekDanmaku(position)
        }
    }

    override fun isSeekable(): Boolean = host.readOnMainThread {
        !host.currentIsLive() && host.engineAvailable() && host.isSeekable()
    }

    override fun setVolume(volume: Int) {
        val value = volume.coerceIn(0, 100)
        host.onMainThread {
            if (host.engineAvailable()) host.setVolume(value, "dlna-volume")
            else LogHub.w(TAG, "dlna volume ignored: player engine unavailable")
            host.showDlnaVolumeState(value)
        }
    }

    override fun positionMs(): Long = host.readOnMainThread {
        if (host.currentIsLive() || !host.engineAvailable()) 0L else host.positionMs()
    }
    override fun durationMs(): Long = host.readOnMainThread {
        if (host.currentIsLive() || !host.engineAvailable()) 0L else host.durationMs()
    }
    override fun isPlaying(): Boolean = host.readOnMainThread {
        host.engineAvailable() && host.isPlaying()
    }
    override fun volume(): Int = host.readOnMainThread { host.volume() }

    private companion object { const val TAG = "SimpleBili" }
}
