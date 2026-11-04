package com.roy233.tvcast.ui.tv

import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvUiState
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

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

    private fun noOpHost(): TvActionHost = Proxy.newProxyInstance(
        TvActionHost::class.java.classLoader,
        arrayOf(TvActionHost::class.java),
    ) { _, _, _ -> null } as TvActionHost
}
