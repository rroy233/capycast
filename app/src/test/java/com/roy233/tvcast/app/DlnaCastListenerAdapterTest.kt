package com.roy233.tvcast.app

import com.roy233.tvcast.feature.cast.application.DlnaCastCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DlnaCastListenerAdapterTest {
    @Test
    fun `protocol callbacks translate to semantic commands`() {
        val calls = mutableListOf<String>()
        val commands = RecordingCommands(calls)

        val listener = DlnaCastListenerAdapter(commands)
        listener.onPlay()
        listener.onPause()
        listener.onStop()
        listener.onSeek(2000L)
        listener.onSetVolume(40)

        assertEquals(listOf("play", "pause", "stop", "seek:2000", "volume:40"), calls)
    }

    @Test
    fun `query callbacks forward to commands`() {
        val calls = mutableListOf<String>()
        val listener = DlnaCastListenerAdapter(RecordingCommands(calls))

        listener.isSeekable()
        listener.positionMs()
        listener.durationMs()
        listener.isPlaying()
        listener.volume()

        assertEquals(listOf("isSeekable", "positionMs", "durationMs", "isPlaying", "volume"), calls)
    }

    private class RecordingCommands(private val calls: MutableList<String>) : DlnaCastCommands {
        override fun cast(uri: String, title: String, longDescription: String) {
            calls += "cast:$uri:$title"
        }

        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop" }
        override fun seek(positionMs: Long) { calls += "seek:$positionMs" }
        override fun isSeekable(): Boolean { calls += "isSeekable"; return false }
        override fun setVolume(volume: Int) { calls += "volume:$volume" }
        override fun positionMs(): Long { calls += "positionMs"; return 0L }
        override fun durationMs(): Long { calls += "durationMs"; return 0L }
        override fun isPlaying(): Boolean { calls += "isPlaying"; return false }
        override fun volume(): Int { calls += "volume"; return 0 }
    }
}
