package com.roy233.tvcast.feature.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 验证访客启动时的账号展示状态。 */
class AccountUiStateTest {

    @Test
    fun `default state represents a logged out account awaiting a QR code`() {
        val state = AccountUiState()

        assertFalse(state.loggedIn)
        assertEquals("未登录", state.accountName)
        assertEquals(0L, state.mid)
        assertEquals("正在获取二维码…", state.loginStatus)
        assertFalse(state.loginSkipped)
    }
}
