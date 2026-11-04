package com.roy233.tvcast.ui.tv.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roy233.tvcast.feature.cast.domain.RecentCast
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 首页每一种按钮组合都必须有焦点，包括内容切换后重新出现的主入口。 */
@RunWith(AndroidJUnit4::class)
class HomeFocusTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeWithoutRecentCastFocusesTheHelpButton() {
        composeRule.setContent {
            HomeScreen(
                state = TvUiState(loggedIn = true, recents = emptyList()),
                actions = TvActions(),
            )
        }

        composeRule.onNodeWithText("投屏帮助").assertIsFocused()
    }

    @Test
    fun dismissingContinueMovesFocusToTheHelpButton() {
        composeRule.setContent {
            val state = remember {
                mutableStateOf(TvUiState(loggedIn = true, recents = listOf(recentCast())))
            }
            HomeScreen(
                state = state.value,
                actions = TvActions(
                    onDismissContinue = {
                        state.value = state.value.copy(showContinue = false)
                    },
                ),
            )
        }

        composeRule.onNodeWithText("继续播放").assertIsFocused()
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("投屏帮助").assertIsFocused()
    }

    @Test
    fun loggedOutHomeFocusesTheLoginButton() {
        composeRule.setContent {
            HomeScreen(state = TvUiState(loggedIn = false), actions = TvActions())
        }

        composeRule.onNodeWithText("扫码登录").assertIsFocused()
    }

    private fun recentCast() = RecentCast(
        aid = 1L,
        cid = 2L,
        title = "测试投屏内容",
        quality = "1080P",
    )
}
