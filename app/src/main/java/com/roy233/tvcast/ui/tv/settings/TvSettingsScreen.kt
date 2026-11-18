package com.roy233.tvcast.ui.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roy233.tvcast.R
import com.roy233.tvcast.ui.designsystem.BilibiliTvLogo
import com.roy233.tvcast.ui.designsystem.FocusableRow
import com.roy233.tvcast.ui.model.SettingsPage
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.model.UiFocusAnchor
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvDimens
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.tv.FocusBinding
import com.roy233.tvcast.ui.tv.RestoreFocus
import com.roy233.tvcast.ui.tv.focusTrace
import com.roy233.tvcast.ui.tv.home.WhiteButton
import com.roy233.tvcast.ui.tv.rememberFocusTarget

/** 账号信息中可选的平台；保留枚举以便后续接入其他平台。 */
private enum class AccountPlatform(val label: String) {
    BILIBILI("Bilibili"),
}

/** 显示由账号、软件与设备信息组成的设置首页。 */
@Composable
internal fun SettingsScreen(state: TvUiState, actions: TvActions) {
    val platformFocus = remember { FocusRequester() }
    val loginFocus = remember { FocusRequester() }
    val updateEntryFocus = remember { FocusRequester() }
    var selectedPlatform by remember { mutableStateOf(AccountPlatform.BILIBILI) }
    val languageFocus = rememberFocusTarget()
    val listState = rememberLazyListState()

    when (state.settingsPage) {
        SettingsPage.UPDATE -> {
            UpdateSettingsScreen(state, actions)
            return
        }

        SettingsPage.UPDATE_NOTES -> {
            UpdateNotesScreen(state, actions)
            return
        }

        SettingsPage.ROOT -> Unit
    }

    LaunchedEffect(Unit) {
        when (state.focusRequest.anchor) {
            UiFocusAnchor.SETTINGS_UPDATE_ENTRY -> updateEntryFocus.requestFocus()
            UiFocusAnchor.SETTINGS_LANGUAGE_ENTRY -> Unit
            else -> platformFocus.requestFocus()
        }
    }
    RestoreFocus(
        request = state.focusRequest,
        bindings = listOf(FocusBinding(UiFocusAnchor.SETTINGS_LANGUAGE_ENTRY, languageFocus, 1)),
        listState = listState,
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 500.dp
        val horizontalPadding = if (compact) 32.dp else 80.dp
        val verticalPadding = if (compact) 20.dp else 52.dp
        val sectionGap = if (compact) 14.dp else 20.dp

        LazyColumn(
            state = listState,
            modifier =
                Modifier.width(
                        (maxWidth - horizontalPadding * 2).coerceAtMost(TvDimens.pageMaxWidth),
                    )
                    .fillMaxHeight()
                    .align(Alignment.Center)
                    .padding(vertical = verticalPadding)
                    .focusRestorer(platformFocus)
                    .testTag("settings-list"),
            verticalArrangement = Arrangement.spacedBy(sectionGap),
        ) {
            item {
                SettingsSection(stringResource(R.string.settings_section_account)) {
                    PlatformGallery(
                        selectedPlatform = selectedPlatform,
                        loggedIn = state.loggedIn,
                        compact = compact,
                        focusRequester = platformFocus,
                        onPlatformSelected = { selectedPlatform = it },
                    )
                    SettingsRowDivider()
                    when (selectedPlatform) {
                        AccountPlatform.BILIBILI ->
                            BilibiliAccountSettings(
                                state = state,
                                actions = actions,
                                loginFocus = loginFocus,
                                compact = compact,
                            )
                    }
                }
            }

            item {
                SettingsSection(stringResource(R.string.settings_section_software)) {
                    SettingsInfoRow(
                        stringResource(R.string.settings_version),
                        versionDisplay(state),
                    )
                    SettingsRowDivider()
                    FocusableRow(
                        title = stringResource(R.string.settings_check_update),
                        onClick = actions.onOpenUpdateSettings,
                        focusRequester = updateEntryFocus,
                        rowHeight = if (compact) 56.dp else 64.dp,
                    )
                    SettingsRowDivider()
                    val language =
                        LanguageOption.entries.firstOrNull { it.tag == state.appLanguage }
                    FocusableRow(
                        title = stringResource(R.string.settings_language),
                        value = language?.let { stringResource(it.label) } ?: state.appLanguage,
                        onClick = actions.onOpenLanguageSettings,
                        focusTarget = languageFocus,
                        modifier = Modifier.testTag("language-settings-entry"),
                        rowHeight = if (compact) 56.dp else 64.dp,
                    )
                    SettingsRowDivider()
                    FocusableRow(
                        title = stringResource(R.string.feedback_title),
                        onClick = actions.onShowFeedback,
                        rowHeight = if (compact) 56.dp else 64.dp,
                    )
                }
            }

            item {
                SettingsSection(stringResource(R.string.settings_section_device)) {
                    SettingsInfoRow(stringResource(R.string.settings_device_name), state.deviceName)
                    SettingsRowDivider()
                    SettingsInfoRow(stringResource(R.string.settings_network), state.network)
                    SettingsRowDivider()
                    SettingsInfoRow(stringResource(R.string.settings_ip), state.ip)
                    SettingsRowDivider()
                    FocusableRow(
                        title = stringResource(R.string.settings_calibration),
                        onClick = actions.onStartCalibration,
                        rowHeight = if (compact) 56.dp else 64.dp,
                    )
                }
            }

            item {
                SettingsSection(stringResource(R.string.settings_section_privacy)) {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_crash_tracking),
                        checked = state.crashTrackingEnabled,
                        onCheckedChange = actions.onSetCrashTracking,
                    )
                    SettingsRowDivider()
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_usage_stats),
                        checked = state.usageStatisticsEnabled,
                        onCheckedChange = actions.onSetUsageStatistics,
                    )
                    SettingsRowDivider()
                    FocusableRow(
                        title = stringResource(R.string.settings_user_agreement),
                        onClick = actions.onOpenPrivacySettings,
                        rowHeight = if (compact) 56.dp else 64.dp,
                    )
                }
            }
        }
    }
}

