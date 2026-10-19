package com.roy233.tvcast.ui.tv.settings

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roy233.tvcast.feature.update.UpdateChannel
import com.roy233.tvcast.feature.update.AppUpdateInfo
import com.roy233.tvcast.feature.update.UpdateStatus
import com.roy233.tvcast.feature.update.UpdateUiState
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
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
    fun updatePageRequestsInitialFocusOnCheckAction() {
        composeRule.setContent {
            UpdateSettingsScreen(state = TvUiState(), actions = TvActions())
        }

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
}
