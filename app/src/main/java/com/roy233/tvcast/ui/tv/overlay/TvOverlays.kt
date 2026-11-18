package com.roy233.tvcast.ui.tv.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roy233.tvcast.R
import com.roy233.tvcast.feature.danmaku.domain.AiShieldLevel
import com.roy233.tvcast.feature.playback.presentation.PlaybackSpeed
import com.roy233.tvcast.ui.designsystem.*
import com.roy233.tvcast.ui.legal.UserAgreementPolicy
import com.roy233.tvcast.ui.legal.UserAgreementPolicyTraditional
import com.roy233.tvcast.ui.legal.userAgreementPolicyEnglish
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.ui.model.SubtitleSummary
import com.roy233.tvcast.ui.model.SubtitleSummaryKind
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.tv.FocusDiagnostics
import com.roy233.tvcast.ui.tv.RequestFocusWhenReady
import com.roy233.tvcast.ui.tv.document.MarkdownBlockKind
import com.roy233.tvcast.ui.tv.document.MarkdownDocumentBlock
import com.roy233.tvcast.ui.tv.document.parseMarkdownDocument
import com.roy233.tvcast.ui.tv.focusTrace
import com.roy233.tvcast.ui.tv.home.WhiteButton
import com.roy233.tvcast.ui.tv.rememberFocusTarget
import kotlin.math.roundToInt

/** 首次安装时显示的欢迎提示。 */
@Composable
internal fun PrivacyNoticeDialog(state: TvUiState, actions: TvActions) {
    val focus = rememberFocusTarget()
    RequestFocusWhenReady(activation = state.privacyNoticeVisible, target = focus)
    TvModalDialog(
        onDismissRequest = {},
        dismissOnBackPress = false,
    ) {
        GlassSurface(
            modifier = Modifier.tvModalCard(),
            material = GlassMaterial.Panel,
            size = GlassSize.Large,
        ) {
            Column(
                Modifier.padding(horizontal = 30.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                BasicText(
                    stringResource(R.string.welcome_title),
                    style = TvType.section.copy(fontSize = 30.sp),
                )
                BasicText(
                    text = stringResource(R.string.welcome_message),
                    style = TvType.body.copy(color = TvColors.textSecondary, fontSize = 19.sp),
                )
                PrivacyAgreementLink(onClick = actions.onOpenPrivacySettings)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    WhiteButton(
                        text = stringResource(R.string.welcome_start),
                        onClick = actions.onAcknowledgePrivacyNotice,
                        focusTarget = focus,
                        compact = true,
                    )
                }
            }
        }
    }
}

