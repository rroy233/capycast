package com.roy233.tvcast.app

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.feature.cast.application.DlnaCastCommands
import com.roy233.tvcast.platform.dlna.DlnaRenderer

/** 将 DLNA 协议回调逐条翻译为投屏会话的语义命令；业务顺序由会话协作者维护。 */
class DlnaCastListenerAdapter(private val commands: DlnaCastCommands) : DlnaRenderer.Listener {
    override fun onCast(uri: String, metadata: String, title: String, longDescription: String) {
        LogHub.i(
            TAG,
            "dlna cast uri=${LogRedaction.url(uri)} title=${title.take(80)} " +
                "metadata=${metadata.length}B longDescription=${longDescription.length}B",
        )
        commands.cast(uri, title, longDescription)
    }

    override fun onPlay() = commands.play()
    override fun onPause() = commands.pause()
    override fun onStop() = commands.stop()
    override fun onSeek(positionMs: Long) = commands.seek(positionMs)
    override fun isSeekable(): Boolean = commands.isSeekable()
    override fun onSetVolume(volume: Int) = commands.setVolume(volume)
    override fun positionMs(): Long = commands.positionMs()
    override fun durationMs(): Long = commands.durationMs()
    override fun isPlaying(): Boolean = commands.isPlaying()
    override fun volume(): Int = commands.volume()

    private companion object { const val TAG = "SimpleBili" }
}
