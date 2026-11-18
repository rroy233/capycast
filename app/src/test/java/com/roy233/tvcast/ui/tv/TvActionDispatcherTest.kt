package com.roy233.tvcast.ui.tv

import com.roy233.tvcast.ui.model.RecentCastFocus
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
    fun languageChoiceIsForwardedToHost() {
        var language: String? = null
        val host =
            Proxy.newProxyInstance(
                TvActionHost::class.java.classLoader,
                arrayOf(TvActionHost::class.java),
            ) { _, method, args ->
                if (method.name == "setAppLanguage") language = args[0] as String
                null
            } as TvActionHost

        val holder = TvStateHolder(TvUiState(destination = TvDestination.SETTINGS))
        val actions = TvActionDispatcher(holder, host).actions
        actions.onOpenLanguageSettings()
        assertTrue(holder.read().languageDialogVisible)
        actions.onSetAppLanguage("zh-TW")

        assertEquals("zh-TW", language)
        assertFalse(holder.read().languageDialogVisible)
    }

    @Test
    fun selectingCurrentLanguageClosesDialogWithoutChangingLocale() {
        var calls = 0
        val host =
            Proxy.newProxyInstance(
                TvActionHost::class.java.classLoader,
                arrayOf(TvActionHost::class.java),
            ) { _, method, _ ->
                if (method.name == "setAppLanguage") calls++
                null
            } as TvActionHost
        val holder =
            TvStateHolder(TvUiState(destination = TvDestination.SETTINGS, appLanguage = "en"))
        val actions = TvActionDispatcher(holder, host).actions
        actions.onOpenLanguageSettings()
        actions.onSetAppLanguage("en")
        assertFalse(holder.read().languageDialogVisible)
        assertEquals(0, calls)
    }

    @Test
    fun dismissingLanguageDialogRetainsSettingsAndFocus() {
        val initial =
            TvUiState(
                destination = TvDestination.SETTINGS,
                focusRequest =
                    com.roy233.tvcast.ui.model.FocusRequest(UiFocusAnchor.SETTINGS_LANGUAGE_ENTRY),
            )
        val holder = TvStateHolder(initial)
        val actions = TvActionDispatcher(holder, noOpHost()).actions
        actions.onOpenLanguageSettings()
        actions.onCloseLanguageSettings()
        assertFalse(holder.read().languageDialogVisible)
        assertEquals(initial.destination, holder.read().destination)
        assertEquals(initial.focusRequest, holder.read().focusRequest)
    }

    @Test
    fun languageDialogCannotOpenOutsideSettingsRoot() {
        val holder = TvStateHolder(TvUiState())
        val actions = TvActionDispatcher(holder, noOpHost()).actions
        actions.onOpenLanguageSettings()
        assertFalse(holder.read().languageDialogVisible)
    }

    @Test
    fun backClosesLanguageDialogBeforeNavigating() {
        val holder = TvStateHolder(TvUiState(destination = TvDestination.SETTINGS))
        val actions = TvActionDispatcher(holder, noOpHost()).actions
        actions.onOpenLanguageSettings()

        TvKeyHandler.dispatchBack(holder.read(), actions)

        assertFalse(holder.read().languageDialogVisible)
        assertFalse(holder.read().sidebarVisible)
        assertEquals(TvDestination.SETTINGS, holder.read().destination)
    }

    @Test
    fun newCastDismissesTheLanguageDialog() {
        val state = TvUiState(destination = TvDestination.SETTINGS, languageDialogVisible = true)
        val incoming =
            com.roy233.tvcast.ui.model.CastPlaybackStateProjector.incomingCast(state, "video")
        assertFalse(incoming.languageDialogVisible)
        assertEquals(null, incoming.topOverlay)
    }

    @Test
    fun navigatingToSettingsClosesTheSidebar() {
        val stateHolder = TvStateHolder(TvUiState(sidebarVisible = true))

        TvActionDispatcher(stateHolder, noOpHost()).actions.onNavigateSettings()

        assertEquals(TvDestination.SETTINGS, stateHolder.read().destination)
        assertFalse(stateHolder.read().sidebarVisible)
    }

    @Test
    fun navigatingHomeClosesTheSidebar() {
        val stateHolder =
            TvStateHolder(
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
        val stateHolder =
            TvStateHolder(
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
        val stateHolder =
            TvStateHolder(
                TvUiState(destination = TvDestination.HOME, sidebarVisible = true),
            )

        TvActionDispatcher(stateHolder, noOpHost()).actions.onMenuFocusMove(1)

        assertEquals(UiFocusAnchor.MENU_SETTINGS, stateHolder.read().focusRequest.anchor)
    }

    @Test
    fun featuredRecentFollowsTheFocusedCard() {
        val recents =
            listOf(
                recentCast(aid = 1L, title = "第一条"),
                recentCast(aid = 2L, title = "第二条"),
            )
        val stateHolder = TvStateHolder(TvUiState(recents = recents))
        val actions = TvActionDispatcher(stateHolder, noOpHost()).actions

        assertEquals(1L, stateHolder.read().featuredRecent?.aid)

        actions.onFocusRecent(2L, 20L)
        assertEquals("第二条", stateHolder.read().featuredRecent?.title)

        // 收起继续投屏时不再展示任何记录。
        stateHolder.set(stateHolder.read().copy(showContinue = false))
        assertEquals(null, stateHolder.read().featuredRecent)
    }

    @Test
    fun featuredRecentDistinguishesDifferentPartsOfTheSameVideo() {
        val stateHolder =
            TvStateHolder(
                TvUiState(
                    recents =
                        listOf(
                            recentCast(aid = 9L, title = "第一集"),
                            recentCast(aid = 9L, title = "第二集").copy(cid = 91L),
                        ),
                ),
            )
        val actions = TvActionDispatcher(stateHolder, noOpHost()).actions

        actions.onFocusRecent(9L, 91L)

        assertEquals("第二集", stateHolder.read().featuredRecent?.title)
    }

    @Test
    fun featuredRecentFallsBackWhenTheFocusedItemDisappears() {
        val stateHolder =
            TvStateHolder(
                TvUiState(
                    recents = listOf(recentCast(aid = 7L, title = "仍在列表")),
                    focusedRecent = RecentCastFocus(aid = 404L, cid = 4040L),
                ),
            )

        assertEquals(7L, stateHolder.read().featuredRecent?.aid)
    }

    @Test
    fun returningHomeAfterPlaybackAlwaysTargetsThePrimaryAction() {
        val returned =
            TvUiState(
                    destination = TvDestination.SETTINGS,
                    sidebarVisible = true,
                    focusedRecent = RecentCastFocus(aid = 7L, cid = 70L),
                )
                .returnToHomeAfterPlayback()

        assertEquals(TvDestination.HOME, returned.destination)
        assertFalse(returned.sidebarVisible)
        assertEquals(null, returned.focusedRecent)
        assertEquals(UiFocusAnchor.HOME_PRIMARY, returned.focusRequest.anchor)
    }

    private fun recentCast(aid: Long, title: String) =
        com.roy233.tvcast.feature.cast.domain.RecentCast(
            aid = aid,
            cid = aid * 10,
            title = title,
        )

    private fun noOpHost(): TvActionHost =
        Proxy.newProxyInstance(
            TvActionHost::class.java.classLoader,
            arrayOf(TvActionHost::class.java),
        ) { _, _, _ ->
            null
        } as TvActionHost
}
