package com.roy233.tvcast.ui.tv.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roy233.tvcast.R
import com.roy233.tvcast.ui.designsystem.FocusableRow
import com.roy233.tvcast.ui.designsystem.GlassIconButton
import com.roy233.tvcast.ui.designsystem.GlassMaterial
import com.roy233.tvcast.ui.designsystem.GlassSize
import com.roy233.tvcast.ui.designsystem.GlassSurface
import com.roy233.tvcast.ui.designsystem.TvModalCardKind
import com.roy233.tvcast.ui.designsystem.TvModalDialog
import com.roy233.tvcast.ui.designsystem.tvModalCard
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.tv.RequestFocusWhenReady
import com.roy233.tvcast.ui.tv.rememberFocusTarget

/** 用户可选的应用语言；空标签表示跟随系统。 */
internal enum class LanguageOption(
    val tag: String,
    @param:StringRes @get:StringRes val label: Int,
) {
    SYSTEM("", R.string.language_system),
    SIMPLIFIED_CHINESE("zh-CN", R.string.language_zh_cn),
    TRADITIONAL_CHINESE("zh-TW", R.string.language_zh_tw),
    ENGLISH("en", R.string.language_en),
}

/** 使用与播放设置一致的面板展示语言选项。 */
@Composable
internal fun LanguageDialog(state: TvUiState, actions: TvActions) {
    val selectedOption = LanguageOption.entries.firstOrNull { it.tag == state.appLanguage }
    val initialFocus = rememberFocusTarget()
    RequestFocusWhenReady(activation = state.languageDialogVisible, target = initialFocus)

    TvModalDialog(onDismissRequest = actions.onCloseLanguageSettings) {
        GlassSurface(
            modifier = Modifier.tvModalCard(TvModalCardKind.PANEL),
            material = GlassMaterial.Panel,
            size = GlassSize.Large,
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        text = stringResource(R.string.settings_language_dialog_title),
                        style = TvType.section,
                        modifier = Modifier.weight(1f),
                    )
                    GlassIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close),
                        onClick = actions.onCloseLanguageSettings,
                        size = 48.dp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier =
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    LanguageOption.entries.forEachIndexed { index, option ->
                        val isSelected = option == selectedOption
                        FocusableRow(
                            title = stringResource(option.label),
                            value =
                                if (isSelected) stringResource(R.string.common_current) else null,
                            selected = isSelected,
                            showChevron = false,
                            focusTarget =
                                if (isSelected || (selectedOption == null && index == 0))
                                    initialFocus
                                else null,
                            modifier =
                                Modifier.testTag("language-${option.tag.ifEmpty { "system" }}")
                                    .semantics { selected = isSelected },
                            onClick = { actions.onSetAppLanguage(option.tag) },
                        )
                    }
                }
            }
        }
    }
}
