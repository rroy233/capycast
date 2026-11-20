package com.roy233.tvcast.ui.tv.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import com.roy233.tvcast.feature.update.presentation.LocalizedReleaseNotes

/** 使用当前应用配置语言，保证摘要与全文选择同一段发布说明。 */
@Composable
internal fun releaseNotesText(body: String): String {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0]
    return remember(body, locale) { LocalizedReleaseNotes.select(body, locale) }
}
