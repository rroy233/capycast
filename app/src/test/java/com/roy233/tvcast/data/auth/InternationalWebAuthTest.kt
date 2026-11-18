package com.roy233.tvcast.data.auth

import com.roy233.tvcast.feature.account.model.AccountPlatform
import com.roy233.tvcast.feature.account.model.Poll
import fi.iki.elonen.NanoHTTPD
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternationalWebAuthTest {
    @Test
    fun `overseas qr requests preserve cookies and validate the confirmed account`() {
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        var pollCount = 0
        var navCount = 0
        val server =
            object : NanoHTTPD("127.0.0.1", 0) {
                override fun serve(session: IHTTPSession): Response {
                    requests +=
                        session.uri + "?" + session.queryParameterString.orEmpty() to
                            session.headers["cookie"].orEmpty()
                    if (session.uri == "/x/web-interface/nav" && ++navCount == 1) {
                        return newFixedLengthResponse(
                            Response.Status.SERVICE_UNAVAILABLE,
                            "text/plain",
                            "retry",
                        )
                    }
                    val body =
                        when (session.uri) {
                            "/login" -> "ok"
                            "/x/passport-login/web/qrcode/generate" ->
                                """{"code":0,"data":{"url":"https://example.com/qr","qrcode_key":"key"}}"""
                            "/x/passport-login/web/qrcode/poll" -> {
                                pollCount++
                                if (pollCount == 1)
                                    """{"code":0,"data":{"code":86090,"message":"confirm"}}"""
                                else """{"code":0,"data":{"code":0,"refresh_token":"refresh"}}"""
                            }
                            "/x/web-interface/nav" ->
                                """{"code":0,"data":{"isLogin":true,"mid":42}}"""
                            else -> error("Unexpected path ${session.uri}")
                        }
                    return newFixedLengthResponse(body).apply {
                        if (session.uri == "/login")
                            addHeader("Set-Cookie", "bootstrap=ready; Domain=.bilibili.com; Path=/")
                        if (session.uri.endsWith("/poll") && pollCount == 2) {
                            addHeader("Set-Cookie", "SESSDATA=secret; Domain=.bilibili.com; Path=/")
                        }
                    }
                }
            }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        val builder =
            OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .dns(
                    object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> =
                            listOf(InetAddress.getByName("127.0.0.1"))
                    },
                )
                .addInterceptor { chain ->
                    val original = chain.request()
                    val target =
                        original.url.newBuilder().scheme("http").port(server.listeningPort).build()
                    chain.proceed(original.newBuilder().url(target).build())
                }
        val auth = InternationalWebAuth(builder)
        try {
            val qr = auth.requestQr()
            assertEquals("https://example.com/qr", qr.url)
            assertEquals(Poll.Pending(86090, "confirm"), auth.poll(qr.authCode))
            assertTrue(runCatching { auth.poll(qr.authCode) }.isFailure)
            val success = auth.poll(qr.authCode) as Poll.Success
            assertEquals(2, pollCount)
            assertEquals(AccountPlatform.INTERNATIONAL, success.token.platform)
            assertEquals(42L, success.token.mid)
            assertNull(success.token.accessKey)
            assertEquals("refresh", success.token.refreshToken)
            assertTrue(success.token.cookie.contains("SESSDATA=secret"))
            assertTrue(requests[0].first.contains("site=OVERSEAS"))
            assertTrue(requests[1].first.contains("source=main_web"))
            assertTrue(requests[1].first.contains("x-bili-device-req-json="))
            assertTrue(requests[1].second.contains("bootstrap=ready"))
            assertTrue(requests[2].first.contains("qrcode_key=key"))
            assertTrue(requests.last().second.contains("SESSDATA=secret"))
        } finally {
            auth.close()
            server.stop()
        }
    }
}
