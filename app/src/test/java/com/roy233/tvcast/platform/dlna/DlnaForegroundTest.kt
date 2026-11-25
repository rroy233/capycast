package com.roy233.tvcast.platform.dlna

import com.roy233.tvcast.core.lifecycle.ForegroundAvailability
import java.lang.reflect.Proxy
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaForegroundTest {
    @Test
    fun `stale SOAP commands cannot alter transport after foreground changes`() {
        val foreground = ForegroundAvailability()
        foreground.setInteractive(true)
        foreground.setResumed(true)
        foreground.activate()
        val oldGeneration = requireNotNull(foreground.capture())
        foreground.setResumed(false)
        foreground.setResumed(true)
        foreground.activate()
        val state = DlnaTransportState().apply { transport = "PAUSED_PLAYBACK" }
        val listener =
            Proxy.newProxyInstance(
                DlnaRenderer.Listener::class.java.classLoader,
                arrayOf(DlnaRenderer.Listener::class.java),
            ) { _, _, _ ->
                error("stale command reached listener")
            } as DlnaRenderer.Listener
        val dispatcher = SoapActionDispatcher(state, listener, foreground)
        val response =
            dispatcher.dispatch(SoapRequest("service", "Play", emptyMap()), oldGeneration)
        assertTrue(response.toString(Charsets.UTF_8).contains("<errorCode>701</errorCode>"))
        assertEquals("PAUSED_PLAYBACK", state.transport)
    }

    @Test
    fun `socket retains its admission generation while request body is delayed`() {
        val generation = AtomicLong(1)
        val accepted = CountDownLatch(1)
        val received = CountDownLatch(1)
        val captured = AtomicLong(-1)
        val server =
            DlnaHttpServer(
                0,
                onGet = { DlnaHttpResponse(200, "OK", "text/plain", byteArrayOf()) },
                onPost = { _, _, _, value ->
                    captured.set(value ?: -1)
                    received.countDown()
                    DlnaHttpResponse(200, "OK", "text/plain", byteArrayOf())
                },
                captureGeneration = { generation.get().also { accepted.countDown() } },
            )
        server.start()
        try {
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5000
                val output = socket.getOutputStream()
                output.write("POST /control HTTP/1.1\r\nContent-Length: 4\r\n\r\n".toByteArray())
                output.flush()
                assertTrue(accepted.await(5, TimeUnit.SECONDS))
                generation.set(2)
                output.write("body".toByteArray())
                output.flush()
                assertTrue(received.await(5, TimeUnit.SECONDS))
                assertEquals(1L, captured.get())
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stopping server closes accepted sockets`() {
        val accepted = CountDownLatch(1)
        val server =
            DlnaHttpServer(
                0,
                onGet = { DlnaHttpResponse(200, "OK", "text/plain", byteArrayOf()) },
                onPost = { _, _, _, _ -> DlnaHttpResponse(200, "OK", "text/plain", byteArrayOf()) },
                captureGeneration = {
                    accepted.countDown()
                    1L
                },
            )
        server.start()
        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.soTimeout = 5000
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            server.stop()
            val closed =
                try {
                    socket.getInputStream().read() == -1
                } catch (_: SocketException) {
                    true
                }
            assertTrue(closed)
        }
    }
}
