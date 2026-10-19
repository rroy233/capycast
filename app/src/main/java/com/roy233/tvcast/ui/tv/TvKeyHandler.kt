package com.roy233.tvcast.ui.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.ui.model.SettingsPage
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState

/** 处理电视遥控器按键并将其转换为界面操作。 */
object TvKeyHandler {

    /**
     * 根据按键、当前界面状态和模态状态分发一次按键操作。
     *
     * @param event 当前按键事件。
     * @param state 当前电视界面状态。
     * @param actions 可执行的界面操作回调。
     * @param modal 当前是否存在模态内容。
     * @return 事件是否已被处理。
     */
    fun handle(
        event: KeyEvent,
        state: TvUiState,
        actions: TvActions,
        modal: Boolean,
    ): Boolean {
        if (event.type != KeyEventType.KeyDown) return false

        if (event.key == Key.Back || event.key == Key.Escape) {
            when {
                state.exitConfirm -> actions.onCancelExit()
                state.helpVisible -> actions.onCloseHelp()
                state.panel != null ->
                    if (state.panel == PanelKind.ROOT) actions.onClosePanel()
                    else actions.onOpenPanel(PanelKind.ROOT)

                state.error != null -> actions.onDismissError()
                state.mode == TvMode.PLAYING -> actions.onRequestExit()
                state.destination == TvDestination.SETTINGS && state.settingsPage == SettingsPage.UPDATE_NOTES ->
                    actions.onCloseUpdateNotes()
                state.destination == TvDestination.SETTINGS && state.settingsPage == SettingsPage.UPDATE ->
                    actions.onCloseUpdateSettings()
                state.destination == TvDestination.SETTINGS -> actions.onNavigateHome()
                else -> actions.onHomeBack()
            }
            return true
        }

        if (event.key == Key.Menu) {
            when {
                state.helpVisible -> actions.onCloseHelp()
                state.panel != null -> actions.onClosePanel()
                state.error != null -> actions.onDismissError()
                state.mode == TvMode.IDLE ->
                    if (state.sidebarVisible) actions.onCloseSidebar() else actions.onOpenSidebar()
            }
            return true
        }

        if (event.key == Key.MediaPlayPause) {
            if (state.mode == TvMode.PLAYING || state.mode == TvMode.LOADING) {
                actions.onPlayPause()
                return true
            }
            return false
        }

        if (state.mode == TvMode.PLAYING &&
            !state.hudVisible &&
            !modal &&
            (
                event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.DirectionUp ||
                    event.key == Key.DirectionDown ||
                    event.key == Key.DirectionLeft ||
                    event.key == Key.DirectionRight
                )
        ) {
            actions.onShowHud()
            return true
        }

        return false
    }
}
