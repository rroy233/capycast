package com.roy233.tvcast.app

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.platform.dlna.DlnaRenderer
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState

/** Android 宿主提供的播放器及主线程能力；协议回调的业务顺序由适配器维护。 */
interface DlnaCastHost {
    fun onCast(uri: String, title: String, longDescription: String)
    fun setPendingPause(paused: Boolean)
    fun engineAvailable(): Boolean
    fun setPaused(paused: Boolean, reason: String)
    fun finishHistory(reason: String)
    fun clearSession(reason: String)
    fun stopPlayback(reason: String)
    fun clearCurrentCast()
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
    fun updateState(transform: (TvUiState) -> TvUiState)
}

/** 将 DLNA 命令映射到当前投屏会话，保留原来的线程调度与清理顺序。 */
class DlnaCastListenerAdapter(private val host: DlnaCastHost) : DlnaRenderer.Listener {
    override fun onCast(uri: String, metadata: String, title: String, longDescription: String) {
        LogHub.i(
            TAG,
            "dlna cast uri=${LogRedaction.url(uri)} title=${title.take(80)} " +
                "metadata=${metadata.length}B longDescription=${longDescription.length}B",
        )
        host.onMainThread { host.onCast(uri, title, longDescription) }
    }

    override fun onPlay() {
        host.setPendingPause(false)
        if (host.engineAvailable()) host.setPaused(false, "dlna-play")
        else LogHub.w(TAG, "dlna play ignored: player engine unavailable")
        host.onMainThread { host.updateState { it.copy(isPlaying = true, hudVisible = true) } }
    }

    override fun onPause() {
        host.setPendingPause(true)
        if (host.engineAvailable()) host.setPaused(true, "dlna-pause")
        else LogHub.w(TAG, "dlna pause ignored: player engine unavailable")
        host.onMainThread { host.updateState { it.copy(isPlaying = false, hudVisible = true) } }
    }

    override fun onStop() {
        host.setPendingPause(false)
        host.finishHistory("dlna-stop")
        host.clearSession("dlna-stop")
        if (host.engineAvailable()) host.stopPlayback("dlna-stop")
        else LogHub.w(TAG, "dlna stop ignored: player engine unavailable")
        host.clearCurrentCast()
        LogHub.setCast("")
        LogHub.setPlay("")
        host.onMainThread {
            host.updateState { state ->
                state.copy(
                    mode = TvMode.IDLE,
                    destination = TvDestination.HOME,
                    sidebarVisible = true,
                    castTitle = "",
                    qualityLabel = "",
                    qualityDetail = "",
                    qualitySource = "",
                    audioLabel = "",
                    isPlaying = false,
                    isBuffering = false,
                    isLive = false,
                    liveFormat = "",
                    liveCdn = "",
                    liveBackupCount = 0,
                    positionMs = 0,
                    durationMs = 0,
                    hudVisible = false,
                    tracks = emptyList(),
                    audios = emptyList(),
                    toast = "投屏已结束",
                    showContinue = state.rememberPlaybackContent,
                )
            }
        }
    }

    override fun onSeek(positionMs: Long) {
        if (host.currentIsLive()) {
            LogHub.w(TAG, "dlna seek rejected for live room=${host.currentCid()}")
            return
        }
        if (host.engineAvailable()) host.seekTo(positionMs, "dlna-seek")
        else LogHub.w(TAG, "dlna seek ignored: player engine unavailable")
        host.onMainThread {
            host.updateState { it.copy(positionMs = positionMs.coerceAtLeast(0L), hudVisible = true) }
            host.seekDanmaku(positionMs.coerceAtLeast(0L))
        }
    }

    override fun isSeekable(): Boolean = !host.currentIsLive() && host.isVlcSeekable()

    override fun onSetVolume(volume: Int) {
        val value = volume.coerceIn(0, 100)
        if (host.engineAvailable()) host.setVolume(value, "dlna-volume")
        else LogHub.w(TAG, "dlna volume ignored: player engine unavailable")
        host.onMainThread { host.updateState { it.copy(volume = value, hudVisible = true) } }
    }

    override fun positionMs(): Long = if (host.currentIsLive()) 0L else host.positionMs()
    override fun durationMs(): Long = if (host.currentIsLive()) 0L else host.durationMs()
    override fun isPlaying(): Boolean = host.isPlaying()
    override fun volume(): Int = host.volume()

    private companion object { const val TAG = "SimpleBili" }
}
