package com.roy233.tvcast.data.bili

import android.os.SystemClock
import com.google.gson.JsonParser
import com.roy233.tvcast.core.diagnostics.LogHub
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 获取并应用主站 WBI 参数签名。Cookie 只用于 nav 请求，不写入缓存或日志。 */
class WbiSigner(
    private val cookieProvider: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    private var cachedKey: String = ""
    private var cachedAtMs: Long = 0L

    @Synchronized
    fun sign(params: Map<String, String>): Map<String, String> {
        val now = SystemClock.elapsedRealtime()
        if (cachedKey.isBlank() || now - cachedAtMs >= KEY_TTL_MS) {
            cachedKey = loadMixinKey(cookieProvider())
            cachedAtMs = now
        }
        check(cachedKey.isNotBlank()) { "WBI key unavailable" }
        return signWithKey(params, cachedKey, System.currentTimeMillis() / 1_000L)
    }

    private fun loadMixinKey(cookie: String): String {
        val request = Request.Builder()
            .url(NAV_URL)
            .header("User-Agent", BiliApi.UA)
            .header("Referer", "https://www.bilibili.com/")
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    LogHub.e(TAG, "nav for WBI key failed status=${response.code}")
                    return ""
                }
                val root = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
                val data = root.objectOrNull("data") ?: return ""
                val image = data.objectOrNull("wbi_img") ?: return ""
                val imgKey = image.string("img_url").substringAfterLast('/').substringBefore('.')
                val subKey = image.string("sub_url").substringAfterLast('/').substringBefore('.')
                if (imgKey.isBlank() || subKey.isBlank()) return ""
                mixinKeyFrom(imgKey, subKey)
            }
        } catch (failure: Exception) {
            LogHub.e(TAG, "nav for WBI key error=${failure.javaClass.simpleName}")
            ""
        }
    }

    companion object {
        private const val TAG = "WbiSigner"
        private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"
        private const val KEY_TTL_MS = 11L * 60L * 60L * 1_000L
        private val WBI_KEY_INDEX_TABLE = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
        )

        internal fun signWithKey(
            params: Map<String, String>,
            mixinKey: String,
            timestampSeconds: Long,
        ): Map<String, String> {
            val sanitized = params.mapValues { (_, value) -> value.filterNot { it in "!'()*" } }
            val toSign = (sanitized + ("wts" to timestampSeconds.toString())).toSortedMap()
            val query = toSign.entries.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
            return toSign + ("w_rid" to md5(query + mixinKey))
        }

        internal fun mixinKeyFrom(imgKey: String, subKey: String): String {
            val source = imgKey + subKey
            require(source.length > WBI_KEY_INDEX_TABLE.max()) { "WBI image keys are too short" }
            return buildString(32) {
                WBI_KEY_INDEX_TABLE.forEach { index -> append(source[index]) }
            }.take(32)
        }

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

        private fun md5(value: String): String = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun com.google.gson.JsonObject.objectOrNull(name: String) =
            get(name)?.takeIf { it.isJsonObject }?.asJsonObject

        private fun com.google.gson.JsonObject.string(name: String): String =
            runCatching { get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty() }.getOrDefault("")
    }
}
