package com.roy233.tvcast.ui.tv.settings

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.tv.TvActionDispatcher
import com.roy233.tvcast.ui.tv.TvActionHost
import com.roy233.tvcast.ui.tv.TvRoot
import com.roy233.tvcast.ui.tv.TvStateHolder
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LanguageSettingsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun languageEntryOpensModalAndSelectionSendsTag() {
        var selected: String? = null
        showSettings(appLanguage = "") { selected = it }

        composeRule.onNodeWithTag("language-settings-entry").performScrollTo().performClick()
        composeRule.onNode(isDialog()).assertExists()
        composeRule.onNodeWithTag("language-en").performClick()

        composeRule.runOnIdle { assertEquals("en", selected) }
        composeRule.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun selectedLanguageReceivesInitialFocusAndCanBeDismissedWithBack() {
        showSettings(appLanguage = "en") {}

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule
            .onNodeWithTag("language-settings-entry")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNode(isDialog()).assertExists()
        composeRule.onNodeWithTag("language-en").assertIsSelected().assertIsFocused()
        composeRule.onNodeWithTag("language-en").performKeyInput { pressKey(Key.Back) }

        composeRule.onNode(isDialog()).assertDoesNotExist()
        composeRule.onNodeWithTag("language-settings-entry").assertIsFocused()
    }

    @Test
    fun remoteNavigatesWithinTheDialog() {
        showSettings(appLanguage = "") {}

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("language-settings-entry").performScrollTo().performClick()
        composeRule.onNodeWithTag("language-system").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeRule.onNodeWithTag("language-zh-CN").assertIsFocused()
    }

    @Test
    fun choosingCurrentLanguageClosesDialogWithoutChangingLocale() {
        var calls = 0
        showSettings(appLanguage = "en") { calls++ }

        composeRule.onNodeWithTag("language-settings-entry").performScrollTo().performClick()
        composeRule.onNodeWithTag("language-en").performClick()

        composeRule.runOnIdle { assertEquals(0, calls) }
        composeRule.onNode(isDialog()).assertDoesNotExist()
    }

    private fun showSettings(appLanguage: String, onLanguage: (String) -> Unit) {
        val host =
            Proxy.newProxyInstance(
                TvActionHost::class.java.classLoader,
                arrayOf(TvActionHost::class.java),
            ) { _, method, args ->
                if (method.name == "setAppLanguage") onLanguage(args[0] as String)
                null
            } as TvActionHost
        composeRule.setContent {
            val holder = remember {
                TvStateHolder(
                    TvUiState(destination = TvDestination.SETTINGS, appLanguage = appLanguage),
                )
            }
            val dispatcher = remember { TvActionDispatcher(holder, host) }
            TvRoot(state = holder.state, actions = dispatcher.actions)
        }
    }
}
