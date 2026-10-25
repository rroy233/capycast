package com.roy233.tvcast.data.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodRecoveryControllerTest {

    @Test
    fun `startup and initial seek buffering never consume CDN candidates`() {
        val controller = VodRecoveryController()
        controller.onOpening(positionMs = 2_000L, nowMs = 0L)

        assertTrue(controller.onBufferingChanged(buffering = true, nowMs = 10L).ignored)
        assertTrue(controller.onBufferingChanged(buffering = false, nowMs = 600L).ignored)
        controller.onPlaying(positionMs = 2_000L, nowMs = 700L)
        controller.onSeek(positionMs = 2_000L, nowMs = 710L)

        assertTrue(controller.onBufferingChanged(buffering = true, nowMs = 720L).ignored)
        assertTrue(controller.onBufferingChanged(buffering = false, nowMs = 1_900L).ignored)
        assertEquals(0, controller.rebufferCount)
        assertNull(controller.onTick(2_000L))
    }

    @Test
    fun `two severe buffering episodes after stable playback request recovery`() {
        val controller = stableController()

        controller.onBufferingChanged(buffering = true, nowMs = 3_000L)
        val first = controller.onBufferingChanged(buffering = false, nowMs = 4_100L)
        assertTrue(first.countedRebuffer)
        assertNull(first.failure)

        controller.onProgress(positionMs = 3_500L, activelyPlaying = true, nowMs = 6_200L)
        controller.onBufferingChanged(buffering = true, nowMs = 6_300L)
        val second = controller.onBufferingChanged(buffering = false, nowMs = 7_400L)

        assertTrue(second.countedRebuffer)
        assertEquals(VodRecoveryController.Failure.TWO_SEVERE_REBUFFERS, second.failure)
    }

    @Test
    fun `healthy interval resets accumulated rebuffer history`() {
        val controller = stableController()
        controller.onBufferingChanged(buffering = true, nowMs = 3_000L)
        controller.onBufferingChanged(buffering = false, nowMs = 4_100L)
        controller.onProgress(positionMs = 40_000L, activelyPlaying = true, nowMs = 35_000L)

        controller.onBufferingChanged(buffering = true, nowMs = 35_100L)
        val later = controller.onBufferingChanged(buffering = false, nowMs = 36_200L)

        assertEquals(1, controller.rebufferCount)
        assertNull(later.failure)
    }

    @Test
    fun `persistent buffering and open timeout are each emitted once`() {
        val buffering = stableController()
        buffering.onBufferingChanged(buffering = true, nowMs = 3_000L)
        assertEquals(VodRecoveryController.Failure.PERSISTENT_BUFFERING, buffering.onTick(8_000L))
        assertNull(buffering.onTick(9_000L))

        val opening = VodRecoveryController()
        opening.onOpening(positionMs = 0L, nowMs = 0L)
        assertEquals(VodRecoveryController.Failure.OPEN_TIMEOUT, opening.onTick(12_000L))
        assertNull(opening.onTick(13_000L))
    }

    @Test
    fun `initial playing cancels open timeout during later stabilization`() {
        val controller = VodRecoveryController()
        controller.onOpening(positionMs = 0L, nowMs = 0L)
        controller.onPlaying(positionMs = 0L, nowMs = 100L)

        assertNull(controller.onTick(12_000L))
    }

    @Test
    fun `switching suppresses stale buffering callbacks`() {
        val controller = stableController()
        controller.onSwitching()

        val update = controller.onBufferingChanged(buffering = true, nowMs = 3_000L)

        assertTrue(update.ignored)
        assertFalse(update.countedRebuffer)
        assertEquals(VodRecoveryController.Phase.SWITCHING, update.phase)
    }

    private fun stableController(): VodRecoveryController = VodRecoveryController().also { controller ->
        controller.onOpening(positionMs = 0L, nowMs = 0L)
        controller.onPlaying(positionMs = 0L, nowMs = 100L)
        val progress = controller.onProgress(positionMs = 1_500L, activelyPlaying = true, nowMs = 2_200L)
        assertTrue(progress.becameStable)
    }
}
