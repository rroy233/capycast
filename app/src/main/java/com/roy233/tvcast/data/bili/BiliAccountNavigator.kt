package com.roy233.tvcast.data.bili

import com.google.gson.JsonParser
import com.roy233.tvcast.data.auth.BiliSessionReader
import com.roy233.tvcast.feature.account.model.AccountNavigator
import com.roy233.tvcast.feature.account.model.AccountProfile
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 账号导航接口的 B 站协议适配器。 */
class BiliAccountNavigator(
    private val sessionReader: BiliSessionReader,
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) : AccountNavigator {
    private val http = BiliHttpClient(client, BiliApi.UA)

    override fun currentProfile(): AccountProfile {
        val text = http.get("https://api.bilibili.com/x/web-interface/nav", "nav", sessionReader.cookie())
        val root = JsonParser.parseString(text).asJsonObject
        val code = root.get("code")?.asInt ?: -1
        if (code != 0) {
            val msg = root.get("message")?.asString ?: "unknown"
            error("nav code=$code message=$msg")
        }
        val data = root.getAsJsonObject("data")
        val vipObject = data.getAsJsonObject("vip")
        val vip = (vipObject?.get("status")?.asInt ?: 0) == 1 ||
            (data.get("vipStatus")?.asInt ?: 0) == 1
        return AccountProfile(
            name = data.get("uname")?.asString ?: "",
            avatar = data.get("face")?.asString ?: "",
            vip = vip,
            mid = data.get("mid")?.asLong ?: 0L,
        )
    }
}
