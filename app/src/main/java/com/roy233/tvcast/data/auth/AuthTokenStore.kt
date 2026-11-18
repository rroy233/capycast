package com.roy233.tvcast.data.auth

import android.content.Context
import com.roy233.tvcast.feature.account.model.AccountPlatform
import com.roy233.tvcast.feature.account.model.AccountSessionStore
import com.roy233.tvcast.feature.account.model.CredentialKind
import com.roy233.tvcast.feature.account.model.Token

/** 按平台保存会话；当前平台同时决定播放器使用的凭据。 */
class AuthTokenStore(context: Context) : BiliSessionReader, AccountSessionStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        migrateLegacy()
    }

    private fun prefix(platform: AccountPlatform) = "account_${platform.name.lowercase()}_"

    @Synchronized
    private fun migrateLegacy() {
        if (prefs.getBoolean(MIGRATED, false)) return
        val editor = prefs.edit()
        val prefix = prefix(AccountPlatform.DOMESTIC)
        if (prefs.contains("access_key"))
            editor.putString(prefix + "credential_kind", CredentialKind.TV.name)
        for (key in listOf("access_key", "cookie", "refresh_token")) {
            prefs.getString(key, null)?.let { editor.putString(prefix + key, it) }
        }
        for (key in listOf("mid", "expires_in", "login_at")) {
            if (prefs.contains(key)) editor.putLong(prefix + key, prefs.getLong(key, 0))
        }
        listOf("access_key", "cookie", "refresh_token", "mid", "expires_in", "login_at")
            .forEach(editor::remove)
        check(editor.putBoolean(MIGRATED, true).commit()) { "Account migration failed" }
    }

    override fun selectedPlatform(): AccountPlatform = runCatching {
        AccountPlatform.valueOf(prefs.getString(PLATFORM, null).orEmpty())
    }
        .getOrDefault(AccountPlatform.DOMESTIC)

    override fun selectPlatform(platform: AccountPlatform) {
        check(prefs.edit().putString(PLATFORM, platform.name).commit()) { "Platform save failed" }
    }

    override fun save(token: Token) {
        val key = prefix(token.platform)
        check(
            prefs
                .edit()
                .putString(key + "access_key", token.accessKey)
                .putLong(key + "mid", token.mid)
                .putString(key + "cookie", token.cookie)
                .putString(key + "credential_kind", token.credentialKind.name)
                .putString(key + "refresh_token", token.refreshToken)
                .putLong(key + "expires_in", token.expiresIn)
                .putLong(key + "login_at", System.currentTimeMillis() / 1000L)
                .commit(),
        ) {
            "Account save failed"
        }
    }

    override fun load(): Token? = load(selectedPlatform())

    override fun load(platform: AccountPlatform): Token? {
        val key = prefix(platform)
        val access = prefs.getString(key + "access_key", null)?.takeIf { it.isNotBlank() }
        val mid = prefs.getLong(key + "mid", 0L)
        val cookie = prefs.getString(key + "cookie", "").orEmpty()
        val kind =
            runCatching {
                CredentialKind.valueOf(prefs.getString(key + "credential_kind", null).orEmpty())
            }
                .getOrNull() ?: return null
        if (
            mid <= 0 ||
                (kind == CredentialKind.TV && access == null) ||
                (kind == CredentialKind.WEB && cookie.isBlank())
        )
            return null
        return Token(
            access,
            mid,
            cookie,
            prefs.getString(key + "refresh_token", "").orEmpty(),
            prefs.getLong(key + "expires_in", 0L),
            platform,
            kind,
        )
    }

    override fun cookie(): String = load()?.cookie.orEmpty()

    override fun clear() {
        val key = prefix(selectedPlatform())
        val editor = prefs.edit()
        listOf(
                "access_key",
                "mid",
                "cookie",
                "refresh_token",
                "expires_in",
                "login_at",
                "credential_kind",
            )
            .forEach { editor.remove(key + it) }
        check(editor.commit()) { "Account clear failed" }
    }

    private companion object {
        const val PREFS = "bili_cast_auth"
        const val PLATFORM = "selected_platform"
        const val MIGRATED = "account_platform_migrated"
    }
}
