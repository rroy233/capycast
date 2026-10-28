package com.roy233.tvcast.feature.cast.application

import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DlnaTransportCoordinatorTest {
    @Test
    fun `stop delegates to the host session teardown`() {
        val calls = mutableListOf<String>()
        DlnaTransportCoordinator(host(calls)).stop()
        assertEquals(listOf("stopCastFromDlna"), calls)
    }

    @Test
    fun `play and pause marshal playback state to the main thread`() {
        val calls = mutableListOf<String>()
        val coordinator = DlnaTransportCoordinator(host(calls, HostState(engineAvailable = true)))

        coordinator.play()
        coordinator.pause()

        assertEquals(
            listOf("pending:false", "paused:false", "play-state:true", "pending:true", "paused:true", "play-state:false"),
            calls,
        )
    }

    @Test
    fun `live transport rejects seeking and reports zero duration`() {
        val calls = mutableListOf<String>()
        val state = HostState(isLive = true, vlcSeekable = true, engineAvailable = true)
        val coordinator = DlnaTransportCoordinator(host(calls, state))

        coordinator.seek(2000L)

        assertFalse(coordinator.isSeekable())
        assertEquals(0L, coordinator.positionMs())
        assertEquals(0L, coordinator.durationMs())
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `seek clamps to zero and forwards danmaku position`() {
        val calls = mutableListOf<String>()
        val state = HostState(isLive = false, vlcSeekable = true, engineAvailable = true)
        val coordinator = DlnaTransportCoordinator(host(calls, state))

        coordinator.seek(-500L)

        assertEquals(listOf("seekTo:-500", "seek-state:0", "seekDanmaku:0"), calls)
    }

    @Test
    fun `volume clamps to range before forwarding`() {
        val calls = mutableListOf<String>()
        val state = HostState(engineAvailable = true)
        val coordinator = DlnaTransportCoordinator(host(calls, state))

        coordinator.setVolume(150)

        assertEquals(listOf("volume:100", "volume-state:100"), calls)
    }

    private class HostState(
        val isLive: Boolean = false,
        val vlcSeekable: Boolean = false,
        val engineAvailable: Boolean = false,
    )

    private fun host(calls: MutableList<String>, state: HostState = HostState()): DlnaTransportHost =
        Proxy.newProxyInstance(
            DlnaTransportHost::class.java.classLoader,
            arrayOf(DlnaTransportHost::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "onCast" -> Unit
                "setPendingPause" -> { calls += "pending:${arguments[0]}"; Unit }
                "engineAvailable" -> state.engineAvailable
                "setPaused" -> { calls += "paused:${arguments[0]}"; Unit }
                "stopCastFromDlna" -> { calls += method.name; Unit }
                "currentIsLive" -> state.isLive
                "currentCid" -> 123L
                "seekTo" -> { calls += "seekTo:${arguments[0]}"; Unit }
                "seekDanmaku" -> { calls += "seekDanmaku:${arguments[0]}"; Unit }
                "isVlcSeekable" -> state.vlcSeekable
                "setVolume" -> { calls += "volume:${arguments[0]}"; Unit }
                "positionMs" -> 5000L
                "durationMs" -> 9000L
                "isPlaying" -> true
                "volume" -> 30
                "onMainThread" -> { (arguments[0] as Function0<*>).invoke(); Unit }
                "showDlnaPlayState" -> { calls += "play-state:${arguments[0]}"; Unit }
                "showDlnaSeekState" -> { calls += "seek-state:${arguments[0]}"; Unit }
                "showDlnaVolumeState" -> { calls += "volume-state:${arguments[0]}"; Unit }
                else -> defaultReturn(method.returnType)
            }
        } as DlnaTransportHost

    private fun defaultReturn(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Long.TYPE -> 0L
        java.lang.Integer.TYPE -> 0
        else -> null
    }
}
