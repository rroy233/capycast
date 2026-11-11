package com.roy233.tvcast.ui.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roy233.tvcast.ui.model.SettingsNavigationState
import com.roy233.tvcast.ui.model.SettingsPage
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvBackNavigationTest {

    @Test
    fun nestedUpdatePagesReturnOneLevelAtATime() {
        val settings =
            TvUiState(
                destination = TvDestination.SETTINGS,
                settingsNavigation =
                    SettingsNavigationState().replaceWith(SettingsPage.UPDATE_NOTES),
            )

        assertEquals(TvBackAction.NAVIGATE_SETTINGS_BACK, TvKeyHandler.resolveBackAction(settings))
        assertEquals(
            TvBackAction.NAVIGATE_SETTINGS_BACK,
            TvKeyHandler.resolveBackAction(
                settings.copy(
                    settingsNavigation = SettingsNavigationState().replaceWith(SettingsPage.UPDATE),
                ),
            ),
        )
        assertEquals(
            TvBackAction.OPEN_SIDEBAR,
            TvKeyHandler.resolveBackAction(
                settings.copy(settingsNavigation = SettingsNavigationState()),
            ),
        )
    }

    @Test
    fun backOnUpdatePagePopsTheSettingsRoute() {
        var navigatedBack = false

        TvKeyHandler.dispatchBack(
            state =
                TvUiState(
                    destination = TvDestination.SETTINGS,
                    settingsNavigation = SettingsNavigationState().replaceWith(SettingsPage.UPDATE),
                ),
            actions = TvActions(onNavigateSettingsBack = { navigatedBack = true }),
        )

        assertEquals(true, navigatedBack)
    }

    @Test
    fun backOnSettingsRootOpensTheSidebar() {
        var openedSidebar = false

        TvKeyHandler.dispatchBack(
            state = TvUiState(destination = TvDestination.SETTINGS),
            actions = TvActions(onOpenSidebar = { openedSidebar = true }),
        )

        assertEquals(true, openedSidebar)
    }

    @Test
    fun playbackBackRequestsConfirmationInsteadOfFinishingImmediately() {
        assertEquals(
            TvBackAction.REQUEST_STOP_CAST,
            TvKeyHandler.resolveBackAction(TvUiState(mode = TvMode.PLAYING)),
        )
        assertEquals(
            TvBackAction.REQUEST_STOP_CAST,
            TvKeyHandler.resolveBackAction(TvUiState(mode = TvMode.LOADING, castActive = true)),
        )
        assertEquals(
            TvBackAction.REQUEST_STOP_CAST,
            TvKeyHandler.resolveBackAction(
                TvUiState(
                    mode = TvMode.PLAYING,
                    destination = TvDestination.SETTINGS,
                    settingsNavigation =
                        SettingsNavigationState().replaceWith(SettingsPage.UPDATE_NOTES),
                ),
            ),
        )
    }

    @Test
    fun homeSidebarClosesBeforeHomeExitAction() {
        val home = TvUiState(destination = TvDestination.HOME)

        assertEquals(TvBackAction.HOME_BACK, TvKeyHandler.resolveBackAction(home))
        assertEquals(
            TvBackAction.CLOSE_SIDEBAR,
            TvKeyHandler.resolveBackAction(home.copy(sidebarVisible = true)),
        )
        assertEquals(
            TvBackAction.CLOSE_SIDEBAR,
            TvKeyHandler.resolveBackAction(
                home.copy(destination = TvDestination.SETTINGS, sidebarVisible = true),
            ),
        )
    }

    @Test
    fun homeExitUsesSmartTubeStyleFiveSecondWindowFromPromptShown() {
        val gate = DoubleBackExitGate(intervalMs = 5_000L)

        assertEquals(false, gate.press(10_000L))
        assertEquals(true, gate.press(15_000L))
        assertEquals(false, gate.press(20_001L))
        assertEquals(false, gate.press(25_002L))
    }
}
