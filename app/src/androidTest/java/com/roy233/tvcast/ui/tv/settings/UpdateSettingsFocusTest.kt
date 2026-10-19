package com.roy233.tvcast.ui.tv.settings

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roy233.tvcast.feature.update.UpdateChannel
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class UpdateSettingsFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun updatePageRequestsInitialFocusOnCheckAction() {
        composeRule.setContent {
            UpdateSettingsScreen(state = TvUiState(), actions = TvActions())
        }

        composeRule.onNodeWithText("检查更新").assertIsFocused()
    }

    @Test
    fun dpadMovesFocusToChannelAndCenterSelectsIt() {
        var selectedChannel: UpdateChannel? = null
        composeRule.setContent {
            UpdateSettingsScreen(
                state = TvUiState(),
                actions = TvActions(onSelectUpdateChannel = { selectedChannel = it }),
            )
        }

        composeRule.onNodeWithText("检查更新")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("正式版").assertIsFocused()
        composeRule.onNodeWithText("正式版")
            .performKeyInput { pressKey(Key.DirectionCenter) }

        composeRule.runOnIdle {
            assertEquals(UpdateChannel.STABLE, selectedChannel)
        }
    }

    @Test
    fun selectedChannelIsExposedAsSelected() {
        composeRule.setContent {
            UpdateSettingsScreen(state = TvUiState(), actions = TvActions())
        }

        composeRule.onNodeWithText("正式版").assertIsSelected()
    }
}
