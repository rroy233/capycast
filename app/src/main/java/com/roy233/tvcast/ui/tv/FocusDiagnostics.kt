package com.roy233.tvcast.ui.tv

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.ui.model.FocusRequest
import com.roy233.tvcast.ui.model.MenuEntry
import com.roy233.tvcast.ui.model.SettingsRouteEntry
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.model.UiFocusAnchor
import com.roy233.tvcast.ui.model.menuEntryOf

/**
 * 焦点与按键归属的诊断日志。
 *
 * 电视端常常无法接 adb，逻辑只能靠猜。这里在每个决策点写一条结构化日志，并且每次
 * 按键都会附上「当前界面栈 + 实际持有焦点的元素」，便于逐帧校验是否符合预期：
 *
 *   curl http://<设备IP>:8098/log     按时间顺序的日志
 *   curl http://<设备IP>:8098/state   当前界面栈与焦点的快照（uiStack）
 *
 * 日志只含界面标识，不含个人信息。
 */
internal object FocusDiagnostics {

    private const val TAG = "TvFocus"

    /** 最近一次收到焦点的元素标签；没有元素获得焦点时为 [NO_FOCUS]。 */
    private var focusOwner: String = NO_FOCUS

    /** 最近一次输出的界面栈，用于去重，避免同一状态刷屏。 */
    private var lastStack: String = ""

    private const val NO_FOCUS = "none"

    /**
     * 记录某个元素获得或失去焦点。
     *
     * 由共享控件通过 [focusTrace] 调用，因此日志里的 focus 始终是真实持有焦点的
     * 元素，而不是“我们请求过的”元素。
     */
    fun trackFocus(label: String, isFocused: Boolean) {
        if (isFocused) {
            focusOwner = label
            LogHub.d(TAG, "focus owner -> $label")
        } else if (focusOwner == label) {
            focusOwner = NO_FOCUS
            LogHub.d(TAG, "focus owner -> none (left $label)")
        }
    }

    /** 当前界面的层级快照：目的页、设置路由、浮层、菜单与栈顶锚点。 */
    fun stackSnapshot(state: TvUiState): String = buildString {
        append("stack{destination=").append(state.destination)
        append("; mode=").append(state.mode)
        append("; route=").append(state.settingsNavigation.stack.joinToString(">") { it.page.name })
        append("; overlays=").append(state.overlayStack.joinToString(",").ifEmpty { "none" })
        append("; menu=").append(if (state.sidebarVisible) "open" else "closed")
        append("; top=").append(state.focusRequest.anchor)
        append("@").append(state.focusRequest.revision)
        append("; focus=").append(focusOwner)
        append("}")
    }

    /** 界面栈发生变化时输出一条快照，并把最新快照发布到诊断端点。 */
    fun stackChanged(state: TvUiState, reason: String) {
        val snapshot = stackSnapshot(state)
        LogHub.setUiStack("$reason $snapshot")
        validate(state)
        if (snapshot == lastStack) return
        lastStack = snapshot
        LogHub.d(TAG, "stack[$reason] $snapshot")
    }

    /**
     * 校验栈与焦点是否自洽，不自洽时以错误级别记录。
     *
     * 这里检查的都是“设计上不可能同时成立”的组合，出现即说明有路径绕过了模型规则。
     */
    fun validate(state: TvUiState) {
        val anchor = state.focusRequest.anchor
        val isMenuAnchor = menuEntryOf(anchor) != null
        if (state.sidebarVisible && !isMenuAnchor) {
            LogHub.e(
                TAG,
                "INCONSISTENT: menu open but top=$anchor; " +
                    "menu focus moves and the page are both dead ${stackSnapshot(state)}",
            )
        }
        if (!state.sidebarVisible && isMenuAnchor) {
            LogHub.e(
                TAG,
                "INCONSISTENT: menu closed but top=$anchor ${stackSnapshot(state)}",
            )
        }
        if (state.topOverlay == null && focusOwner == NO_FOCUS && state.mode == TvMode.IDLE) {
            LogHub.w(TAG, "NO FOCUS: nothing holds focus ${stackSnapshot(state)}")
        }
    }

