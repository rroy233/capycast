package com.roy233.tvcast.ui.tv.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvSidebarTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun floatingSidebarExposesPanelAndNavigationEntries() {
        composeRule.setContent {
            Sidebar(state = TvUiState(version = "0.1.6"), actions = TvActions())
        }

        composeRule.onNodeWithTag("sidebar-panel").assertIsDisplayed()
        composeRule.onNodeWithText("Bilibili 投屏").assertIsDisplayed()
        composeRule.onNodeWithText("设置").assertIsDisplayed()
    }

    @Test
    fun currentDestinationClosesAndOtherDestinationNavigates() {
        var closed = false
        var navigatedToSettings = false
        composeRule.setContent {
            Sidebar(
                state = TvUiState(destination = TvDestination.HOME),
                actions =
                    TvActions(
                        onCloseSidebar = { closed = true },
                        onNavigateSettings = { navigatedToSettings = true },
                    ),
            )
        }

        composeRule.onNodeWithText("Bilibili 投屏").performClick()
        composeRule.runOnIdle { assertTrue(closed) }

        composeRule.onNodeWithText("设置").performClick()
        composeRule.runOnIdle { assertTrue(navigatedToSettings) }
    }
}
