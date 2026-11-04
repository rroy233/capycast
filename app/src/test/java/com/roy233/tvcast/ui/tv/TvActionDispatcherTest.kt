package com.roy233.tvcast.ui.tv

import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.model.UiFocusAnchor
import com.roy233.tvcast.ui.model.menuEntryFor
import com.roy233.tvcast.ui.model.pageAnchorFor
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证界面命令对菜单、导航与焦点交接的状态影响。 */
class TvActionDispatcherTest {

    @Test
    fun navigatingToSettingsClosesTheSidebar() {
        val stateHolder = TvStateHolder(TvUiState(sidebarVisible = true))

        TvActionDispatcher(stateHolder, noOpHost()).actions.onNavigateSettings()

        assertEquals(TvDestination.SETTINGS, stateHolder.read().destination)
        assertFalse(stateHolder.read().sidebarVisible)
    }

    @Test
    fun navigatingHomeClosesTheSidebar() {
        val stateHolder = TvStateHolder(
            TvUiState(destination = TvDestination.SETTINGS, sidebarVisible = true),
        )

        TvActionDispatcher(stateHolder, noOpHost()).actions.onNavigateHome()

        assertEquals(TvDestination.HOME, stateHolder.read().destination)
        assertFalse(stateHolder.read().sidebarVisible)
    }

    @Test
    fun openingTheMenuFocusesTheEntryOfTheCurrentPage() {
        val stateHolder = TvStateHolder(TvUiState(destination = TvDestination.SETTINGS))

        TvActionDispatcher(stateHolder, noOpHost()).actions.onOpenSidebar()

        assertTrue(stateHolder.read().sidebarVisible)
        assertEquals(UiFocusAnchor.MENU_SETTINGS, stateHolder.read().focusRequest.anchor)
    }

    @Test
    fun menuFocusStopsAtBothEnds() {
        val stateHolder = TvStateHolder(TvUiState())
        val actions = TvActionDispatcher(stateHolder, noOpHost()).actions
        actions.onOpenSidebar()

        actions.onMenuFocusMove(-1)
        assertEquals(UiFocusAnchor.MENU_HOME, stateHolder.read().focusRequest.anchor)

        actions.onMenuFocusMove(1)
        assertEquals(UiFocusAnchor.MENU_SETTINGS, stateHolder.read().focusRequest.anchor)

        actions.onMenuFocusMove(1)
        assertEquals(UiFocusAnchor.MENU_SETTINGS, stateHolder.read().focusRequest.anchor)
    }

    @Test
    fun menuFocusIgnoresInputWhileTheMenuIsClosed() {
        val stateHolder = TvStateHolder(TvUiState())
        val actions = TvActionDispatcher(stateHolder, noOpHost()).actions

        actions.onMenuFocusMove(1)

        assertEquals(UiFocusAnchor.HOME_PRIMARY, stateHolder.read().focusRequest.anchor)
    }

    @Test
    fun closingTheMenuPutsFocusBackOnTheCurrentPage() {
        val stateHolder = TvStateHolder(
            TvUiState(destination = TvDestination.SETTINGS, sidebarVisible = true),
        )
        val actions = TvActionDispatcher(stateHolder, noOpHost()).actions
        actions.onOpenSidebar()
        assertEquals(UiFocusAnchor.MENU_SETTINGS, stateHolder.read().focusRequest.anchor)

        actions.onCloseSidebar()

        assertFalse(stateHolder.read().sidebarVisible)
        assertEquals(UiFocusAnchor.SETTINGS_PLATFORM, stateHolder.read().focusRequest.anchor)
    }

    @Test
    fun settingsSubPagePutsFocusOnItsOwnTopAnchor() {
        val stateHolder = TvStateHolder(TvUiState(destination = TvDestination.SETTINGS))
        val actions = TvActionDispatcher(stateHolder, noOpHost()).actions

        actions.onOpenUpdateSettings()
        assertEquals(UiFocusAnchor.SETTINGS_UPDATE_BACK, stateHolder.read().focusRequest.anchor)

        actions.onNavigateSettingsBack()
        assertEquals(UiFocusAnchor.SETTINGS_UPDATE_ENTRY, stateHolder.read().focusRequest.anchor)
    }

    @Test
    fun reclaimingFocusAfterAnOverlayTargetsTheCurrentPage() {
        val stateHolder = TvStateHolder(TvUiState(destination = TvDestination.SETTINGS))

        TvActionDispatcher(stateHolder, noOpHost()).actions.onReclaimPageFocus()

        assertEquals(UiFocusAnchor.SETTINGS_PLATFORM, stateHolder.read().focusRequest.anchor)
    }

    @Test
    fun menuOpenedStateIsConsistentOnEveryDestination() {
        TvDestination.entries.forEach { destination ->
            val opened = TvUiState(destination = destination).withMenuOpen()

            assertTrue(opened.sidebarVisible)
            assertEquals(
                menuEntryFor(destination).anchor,
                opened.focusRequest.anchor,
            )

            val closed = opened.withMenuClosed()
            assertFalse(closed.sidebarVisible)
            assertEquals(pageAnchorFor(destination), closed.focusRequest.anchor)
        }
    }

    @Test
    fun menuFocusMoveRepairsAStaleAnchor() {
        // 状态里菜单已可见但栈顶仍是页面锚点（旧路径遗留），上下键必须能自愈。
        val stateHolder = TvStateHolder(
            TvUiState(destination = TvDestination.HOME, sidebarVisible = true),
        )

        TvActionDispatcher(stateHolder, noOpHost()).actions.onMenuFocusMove(1)

        assertEquals(UiFocusAnchor.MENU_SETTINGS, stateHolder.read().focusRequest.anchor)
    }

    private fun noOpHost(): TvActionHost = Proxy.newProxyInstance(
        TvActionHost::class.java.classLoader,
        arrayOf(TvActionHost::class.java),
    ) { _, _, _ -> null } as TvActionHost
}
