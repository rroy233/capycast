package com.roy233.tvcast.ui.tv.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roy233.tvcast.feature.cast.domain.RecentCast
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 首页每一种按钮组合都必须有焦点，且货架在电视视口内可被方向键到达。 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
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
                mutableStateOf(TvUiState(loggedIn = true, recents = listOf(recentCast(1))))
            }
            HomeScreen(
                state = state.value,
                actions = TvActions(
                    onDismissContinue = { state.value = state.value.copy(showContinue = false) },
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

    /** 电视视口只有 960x540dp，货架曾被挤到屏幕外而无法到达。 */
    @Test
    fun recentShelfIsReachableWithDownKeyOnATvViewport() {
        var castedTitle: String? = null
        composeRule.setContent {
            Box(Modifier.size(width = 960.dp, height = 540.dp)) {
                HomeScreen(
                    state = TvUiState(
                        loggedIn = true,
                        recents = listOf(recentCast(1), recentCast(2)),
                    ),
                    actions = TvActions(onRecast = { castedTitle = it.title }),
                )
            }
        }

        composeRule.onNodeWithText("继续播放").assertIsFocused()
        composeRule.onNodeWithText("继续播放")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("最近投屏 2").assertIsFocused()

        composeRule.onNodeWithText("最近投屏 2").performClick()
        composeRule.runOnIdle { assert(castedTitle == "最近投屏 2") }
    }

    private fun recentCast(aid: Long) = RecentCast(
        aid = aid,
        cid = aid * 10,
        title = "最近投屏 $aid",
        quality = "1080P",
    )
}
