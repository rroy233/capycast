package com.roy233.tvcast.ui.tv

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.tv.account.LoginDialog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginDialogTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun loginCardExposesScanInstructionAndCloseAction() {
        var closed = false
        composeRule.setContent {
            LoginDialog(
                state = TvUiState(loginDialogVisible = true),
                actions = TvActions(onCloseLogin = { closed = true }),
            )
        }

        composeRule.onNodeWithText("打开bilibili客户端扫码").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("关闭登录").performClick()
        composeRule.runOnIdle { assertTrue(closed) }
    }
}
