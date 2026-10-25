package com.roy233.tvcast.data.bili

import android.os.SystemClock
import com.google.gson.JsonParser
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 发送带公共请求头与登录 Cookie 的 B 站 HTTP 请求。
 *
 * @param client 复用的 OkHttp 客户端。
 * @param userAgent 请求使用的 User-Agent。
 */
internal class BiliHttpClient(
    private val client: OkHttpClient,
    private val userAgent: String,
) {

    /**
     * 发送带有公共请求头和登录 Cookie 的 GET 请求。
     *
     * @param url 请求地址。
     * @param purpose 请求用途，用于日志区分。
     */
    fun get(url: String, purpose: String, cookie: String): String {
        val auth = if (cookie.isNotEmpty()) "account" else "guest"
        val safeUrl = LogRedaction.url(url)
        val startedAt = SystemClock.elapsedRealtime()
        LogHub.d(TAG, "http start purpose=$purpose auth=$auth url=$safeUrl")
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com")
            .header("Accept", "application/json, text/plain, */*")
        if (cookie.isNotEmpty()) builder.header("Cookie", cookie)
        try {
            client.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (!resp.isSuccessful) {
                    LogHub.e(
                        TAG,
                        "http failed purpose=$purpose status=${resp.code} " +
                            "bytes=${body.length} ms=$elapsed url=$safeUrl " +
                            "body=${LogRedaction.text(body.take(400))}",
                    )
                    val apiMessage = runCatching {
                        JsonParser.parseString(body).asJsonObject
                            .get("message")?.asString
                    }.getOrNull()
                    val suffix = apiMessage?.let { " ${LogRedaction.text(it).take(120)}" }.orEmpty()
                    error("HTTP ${resp.code}$suffix ($purpose)")
                }
                LogHub.i(
                    TAG,
                    "http done purpose=$purpose status=${resp.code} " +
                        "bytes=${body.length} ms=$elapsed",
                )
                if (body.isEmpty()) error("empty body ($purpose)")
                return body
            }
        } catch (failure: IOException) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            LogHub.e(
                TAG,
                "http error purpose=$purpose after=${elapsed}ms url=$safeUrl " +
                    LogRedaction.reason(failure),
                failure,
                stack = true,
            )
            throw failure
        }
    }

    /**
     * 发送带有公共请求头和登录 Cookie 的二进制 GET 请求。
     *
     * 用于弹幕 `seg.so` / `view` 这类 protobuf 响应，不能按文本解码。
     *
     * @param url 请求地址。
     * @param purpose 请求用途，用于日志区分。
     */
    fun getBytes(url: String, purpose: String, cookie: String): ByteArray {
        val auth = if (cookie.isNotEmpty()) "account" else "guest"
        val safeUrl = LogRedaction.url(url)
        val startedAt = SystemClock.elapsedRealtime()
        LogHub.d(TAG, "http(bin) start purpose=$purpose auth=$auth url=$safeUrl")
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com")
            .header("Accept", "*/*")
        if (cookie.isNotEmpty()) builder.header("Cookie", cookie)
        try {
            client.newCall(builder.build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (!resp.isSuccessful) {
                    val preview = String(bytes, Charsets.UTF_8).take(400)
                    LogHub.e(
                        TAG,
                        "http(bin) failed purpose=$purpose status=${resp.code} " +
                            "bytes=${bytes.size} ms=$elapsed url=$safeUrl " +
                            "body=${LogRedaction.text(preview)}",
                    )
                    error("HTTP ${resp.code} ($purpose)")
                }
                LogHub.i(
                    TAG,
                    "http(bin) done purpose=$purpose status=${resp.code} " +
                        "bytes=${bytes.size} ms=$elapsed",
                )
                if (bytes.isEmpty()) error("empty body ($purpose)")
                return bytes
            }
        } catch (failure: IOException) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            LogHub.e(
                TAG,
                "http(bin) error purpose=$purpose after=${elapsed}ms url=$safeUrl " +
                    LogRedaction.reason(failure),
                failure,
                stack = true,
            )
            throw failure
        }
    }
    private companion object {
        /** 日志标签。 */
        const val TAG = "BiliApi"
    }
}
