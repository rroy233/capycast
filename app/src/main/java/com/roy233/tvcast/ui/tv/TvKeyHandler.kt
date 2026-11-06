package com.roy233.tvcast.ui.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.model.UiOverlay
import com.roy233.tvcast.feature.display.CalibrationDirection
import com.roy233.tvcast.feature.display.CalibrationPhase
import com.roy233.tvcast.ui.tv.player.PlaybackSeekAccelerator
import com.roy233.tvcast.ui.tv.player.handlePlaybackSeekKey

/** 当前页面按一次返回键应执行的层级动作。 */
internal enum class TvBackAction {
    CANCEL_CALIBRATION,
    CANCEL_STOP_CAST_CONFIRM,
    DISMISS_ERROR,
    BACK_PANEL,
    CLOSE_PANEL,
    CLOSE_HELP,
    CLOSE_LOGIN,
    NAVIGATE_SETTINGS_BACK,
    OPEN_SIDEBAR,
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

/** 同一时刻唯一拥有按键的界面表面。 */
internal enum class KeySurface {
    CALIBRATION,
    STOP_CAST_CONFIRM,
    ERROR,
    PANEL,
    HELP,
    LOGIN_DIALOG,
    MENU,
    PAGE,
}

/** 菜单显示期间允许的方向键动作；其余按键一律吞掉，下层页面不可操作。 */
internal enum class MenuKeyAction {
    FOCUS_PREVIOUS,
    FOCUS_NEXT,
    CLOSE_MENU,

    /** 确认类按键交给已获焦点的菜单条目自己响应。 */
    ACTIVATE_FOCUSED,

    SWALLOW,
}

/** 处理电视遥控器按键并将其转换为界面操作。 */
object TvKeyHandler {

    /** 解析当前应当接收按键的表面：浮层栈顶 > 导航菜单 > 目的页。 */
    internal fun resolveKeySurface(state: TvUiState): KeySurface {
        when (state.topOverlay) {
            UiOverlay.CALIBRATION -> return KeySurface.CALIBRATION
            UiOverlay.STOP_CAST_CONFIRM -> return KeySurface.STOP_CAST_CONFIRM
            UiOverlay.ERROR -> return KeySurface.ERROR
            UiOverlay.PANEL -> return KeySurface.PANEL
            UiOverlay.HELP -> return KeySurface.HELP
            UiOverlay.LOGIN_DIALOG -> return KeySurface.LOGIN_DIALOG
            null -> Unit
        }
        return if (state.sidebarVisible && state.mode == TvMode.IDLE) {
            KeySurface.MENU
        } else {
            KeySurface.PAGE
        }
    }

    /** 导航菜单的按键策略：上下移动焦点，向右收起菜单，确认键交给条目。 */
    internal fun resolveMenuKeyAction(event: KeyEvent): MenuKeyAction = when (event.key) {
        Key.DirectionUp -> MenuKeyAction.FOCUS_PREVIOUS
        Key.DirectionDown -> MenuKeyAction.FOCUS_NEXT
        Key.DirectionRight -> MenuKeyAction.CLOSE_MENU
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> MenuKeyAction.ACTIVATE_FOCUSED
        else -> MenuKeyAction.SWALLOW
    }

    /**
     * 左侧是否已到当前页面的边界。
     *
     * 边界由调用方用焦点搜索判定；这里只回答“当前表面是否允许打开导航菜单”。
     */
    internal fun canOpenMenuFromEdge(state: TvUiState): Boolean =
        resolveKeySurface(state) == KeySurface.PAGE && state.mode == TvMode.IDLE


    /** 按照由上到下的界面层级，解析返回键的唯一目标。 */
    internal fun resolveBackAction(state: TvUiState): TvBackAction = when {
        state.calibration.phase != CalibrationPhase.HIDDEN -> TvBackAction.CANCEL_CALIBRATION
        state.stopCastConfirm -> TvBackAction.CANCEL_STOP_CAST_CONFIRM
        state.error != null -> TvBackAction.DISMISS_ERROR
        state.panel != null && state.panel != PanelKind.ROOT -> TvBackAction.BACK_PANEL
        state.panel == PanelKind.ROOT -> TvBackAction.CLOSE_PANEL
        state.helpVisible -> TvBackAction.CLOSE_HELP
        state.loginDialogVisible -> TvBackAction.CLOSE_LOGIN
        state.mode == TvMode.PLAYING || (state.mode == TvMode.LOADING && state.castActive) ->
            TvBackAction.REQUEST_STOP_CAST
        state.sidebarVisible -> TvBackAction.CLOSE_SIDEBAR
        state.destination == TvDestination.SETTINGS &&
            state.settingsNavigation.canNavigateBack -> TvBackAction.NAVIGATE_SETTINGS_BACK
        state.destination == TvDestination.SETTINGS -> TvBackAction.OPEN_SIDEBAR
        else -> TvBackAction.HOME_BACK
    }

