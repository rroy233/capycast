package com.roy233.tvcast.data.bili.danmaku

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.JsonParser
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.data.bili.BiliApi
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * B 站直播弹幕长连接准入接口。
 *
 * 参考 blivedm 的 web 客户端实现：`getDanmuInfo` 需要设备 buvid3 与 wbi 签名，
 * 否则返回 -352 风控。buvid3 由主站下发，wbi key 从 `nav` 的 `wbi_img` 提取，
 * 均按设备持久化并复用。
 */
object LiveDanmakuApi {

    /** 日志标签。 */
    private const val TAG = "LiveDanmakuApi"

    /** 主站根地址，用于初始化 buvid3 Cookie。 */
    private const val BUVID_INIT_URL = "https://www.bilibili.com/"

    /** 提供 wbi 鉴权口令的接口。 */
    private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"

    /** 直播弹幕服务器配置接口。 */
    private const val DANMAKU_INFO_URL =
        "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo"

    /** 设备偏好文件名。 */
    private const val PREFS = "bili_live_danmaku"
    private const val KEY_BUVID3 = "buvid3"
    private const val KEY_WBI_KEY = "wbi_key"
    private const val KEY_WBI_KEY_AT = "wbi_key_at"

    /** wbi 鉴权口令有效期，接近官方 12 小时上限。 */
    private const val WBI_KEY_TTL_MS = 11L * 60 * 60 * 1000

