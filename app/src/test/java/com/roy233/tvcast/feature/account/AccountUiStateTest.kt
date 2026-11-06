package com.roy233.tvcast.feature.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 验证访客启动时不会提前显示扫码任务状态。 */
class AccountUiStateTest {

    @Test
    fun `default state represents a logged out account before QR login`() {
        val state = AccountUiState()

        assertFalse(state.loggedIn)
        assertEquals("未登录", state.accountName)
        assertEquals(0L, state.mid)
        assertEquals("", state.loginStatus)
        assertFalse(state.loginSkipped)
    }
}
