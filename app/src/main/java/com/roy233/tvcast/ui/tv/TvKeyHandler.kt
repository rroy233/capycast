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
import com.roy233.tvcast.ui.tv.player.PlaybackSeekAccelerator
import com.roy233.tvcast.ui.tv.player.handlePlaybackSeekKey

/** 当前页面按一次返回键应执行的层级动作。 */
internal enum class TvBackAction {
    CANCEL_STOP_CAST_CONFIRM,
    DISMISS_ERROR,
    BACK_PANEL,
    CLOSE_PANEL,
    CLOSE_HELP,
    CLOSE_UPDATE_NOTES,
    CLOSE_UPDATE_SETTINGS,
    NAVIGATE_HOME,
    REQUEST_STOP_CAST,
    CLOSE_SIDEBAR,
    HOME_BACK,
}

/** 识别限定时间内的连续两次主页返回。 */
internal class DoubleBackExitGate(private val intervalMs: Long) {
    private var backPressCount = 0
    private var promptShownAtMs = 0L

    fun press(nowMs: Long): Boolean {
        if (nowMs - promptShownAtMs > intervalMs) backPressCount = 0
        backPressCount++
        if (backPressCount == 1) promptShownAtMs = nowMs
        return backPressCount >= 2
    }
}

/** 主页退出提示文案与连续返回时间窗。 */
internal object HomeBackExitTiming {
    const val PROMPT_TEXT = "再按一次返回键退出"
    const val EXIT_WINDOW_MS = 5_000L
}

/** 处理电视遥控器按键并将其转换为界面操作。 */
object TvKeyHandler {

    /** 按照由上到下的界面层级，解析返回键的唯一目标。 */
    internal fun resolveBackAction(state: TvUiState): TvBackAction = when {
        state.stopCastConfirm -> TvBackAction.CANCEL_STOP_CAST_CONFIRM
        state.error != null -> TvBackAction.DISMISS_ERROR
        state.panel != null && state.panel != PanelKind.ROOT -> TvBackAction.BACK_PANEL
        state.panel == PanelKind.ROOT -> TvBackAction.CLOSE_PANEL
        state.helpVisible -> TvBackAction.CLOSE_HELP
        state.mode == TvMode.PLAYING || (state.mode == TvMode.LOADING && state.castActive) ->
            TvBackAction.REQUEST_STOP_CAST
        state.destination == TvDestination.SETTINGS &&
            state.settingsPage == SettingsPage.UPDATE_NOTES -> TvBackAction.CLOSE_UPDATE_NOTES
        state.destination == TvDestination.SETTINGS &&
            state.settingsPage == SettingsPage.UPDATE -> TvBackAction.CLOSE_UPDATE_SETTINGS
        state.destination == TvDestination.SETTINGS -> TvBackAction.NAVIGATE_HOME
        state.destination == TvDestination.HOME && state.sidebarVisible -> TvBackAction.CLOSE_SIDEBAR
        else -> TvBackAction.HOME_BACK
    }

    /** 将解析后的返回层级分发到宿主操作。 */
    internal fun dispatchBack(state: TvUiState, actions: TvActions) {
        when (resolveBackAction(state)) {
            TvBackAction.CANCEL_STOP_CAST_CONFIRM -> actions.onCancelStopCast()
            TvBackAction.DISMISS_ERROR -> actions.onDismissError()
            TvBackAction.BACK_PANEL -> actions.onOpenPanel(PanelKind.ROOT)
            TvBackAction.CLOSE_PANEL -> actions.onClosePanel()
            TvBackAction.CLOSE_HELP -> actions.onCloseHelp()
            TvBackAction.CLOSE_UPDATE_NOTES -> actions.onCloseUpdateNotes()
            TvBackAction.CLOSE_UPDATE_SETTINGS -> actions.onCloseUpdateSettings()
            TvBackAction.NAVIGATE_HOME -> actions.onNavigateHome()
            TvBackAction.REQUEST_STOP_CAST -> actions.onRequestStopCast()
            TvBackAction.CLOSE_SIDEBAR -> actions.onCloseSidebar()
            TvBackAction.HOME_BACK -> actions.onHomeBack()
        }
    }

    /**
     * 根据按键、当前界面状态和模态状态分发一次按键操作。
     *
     * @param event 当前按键事件。
     * @param state 当前电视界面状态。
     * @param actions 可执行的界面操作回调。
     * @param modal 当前是否存在模态内容。
     * @return 事件是否已被处理。
     */
    internal fun handle(
        event: KeyEvent,
        state: TvUiState,
        actions: TvActions,
        modal: Boolean,
        seekAccelerator: PlaybackSeekAccelerator,
    ): Boolean {
        if (event.type != KeyEventType.KeyDown) return false

        // Back is dispatched by Activity's OnBackPressedDispatcher. Handling it
        // here as well can count one remote press twice (KeyDown + system Back).
        if (event.key == Key.Back) return false

        if (event.key == Key.Escape) {
            if (event.nativeKeyEvent.repeatCount == 0) {
                dispatchBack(state, actions)
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

        if (state.mode == TvMode.PLAYING && !state.hudVisible && !modal) {
            if (handlePlaybackSeekKey(
                    event = event,
                    isSeeking = state.seekPreviewPositionMs != null,
                    seekAccelerator = seekAccelerator,
                    onPreviewSeekBy = actions.onPreviewSeekBy,
                    onConfirmSeek = actions.onConfirmSeek,
                )
            ) return true
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
