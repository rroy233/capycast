package com.roy233.tvcast.ui.tv.feedback

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roy233.tvcast.feature.feedback.FeedbackChannel
import com.roy233.tvcast.feature.feedback.FeedbackUiState
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class FeedbackDialogTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun feedbackDialogStartsWithGitHubFocusedAndSwitchesChannelOnClick() {
        var selected: FeedbackChannel? = null
        composeRule.setContent {
            FeedbackDialog(
                state =
                    TvUiState(
                        feedback =
                            FeedbackUiState(
                                visible = true,
                                errorMessage = "二维码生成失败，请重试。",
                                versionLabel = "0.2.1 (19)",
                            ),
                    ),
                actions = TvActions(onSelectFeedbackChannel = { selected = it }),
            )
        }

        composeRule.onNodeWithContentDescription("反馈渠道：GitHub").assertIsFocused()
        composeRule.onNodeWithText("问题反馈").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("反馈渠道：GitHub").performKeyInput {
            pressKey(androidx.compose.ui.input.key.Key.DirectionRight)
        }
        composeRule.runOnIdle { assertEquals(FeedbackChannel.EMAIL, selected) }
    }

    @Test
    fun closeButtonClosesFeedbackDialog() {
        var closed = false
        composeRule.setContent {
            FeedbackDialog(
                state = TvUiState(feedback = FeedbackUiState(visible = true)),
                actions = TvActions(onCloseFeedback = { closed = true }),
            )
        }

        composeRule.onNodeWithContentDescription("关闭反馈").performClick()
        composeRule.runOnIdle { assertTrue(closed) }
    }
}
