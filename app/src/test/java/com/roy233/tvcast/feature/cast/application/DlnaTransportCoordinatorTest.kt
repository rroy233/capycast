package com.roy233.tvcast.feature.cast.application

import com.roy233.tvcast.core.lifecycle.ForegroundAvailability
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaTransportCoordinatorTest {
    private val foreground =
        ForegroundAvailability().apply {
            setInteractive(true)
            setResumed(true)
            activate()
        }

    private val generation: Long
        get() = requireNotNull(foreground.capture())

    @Test
    fun `queued remote play is discarded when foreground session changes`() {
        val calls = mutableListOf<String>()
        val queued = mutableListOf<() -> Unit>()
        val coordinator =
            DlnaTransportCoordinator(
                host(calls, HostState(engineAvailable = true), queued),
                foreground,
            )
        coordinator.play(generation)
        foreground.setResumed(false)
        foreground.setResumed(true)
        foreground.activate()
        queued.forEach { it() }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `remote commands are not queued in background`() {
        val calls = mutableListOf<String>()
        val queued = mutableListOf<() -> Unit>()
        val priorGeneration = generation
        foreground.setResumed(false)
        val coordinator = DlnaTransportCoordinator(host(calls, queue = queued), foreground)
        coordinator.cast("uri", "title", "metadata", priorGeneration)
        coordinator.play(priorGeneration)
        coordinator.pause(priorGeneration)
        coordinator.seek(1000, priorGeneration)
        coordinator.stop(priorGeneration)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `request from prior foreground remains invalid after return`() {
        val calls = mutableListOf<String>()
        val queued = mutableListOf<() -> Unit>()
        val priorGeneration = generation
        foreground.setResumed(false)
        foreground.setResumed(true)
        foreground.activate()
        val coordinator = DlnaTransportCoordinator(host(calls, queue = queued), foreground)
        coordinator.cast("uri", "title", "metadata", priorGeneration)
        coordinator.play(priorGeneration)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `stop delegates to the host session teardown`() {
        val calls = mutableListOf<String>()
        DlnaTransportCoordinator(host(calls), foreground).stop(generation)
        assertEquals(listOf("stopCastFromDlna"), calls)
    }

    @Test
    fun `play and pause marshal playback state to the main thread`() {
        val calls = mutableListOf<String>()
        val coordinator =
            DlnaTransportCoordinator(host(calls, HostState(engineAvailable = true)), foreground)

        coordinator.play(generation)
        coordinator.pause(generation)

        assertEquals(
            listOf(
                "pending:false",
                "paused:false",
                "play-state:true",
                "pending:true",
                "paused:true",
                "play-state:false",
            ),
            calls,
        )
    }

    @Test
    fun `live transport rejects seeking and reports zero duration`() {
        val calls = mutableListOf<String>()
        val state = HostState(isLive = true, seekable = true, engineAvailable = true)
        val coordinator = DlnaTransportCoordinator(host(calls, state), foreground)

        coordinator.seek(2000L, generation)

        assertFalse(coordinator.isSeekable())
        assertEquals(0L, coordinator.positionMs())
        assertEquals(0L, coordinator.durationMs())
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `vod transport reports seekable from the active backend`() {
        val calls = mutableListOf<String>()
        val state = HostState(isLive = false, seekable = true, engineAvailable = true)
        val coordinator = DlnaTransportCoordinator(host(calls, state), foreground)

        assertEquals(true, coordinator.isSeekable())
    }

    @Test
    fun `seek clamps to zero and forwards danmaku position`() {
        val calls = mutableListOf<String>()
        val state = HostState(isLive = false, seekable = true, engineAvailable = true)
        val coordinator = DlnaTransportCoordinator(host(calls, state), foreground)

        coordinator.seek(-500L, generation)

        assertEquals(listOf("seekTo:-500", "seek-state:0", "seekDanmaku:0"), calls)
    }

    @Test
    fun `volume command is ignored by Bilibili cast policy`() {
        val calls = mutableListOf<String>()
        val queued = mutableListOf<() -> Unit>()
        val coordinator = DlnaTransportCoordinator(host(calls, queue = queued), foreground)

        coordinator.setVolume(0)

        assertTrue(calls.isEmpty())
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `SOAP worker commands and queries cross the playback main thread gate`() {
        val calls = mutableListOf<String>()
        val queued = mutableListOf<() -> Unit>()
        val state = HostState(engineAvailable = true, seekable = true, enforceMainAccess = true)
        val coordinator = DlnaTransportCoordinator(host(calls, state, queued), foreground)

        coordinator.play(generation)
        coordinator.pause(generation)
        coordinator.seek(2_000L, generation)
        coordinator.setVolume(75)
        coordinator.stop(generation)
        assertTrue(calls.isEmpty())
        assertEquals(4, queued.size)

        queued.forEach { it() }
        assertEquals(
            listOf(
                "pending:false",
                "paused:false",
                "play-state:true",
                "pending:true",
                "paused:true",
                "play-state:false",
                "seekTo:2000",
                "seek-state:2000",
                "seekDanmaku:2000",
                "stopCastFromDlna",
            ),
            calls,
        )
        assertTrue(coordinator.isSeekable())
        assertEquals(5_000L, coordinator.positionMs())
        assertEquals(9_000L, coordinator.durationMs())
        assertTrue(coordinator.isPlaying())
        assertEquals(30, coordinator.volume())
    }

    private class HostState(
        val isLive: Boolean = false,
        val seekable: Boolean = false,
        val engineAvailable: Boolean = false,
        val enforceMainAccess: Boolean = false,
    ) {
        var onMain = false

        fun verifyThread() {
            if (enforceMainAccess) assertTrue("player accessed off main thread", onMain)
        }

        fun <T> onMain(action: () -> T): T {
            val prior = onMain
            onMain = true
            return try {
                action()
            } finally {
                onMain = prior
            }
        }
    }

    private fun host(
        calls: MutableList<String>,
        state: HostState = HostState(),
        queue: MutableList<() -> Unit>? = null,
    ): DlnaTransportHost =
        Proxy.newProxyInstance(
            DlnaTransportHost::class.java.classLoader,
            arrayOf(DlnaTransportHost::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "onCast" -> Unit
                "setPendingPause" -> {
                    state.verifyThread()
                    calls += "pending:${arguments[0]}"
                    Unit
                }
                "engineAvailable" -> {
                    state.verifyThread()
                    state.engineAvailable
                }
                "setPaused" -> {
                    state.verifyThread()
                    calls += "paused:${arguments[0]}"
                    Unit
                }
                "stopCastFromDlna" -> {
                    state.verifyThread()
                    calls += method.name
                    Unit
                }
                "currentIsLive" -> {
                    state.verifyThread()
                    state.isLive
                }
                "currentCid" -> {
                    state.verifyThread()
                    123L
                }
                "seekTo" -> {
                    state.verifyThread()
                    calls += "seekTo:${arguments[0]}"
                    Unit
                }
                "seekDanmaku" -> {
                    state.verifyThread()
                    calls += "seekDanmaku:${arguments[0]}"
                    Unit
                }
                "isSeekable" -> {
                    state.verifyThread()
                    state.seekable
                }
                "positionMs" -> {
                    state.verifyThread()
                    5000L
                }
                "durationMs" -> {
                    state.verifyThread()
                    9000L
                }
                "isPlaying" -> {
                    state.verifyThread()
                    true
                }
                "volume" -> {
                    state.verifyThread()
                    30
                }
                "onMainThread" -> {
                    val action = arguments[0] as Function0<*>
                    if (queue == null) state.onMain { action.invoke() }
                    else
                        queue += {
                            state.onMain { action.invoke() }
                            Unit
                        }
                    Unit
                }
                "readOnMainThread" -> state.onMain { (arguments[0] as Function0<*>).invoke() }
                "showDlnaPlayState" -> {
                    calls += "play-state:${arguments[0]}"
                    Unit
                }
                "showDlnaSeekState" -> {
                    calls += "seek-state:${arguments[0]}"
                    Unit
                }
                else -> defaultReturn(method.returnType)
            }
        } as DlnaTransportHost

    private fun defaultReturn(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Long.TYPE -> 0L
            java.lang.Integer.TYPE -> 0
            else -> null
        }
}