    /** wbi 密码重排表，对齐 blivedm。 */
    private val WBI_KEY_INDEX_TABLE = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
    )

    /** getDanmuInfo 响应中的一个可用 WebSocket 主机。 */
    data class Host(
        val host: String,
        val wssPort: Int,
    ) {
        /** 拼接后的 wss 地址。 */
        val wssUrl: String
            get() = "wss://$host:$wssPort/sub"
    }

    /** getDanmuInfo 响应中进房所需的信息。 */
    data class DanmuInfo(
        val token: String,
        val hosts: List<Host>,
        val buvid3: String,
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 请求直播弹幕进房信息。
     *
     * @param context 用于持久化设备 buvid3 与 wbi 口令。
     * @param roomId 直播间真实标识。
     * @param cookie 登录 Cookie；会与设备 buvid3 合并后携带。
     */
    fun fetchDanmuInfo(context: Context, roomId: Long, cookie: String): DanmuInfo {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val buvid3 = ensureBuvid3(prefs)
        val wbiKey = ensureWbiKey(prefs, cookie)

        val params = signWbi(
            params = mapOf("id" to roomId.toString(), "type" to "0"),
            wbiKey = wbiKey,
        )
        val url = DANMAKU_INFO_URL + "?" + params.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", BiliApi.UA)
            .header("Referer", "https://live.bilibili.com/")
            .header("Origin", "https://live.bilibili.com")
            .header("Accept", "application/json, text/plain, */*")
        val mergedCookie = mergeCookie(cookie, buvid3)
        if (mergedCookie.isNotBlank()) builder.header("Cookie", mergedCookie)

        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                LogHub.e(
                    TAG,
                    "getDanmuInfo http failed room=$roomId status=${resp.code} " +
                        "body=${LogRedaction.text(body.take(200))}",
                )
                error("getDanmuInfo HTTP ${resp.code}")
            }
            val root = JsonParser.parseString(body).asJsonObject
            val code = root.get("code")?.asInt ?: -1
            if (code != 0) {
                val message = root.get("message")?.asString.orEmpty()
                LogHub.e(
                    TAG,
                    "getDanmuInfo rejected room=$roomId code=$code " +
                        "message=${LogRedaction.text(message).take(120)}",
                )
                error("getDanmuInfo code=$code")
            }
            val data = root.getAsJsonObject("data")
                ?: error("getDanmuInfo data missing")
            val token = data.get("token")?.asString.orEmpty()
            if (token.isEmpty()) error("getDanmuInfo token missing")
            val hosts = data.getAsJsonArray("host_list")
                ?.mapNotNull { element ->
                    val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
                    val host = obj.get("host")?.asString.orEmpty()
                    if (host.isEmpty()) return@mapNotNull null
                    Host(host = host, wssPort = obj.get("wss_port")?.asInt ?: 443)
                }
                .orEmpty()
            LogHub.i(
                TAG,
                "getDanmuInfo ok room=$roomId hosts=${hosts.joinToString(",") { it.host }}",
            )
            return DanmuInfo(token = token, hosts = hosts, buvid3 = buvid3)
        }
    }

    /** 读取或初始化设备 buvid3。 */
    private fun ensureBuvid3(prefs: SharedPreferences): String {
        prefs.getString(KEY_BUVID3, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val buvid3 = fetchBuvid3()
        if (buvid3.isNotBlank()) {
            prefs.edit().putString(KEY_BUVID3, buvid3).apply()
        }
        return buvid3
    }

    /** 访问主站从 Set-Cookie 中提取 buvid3。 */
    private fun fetchBuvid3(): String {
        return runCatching {
            val request = Request.Builder()
                .url(BUVID_INIT_URL)
                .header("User-Agent", BiliApi.UA)
                .build()
            client.newCall(request).execute().use { resp ->
                resp.headers("Set-Cookie").asSequence()
                    .map { it.substringBefore(';') }
                    .map { it.split('=', limit = 2) }
                    .firstOrNull { it.size == 2 && it[0].trim() == "buvid3" }
                    ?.get(1)
                    ?.trim()
                    .orEmpty()
            }
        }.onFailure { failure ->
            LogHub.w(TAG, "buvid3 init failed: ${failure.message}")
        }.getOrDefault("")
    }

    /** 读取或刷新 wbi 鉴权口令。 */
    private fun ensureWbiKey(prefs: SharedPreferences, cookie: String): String {
        val cachedAt = prefs.getLong(KEY_WBI_KEY_AT, 0L)
        val fresh = cachedAt > 0L &&
            System.currentTimeMillis() - cachedAt < WBI_KEY_TTL_MS
        if (fresh) {
            prefs.getString(KEY_WBI_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val wbiKey = fetchWbiKey(cookie)
        if (wbiKey.isNotBlank()) {
            prefs.edit()
                .putString(KEY_WBI_KEY, wbiKey)
                .putLong(KEY_WBI_KEY_AT, System.currentTimeMillis())
                .apply()
        }
        return wbiKey
    }

    /** 从 nav 的 wbi_img 提取并重排鉴权口令。 */
    private fun fetchWbiKey(cookie: String): String {
        return runCatching {
            val builder = Request.Builder()
                .url(NAV_URL)
                .header("User-Agent", BiliApi.UA)
                .header("Referer", "https://www.bilibili.com/")
            if (cookie.isNotBlank()) builder.header("Cookie", cookie)
            client.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val root = JsonParser.parseString(body).asJsonObject
                val wbiImg = root.getAsJsonObject("data")?.getAsJsonObject("wbi_img")
                    ?: return ""
                val imgKey = fileNameOf(wbiImg.get("img_url")?.asString.orEmpty())
                val subKey = fileNameOf(wbiImg.get("sub_url")?.asString.orEmpty())
                if (imgKey.isEmpty() || subKey.isEmpty()) return ""
                val shuffled = imgKey + subKey
                buildString {
                    for (index in WBI_KEY_INDEX_TABLE) {
                        if (index < shuffled.length) append(shuffled[index])
                    }
                }
            }
        }.onFailure { failure ->
            LogHub.w(TAG, "wbi key fetch failed: ${failure.message}")
        }.getOrDefault("")
    }

    /** 计算 wbi 签名参数。 */
    private fun signWbi(params: Map<String, String>, wbiKey: String): Map<String, String> {
        if (wbiKey.isBlank()) return params
        val wts = (System.currentTimeMillis() / 1000L).toString()
        val toSign = (params + mapOf("wts" to wts)).toSortedMap()
        val filtered = toSign.mapValues { (_, value) ->
            value.filter { it !in "!'()*" }
        }
        val query = filtered.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        val wRid = md5(query + wbiKey)
        return params + mapOf("wts" to wts, "w_rid" to wRid)
    }

    /** 合并登录 Cookie 与设备 buvid3。 */
    private fun mergeCookie(cookie: String, buvid3: String): String {
        val parts = mutableListOf<String>()
        if (cookie.isNotBlank()) parts += cookie
        if (buvid3.isNotBlank() && "buvid3=" !in cookie) parts += "buvid3=$buvid3"
        return parts.joinToString("; ")
    }

    private fun fileNameOf(url: String): String =
        url.substringAfterLast('/').substringBefore('.')

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
