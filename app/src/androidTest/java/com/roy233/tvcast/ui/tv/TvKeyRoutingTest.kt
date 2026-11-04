package com.roy233.tvcast.ui.tv

import androidx.compose.ui.input.key.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvError
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.model.UiFocusAnchor
import com.roy233.tvcast.ui.tv.player.PlaybackSeekAccelerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 验证按键归属：同一时刻只有一个表面接收按键，菜单显示期间页面不可操作。 */
@RunWith(AndroidJUnit4::class)
class TvKeyRoutingTest {

    @Test
    fun visibleMenuOwnsTheKeysOnBrowseSurfaces() {
        val state = TvUiState(destination = TvDestination.SETTINGS, sidebarVisible = true)

        assertEquals(KeySurface.MENU, TvKeyHandler.resolveKeySurface(state))
        assertEquals(
            KeySurface.PAGE,
            TvKeyHandler.resolveKeySurface(state.copy(mode = TvMode.PLAYING)),
        )
    }

    @Test
    fun overlaysOutrankTheMenu() {
        val menu = TvUiState(sidebarVisible = true)

        assertEquals(
            KeySurface.HELP,
            TvKeyHandler.resolveKeySurface(menu.copy(helpVisible = true)),
        )
        assertEquals(
            KeySurface.LOGIN_DIALOG,
            TvKeyHandler.resolveKeySurface(menu.copy(loginDialogVisible = true)),
        )
        assertEquals(
            KeySurface.PANEL,
            TvKeyHandler.resolveKeySurface(menu.copy(panel = PanelKind.QUALITY)),
        )
        assertEquals(
            KeySurface.ERROR,
            TvKeyHandler.resolveKeySurface(menu.copy(error = TvError("错误", "详情"))),
        )
        assertEquals(
            KeySurface.STOP_CAST_CONFIRM,
            TvKeyHandler.resolveKeySurface(menu.copy(stopCastConfirm = true)),
        )
    }

    @Test
    fun menuKeepsFocusMovementAndClosesOnRight() {
        assertEquals(MenuKeyAction.FOCUS_PREVIOUS, TvKeyHandler.resolveMenuKeyAction(keyDown(android.view.KeyEvent.KEYCODE_DPAD_UP)))
        assertEquals(MenuKeyAction.FOCUS_NEXT, TvKeyHandler.resolveMenuKeyAction(keyDown(android.view.KeyEvent.KEYCODE_DPAD_DOWN)))
        assertEquals(MenuKeyAction.CLOSE_MENU, TvKeyHandler.resolveMenuKeyAction(keyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals(MenuKeyAction.SWALLOW, TvKeyHandler.resolveMenuKeyAction(keyDown(android.view.KeyEvent.KEYCODE_DPAD_LEFT)))
        // 确认键必须交给已获焦点的菜单条目，否则菜单里“点了没反应”。
        assertEquals(
            MenuKeyAction.ACTIVATE_FOCUSED,
            TvKeyHandler.resolveMenuKeyAction(keyDown(android.view.KeyEvent.KEYCODE_DPAD_CENTER)),
        )
        assertEquals(
            MenuKeyAction.ACTIVATE_FOCUSED,
            TvKeyHandler.resolveMenuKeyAction(keyDown(android.view.KeyEvent.KEYCODE_ENTER)),
        )
    }

    @Test
    fun menuKeysNeverReachThePage() {
        val state = TvUiState(destination = TvDestination.SETTINGS, sidebarVisible = true)
        var moved = 0
        var closed = false
        var selectedPage: UiFocusAnchor? = null
        val actions = TvActions(
            onMenuFocusMove = { moved += it },
            onCloseSidebar = { closed = true },
            onNavigateSettings = { selectedPage = UiFocusAnchor.SETTINGS_PLATFORM },
        )

        listOf(
            android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        ).forEach { key ->
            TvKeyHandler.handle(keyDown(key), state, actions, modal = true, seekAccelerator = seek())
        }

        assertEquals(1, moved)
        assertTrue(closed)
        assertEquals(null, selectedPage)
    }

    @Test
    fun confirmKeyStaysAvailableToTheFocusedMenuItem() {
        val state = TvUiState(sidebarVisible = true)
        var moved = 0
        val actions = TvActions(onMenuFocusMove = { moved += it })

        val consumed = TvKeyHandler.handle(
            keyDown(android.view.KeyEvent.KEYCODE_DPAD_CENTER),
            state,
            actions,
            modal = true,
            seekAccelerator = seek(),
        )

        assertFalse(consumed)
        assertEquals(0, moved)
    }

    @Test
    fun leftEdgeMayOpenTheMenuOnlyFromAPage() {
        assertTrue(TvKeyHandler.canOpenMenuFromEdge(TvUiState()))
        assertTrue(
            TvKeyHandler.canOpenMenuFromEdge(TvUiState(destination = TvDestination.SETTINGS)),
        )
        assertFalse(TvKeyHandler.canOpenMenuFromEdge(TvUiState(sidebarVisible = true)))
        assertFalse(TvKeyHandler.canOpenMenuFromEdge(TvUiState(helpVisible = true)))
        assertFalse(
            TvKeyHandler.canOpenMenuFromEdge(TvUiState(mode = TvMode.PLAYING)),
        )
    }

    @Test
    fun backOnTheOpenMenuClosesIt() {
        var closed = false

        TvKeyHandler.dispatchBack(
            state = TvUiState(sidebarVisible = true),
            actions = TvActions(onCloseSidebar = { closed = true }),
        )

        assertTrue(closed)
    }

    private fun keyDown(androidKeyCode: Int): KeyEvent = KeyEvent(
        android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, androidKeyCode),
    )

    private fun seek(): PlaybackSeekAccelerator = PlaybackSeekAccelerator()
}
