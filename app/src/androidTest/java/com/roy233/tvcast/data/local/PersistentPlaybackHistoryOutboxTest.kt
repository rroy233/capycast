package com.roy233.tvcast.data.local

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roy233.tvcast.feature.account.model.AccountPlatform
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistentPlaybackHistoryOutboxTest {
    @Test
    fun oldRecordsBelongToDomesticAndNewRecordsRemainSeparatedByPlatform() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefsName = "test_history_platform_${System.nanoTime()}"
        val context =
            object : ContextWrapper(base) {
                override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                    base.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            }
        val prefs = context.getSharedPreferences("ignored", Context.MODE_PRIVATE)
        try {
            prefs
                .edit()
                .putString(
                    "items",
                    """[{"sessionId":"legacy","accountMid":10,"aid":1,"cid":2,"updatedAtMs":1}]""",
                )
                .commit()
            val outbox = PersistentPlaybackHistoryOutbox(context)
            val legacy = outbox.pendingForAccount(AccountPlatform.DOMESTIC, 10).single()
            assertEquals("legacy", legacy.sessionId)
            outbox.upsert(
                legacy.copy(
                    sessionId = "international",
                    accountPlatform = AccountPlatform.INTERNATIONAL,
                    updatedAtMs = 2,
                ),
            )
            assertEquals(
                listOf("legacy"),
                outbox.pendingForAccount(AccountPlatform.DOMESTIC, 10).map { it.sessionId },
            )
            assertEquals(
                listOf("international"),
                outbox.pendingForAccount(AccountPlatform.INTERNATIONAL, 10).map { it.sessionId },
            )
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
