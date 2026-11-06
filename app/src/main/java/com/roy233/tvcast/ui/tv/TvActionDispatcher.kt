package com.roy233.tvcast.ui.tv

import com.roy233.tvcast.feature.danmaku.domain.AiShieldLevel
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.feature.update.model.UpdateChannel
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.feature.cast.domain.RecentCast
import com.roy233.tvcast.ui.model.FocusRequest
import com.roy233.tvcast.ui.model.SettingsPage
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.UiFocusAnchor
import com.roy233.tvcast.ui.model.menuEntryFor
import com.roy233.tvcast.ui.model.menuEntryOf
import com.roy233.tvcast.ui.model.moved

/**
 * 电视界面需要宿主执行的业务命令。
 *
 * 界面的纯状态切换由 [TvActionDispatcher] 完成；涉及播放、账号、更新、弹幕与
 * 持久化的动作通过本接口交给宿主执行。
 */
interface TvActionHost {
    fun requestLogin()
    fun requestCloseLogin()
    fun requestSkipLogin()
    fun requestLogout()
    fun selectUpdateChannel(channel: UpdateChannel)
    fun setAutomaticUpdateCheck(enabled: Boolean)
    fun checkForUpdates()
    fun downloadUpdate()
    fun cancelUpdateDownload()
    fun installUpdate()
    fun requestPlayPause()
    fun requestSeekBy(deltaMs: Long)
    fun requestPreviewSeekBy(deltaMs: Long)
    fun requestConfirmSeek()
    fun requestPlaybackSpeed(speed: Float)
    fun toggleDanmaku()
    fun setDanmakuAiShield(level: AiShieldLevel)
    fun setDanmakuArea(ratio: Float)
    fun setDanmakuMaxOnScreen(max: Int)
    fun setDanmakuOpacity(opacity: Float)
    fun setDanmakuFontScale(scale: Float)
    fun setDanmakuDuration(seconds: Float)
    fun requestVolume(volume: Int)
    fun selectQuality(qn: Int)
    fun selectAudio(id: Int)
    fun requestRetry()
    fun requestDismissError()
    fun requestConfirmStopCast()
    fun requestClearToast()
    fun requestRecast(target: RecentCast)
    fun setRememberPlaybackContent(enabled: Boolean)
    fun setSyncPlaybackHistory(enabled: Boolean)
    fun handleHomeBack()
}

/**
 * 装配 [TvActions]，把界面操作分成两类处理。
 *
 * 纯界面状态（侧边栏、页面导航、控制栏、面板、停止确认等）直接写入 [TvStateHolder]；
 * 其余命令转交 [TvActionHost]。
 */