/** 绘制可横向导航的平台选择器。 */
@Composable
private fun PlatformGallery(
    selectedPlatform: AccountPlatform,
    loggedIn: Boolean,
    compact: Boolean,
    focusRequester: FocusRequester,
    onPlatformSelected: (AccountPlatform) -> Unit,
) {
    Column(Modifier.padding(start = 20.dp, top = if (compact) 12.dp else 16.dp, bottom = 14.dp)) {
        BasicText(
            text = stringResource(R.string.settings_platform),
            style =
                TvType.caption.copy(
                    color = TvColors.textSecondary,
                    fontSize = if (compact) 16.sp else 18.sp,
                    fontWeight = FontWeight.Medium,
                ),
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(AccountPlatform.entries.size) { index ->
                val platform = AccountPlatform.entries[index]
                PlatformTile(
                    platform = platform,
                    selected = platform == selectedPlatform,
                    loggedIn = loggedIn,
                    compact = compact,
                    focusRequester =
                        if (platform == AccountPlatform.BILIBILI) focusRequester else null,
                    onClick = { onPlatformSelected(platform) },
                )
            }
        }
    }
}

/** 以账户状态和选择状态共同呈现平台卡片。 */
@Composable
private fun PlatformTile(
    platform: AccountPlatform,
    selected: Boolean,
    loggedIn: Boolean,
    compact: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(if (compact) 16.dp else 18.dp)
    val cardColor =
        when {
            focused && loggedIn -> Color.White.copy(alpha = 0.14f)
            focused -> Color.White.copy(alpha = 0.16f)
            !loggedIn && selected -> Color.White.copy(alpha = 0.12f)
            !loggedIn -> Color.White.copy(alpha = 0.055f)
            selected -> TvColors.blue.copy(alpha = 0.20f)
            else -> Color.White.copy(alpha = 0.065f)
        }
    val borderColor =
        when {
            focused -> TvColors.accent
            selected && loggedIn -> TvColors.accent.copy(alpha = 0.82f)
            selected -> Color.White.copy(alpha = 0.34f)
            else -> Color.White.copy(alpha = 0.10f)
        }
    val textColor = if (loggedIn) TvColors.textPrimary else TvColors.textDisabled
    val selectionDescription =
        stringResource(if (selected) R.string.settings_selected else R.string.settings_unselected)
    val loginDescription =
        stringResource(
            if (loggedIn) R.string.settings_logged_in else R.string.settings_not_logged_in,
        )

    Box(
        modifier =
            Modifier.width(if (compact) 136.dp else 164.dp)
                .height(if (compact) 82.dp else 96.dp)
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier,
                )
                .focusTrace("platform:${platform.label}")
                .clip(shape)
                .background(cardColor)
                .border(if (focused) 2.dp else 1.dp, borderColor, shape)
                .selectable(
                    selected = selected,
                    interactionSource = interaction,
                    indication = null,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .semantics {
                    contentDescription = buildString {
                        append(platform.label)
                        append(", ")
                        append(selectionDescription)
                        append(", ")
                        append(loginDescription)
                    }
                }
                .testTag("platform-bilibili"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BilibiliTvLogo(
                modifier = Modifier.size(if (compact) 25.dp else 29.dp),
                color = if (loggedIn) TvColors.textPrimary else TvColors.textDisabled,
            )
            Spacer(Modifier.height(if (compact) 5.dp else 7.dp))
            BasicText(
                text = platform.label,
                style =
                    TvType.caption.copy(
                        color = textColor,
                        fontSize = if (compact) 14.sp else 16.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = if (loggedIn) TvColors.accent else TvColors.textSecondary,
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(if (compact) 15.dp else 17.dp),
            )
        }
    }
}

/** 显示当前 Bilibili 账户的设置；未登录时收束为单一登录入口。 */
@Composable
private fun BilibiliAccountSettings(
    state: TvUiState,
    actions: TvActions,
    loginFocus: FocusRequester,
    compact: Boolean,
) {
    if (!state.loggedIn) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = if (compact) 14.dp else 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            WhiteButton(
                text = stringResource(R.string.settings_scan_login),
                onClick = actions.onLogin,
                focusRequester = loginFocus,
                compact = compact,
            )
        }
        return
    }

    SettingsInfoRow(
        stringResource(R.string.settings_account),
        state.accountName.ifBlank { stringResource(R.string.settings_logged_in) },
    )
    SettingsRowDivider()
    SettingsInfoRow(
        stringResource(R.string.settings_vip),
        if (state.vip) stringResource(R.string.settings_vip_active)
        else stringResource(R.string.settings_vip_normal),
    )
    SettingsRowDivider()
    SettingsToggleRow(
        title = stringResource(R.string.settings_remember_content),
        checked = state.rememberPlaybackContent,
        onCheckedChange = actions.onSetRememberPlaybackContent,
    )
    SettingsRowDivider()
    SettingsToggleRow(
        title = stringResource(R.string.settings_sync_history),
        checked = state.syncPlaybackHistory,
        onCheckedChange = actions.onSetSyncPlaybackHistory,
    )
    SettingsRowDivider()
    FocusableRow(
        title = stringResource(R.string.settings_logout),
        onClick = actions.onLogout,
        destructive = true,
        showChevron = false,
        rowHeight = if (compact) 56.dp else 64.dp,
    )
}

/** 将语义版本与 Android version code 作为同一条软件版本信息呈现。 */
@Composable
private fun versionDisplay(state: TvUiState): String =
    "${state.version.ifBlank { stringResource(R.string.settings_unknown_version) }} (${state.versionCode})"
