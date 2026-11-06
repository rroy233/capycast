package com.roy233.tvcast.ui.tv.settings

import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roy233.tvcast.feature.update.model.UpdateChannel
import com.roy233.tvcast.feature.update.AppUpdateInfo
import com.roy233.tvcast.feature.update.UpdateStatus
import com.roy233.tvcast.feature.update.UpdateUiState
import com.roy233.tvcast.ui.model.FocusRequest
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.model.UiFocusAnchor
import com.roy233.tvcast.ui.tv.TvActionDispatcher
import com.roy233.tvcast.ui.tv.TvActionHost
import com.roy233.tvcast.ui.tv.TvStateHolder
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class UpdateSettingsFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun updatePageRequestsInitialFocusAtTopAndMovesThroughSections() {
        composeRule.setContent {
            UpdateSettingsScreen(state = TvUiState(), actions = TvActions())
        }

        composeRule.onNodeWithText("返回").assertIsFocused()
        composeRule.onNodeWithText("返回")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("选择通道后检查更新").assertIsFocused()
        composeRule.onNodeWithText("选择通道后检查更新")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("检查更新").assertIsFocused()
    }

    @Test
    fun dpadMovesFocusToChannelAndCenterSelectsIt() {
        var selectedChannel: UpdateChannel? = null
        composeRule.setContent {
            UpdateSettingsScreen(
                state = TvUiState(),
                actions = TvActions(onSelectUpdateChannel = { selectedChannel = it }),
            )
        }

        composeRule.onNodeWithText("返回")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("选择通道后检查更新")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("检查更新")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("正式版").assertIsFocused()
        composeRule.onNodeWithText("正式版")
            .performKeyInput { pressKey(Key.DirectionCenter) }

        composeRule.runOnIdle {
            assertEquals(UpdateChannel.STABLE, selectedChannel)
        }
    }

    @Test
    fun updatePageReturnButtonNavigatesBack() {
        var navigatedBack = false
        composeRule.setContent {
            UpdateSettingsScreen(
                state = TvUiState(),
                actions = TvActions(onNavigateSettingsBack = { navigatedBack = true }),
            )
        }

        composeRule.onNodeWithText("返回").performClick()
        composeRule.runOnIdle { assertTrue(navigatedBack) }
    }

    @Test
    fun updatePageReturnRestoresTheSettingsEntryThroughTheDispatcher() {
        lateinit var dispatcher: TvActionDispatcher
        composeRule.setContent {
            val stateHolder = remember {
                TvStateHolder(
                    TvUiState(
                        destination = TvDestination.SETTINGS,
                        focusRequest = FocusRequest(UiFocusAnchor.SETTINGS_UPDATE_ENTRY),
                        loggedIn = true,
                        accountName = "tv-user",
                    ),
                )
            }
            dispatcher = remember { TvActionDispatcher(stateHolder, noOpHost()) }
            Box(Modifier.fillMaxSize()) {
                SettingsScreen(
                    state = stateHolder.state,
                    actions = dispatcher.actions,
                )
            }
        }

        composeRule.onNodeWithText("检查更新").assertIsFocused()
        composeRule.runOnIdle { dispatcher.actions.onOpenUpdateSettings() }
        composeRule.onNodeWithText("返回").assertIsFocused()
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("账号信息").assertExists()
        composeRule.onNodeWithText("检查更新").assertIsFocused()
    }

    @Test
    fun selectedChannelIsExposedAsSelected() {
        composeRule.setContent {
            UpdateSettingsScreen(state = TvUiState(), actions = TvActions())
        }

        composeRule.onNodeWithText("正式版").assertIsSelected()
    }

    @Test
    fun availableUpdateShowsVersionAndNotesEntry() {
        val release = sampleUpdate()
        composeRule.setContent {
            UpdateSettingsScreen(
                state = TvUiState(
                    version = "0.1.0",
                    update = UpdateUiState(
                        status = UpdateStatus.AVAILABLE,
                        availableUpdate = release,
                    ),
                ),
                actions = TvActions(),
            )
        }

        composeRule.onNodeWithText("v1.2.3").assertExists()
        composeRule.onNodeWithText("查看更新日志").assertExists()
        composeRule.onNodeWithText("改进").assertExists()
        composeRule.onNodeWithText("下载").assertExists()
    }

    @Test
    fun updateNotesEntryOpensNotesPage() {
        var opened = false
        composeRule.setContent {
            UpdateSettingsScreen(
                state = TvUiState(
                    update = UpdateUiState(
                        status = UpdateStatus.AVAILABLE,
                        availableUpdate = sampleUpdate(),
                    ),
                ),
                actions = TvActions(onOpenUpdateNotes = { opened = true }),
            )
        }

        composeRule.onNodeWithText("查看更新日志").performClick()
        composeRule.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun updateNotesPageShowsFullReleaseNotes() {
        composeRule.setContent {
            UpdateNotesScreen(
                state = TvUiState(
                    update = UpdateUiState(
                        status = UpdateStatus.AVAILABLE,
                        availableUpdate = sampleUpdate(),
                    ),
                ),
                actions = TvActions(),
            )
        }

        composeRule.onNodeWithText("更新日志").assertExists()
        composeRule.onNodeWithText("v1.2.3").assertExists()
        composeRule.onNodeWithText("改进").assertExists()
        composeRule.onNodeWithText("适配电视遥控器", substring = true).assertExists()
        composeRule.onNodeWithText("返回").assertExists()
    }

    @Test
    fun updateNotesPageMovesFocusThroughEntries() {
        val notes = "# 改进\n" + (1..12).joinToString("\n") { "- 条目 $it" }
        composeRule.setContent {
            UpdateNotesScreen(
                state = TvUiState(
                    update = UpdateUiState(
                        status = UpdateStatus.AVAILABLE,
                        availableUpdate = sampleUpdate(notes),
                    ),
                ),
                actions = TvActions(),
            )
        }

        composeRule.onNodeWithText("改进").assertIsFocused()
        composeRule.onNodeWithText("改进")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("条目 1", substring = true).assertIsFocused()
        composeRule.onNodeWithText("条目 1", substring = true)
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("条目 2", substring = true).assertIsFocused()
    }

    @Test
    fun longReleaseNoteScrollsInSmallStepsBeforeMovingFocus() {
        val longNote = "条目内容 ".repeat(120)
        composeRule.setContent {
            UpdateNotesScreen(
                state = TvUiState(
                    update = UpdateUiState(
                        status = UpdateStatus.AVAILABLE,
                        availableUpdate = sampleUpdate("# 说明\n$longNote\n- 下一项"),
                    ),
                ),
                actions = TvActions(),
            )
        }

        composeRule.onNodeWithText("说明").assertIsFocused()
        composeRule.onNodeWithText("说明").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("条目内容", substring = true).assertIsFocused()
        composeRule.onNodeWithText("条目内容", substring = true)
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("条目内容", substring = true).assertIsFocused()
    }

    @Test
    fun loggedInBilibiliSettingsExposeInteractivePlaybackOptions() {
        var rememberPlaybackContent = true
        composeRule.setContent {
            SettingsScreen(
                state = TvUiState(loggedIn = true, accountName = "tv-user"),
                actions = TvActions(
                    onSetRememberPlaybackContent = { rememberPlaybackContent = it },
                ),
            )
        }

        composeRule.onNodeWithTag("platform-bilibili")
            .assertIsFocused()
            .assertIsSelected()
        composeRule.onNodeWithTag("platform-bilibili")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("tv-user").assertIsFocused()
        composeRule.onNodeWithText("tv-user")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(true, rememberPlaybackContent)
        composeRule.onNodeWithText("记忆播放内容").performClick()
        assertEquals(false, rememberPlaybackContent)
    }

    @Test
    fun loggedOutBilibiliPlatformExposesLoginAction() {
        var loginRequested = false
        composeRule.setContent {
            SettingsScreen(
                state = TvUiState(loggedIn = false),
                actions = TvActions(onLogin = { loginRequested = true }),
            )
        }

        composeRule.onNodeWithTag("platform-bilibili")
            .assertIsFocused()
            .assertIsSelected()
        composeRule.onNodeWithText("扫码登录").performClick()
        composeRule.runOnIdle { assertTrue(loginRequested) }
    }

    @Test
    fun downloadButtonDisplaysProgressAndCanCancel() {
        var cancelled = false
        composeRule.setContent {
            UpdateSettingsScreen(
                state = TvUiState(
                    version = "0.1.0",
                    update = UpdateUiState(
                        status = UpdateStatus.DOWNLOADING,
                        availableUpdate = sampleUpdate(),
                        downloadedBytes = 420L,
                        totalBytes = 1_000L,
                    ),
                ),
                actions = TvActions(onCancelUpdateDownload = { cancelled = true }),
            )
        }

        composeRule.onNodeWithText("下载中 42%").assertExists()
        composeRule.onNodeWithText("应用更新")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("正在下载新版本").assertIsFocused()
        composeRule.onNodeWithText("正在下载新版本")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("查看更新日志")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("下载中 42%")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle { assertEquals(true, cancelled) }
    }

    @Test
    fun verifiedUpdateShowsInstallAction() {
        composeRule.setContent {
            UpdateSettingsScreen(
                state = TvUiState(
                    version = "0.1.0",
                    update = UpdateUiState(
                        status = UpdateStatus.READY_TO_INSTALL,
                        availableUpdate = sampleUpdate(),
                        downloadedApkPath = "/tmp/update.apk",
                    ),
                ),
                actions = TvActions(),
            )
        }

        composeRule.onNodeWithText("安装包已准备就绪").assertExists()
        composeRule.onNodeWithText("安装").assertExists()
    }

    private fun sampleUpdate(notes: String = "# 改进\n- 适配电视遥控器") = AppUpdateInfo(
        channel = UpdateChannel.STABLE,
        versionName = "1.2.3",
        versionCode = 123L,
        apkName = "capycast-1.2.3.apk",
        downloadUrl = "https://github.com/rroy233/capycast/releases/download/v1.2.3/capycast-1.2.3.apk",
        sha256 = "a".repeat(64),
        sizeBytes = 1_000L,
        releaseNotes = notes,
        releaseUrl = "https://github.com/rroy233/capycast/releases/tag/v1.2.3",
    )

    private fun noOpHost(): TvActionHost = Proxy.newProxyInstance(
        TvActionHost::class.java.classLoader,
        arrayOf(TvActionHost::class.java),
    ) { _, _, _ -> null } as TvActionHost
}
