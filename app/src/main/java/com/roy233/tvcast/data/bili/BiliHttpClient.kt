package com.roy233.tvcast.data.bili

import android.os.SystemClock
import com.google.gson.JsonParser
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

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
        val builder =
            Request.Builder()
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
                        JsonParser.parseString(body).asJsonObject.get("message")?.asString
                    }
                        .getOrNull()
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
        val request = binaryRequest(url, cookie).build()
        try {
            return client.newCall(request).execute().use { resp ->
                readBinary(resp, purpose, safeUrl, startedAt)
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

    /**
     * 发送可取消的二进制 GET 请求；协程取消时中止对应的 OkHttp 调用。
     *
     * @param url 请求地址。
     * @param purpose 请求用途，用于日志区分。
     * @param cookie 登录 Cookie；为空时以游客身份请求。
     */
    suspend fun getBytesCancellable(url: String, purpose: String, cookie: String): ByteArray {
        val auth = if (cookie.isNotEmpty()) "account" else "guest"
        val safeUrl = LogRedaction.url(url)
        val startedAt = SystemClock.elapsedRealtime()
        LogHub.d(TAG, "http(bin) start purpose=$purpose auth=$auth url=$safeUrl")
        val request = binaryRequest(url, cookie).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation {
                LogHub.d(TAG, "http(bin) cancel purpose=$purpose url=$safeUrl")
                call.cancel()
            }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        val elapsed = SystemClock.elapsedRealtime() - startedAt
                        if (continuation.isCancelled) {
                            LogHub.d(
                                TAG,
                                "http(bin) cancelled purpose=$purpose after=${elapsed}ms",
                            )
                            return
                        }
                        LogHub.e(
                            TAG,
                            "http(bin) error purpose=$purpose after=${elapsed}ms url=$safeUrl " +
                                LogRedaction.reason(e),
                            e,
                            stack = true,
                        )
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching {
                            response.use { resp ->
                                readBinary(resp, purpose, safeUrl, startedAt)
                            }
                        }
                        result.fold(
                            onSuccess = { bytes ->
                                if (continuation.isActive) continuation.resume(bytes)
                            },
                            onFailure = { failure ->
                                if (continuation.isActive) continuation.resumeWithException(failure)
                            },
                        )
                    }
                },
            )
        }
    }

    /** 构造带公共请求头与登录 Cookie 的二进制 GET 请求。 */
    private fun binaryRequest(url: String, cookie: String): Request.Builder {
        val builder =
            Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", "https://www.bilibili.com/")
                .header("Origin", "https://www.bilibili.com")
                .header("Accept", "*/*")
        if (cookie.isNotEmpty()) builder.header("Cookie", cookie)
        return builder
    }

    /** 校验状态码并读取二进制响应体；失败时记录并抛出。 */
    private fun readBinary(
        response: Response,
        purpose: String,
        safeUrl: String,
        startedAt: Long,
    ): ByteArray {
        val bytes = response.body?.bytes() ?: ByteArray(0)
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (!response.isSuccessful) {
            val preview = String(bytes, Charsets.UTF_8).take(400)
            LogHub.e(
                TAG,
                "http(bin) failed purpose=$purpose status=${response.code} " +
                    "bytes=${bytes.size} ms=$elapsed url=$safeUrl " +
                    "body=${LogRedaction.text(preview)}",
            )
            error("HTTP ${response.code} ($purpose)")
        }
        LogHub.i(
            TAG,
            "http(bin) done purpose=$purpose status=${response.code} " +
                "bytes=${bytes.size} ms=$elapsed",
        )
        if (bytes.isEmpty()) error("empty body ($purpose)")
        return bytes
    }

    private companion object {
        /** 日志标签。 */
        const val TAG = "BiliApi"
    }
}
