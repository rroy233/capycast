package com.roy233.tvcast.feature.account

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roy233.tvcast.data.auth.AuthTokenStore
import com.roy233.tvcast.feature.account.model.AccountAuthService
import com.roy233.tvcast.feature.account.model.AccountNavigator
import com.roy233.tvcast.feature.account.model.AccountPlatform
import com.roy233.tvcast.feature.account.model.AccountProfile
import com.roy233.tvcast.feature.account.model.Poll
import com.roy233.tvcast.feature.account.model.Qr
import com.roy233.tvcast.feature.account.model.Token
import com.roy233.tvcast.platform.qr.QrCodeGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountLoginCoordinatorTest {
    @Test
    fun switchingAndLogoutKeepAccountsSeparateAndIgnoreAnObsoleteQr() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val prefsName = "test_account_coordinator_${System.nanoTime()}"
        val context =
            object : ContextWrapper(base) {
                override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                    base.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            }
        val store = AuthTokenStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val qrStarted = CountDownLatch(1)
        val releaseQr = CountDownLatch(1)
        val obsoleteService =
            object : AccountAuthService {
                override fun requestQr(context: Context): Qr {
                    qrStarted.countDown()
                    releaseQr.await(5, TimeUnit.SECONDS)
                    return Qr("https://example.com/obsolete", "code", "session")
                }

                override fun poll(context: Context, authCode: String, sessionId: String): Poll =
                    error("An obsolete QR must never be polled")
            }
        var state = AccountUiState()
        var logoutCount = 0
        val coordinator =
            AccountLoginCoordinator(
                context = context,
                scope = scope,
                writeState = { state = it },
                sessionStore = store,
                authServices = AccountPlatform.entries.associateWith { { obsoleteService } },
                accountNavigator = AccountNavigator { AccountProfile("user", "", false, 0L) },
                qrCodeGenerator = QrCodeGenerator(),
                onTokenAvailable = {},
                onLogout = { logoutCount++ },
                onLogoutUi = {},
                onToast = {},
            )
        try {
            store.save(Token("tv-key", 10L, "SESSDATA=domestic", "", 0L))
            store.save(
                Token(null, 20L, "SESSDATA=international", "", 0L, AccountPlatform.INTERNATIONAL),
            )
            instrumentation.runOnMainSync {
                coordinator.startOrLogin()
                assertEquals(10L, state.mid)
                assertEquals(
                    mapOf(AccountPlatform.DOMESTIC to true, AccountPlatform.INTERNATIONAL to true),
                    state.platformLoginStates,
                )
                coordinator.selectPlatform(AccountPlatform.INTERNATIONAL)
                assertEquals(20L, state.mid)
                assertTrue(state.loggedIn)
                coordinator.logout()
                assertFalse(state.loggedIn)
                assertNull(store.load())
                assertEquals(10L, store.load(AccountPlatform.DOMESTIC)?.mid)
                coordinator.startQrLogin()
            }
            assertTrue(qrStarted.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                coordinator.selectPlatform(AccountPlatform.DOMESTIC)
                assertEquals(10L, state.mid)
            }
            releaseQr.countDown()
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(AccountPlatform.DOMESTIC, state.platform)
                assertEquals(10L, state.mid)
                assertNull(state.loginQr)
                assertEquals(3, logoutCount)
            }
        } finally {
            releaseQr.countDown()
            instrumentation.runOnMainSync {
                coordinator.cancel()
                scope.cancel()
            }
            context.getSharedPreferences("ignored", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
