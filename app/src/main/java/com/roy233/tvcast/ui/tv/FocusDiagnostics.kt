package com.roy233.tvcast.ui.tv

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.ui.model.FocusRequest
import com.roy233.tvcast.ui.model.MenuEntry
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.model.UiFocusAnchor

/**
 * 焦点与按键归属的诊断日志。
 *
 * 电视端常常无法接 adb，逻辑只能靠猜；这里在每个决策点写一条结构化日志，
 * 可通过 `http://<设备IP>:8098/log` 直接查看。日志只含界面标识，不含个人信息。
 */
internal object FocusDiagnostics {

    private const val TAG = "TvFocus"

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
        LogHub.d(
            TAG,
            "focus back to page destination=${state.destination} page=${state.settingsPage} " +
                "anchor=${state.focusRequest.anchor}",
        )
    }
}