class TvActionDispatcher(
    private val stateHolder: TvStateHolder,
    private val host: TvActionHost,
) {

    /** 提供给电视界面的操作回调集合；实例在构造期固定，避免重组时重建。 */
    val actions: TvActions = TvActions(
        onLogin = {
            val state = stateHolder.read()
            if (state.mode == TvMode.IDLE && !state.loggedIn) {
                stateHolder.set(state.copy(loginDialogVisible = true, sidebarVisible = false))
                host.requestLogin()
            }
        },
        onCloseLogin = {
            stateHolder.set(stateHolder.read().copy(loginDialogVisible = false))
            host.requestCloseLogin()
        },
        onSkipLogin = { host.requestSkipLogin() },
        onOpenSidebar = {
            val state = stateHolder.read()
            stateHolder.set(state.withMenuOpen())
            FocusDiagnostics.menuOpened(
                entry = menuEntryFor(state.destination),
                destination = state.destination.name,
                anchor = stateHolder.read().focusRequest.anchor,
            )
        },
        onCloseSidebar = {
            stateHolder.set(stateHolder.read().withMenuClosed())
            FocusDiagnostics.pageFocusReclaimed(stateHolder.read())
        },
        onMenuFocusMove = { step ->
            val state = stateHolder.read()
            if (state.sidebarVisible) {
                // 栈顶若不是菜单条目，说明打开菜单的路径漏了锚点；从当前目的页条目重新起步。
                val current = menuEntryOf(state.focusRequest.anchor)
                    ?: menuEntryFor(state.destination)
                val target = current.moved(step)
                if (target != current || current.anchor != state.focusRequest.anchor) {
                    stateHolder.set(
                        state.copy(focusRequest = state.focusRequest.moveTo(target.anchor)),
                    )
                }
            }
        },
        onFocusRecent = { aid ->
            val state = stateHolder.read()
            if (state.focusedRecentAid != aid) {
                stateHolder.set(state.copy(focusedRecentAid = aid))
            }
        },
        onReclaimPageFocus = {
            val state = stateHolder.read().focusCurrentPage()
            stateHolder.set(state)
            FocusDiagnostics.pageFocusReclaimed(state)
        },
        onNavigateHome = {
            val state = stateHolder.read()
            stateHolder.set(
                state.copy(
                    destination = TvDestination.HOME,
                    settingsNavigation = state.settingsNavigation.reset(),
                    sidebarVisible = false,
                    hudVisible = state.mode == TvMode.PLAYING,
                ).focusCurrentPage(),
            )
        },
        onNavigateSettings = {
            val state = stateHolder.read()
            stateHolder.set(
                state.copy(
                    destination = TvDestination.SETTINGS,
                    settingsNavigation = state.settingsNavigation.reset(),
                    sidebarVisible = false,
                    hudVisible = false,
                ).focusCurrentPage(),
            )
        },
        onOpenUpdateSettings = {
            val state = stateHolder.read()
            val navigation = state.settingsNavigation.push(
                page = SettingsPage.UPDATE,
                returnFocus = UiFocusAnchor.SETTINGS_UPDATE_ENTRY,
            )
            stateHolder.set(
                state.copy(
                    destination = TvDestination.SETTINGS,
                    sidebarVisible = false,
                    hudVisible = false,
                    settingsNavigation = navigation,
                    focusRequest = state.focusRequest.moveTo(navigation.topAnchor),
                ),
            )
        },
        onNavigateSettingsBack = {
            val state = stateHolder.read()
            // 出栈：栈顶锚点由新栈顶决定，父页声明的返回锚点优先。
            val returnAnchor = state.settingsNavigation.returnAnchor
            val navigation = state.settingsNavigation.pop()
            stateHolder.set(
                state.copy(
                    settingsNavigation = navigation,
                    focusRequest = state.focusRequest.moveTo(returnAnchor ?: navigation.topAnchor),
                ),
            )
        },
        onOpenUpdateNotes = {
            val state = stateHolder.read()
            if (state.update.availableUpdate != null) {
                val navigation = state.settingsNavigation.push(
                    page = SettingsPage.UPDATE_NOTES,
                    returnFocus = UiFocusAnchor.SETTINGS_UPDATE_RELEASE_NOTES,
                )
                stateHolder.set(
                    state.copy(
                        settingsNavigation = navigation,
                        focusRequest = state.focusRequest.moveTo(navigation.topAnchor),
                    ),
                )
            }
        },
        onSelectUpdateChannel = { channel -> host.selectUpdateChannel(channel) },
        onSetAutoUpdateCheck = { enabled -> host.setAutomaticUpdateCheck(enabled) },
        onCheckForUpdates = { host.checkForUpdates() },
        onDownloadUpdate = { host.downloadUpdate() },
        onCancelUpdateDownload = { host.cancelUpdateDownload() },
        onInstallUpdate = { host.installUpdate() },
        onShowHelp = {
            stateHolder.set(stateHolder.read().copy(helpVisible = true))
        },
        onCloseHelp = {
            stateHolder.set(stateHolder.read().copy(helpVisible = false))
        },
        onLogout = { host.requestLogout() },
        onPlayPause = { host.requestPlayPause() },
        onSeekBy = { host.requestSeekBy(it) },
        onPreviewSeekBy = { host.requestPreviewSeekBy(it) },
        onConfirmSeek = { host.requestConfirmSeek() },
        onSetPlaybackSpeed = { host.requestPlaybackSpeed(it) },
        onToggleDanmaku = { host.toggleDanmaku() },
        onSetDanmakuAiShield = { host.setDanmakuAiShield(it) },
        onSetDanmakuArea = { host.setDanmakuArea(it) },
        onSetDanmakuMaxOnScreen = { host.setDanmakuMaxOnScreen(it) },
        onSetDanmakuOpacity = { host.setDanmakuOpacity(it) },
        onSetDanmakuFontScale = { host.setDanmakuFontScale(it) },
        onSetDanmakuDuration = { host.setDanmakuDuration(it) },
        onSetVolume = { host.requestVolume(it) },
        onShowHud = {
            stateHolder.set(stateHolder.read().copy(hudVisible = true))
        },
        onHideHud = {
            val state = stateHolder.read()
            if (state.mode == TvMode.PLAYING &&
                state.panel == null &&
                state.error == null &&
                !state.stopCastConfirm
            ) {
                stateHolder.set(state.copy(hudVisible = false))
            }
        },
        onOpenPanel = { kind ->
            val state = stateHolder.read()
            if (state.isLive && kind != PanelKind.ROOT && kind != PanelKind.LIVE_QUALITY) {
                LogHub.i(TAG, "live playback ignored unsupported panel=$kind")
            } else {
                stateHolder.set(
                    state.copy(
                        panel = kind,
                        hudVisible = true,
                        stopCastConfirm = false,
                    ),
                )
            }
        },
        onClosePanel = {
            stateHolder.set(stateHolder.read().copy(panel = null, hudVisible = true))
        },
        onSelectQuality = { qn -> host.selectQuality(qn) },
        onSelectAudio = { id -> host.selectAudio(id) },
        onRetry = { host.requestRetry() },
        onDismissError = { host.requestDismissError() },
        onRequestStopCast = {
            stateHolder.set(
                stateHolder.read().copy(stopCastConfirm = true, hudVisible = true),
            )
        },
        onConfirmStopCast = { host.requestConfirmStopCast() },
        onCancelStopCast = {
            stateHolder.set(
                stateHolder.read().copy(stopCastConfirm = false, hudVisible = true),
            )
        },
        onClearToast = { host.requestClearToast() },
        onRecast = { target -> host.requestRecast(target) },
        onDismissContinue = {
            stateHolder.set(stateHolder.read().copy(showContinue = false))
        },
        onSetRememberPlaybackContent = { enabled -> host.setRememberPlaybackContent(enabled) },
        onSetSyncPlaybackHistory = { enabled -> host.setSyncPlaybackHistory(enabled) },
        onHomeBack = { host.handleHomeBack() },
    )

    private companion object {
        /** 日志标签。 */
        const val TAG = "SimpleBili"
    }
}
