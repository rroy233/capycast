package com.roy233.tvcast.data.auth

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.roy233.tvcast.feature.account.model.AccountAuthService
import com.roy233.tvcast.feature.account.model.AccountPlatform
import com.roy233.tvcast.feature.account.model.Poll
import com.roy233.tvcast.feature.account.model.Qr
import com.roy233.tvcast.feature.account.model.Token
import java.util.UUID
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** 单次海外网页扫码会话；临时 Cookie 随该实例丢弃。 */
class InternationalWebAuth
internal constructor(clientBuilder: OkHttpClient.Builder = OkHttpClient.Builder()) :
    AccountAuthService {
    private val cookies = mutableListOf<Cookie>()
    private var confirmedRefreshToken: String? = null
    private val jar =
        object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(this@InternationalWebAuth.cookies) {
                    for (cookie in cookies) {
                        this@InternationalWebAuth.cookies.removeAll {
                            it.name == cookie.name &&
                                it.domain == cookie.domain &&
                                it.path == cookie.path
                        }
                        if (cookie.expiresAt > System.currentTimeMillis())
                            this@InternationalWebAuth.cookies.add(cookie)
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                synchronized(cookies) {
                    cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
                }
        }
    private val client = clientBuilder.cookieJar(jar).build()

    override fun close() {
        client.dispatcher.cancelAll()
        synchronized(cookies) { cookies.clear() }
    }

    private val common =
        mapOf(
            "source" to "main_web",
            "web_location" to "333.1228",
            "x-bili-redirect" to "1",
            "x-bili-locale-json" to
                "{\"c_locale\":{\"language\":\"en\"},\"always_translate\":true}",
            "x-bili-device-req-json" to
                "{\"platform\":\"web\",\"device\":\"pc\",\"spmid\":\"333.1228\",\"mobi_app\":\"web_i\"}",
        )

    private fun get(url: HttpUrl): String {
        val request =
            Request.Builder()
                .url(url)
                .header("Referer", "https://passport.bilibili.com/")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
                )
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string() ?: error("empty response")
        }
    }

    private fun url(path: String, params: Map<String, String>): HttpUrl {
        val builder = path.toHttpUrl().newBuilder()
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private fun json(url: HttpUrl): JsonObject = JsonParser.parseString(get(url)).asJsonObject

    override fun requestQr(context: Context): Qr = requestQr()

    internal fun requestQr(): Qr {
        get(url("https://passport.bilibili.com/login", mapOf("site" to "OVERSEAS", "lang" to "en")))
        val response =
            json(
                url(
                    "https://passport.bilibili.com/x/passport-login/web/qrcode/generate",
                    common + ("go_url" to "https://www.bilibili.com/"),
                ),
            )
        check(response.get("code")?.asInt == 0) {
            "QR request rejected code=${response.get("code")?.asInt}"
        }
        val data = response.getAsJsonObject("data") ?: error("QR data missing")
        return Qr(
            data.get("url").asString,
            data.get("qrcode_key").asString,
            UUID.randomUUID().toString(),
        )
    }

    override fun poll(context: Context, authCode: String, sessionId: String): Poll = poll(authCode)

    internal fun poll(authCode: String): Poll {
        val refresh =
            confirmedRefreshToken
                ?: run {
                    val response =
                        json(
                            url(
                                "https://passport.bilibili.com/x/passport-login/web/qrcode/poll",
                                common + ("qrcode_key" to authCode),
                            ),
                        )
                    when (val result = parseInternationalQrPoll(response)) {
                        is InternationalQrPoll.Pending ->
                            return Poll.Pending(result.code, result.message)
                        is InternationalQrPoll.Failure ->
                            return Poll.Failure(result.code, result.message)
                        is InternationalQrPoll.Confirmed ->
                            result.refreshToken.also { confirmedRefreshToken = it }
                    }
                }
        val navUrl = "https://api.bilibili.com/x/web-interface/nav".toHttpUrl()
        val nav = json(navUrl)
        val profile = nav.getAsJsonObject("data")
        val mid = profile?.get("mid")?.asLong ?: 0L
        if (nav.get("code")?.asInt != 0 || profile?.get("isLogin")?.asBoolean != true || mid <= 0) {
            return Poll.Failure(-1, "账号校验失败")
        }
        val cookie = jar.loadForRequest(navUrl).joinToString("; ") { "${it.name}=${it.value}" }
        if (cookie.isBlank()) return Poll.Failure(-1, "登录凭据缺失")
        return Poll.Success(Token(null, mid, cookie, refresh, 0L, AccountPlatform.INTERNATIONAL))
    }
}
