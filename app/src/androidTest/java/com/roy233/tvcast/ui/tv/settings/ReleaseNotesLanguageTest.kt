package com.roy233.tvcast.ui.tv.settings

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roy233.tvcast.feature.update.AppUpdateInfo
import com.roy233.tvcast.feature.update.UpdateStatus
import com.roy233.tvcast.feature.update.UpdateUiState
import com.roy233.tvcast.feature.update.model.UpdateChannel
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseNotesLanguageTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun previewTracksTheEffectiveAppLanguage() {
        var locale by mutableStateOf(Locale.SIMPLIFIED_CHINESE)
        composeRule.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply { setLocale(locale) }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                UpdateSettingsScreen(updateState(), TvActions())
            }
        }

        composeRule.onNodeWithText("简体更新").assertExists()
        composeRule.runOnIdle { locale = Locale.ENGLISH }
        composeRule.onNodeWithText("English update").assertExists()
        composeRule.onNodeWithText("简体更新").assertDoesNotExist()
    }

    @Test
    fun fullNotesUseTheSameLanguageAsThePreview() {
        composeRule.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply { setLocale(Locale.ENGLISH) }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                UpdateNotesScreen(updateState(), TvActions())
            }
        }

        composeRule.onNodeWithText("English update").assertExists()
        composeRule.onNodeWithText("Added subtitles").assertExists()
        composeRule.onNodeWithText("简体更新").assertDoesNotExist()
    }

    private fun updateState() =
        TvUiState(
            update =
                UpdateUiState(
                    status = UpdateStatus.AVAILABLE,
                    availableUpdate =
                        AppUpdateInfo(
                            channel = UpdateChannel.STABLE,
                            versionName = "0.2.1",
                            versionCode = 21,
                            apkName = "capycast-0.2.1.apk",
                            downloadUrl = "https://example.com/capycast.apk",
                            sha256 = "a".repeat(64),
                            sizeBytes = 1_000,
                            releaseNotes = NOTES,
                            releaseUrl = "https://example.com/release",
                        ),
                ),
        )

    private companion object {
        val NOTES =
            """
            <!-- capycast:release-notes:v1 -->
            <!-- capycast:release-notes:zh-CN:start -->
            # 简体更新
            - 支持字幕
            <!-- capycast:release-notes:zh-CN:end -->
            <details>
            <summary>English</summary>

            <!-- capycast:release-notes:en:start -->
            # English update
            - Added subtitles
            <!-- capycast:release-notes:en:end -->

            </details>
            """
                .trimIndent()
    }
}
