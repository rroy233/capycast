package com.roy233.tvcast.app

import com.roy233.tvcast.core.lifecycle.ForegroundAvailability
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaServiceLifecycleTest {
    @Test
    fun `rapid background and foreground transitions stop before restarting`() {
        val foreground = ForegroundAvailability()
        foreground.setInteractive(true)
        foreground.setResumed(true)
        foreground.activate()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val entered = CountDownLatch(1)
        val resumeStart = CountDownLatch(1)
        val restarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val lifecycle =
            DlnaServiceLifecycle(
                start = {
                    calls += "start"
                    if (entered.count > 0) {
                        entered.countDown()
                        check(resumeStart.await(5, TimeUnit.SECONDS))
                    } else restarted.countDown()
                    true
                },
                stop = {
                    calls += "stop"
                    if (restarted.count == 0L) closed.countDown()
                },
                foreground = foreground,
            )
        try {
            lifecycle.setActive(true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            foreground.setResumed(false)
            lifecycle.setActive(false)
            foreground.setResumed(true)
            foreground.activate()
            lifecycle.setActive(true)
            resumeStart.countDown()
            assertTrue(restarted.await(5, TimeUnit.SECONDS))
        } finally {
            resumeStart.countDown()
            lifecycle.close()
        }
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals(2, calls.count { it == "start" })
        assertTrue(calls.indexOf("stop") in 1 until calls.lastIndexOf("start"))
        assertEquals("stop", calls.last())
    }
}
