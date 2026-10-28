package com.roy233.tvcast.app

import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DlnaCastListenerAdapterTest {
    @Test
    fun `stop preserves session cleanup order and completion state`() {
        val calls = mutableListOf<String>()
        var state = TvUiState(mode = TvMode.PLAYING, castActive = true, rememberPlaybackContent = false)
        val host = host { method, arguments ->
            when (method) {
                "engineAvailable" -> true
                "onMainThread" -> { (arguments[0] as Function0<*>).invoke(); Unit }
                "updateState" -> {
                    @Suppress("UNCHECKED_CAST")
                    state = (arguments[0] as (TvUiState) -> TvUiState)(state)
                    Unit
                }
                "setPendingPause" -> { calls += "pending:${arguments[0]}"; Unit }
                "finishHistory" -> { calls += "history:${arguments[0]}"; Unit }
                "clearSession" -> { calls += "session:${arguments[0]}"; Unit }
                "stopPlayback" -> { calls += "stop:${arguments[0]}"; Unit }
                "clearCurrentCast" -> { calls += "clear-current"; Unit }
                else -> Unit
            }
        }

        DlnaCastListenerAdapter(host).onStop()

        assertEquals(
            listOf("pending:false", "history:dlna-stop", "session:dlna-stop", "stop:dlna-stop", "clear-current"),
            calls,
        )
        assertEquals(TvMode.IDLE, state.mode)
        assertEquals("投屏已结束", state.toast)
        assertFalse(state.showContinue)
    }

    @Test
    fun `live transport rejects seeking and reports zero duration`() {
        val calls = mutableListOf<String>()
        val host = host { method, _ ->
            when (method) {
                "currentIsLive" -> true
                "currentCid" -> 123L
                "isVlcSeekable" -> true
                "positionMs", "durationMs" -> { calls += method; 5000L }
                "seekTo" -> { calls += method; Unit }
                else -> Unit
            }
        }

        val listener = DlnaCastListenerAdapter(host)
        listener.onSeek(2000L)

        assertFalse(listener.isSeekable())
        assertEquals(0L, listener.positionMs())
        assertEquals(0L, listener.durationMs())
        assertEquals(emptyList<String>(), calls)
    }

    private fun host(handle: (String, Array<out Any?>) -> Any?): DlnaCastHost =
        Proxy.newProxyInstance(
            DlnaCastHost::class.java.classLoader,
            arrayOf(DlnaCastHost::class.java),
        ) { _, method, arguments -> handle(method.name, arguments ?: emptyArray()) } as DlnaCastHost
}
