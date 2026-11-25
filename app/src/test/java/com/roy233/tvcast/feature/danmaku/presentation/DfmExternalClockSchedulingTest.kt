package com.roy233.tvcast.feature.danmaku.presentation

import com.roy233.tvcast.feature.danmaku.domain.MediaClock
import java.util.LinkedList
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.renderer.IRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises DFM's real timing branch without constructing Android's Handler on the JVM. */
class DfmExternalClockSchedulingTest {
    private val unsafeClass = Class.forName("sun.misc.Unsafe")
    private val unsafe =
        unsafeClass.getDeclaredField("theUnsafe").let {
            it.isAccessible = true
            it.get(null)
        }

    private fun allocate(type: Class<*>): Any =
        requireNotNull(
            unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type),
        )

    private fun field(instance: Any, name: String, value: Any) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }

    private fun handler(timer: DanmakuTimer): DrawHandler {
        val handler = allocate(DrawHandler::class.java) as DrawHandler
        val state = allocate(IRenderer.RenderingState::class.java) as IRenderer.RenderingState
        state.consumingTime = 100L
        state.nothingRendered = false
        field(handler, "timer", timer)
        field(handler, "mRenderingState", state)
        field(handler, "mDanmakusVisible", true)
        field(handler, "mFrameUpdateRate", 16L)
        field(handler, "mCordonTime", 40L)
        field(handler, "mDrawTimes", LinkedList<Long>())
        return handler
    }

    private fun sync(handler: DrawHandler, wallMs: Long): Long =
        DrawHandler::class.java.getDeclaredMethod("syncTimer", Long::class.javaPrimitiveType).let {
            it.isAccessible = true
            it.invoke(handler, wallMs) as Long
        }

    @Test
    fun upstreamSchedulerCanReturnNegativeAfterSlowFrame() {
        val timer = DanmakuTimer()
        timer.update(2_000L)
        assertTrue(sync(handler(timer), 1_000L) < 0L)
    }

    @Test
    fun slowFrameAndMediaTimeAheadCannotStopFrameScheduling() {
        var nanos = 0L
        val clock = MediaClock { nanos }
        clock.start(1_000L)
        clock.setRate(1.5f)
        val timer = MediaDanmakuTimer(clock)
        val handler = handler(timer)

        repeat(100) { frame ->
            nanos += 100_000_000L
            val result = sync(handler, 1_000L + (frame + 1) * 100L)
            assertTrue("frame $frame was skipped", result >= 0L)
            assertEquals(1_000L + (frame + 1) * 150L, timer.currMillisecond)
        }
    }

    @Test
    fun externalTimelineSurvivesRateChangesAndPausedSeek() {
        var nanos = 0L
        val clock = MediaClock { nanos }
        clock.start(4_000L)
        val timer = MediaDanmakuTimer(clock)
        val handler = handler(timer)

        clock.setRate(0.5f)
        nanos += 2_000_000_000L
        assertTrue(sync(handler, 6_000L) >= 0L)
        assertEquals(5_000L, timer.currMillisecond)
        clock.pause(12_000L)
        nanos += 1_000_000_000L
        assertTrue(sync(handler, 7_000L) >= 0L)
        assertEquals(12_000L, timer.currMillisecond)
        clock.rebase(12_000L)
        clock.setRate(2f)
        nanos += 1_000_000_000L
        assertTrue(sync(handler, 8_000L) >= 0L)
        assertEquals(14_000L, timer.currMillisecond)
    }
}
