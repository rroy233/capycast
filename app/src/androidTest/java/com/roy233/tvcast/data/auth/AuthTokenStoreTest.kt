package com.roy233.tvcast.data.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roy233.tvcast.feature.account.model.AccountPlatform
import com.roy233.tvcast.feature.account.model.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthTokenStoreTest {
    @Test
    fun legacyMigrationAndPlatformLogoutPreserveOtherAccountAndDeviceId() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefsName = "test_auth_platform_${System.nanoTime()}"
        val context =
            object : ContextWrapper(base) {
                override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                    base.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            }
        val prefs = context.getSharedPreferences("ignored", Context.MODE_PRIVATE)
        try {
            prefs
                .edit()
                .putString("buvid", "device-id")
                .putString("access_key", "tv-key")
                .putLong("mid", 10L)
                .putString("cookie", "SESSDATA=domestic")
                .commit()
            val store = AuthTokenStore(context)
            assertEquals(AccountPlatform.DOMESTIC, store.selectedPlatform())
            assertEquals("tv-key", store.load()?.accessKey)
            store.selectPlatform(AccountPlatform.INTERNATIONAL)
            assertNull(store.load())
            store.save(
                Token(
                    null,
                    20L,
                    "SESSDATA=international",
                    "refresh",
                    0L,
                    AccountPlatform.INTERNATIONAL,
                ),
            )
            assertEquals(20L, store.load()?.mid)
            store.clear()
            assertNull(store.load())
            assertEquals("device-id", prefs.getString("buvid", null))
            store.selectPlatform(AccountPlatform.DOMESTIC)
            assertEquals(10L, AuthTokenStore(context).load()?.mid)
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
