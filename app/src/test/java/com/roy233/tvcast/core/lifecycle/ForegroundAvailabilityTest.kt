package com.roy233.tvcast.core.lifecycle

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundAvailabilityTest {
    @Test
    fun `requires resumed top activity and interactive device`() {
        val state = ForegroundAvailability()
        state.setTopResumed(false)
        state.setResumed(true)
        state.setInteractive(true)
        assertFalse(state.isAvailable)
        assertEquals(ForegroundAvailability.Transition.AVAILABLE, state.setTopResumed(true))
        assertFalse(state.isAvailable)
        state.activate()
        assertTrue(state.isAvailable)
        assertEquals(ForegroundAvailability.Transition.UNAVAILABLE, state.setInteractive(false))
        assertFalse(state.isAvailable)
    }

    @Test
    fun `backgrounding invalidates queued foreground commands even after return`() {
        val state = ForegroundAvailability()
        state.setResumed(true)
        state.setInteractive(true)
        state.activate()
        val prior = requireNotNull(state.capture())
        state.setResumed(false)
        state.setResumed(true)
        state.activate()
        assertFalse(state.isCurrent(prior))
        assertTrue(state.isCurrent(requireNotNull(state.capture())))
    }

    @Test
    fun `asynchronous result waits for foreground`() = runBlocking {
        val state = ForegroundAvailability()
        val result = async {
            state.awaitAvailable()
            "ready"
        }
        yield()
        assertFalse(result.isCompleted)
        state.setInteractive(true)
        state.setResumed(true)
        yield()
        assertFalse(result.isCompleted)
        state.activate()
        assertEquals("ready", result.await())
    }
}