    /** 将解析后的返回层级分发到宿主操作。 */
    internal fun dispatchBack(state: TvUiState, actions: TvActions) {
        when (resolveBackAction(state)) {
            TvBackAction.CANCEL_CALIBRATION -> actions.onCancelCalibration()
            TvBackAction.CANCEL_STOP_CAST_CONFIRM -> actions.onCancelStopCast()
            TvBackAction.DISMISS_ERROR -> actions.onDismissError()
            TvBackAction.BACK_PANEL -> actions.onOpenPanel(PanelKind.ROOT)
            TvBackAction.CLOSE_PANEL -> actions.onClosePanel()
            TvBackAction.CLOSE_HELP -> actions.onCloseHelp()
            TvBackAction.CLOSE_LOGIN -> actions.onCloseLogin()
            TvBackAction.NAVIGATE_SETTINGS_BACK -> actions.onNavigateSettingsBack()
            TvBackAction.OPEN_SIDEBAR -> actions.onOpenSidebar()
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
        val surface = resolveKeySurface(state)
        val handled = handleKeyDown(event, state, actions, modal, seekAccelerator, surface)
        FocusDiagnostics.keyRouted(
            state = state,
            surface = surface,
            key = FocusDiagnostics.keyName(event.nativeKeyEvent.keyCode),
            handled = handled,
        )
        return handled
    }

    /** [handle] 的按键处理主体；日志与归属解析由调用方统一负责。 */
    private fun handleKeyDown(
        event: KeyEvent,
        state: TvUiState,
        actions: TvActions,
        modal: Boolean,
        seekAccelerator: PlaybackSeekAccelerator,
        surface: KeySurface,
    ): Boolean {

        // Back is dispatched by Activity's OnBackPressedDispatcher. Handling it
        // here as well can count one remote press twice (KeyDown + system Back).
        if (event.key == Key.Back) return false

        if (event.key == Key.Escape) {
            if (event.nativeKeyEvent.repeatCount == 0) {
                dispatchBack(state, actions)
            }
            return true
        }

        if (surface == KeySurface.CALIBRATION) {
            if (state.calibration.phase == CalibrationPhase.ADJUST) {
                val direction = when (event.key) {
                    Key.DirectionLeft -> CalibrationDirection.LEFT
                    Key.DirectionUp -> CalibrationDirection.UP
                    Key.DirectionRight -> CalibrationDirection.RIGHT
                    Key.DirectionDown -> CalibrationDirection.DOWN
                    else -> null
                }
                if (direction != null) {
                    actions.onAdjustCalibration(direction)
                    return true
                }
                if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                    if (event.nativeKeyEvent.repeatCount == 0) actions.onNextCalibrationCorner()
                    return true
                }
            }
            // 提示与确认弹窗的方向键和确认键交给获得焦点的按钮。
            return state.calibration.phase == CalibrationPhase.ADJUST ||
                (event.key != Key.DirectionLeft && event.key != Key.DirectionRight &&
                    event.key != Key.DirectionUp && event.key != Key.DirectionDown &&
                    event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter)
        }

        if (event.key == Key.Menu) {
            when {
                state.helpVisible -> actions.onCloseHelp()
                state.loginDialogVisible -> actions.onCloseLogin()
                state.panel != null -> actions.onClosePanel()
                state.error != null -> actions.onDismissError()
                state.mode == TvMode.IDLE ->
                    if (state.sidebarVisible) actions.onCloseSidebar() else actions.onOpenSidebar()
            }
            return true
        }

        if (surface == KeySurface.MENU) {
            val action = resolveMenuKeyAction(event)
            FocusDiagnostics.menuKey(
                key = FocusDiagnostics.keyName(event.nativeKeyEvent.keyCode),
                action = action,
                focused = state.focusRequest.anchor,
            )
            when (action) {
                MenuKeyAction.FOCUS_PREVIOUS -> actions.onMenuFocusMove(-1)
                MenuKeyAction.FOCUS_NEXT -> actions.onMenuFocusMove(1)
                MenuKeyAction.CLOSE_MENU -> actions.onCloseSidebar()
                // 菜单条目用 clickable 响应确认键，所以这里不能吞掉事件。
                MenuKeyAction.ACTIVATE_FOCUSED -> return false
                MenuKeyAction.SWALLOW -> Unit
            }
            // 菜单显示期间其余按键不传向页面，避免隔层操作。
            return true
        }

        if (event.key == Key.MediaPlayPause) {
            if (state.mode == TvMode.PLAYING || state.mode == TvMode.LOADING) {
                actions.onPlayPause()
                return true
            }
            return false
        }

        if (state.mode == TvMode.PLAYING && !state.isLive && !state.hudVisible && !modal) {
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
