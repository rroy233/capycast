package com.roy233.tvcast.data.auth

import android.content.Context
import android.os.Build
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.roy233.tvcast.core.diagnostics.LogHub
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Bilibili TV QR login through the Passport service
 * (`/x/passport-tv-login/qrcode/auth_code` + `/poll`).
 *
 * Why TV login instead of web cookie login: it returns an `access_token`
 * (access_key) which the Moss broadcast channel requires, plus the cookie set
 * used by web playurl.
 */
object CastAuth {

    /** 日志标签。 */
    private const val TAG = "SimpleBiliAuth"
    /** Passport 接口使用的应用标识。 */
    private const val APPKEY = "4409e2ce8ffd12b8"
    /** Passport 接口使用的应用密钥。 */
    private const val APPSEC = "59b43e04ad6965f34319062b478f83dd"
    /** 电视端移动应用标识。 */
    private const val MOBI_APP = "android_tv_yst"
    /** 请求渠道标识。 */
    private const val CHANNEL = "yst"
    /** 请求使用的客户端版本号。 */
    private const val BUILD = "108700"
    /** 官方 TV 客户端版本号，直播心跳的 build 字段也使用该值。 */
    val tvBuildNumber: Int
        get() = BUILD.toInt()
    /** Passport 服务根地址。 */
    private const val PASSPORT = "https://passport.bilibili.com"
    /** 认证请求使用的 User-Agent。 */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    /** 保存设备 buvid 的偏好文件名。 */
    private const val PREFS = "bili_cast_auth"

    /**
     * 保存电视账号认证信息。
     *
     * @property accessKey 电视端访问令牌。
     * @property mid 账号标识。
     * @property cookie 登录后获得的 Cookie 集合。
     * @property refreshToken 刷新令牌。
     * @property expiresIn 令牌有效期。
     */
    data class Token(
        val accessKey: String,
        val mid: Long,
        val cookie: String,
        val refreshToken: String,
        val expiresIn: Long,
    )

    /**
     * 保存二维码地址及其轮询所需的会话信息。
     *
     * @property url 二维码内容地址。
     * @property authCode 二维码认证码。
     * @property sessionId 登录会话标识。
     */
    data class Qr(val url: String, val authCode: String, val sessionId: String)

    /** 表示二维码登录轮询结果。 */
    sealed class Poll {
        /** 表示用户尚未完成扫码或确认。 */
        data class Pending(val code: Int, val message: String) : Poll()
        /** 表示登录成功并返回认证令牌。 */
        data class Success(val token: Token) : Poll()
    }

    /** 用于请求认证接口的 HTTP 客户端。 */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------ //
    // identity used by the signed TV requests
    // ------------------------------------------------------------------ //
    /** 读取或生成设备级 buvid。 */
    private fun buvid(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString("buvid", null)?.let { return it }
        val v = UUID.randomUUID().toString()
        sp.edit().putString("buvid", v).apply()
        return v
    }

    /** 构造电视端认证接口使用的公共参数。 */
    private fun commonParams(ctx: Context): MutableMap<String, String> {
        val buvid = buvid(ctx)
        return mutableMapOf(
            "appkey" to APPKEY,
            "mobi_app" to MOBI_APP,
            "platform" to "android",
            "build" to BUILD,
            "channel" to CHANNEL,
            "sys_ver" to Build.VERSION.SDK_INT.toString(),
            "brand" to Build.BRAND,
            "model" to Build.MODEL,
            "uid" to "0",
            "tv_brand" to Build.BRAND,
            "memory" to "0",
            "mode_switch" to "0",
            "teenager_mode" to "0",
            "build_sn" to "",
            "child_lock" to "0",
            "top_speed" to "0",
            "product_top_speed" to "0",
            "device" to Build.DEVICE,
            "device_id" to buvid,
            "bili_local_id" to buvid,
            "device_name" to Build.MODEL,
            "device_platform" to
                "Android${Build.VERSION.RELEASE}${Build.MANUFACTURER}${Build.MODEL}",
            "networkstate" to "wifi",
            "buvid" to buvid,
            "guid" to buvid,
            "local_id" to buvid,
            "local_fingerprint" to "",
            "fingerprint" to "",
        )
    }

