package com.roy233.tvcast.data.auth

import android.content.Context

/**
 * 持久化电视端登录令牌。
 *
 * @param context 用于访问应用偏好的上下文。
 */
class AuthTokenStore(context: Context) {

    /** 保存登录令牌的偏好存储。 */
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 保存登录令牌及其关联的账号信息。 */
    fun save(token: CastAuth.Token) {
        prefs.edit()
            .putString("access_key", token.accessKey)
            .putLong("mid", token.mid)
            .putString("cookie", token.cookie)
            .putString("refresh_token", token.refreshToken)
            .putLong("expires_in", token.expiresIn)
            .putLong("login_at", System.currentTimeMillis() / 1000L)
            .apply()
    }

    /**
     * 读取已保存的登录令牌。
     *
     * @return 完整且有效的令牌；没有可用令牌时返回 null。
     */
    fun load(): CastAuth.Token? {
        val access = prefs.getString("access_key", null) ?: return null
        val mid = prefs.getLong("mid", 0L)
        if (access.isEmpty() || mid <= 0) return null
        return CastAuth.Token(
            accessKey = access,
            mid = mid,
            cookie = prefs.getString("cookie", "").orEmpty(),
            refreshToken = prefs.getString("refresh_token", "").orEmpty(),
            expiresIn = prefs.getLong("expires_in", 0L),
        )
    }

    /** 清除已保存的登录令牌。 */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        /** 登录令牌使用的偏好文件名。 */
        const val PREFS = "bili_cast_auth"
    }
}
