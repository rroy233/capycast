package com.roy233.tvcast.ui.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.ui.model.SettingsPage
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvError
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvBackNavigationTest {

    @Test
    fun nestedUpdatePagesReturnOneLevelAtATime() {
        val settings = TvUiState(
            destination = TvDestination.SETTINGS,
            settingsPage = SettingsPage.UPDATE_NOTES,
        )

        assertEquals(TvBackAction.CLOSE_UPDATE_NOTES, TvKeyHandler.resolveBackAction(settings))
        assertEquals(
            TvBackAction.CLOSE_UPDATE_SETTINGS,
            TvKeyHandler.resolveBackAction(settings.copy(settingsPage = SettingsPage.UPDATE)),
        )
        assertEquals(
            TvBackAction.NAVIGATE_HOME,
            TvKeyHandler.resolveBackAction(settings.copy(settingsPage = SettingsPage.ROOT)),
        )
    }

    @Test
    fun topmostOverlayClosesBeforeUnderlyingPage() {
        val state = TvUiState(
            destination = TvDestination.SETTINGS,
            settingsPage = SettingsPage.UPDATE_NOTES,
            panel = PanelKind.QUALITY,
            helpVisible = true,
            error = TvError("错误", "详情"),
            stopCastConfirm = true,
        )

        assertEquals(TvBackAction.CANCEL_STOP_CAST_CONFIRM, TvKeyHandler.resolveBackAction(state))
        assertEquals(
            TvBackAction.DISMISS_ERROR,
            TvKeyHandler.resolveBackAction(state.copy(stopCastConfirm = false)),
        )
        assertEquals(
            TvBackAction.BACK_PANEL,
            TvKeyHandler.resolveBackAction(state.copy(stopCastConfirm = false, error = null)),
        )
        assertEquals(
            TvBackAction.CLOSE_PANEL,
            TvKeyHandler.resolveBackAction(
                state.copy(stopCastConfirm = false, error = null, panel = PanelKind.ROOT),
            ),
        )
        assertEquals(
            TvBackAction.CLOSE_HELP,
            TvKeyHandler.resolveBackAction(
                state.copy(stopCastConfirm = false, error = null, panel = null),
            ),
        )
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
                    settingsPage = SettingsPage.UPDATE_NOTES,
                ),
            ),
        )
    }

    @Test
    fun homeSidebarClosesBeforeHomeExitAction() {
        val home = TvUiState(destination = TvDestination.HOME)

        assertEquals(TvBackAction.CLOSE_SIDEBAR, TvKeyHandler.resolveBackAction(home))
        assertEquals(
            TvBackAction.HOME_BACK,
            TvKeyHandler.resolveBackAction(home.copy(sidebarVisible = false)),
        )
    }

    @Test
    fun onlyTwoHomeBackPressesWithinWindowRequestExit() {
        val gate = DoubleBackExitGate(intervalMs = 2_000L)

        assertEquals(false, gate.press(1_000L))
        assertEquals(true, gate.press(2_500L))
        assertEquals(false, gate.press(3_000L))
        assertEquals(false, gate.press(5_100L))
    }
}