/** 欢迎弹窗中的协议入口；焦点状态需要在电视端清晰可辨。 */
@Composable
private fun PrivacyAgreementLink(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    BasicText(
        text = stringResource(R.string.welcome_agreement_link),
        style =
            TvType.caption.copy(
                color = if (focused) Color.White else TvColors.accent,
                fontSize = 16.sp,
            ),
        modifier =
            Modifier.clip(shape)
                .background(
                    if (focused) TvColors.accent.copy(alpha = 0.36f) else Color.Transparent,
                )
                .border(
                    width = 1.dp,
                    color = if (focused) TvColors.accent else Color.Transparent,
                    shape = shape,
                )
                .focusProperties { canFocus = true }
                .focusTrace("link:隐私协议")
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** 由欢迎弹窗或设置页进入的隐私协议模态层。 */
@Composable
internal fun PrivacyAgreementDialog(state: TvUiState, actions: TvActions) {
    val locales = androidx.core.os.ConfigurationCompat.getLocales(LocalConfiguration.current)
    val language = locales[0]?.language
    val country = locales[0]?.country
    val script = locales[0]?.script
    val markdown =
        when {
            language == "en" -> userAgreementPolicyEnglish
            language == "zh" && (script == "Hant" || country in setOf("TW", "HK", "MO")) ->
                UserAgreementPolicyTraditional.markdown
            else -> UserAgreementPolicy.markdown
        }
    val blocks = remember(markdown) { parseMarkdownDocument(markdown) }
    val initialFocus = rememberFocusTarget()
    val backFocus = rememberFocusTarget()
    val listState = rememberLazyListState()
    val firstFocusable = blocks.indexOfFirst { it.kind != MarkdownBlockKind.DIVIDER }
    RequestFocusWhenReady(
        activation = state.privacyAgreementVisible,
        target = if (firstFocusable >= 0) initialFocus else backFocus,
    )
    TvModalDialog(onDismissRequest = actions.onClosePrivacyAgreement) {
        GlassSurface(
            modifier = Modifier.tvModalCard(TvModalCardKind.DOCUMENT),
            material = GlassMaterial.Panel,
            size = GlassSize.Large,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BasicText(
                    stringResource(R.string.settings_user_agreement),
                    style = TvType.section.copy(fontSize = 30.sp),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    blocks.forEachIndexed { index, block ->
                        item(key = index) {
                            MarkdownDocumentBlock(
                                block = block,
                                focusTarget = if (index == firstFocusable) initialFocus else null,
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    WhiteButton(
                        text = stringResource(R.string.common_back),
                        onClick = actions.onClosePrivacyAgreement,
                        focusTarget = backFocus,
                        compact = true,
                    )
                }
            }
        }
    }
}

/**
 * 显示投屏帮助模态层。
 *
 * @param state 当前界面状态。
 * @param actions 帮助层可执行的操作回调。
 */
@Composable
internal fun HelpSheet(state: TvUiState, actions: TvActions) {
    val focus = rememberFocusTarget()
    RequestFocusWhenReady(activation = state.helpVisible, target = focus)

    TvModalDialog(
        onDismissRequest = actions.onCloseHelp,
        dismissOnClickOutside = true,
    ) {
        GlassSurface(
            modifier = Modifier.tvModalCard(),
            material = GlassMaterial.Panel,
            size = GlassSize.Large,
        ) {
            Column(
                Modifier.padding(horizontal = 30.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BasicText(
                    stringResource(R.string.help_title),
                    style = TvType.section.copy(fontSize = 30.sp),
                )
                HelpStep("1", stringResource(R.string.help_step_1))
                HelpStep("2", stringResource(R.string.help_step_2))
                HelpStep("3", stringResource(R.string.help_step_3, state.deviceName))
                Spacer(Modifier.height(4.dp))
                WhiteButton(
                    text = stringResource(R.string.common_got_it),
                    onClick = actions.onCloseHelp,
                    focusTarget = focus,
                    compact = true,
                )
            }
        }
    }
}

/** 显示帮助步骤编号和说明文本。 */
@Composable
private fun HelpStep(number: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BasicText(
            text = number,
            style = TvType.bodyMedium.copy(color = TvColors.accent, fontSize = 20.sp),
        )
        BasicText(
            text = text,
            style = TvType.body.copy(color = TvColors.textSecondary, fontSize = 21.sp),
        )
    }
}

/**
 * 显示播放设置、清晰度和音轨选择面板。
 *
 * @param state 当前播放状态。
 * @param actions 面板可执行的操作回调。
 */
@Composable
internal fun QuickPanel(state: TvUiState, actions: TvActions) {
    val closeFocus = rememberFocusTarget()
    val firstFocus = rememberFocusTarget()
    val selectedSpeedFocus = rememberFocusTarget()
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val panel = state.panel ?: return

    val preferredFocus = if (panel == PanelKind.SPEED) selectedSpeedFocus else firstFocus
    LaunchedEffect(
        panel,
        state.playbackSpeed,
        preferredFocus.isReady,
        closeFocus.isReady,
    ) {
        val initialFocus =
            when {
                preferredFocus.isReady -> preferredFocus
                closeFocus.isReady -> closeFocus
                else -> return@LaunchedEffect
            }
        if (panel == PanelKind.SPEED) {
            val selectedIndex =
                PlaybackSpeed.options
                    .indexOf(
                        PlaybackSpeed.normalize(state.playbackSpeed),
                    )
                    .coerceAtLeast(0)
            scrollState.scrollTo(
                with(density) { (QUICK_PANEL_ROW_PITCH * selectedIndex).roundToPx() },
            )
        } else {
            scrollState.scrollTo(0)
        }
        val focused = runCatching { initialFocus.requester.requestFocus() }.getOrDefault(false)
        FocusDiagnostics.transientFocusResult(focused)
    }

    TvModalDialog(
        onDismissRequest = {
            if (panel == PanelKind.ROOT) actions.onClosePanel()
            else actions.onOpenPanel(PanelKind.ROOT)
        },
    ) {
        GlassSurface(
            modifier = Modifier.tvModalCard(TvModalCardKind.PANEL),
            material = GlassMaterial.Panel,
            size = GlassSize.Large,
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        text =
                            when (panel) {
                                PanelKind.ROOT -> stringResource(R.string.panel_root_title)
                                PanelKind.QUALITY -> stringResource(R.string.panel_quality_title)
                                PanelKind.AUDIO -> stringResource(R.string.panel_audio_title)
                                PanelKind.SUBTITLE -> stringResource(R.string.panel_subtitle_title)
                                PanelKind.SPEED -> stringResource(R.string.panel_speed_title)
                                PanelKind.DANMAKU -> stringResource(R.string.panel_danmaku_title)
                                PanelKind.LIVE_QUALITY ->
                                    stringResource(R.string.panel_live_quality_title)
                            },
                        style = TvType.section,
                        modifier = Modifier.weight(1f),
                    )
                    GlassIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close),
                        onClick = actions.onClosePanel,
                        size = 48.dp,
                        focusTarget = closeFocus,
                    )
                }
                Spacer(Modifier.height(8.dp))

                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    when (panel) {
                        PanelKind.ROOT -> {
                            if (state.isLive) {
                                FocusableRow(
                                    title = stringResource(R.string.panel_live_quality_title),
                                    value =
                                        state.qualityLabel.ifBlank {
                                            stringResource(R.string.common_auto)
                                        },
                                    onClick = { actions.onOpenPanel(PanelKind.LIVE_QUALITY) },
                                    focusTarget = firstFocus,
                                )
                                FocusableRow(
                                    title = stringResource(R.string.danmaku_toggle),
                                    value =
                                        if (state.danmakuEnabled) stringResource(R.string.common_on)
                                        else stringResource(R.string.common_off),
                                    selected = state.danmakuEnabled,
                                    onClick = actions.onToggleDanmaku,
                                )
                            } else {
                                FocusableRow(
                                    title = stringResource(R.string.panel_quality_title),
                                    value =
                                        state.qualityLabel.ifBlank {
                                            stringResource(R.string.common_auto)
                                        },
                                    onClick = { actions.onOpenPanel(PanelKind.QUALITY) },
                                    focusTarget = firstFocus,
                                )
                                FocusableRow(
                                    title = stringResource(R.string.panel_audio_title),
                                    value =
                                        state.audioLabel.ifBlank {
                                            stringResource(R.string.common_auto)
                                        },
                                    onClick = { actions.onOpenPanel(PanelKind.AUDIO) },
                                )
                                FocusableRow(
                                    title = stringResource(R.string.panel_subtitle_title),
                                    value = subtitleSummaryText(state.subtitleSummary),
                                    enabled = state.subtitleTracks.isNotEmpty(),
                                    onClick = { actions.onOpenPanel(PanelKind.SUBTITLE) },
                                )
                                FocusableRow(
                                    title = stringResource(R.string.panel_speed_title),
                                    value = PlaybackSpeed.label(state.playbackSpeed),
                                    onClick = { actions.onOpenPanel(PanelKind.SPEED) },
                                )
                                FocusableRow(
                                    title = stringResource(R.string.panel_danmaku_title),
                                    value =
                                        if (!state.danmakuEnabled) {
                                            stringResource(R.string.subtitle_disabled)
                                        } else {
                                            aiShieldLabel(state.danmakuAiShield)
                                        },
                                    onClick = { actions.onOpenPanel(PanelKind.DANMAKU) },
                                )
                            }
                            if (!state.isLive) {
                                BasicText(
                                    text = "aid ${state.aid} · cid ${state.cid}",
                                    style =
                                        TvType.caption.copy(
                                            color = TvColors.textTertiary,
                                            fontSize = 15.sp,
                                        ),
                                    modifier =
                                        Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                )
                            }
                        }

                        PanelKind.LIVE_QUALITY -> {
                            state.liveQualities.forEachIndexed { index, option ->
                                FocusableRow(
                                    title = option.label,
                                    value =
                                        if (option.qn == state.selectedQn)
                                            stringResource(R.string.common_current)
                                        else null,
                                    selected = option.qn == state.selectedQn,
                                    onClick = { actions.onSelectQuality(option.qn) },
                                    focusTarget = if (index == 0) firstFocus else null,
                                )
                            }
                        }

                        PanelKind.QUALITY -> {
                            state.tracks.forEachIndexed { index, track ->
                                FocusableRow(
                                    title = track.label,
                                    value = track.detail,
                                    selected = track.qn == state.selectedQn,
                                    onClick = { actions.onSelectQuality(track.qn) },
                                    focusTarget = if (index == 0) firstFocus else null,
                                )
                            }
                        }

                        PanelKind.AUDIO -> {
                            state.audios.forEachIndexed { index, audio ->
                                FocusableRow(
                                    title = audio.label,
                                    value =
                                        if (audio.id == state.selectedAudioId)
                                            stringResource(R.string.common_current)
                                        else null,
                                    selected = audio.id == state.selectedAudioId,
                                    onClick = { actions.onSelectAudio(audio.id) },
                                    focusTarget = if (index == 0) firstFocus else null,
                                )
                            }
                        }

                        PanelKind.SUBTITLE -> {
                            FocusableRow(
                                title = stringResource(R.string.subtitle_turn_off),
                                value =
                                    if (state.selectedSubtitleKey == null)
                                        stringResource(R.string.common_current)
                                    else null,
                                selected = state.selectedSubtitleKey == null,
                                onClick = { actions.onSelectSubtitleTrack(null) },
                                focusTarget = firstFocus,
                            )
                            state.subtitleTracks.forEach { option ->
                                FocusableRow(
                                    title = option.label,
                                    value =
                                        if (option.selected) stringResource(R.string.common_current)
                                        else option.detail,
                                    selected = option.selected,
                                    enabled = !option.locked,
                                    onClick = { actions.onSelectSubtitleTrack(option.key) },
                                )
                            }
                        }

                        PanelKind.SPEED -> {
                            val selectedSpeed = PlaybackSpeed.normalize(state.playbackSpeed)
                            PlaybackSpeed.options.forEachIndexed { index, speed ->
                                FocusableRow(
                                    title = PlaybackSpeed.label(speed),
                                    value =
                                        if (speed == 1f) stringResource(R.string.speed_normal)
                                        else null,
                                    selected = speed == selectedSpeed,
                                    onClick = { actions.onSetPlaybackSpeed(speed) },
                                    focusTarget =
                                        if (speed == selectedSpeed) selectedSpeedFocus else null,
                                )
                            }
                        }

                        PanelKind.DANMAKU -> {
                            FocusableRow(
                                title = stringResource(R.string.danmaku_toggle),
                                value =
                                    if (state.danmakuEnabled) stringResource(R.string.common_on)
                                    else stringResource(R.string.common_off),
                                selected = state.danmakuEnabled,
                                onClick = actions.onToggleDanmaku,
                                focusTarget = firstFocus,
                            )
                            FocusableRow(
                                title = stringResource(R.string.danmaku_ai_shield),
                                value = aiShieldLabel(state.danmakuAiShield),
                                onClick = {
                                    actions.onSetDanmakuAiShield(
                                        nextAiShield(state.danmakuAiShield),
                                    )
                                },
                            )
                            FocusableRow(
                                title = stringResource(R.string.danmaku_area),
                                value = areaLabel(state.danmakuAreaRatio),
                                onClick = {
                                    actions.onSetDanmakuArea(
                                        nextArea(state.danmakuAreaRatio),
                                    )
                                },
                            )
                            FocusableRow(
                                title = stringResource(R.string.danmaku_max_on_screen),
                                value = maxOnScreenLabel(state.danmakuMaxOnScreen),
                                onClick = {
                                    actions.onSetDanmakuMaxOnScreen(
                                        nextMaxOnScreen(state.danmakuMaxOnScreen),
                                    )
                                },
                            )
                            FocusableRow(
                                title = stringResource(R.string.danmaku_opacity),
                                value = opacityLabel(state.danmakuOpacity),
                                onClick = {
                                    actions.onSetDanmakuOpacity(
                                        nextOpacity(state.danmakuOpacity),
                                    )
                                },
                            )
                            FocusableRow(
                                title = stringResource(R.string.danmaku_font_scale),
                                value = fontScaleLabel(state.danmakuFontScale),
                                onClick = {
                                    actions.onSetDanmakuFontScale(
                                        nextFontScale(state.danmakuFontScale),
                                    )
                                },
                            )
                            FocusableRow(
                                title = stringResource(R.string.danmaku_duration),
                                value = durationLabel(state.danmakuDurationSec),
                                onClick = {
                                    actions.onSetDanmakuDuration(
                                        nextDuration(state.danmakuDurationSec),
                                    )
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                GlassButton(
                    text = stringResource(R.string.common_done),
                    onClick = actions.onClosePanel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private val QUICK_PANEL_ROW_PITCH = 62.dp

@Composable
private fun aiShieldLabel(level: AiShieldLevel): String =
    when (level) {
        AiShieldLevel.OFF -> stringResource(R.string.common_off)
        AiShieldLevel.LOW -> stringResource(R.string.danmaku_shield_low)
        AiShieldLevel.MEDIUM -> stringResource(R.string.danmaku_shield_medium)
        AiShieldLevel.HIGH -> stringResource(R.string.danmaku_shield_high)
    }

private fun nextAiShield(level: AiShieldLevel): AiShieldLevel =
    when (level) {
        AiShieldLevel.OFF -> AiShieldLevel.LOW
        AiShieldLevel.LOW -> AiShieldLevel.MEDIUM
        AiShieldLevel.MEDIUM -> AiShieldLevel.HIGH
        AiShieldLevel.HIGH -> AiShieldLevel.OFF
    }

@Composable
private fun areaLabel(ratio: Float): String =
    when {
        ratio <= 0.30f -> stringResource(R.string.danmaku_area_quarter)
        ratio <= 0.55f -> stringResource(R.string.danmaku_area_half)
        ratio <= 0.80f -> stringResource(R.string.danmaku_area_three_quarters)
        else -> stringResource(R.string.danmaku_area_full)
    }

private fun nextArea(ratio: Float): Float =
    when {
        ratio <= 0.30f -> 0.50f
        ratio <= 0.55f -> 0.75f
        ratio <= 0.80f -> 1.0f
        else -> 0.25f
    }

@Composable
private fun maxOnScreenLabel(max: Int): String =
    if (max <= 0) stringResource(R.string.danmaku_unlimited) else max.toString()

private fun nextMaxOnScreen(max: Int): Int =
    when (max) {
        30 -> 60
        60 -> 100
        100 -> 0
        else -> 30
    }

private fun opacityLabel(opacity: Float): String = "${(opacity * 100).roundToInt()}%"

private fun nextOpacity(opacity: Float): Float {
    val step = ((opacity * 100).roundToInt() + 20) / 20 * 20
    return if (step > 100) 20f / 100f else step / 100f
}

@Composable
private fun fontScaleLabel(scale: Float): String =
    when {
        scale <= 0.80f -> stringResource(R.string.danmaku_font_small)
        scale <= 1.05f -> stringResource(R.string.danmaku_font_normal)
        scale <= 1.30f -> stringResource(R.string.danmaku_font_large)
        else -> stringResource(R.string.danmaku_font_extra_large)
    }

private fun nextFontScale(scale: Float): Float =
    when {
        scale <= 0.80f -> 1.0f
        scale <= 1.05f -> 1.25f
        scale <= 1.30f -> 1.5f
        else -> 0.75f
    }

@Composable
private fun durationLabel(seconds: Float): String =
    when {
        seconds >= 10f -> stringResource(R.string.danmaku_speed_slow)
        seconds >= 7f -> stringResource(R.string.danmaku_speed_normal)
        else -> stringResource(R.string.danmaku_speed_fast)
    }

private fun nextDuration(seconds: Float): Float =
    when {
        seconds >= 10f -> 8f
        seconds >= 7f -> 5f
        else -> 12f
    }

@Composable
private fun subtitleSummaryText(summary: SubtitleSummary): String =
    when (summary.kind) {
        SubtitleSummaryKind.SELECTED -> summary.label
        SubtitleSummaryKind.DISABLED -> stringResource(R.string.subtitle_disabled)
        SubtitleSummaryKind.LOADING -> stringResource(R.string.subtitle_loading)
        SubtitleSummaryKind.UNAVAILABLE -> stringResource(R.string.subtitle_unavailable)
        SubtitleSummaryKind.OFF -> stringResource(R.string.common_close)
        SubtitleSummaryKind.NONE,
        SubtitleSummaryKind.EMPTY -> stringResource(R.string.subtitle_none)
    }
