package com.roy233.tvcast.ui.tv.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.tv.TvRoot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CastAutoplayPromptTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun focusedPromptAcceptsRemoteConfirm() {
        compose.mainClock.autoAdvance = false
        val state =
            mutableStateOf(
                TvUiState(mode = TvMode.PLAYING, sidebarVisible = false, castAutoplayPrompt = true),
            )
        val answers = mutableListOf<Boolean>()
        compose.setContent {
            TvRoot(
                state.value,
                TvActions(
                    onAutoplayAnswer = { answer ->
                        answers.add(answer)
                        state.value = state.value.copy(castAutoplayPrompt = false)
                    },
                ),
            )
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("开启自动连播").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        compose.runOnIdle { assertEquals(true, answers.first()) }
    }

    @Test
    fun promptClosesAfterFiveSeconds() {
        compose.mainClock.autoAdvance = false
        val state =
            mutableStateOf(
                TvUiState(mode = TvMode.PLAYING, sidebarVisible = false, castAutoplayPrompt = true),
            )
        compose.setContent {
            TvRoot(
                state.value,
                TvActions(
                    onAutoplayAnswer = {
                        state.value = state.value.copy(castAutoplayPrompt = false)
                    },
                ),
            )
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(5_100)
        compose.waitForIdle()
        compose.onNodeWithText("开启自动连播").assertDoesNotExist()
    }
}