    /** 对签名参数执行 UTF-8 URL 编码。 */
    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 计算认证接口使用的 MD5 摘要。 */
    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** 按排序后的查询参数和应用密钥构造签名请求体。 */
    private fun signedBody(extra: Map<String, String>, ctx: Context): FormBody {
        val params = signedParams(extra, ctx)
        val b = FormBody.Builder()
        params.forEach { (k, v) -> b.add(k, v) }
        return b.build()
    }

    /**
     * 构造官方 TV API 使用的公共参数和签名，供需要 GET 查询串的电视接口复用。
     * 返回值包含签名，但调用方必须确保日志只记录脱敏后的 URL。
     */
    fun signedParams(extra: Map<String, String>, ctx: Context): Map<String, String> {
        val params = commonParams(ctx).apply { putAll(extra) }
        params["ts"] = (System.currentTimeMillis() / 1000L).toString()
        val query = params.toSortedMap().entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        params["sign"] = md5(query + APPSEC)
        return params
    }

    /** 向 Passport 接口发送表单请求并解析 JSON 响应。 */
    private fun post(path: String, body: FormBody, ctx: Context): JsonObject {
        val req = Request.Builder()
            .url("$PASSPORT$path")
            .header("User-Agent", UA)
            .header("Referer", "https://www.bilibili.com/")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: $text")
            return JsonParser.parseString(text).asJsonObject
        }
    }

    // ------------------------------------------------------------------ //
    // QR flow
    // ------------------------------------------------------------------ //
    /** 请求一组新的二维码登录参数。 */
    fun requestQr(ctx: Context): Qr {
        val sessionId = UUID.randomUUID().toString()
        val body = signedBody(
            mapOf(
                "gourl" to "",
                "login_session_id" to sessionId,
                "spm_id" to "",
                "extend" to "",
                "device_tourist_id" to "",
            ),
            ctx
        )
        val root = post("/x/passport-tv-login/qrcode/auth_code", body, ctx)
        val code = root.get("code")?.asInt ?: -1
        if (code != 0) error("auth_code code=$code ${root.get("message")?.asString}")
        val data = root.getAsJsonObject("data")
        val url = data.get("url")?.asString ?: error("no qr url")
        val auth = data.get("auth_code")?.asString ?: error("no auth_code")
        LogHub.i(TAG, "qr requested auth_code=${auth.take(8)}… session=${sessionId.take(8)}…")
        return Qr(url, auth, sessionId)
    }

    /** 轮询二维码登录状态并返回当前登录结果。 */
    fun poll(ctx: Context, authCode: String, sessionId: String): Poll {
        val body = signedBody(
            mapOf(
                "auth_code" to authCode,
                "login_session_id" to sessionId,
                "spm_id" to "",
                "extend" to "",
                "device_tourist_id" to "",
            ),
            ctx
        )
        val root = post("/x/passport-tv-login/qrcode/poll", body, ctx)
        val code = root.get("code")?.asInt ?: -1
        val msg = root.get("message")?.asString ?: ""
        if (code != 0) return Poll.Pending(code, msg)

        val data = root.getAsJsonObject("data")
        val access = data.get("access_token")?.asString
            ?: data.get("access_key")?.asString
            ?: ""
        val mid = data.get("mid")?.asLong ?: 0L
        val refresh = data.get("refresh_token")?.asString ?: ""
        val expires = data.get("expires_in")?.asLong ?: 0L
        val cookie = parseCookies(data)
        if (access.isEmpty() || mid <= 0) return Poll.Pending(-2, "token missing")
        return Poll.Success(Token(access, mid, cookie, refresh, expires))
    }

    /** 从登录响应中提取 Cookie 并组合为请求头格式。 */
    private fun parseCookies(data: JsonObject): String {
        val info = data.getAsJsonObject("cookie_info") ?: return ""
        val arr = info.getAsJsonArray("cookies") ?: return ""
        val parts = mutableListOf<String>()
        arr.forEach { el ->
            val o = el.asJsonObject
            val name = o.get("name")?.asString ?: return@forEach
            val value = o.get("value")?.asString ?: return@forEach
            if (name.isNotEmpty()) parts += "$name=$value"
        }
        return parts.joinToString("; ")
    }

}