    /**
     * 把 Android 键码翻译成可读名字。
     *
     * Compose 的 `Key.toString()` 形如 `Key code: 85899345920`，无法直接读懂，
     * 日志里统一使用这个映射。
     */
    fun keyName(keyCode: Int): String = when (keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_UP -> "DPAD_UP"
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> "DPAD_DOWN"
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> "DPAD_LEFT"
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> "DPAD_RIGHT"
        android.view.KeyEvent.KEYCODE_DPAD_CENTER -> "DPAD_CENTER"
        android.view.KeyEvent.KEYCODE_ENTER -> "ENTER"
        android.view.KeyEvent.KEYCODE_BACK -> "BACK"
        android.view.KeyEvent.KEYCODE_ESCAPE -> "ESCAPE"
        android.view.KeyEvent.KEYCODE_MENU -> "MENU"
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "MEDIA_PLAY_PAUSE"
        else -> "KEYCODE_$keyCode"
    }

    /** 一次按键的归属、动作与实际焦点。 */
    fun keyRouted(state: TvUiState, surface: KeySurface, key: String, handled: Boolean) {
        LogHub.d(TAG, "key=$key surface=$surface handled=$handled ${stackSnapshot(state)}")
        LogHub.setUiStack(stackSnapshot(state))
        validate(state)
    }

    /** 按键归属表面发生变化。 */
    fun surfaceChanged(from: KeySurface, to: KeySurface) {
        LogHub.d(TAG, "surface $from -> $to")
    }

    /** 菜单显示期间的一次按键决策。 */
    fun menuKey(key: String, action: MenuKeyAction, focused: UiFocusAnchor) {
        LogHub.d(TAG, "menu key=$key action=$action focused=$focused")
    }

    /** 菜单条目被点击或被确认键触发。 */
    fun menuItemActivated(entry: MenuEntry, destination: String) {
        LogHub.d(TAG, "menu item activated entry=$entry destination=$destination")
    }

    /** 菜单打开。 */
    fun menuOpened(entry: MenuEntry, destination: String, anchor: UiFocusAnchor) {
        LogHub.d(TAG, "menu opened entry=$entry destination=$destination anchor=$anchor")
    }

    /** 左侧边界判定结果。 */
    fun leftEdge(movedInsidePage: Boolean) {
        LogHub.d(
            TAG,
            if (movedInsidePage) "left edge: focus moved inside page" else "left edge: opening menu",
        )
    }

    /** 焦点恢复请求开始。 */
    fun focusRequest(request: FocusRequest, itemIndex: Int?) {
        LogHub.d(
            TAG,
            "focus request anchor=${request.anchor} revision=${request.revision} item=$itemIndex",
        )
    }

    /** 焦点恢复结果；始终失败时说明锚点没有可聚焦节点。 */
    fun focusResult(anchor: UiFocusAnchor, attempts: Int, focused: Boolean) {
        if (focused) {
            LogHub.d(TAG, "focus granted anchor=$anchor attempts=$attempts")
        } else {
            LogHub.w(TAG, "focus rejected anchor=$anchor attempts=$attempts")
        }
    }

    /** 栈顶焦点交还给页面。 */
    fun pageFocusReclaimed(state: TvUiState) {
        LogHub.d(TAG, "focus back to page ${stackSnapshot(state)}")
    }
}

/**
 * 标记一个可聚焦元素，使其获得焦点时写入诊断日志。
 *
 * @param label 元素的可读标识，例如 `button:投屏帮助`、`menu:设置`。
 */
internal fun Modifier.focusTrace(label: String): Modifier =
    onFocusChanged { FocusDiagnostics.trackFocus(label, it.isFocused) }
