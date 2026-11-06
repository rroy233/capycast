package com.roy233.tvcast.ui.tv.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roy233.tvcast.feature.cast.domain.RecentCast
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.RecentCastFocus
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.tv.TvRoot
import org.junit.Assert.assertEquals
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

    /** 首页声明主操作、次操作与最近投屏货架之间的二维焦点关系。 */
    @Test
    fun homeFocusGraphMovesBetweenActionsAndRecentCards() {
        composeRule.setContent {
            HomeScreen(
                state = TvUiState(
                    loggedIn = true,
                    recents = listOf(recentCast(1), recentCast(2)),
                ),
                actions = TvActions(),
            )
        }

        composeRule.onNodeWithText("继续播放").assertIsFocused()
        composeRule.onNodeWithText("继续播放")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("recent-card-1-10").assertIsFocused()

        composeRule.onNodeWithTag("recent-card-1-10")
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("recent-card-2-20").assertIsFocused()

        composeRule.onNodeWithTag("recent-card-2-20")
            .performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithTag("recent-card-1-10").assertIsFocused()

        composeRule.onNodeWithTag("recent-card-1-10")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithText("继续播放").assertIsFocused()

        composeRule.onNodeWithText("继续播放")
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithText("返回").assertIsFocused()
    }

    @Test
    fun leftFromHomePrimaryOpensTheSidebarAtThePageBoundary() {
        composeRule.setContent {
            val state = remember {
                mutableStateOf(
                    TvUiState(
                        loggedIn = true,
                        recents = listOf(recentCast(1), recentCast(2)),
                    ),
                )
            }
            TvRoot(
                state = state.value,
                actions = TvActions(
                    onOpenSidebar = { state.value = state.value.withMenuOpen() },
                    onCloseSidebar = { state.value = state.value.withMenuClosed() },
                ),
            )
        }

        composeRule.onNodeWithText("继续播放").assertIsFocused()
        composeRule.onNodeWithText("继续播放")
            .performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithTag("sidebar-panel").assertExists()
        composeRule.onNodeWithText("Bilibili 投屏").assertIsFocused()
    }

    @Test
    fun focusedRecentCardUpdatesTheHomeBackdrop() {
        composeRule.setContent {
            val state = remember {
                mutableStateOf(
                    TvUiState(
                        loggedIn = true,
                        recents = listOf(
                            recentCast(1).copy(cover = "https://example.com/one.jpg"),
                            recentCast(2).copy(cover = "https://example.com/two.jpg"),
                        ),
                    ),
                )
            }
            HomeScreen(
                state = state.value,
                actions = TvActions(
                    onFocusRecent = { aid, cid ->
                        state.value = state.value.copy(
                            focusedRecent = RecentCastFocus(aid, cid),
                        )
                    },
                ),
            )
        }

        assertBackdropFillsScreen("home-backdrop-1-10")
        composeRule.onNodeWithText("继续播放")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("recent-card-1-10")
            .performKeyInput { pressKey(Key.DirectionRight) }
        assertBackdropFillsScreen("home-backdrop-2-20")
    }

    private fun assertBackdropFillsScreen(tag: String) {
        val viewport = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val cover = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.width, cover.width, 1f)
        assertEquals(viewport.height, cover.height, 1f)
    }

    private fun recentCast(aid: Long) = RecentCast(
        aid = aid,
        cid = aid * 10,
        title = "最近投屏 $aid",
        quality = "1080P",
    )
}
