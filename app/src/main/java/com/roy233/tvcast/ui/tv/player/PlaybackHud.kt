package com.roy233.tvcast.ui.tv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roy233.tvcast.R
import com.roy233.tvcast.feature.playback.presentation.PlaybackSpeed
import com.roy233.tvcast.ui.designsystem.*
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.tv.FocusTarget

@Composable
internal fun PlaybackHud(
    state: TvUiState,
    actions: TvActions,
    enabled: Boolean,
    displayedPositionMs: Long,
    progressFocus: FocusTarget,
    playFocus: FocusTarget,
    seekAccelerator: PlaybackSeekAccelerator,
) {
    val liveLabel = stringResource(R.string.player_live)
    AnimatedVisibility(
        visible = state.hudVisible,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(220)),
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier.align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent),
                            ),
                        )
                        .padding(horizontal = 48.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = state.castTitle.ifBlank { stringResource(R.string.player_playing) },
                    style = TvType.bodyMedium.copy(fontSize = 24.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.qualityLabel.isNotBlank()) {
                    GlassPill(
                        text =
                            buildString {
                                append(state.qualityLabel)
                                if (state.qualitySource.isNotBlank()) {
                                    append(" · ")
                                    append(state.qualitySource)
                                }
                            },
                        emphasized = true,
                    )
                }
                if (!state.isLive && state.playbackSpeed != 1f) {
                    Spacer(Modifier.width(10.dp))
                    GlassPill(
                        text = PlaybackSpeed.label(state.playbackSpeed),
                        emphasized = true,
                    )
                }
            }

            Box(
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .widthIn(max = 760.dp)
                        .fillMaxWidth(0.66f)
                        .padding(bottom = 26.dp),
            ) {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 148.dp, max = 160.dp),
                    material = GlassMaterial.Hud,
                    size = GlassSize.Large,
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Column(
                        modifier =
                            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        if (state.isLive) {
                            Spacer(Modifier.height(18.dp))
                        } else {
                            SeekableProgressBar(
                                positionMs = displayedPositionMs,
                                durationMs = state.durationMs,
                                isSeeking = state.seekPreviewPositionMs != null,
                                focusTarget = progressFocus,
                                playFocusTarget = playFocus,
                                seekAccelerator = seekAccelerator,
                                onPreviewSeekBy = actions.onPreviewSeekBy,
                                onConfirmSeek = actions.onConfirmSeek,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                text =
                                    if (state.isLive) {
                                        buildString {
                                            append(liveLabel)
                                            if (state.qualityLabel.isNotBlank())
                                                append(" · ${state.qualityLabel}")
                                        }
                                    } else {
                                        "${formatTime(displayedPositionMs)}  /  ${formatTime(state.durationMs)}"
                                    },
                                style =
                                    TvType.caption.copy(
                                        color = TvColors.textSecondary,
                                        fontSize = 14.sp,
                                    ),
                            )
                            Spacer(Modifier.weight(1f))
                        }
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PrimaryPlayButton(
                                isPlaying = state.isPlaying,
                                onClick = actions.onPlayPause,
                                modifier =
                                    Modifier.focusProperties {
                                        if (!state.isLive) up = progressFocus.requester
                                    },
                                size = 64.dp,
                                focusTarget = playFocus,
                            )
                            if (!state.isLive) {
                                Spacer(Modifier.width(12.dp))
                                GlassIconButton(
                                    icon = ImageVector.vectorResource(R.drawable.ic_speed),
                                    contentDescription =
                                        "${stringResource(R.string.panel_speed_title)} ${PlaybackSpeed.label(state.playbackSpeed)}",
                                    onClick = { actions.onOpenPanel(PanelKind.SPEED) },
                                    enabled = enabled,
                                    selected = state.playbackSpeed != 1f,
                                    size = 56.dp,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GlassIconButton(
                                    icon = Icons.Rounded.HighQuality,
                                    contentDescription =
                                        if (state.isLive)
                                            "${stringResource(R.string.panel_live_quality_title)} ${state.qualityLabel}"
                                        else
                                            "${stringResource(R.string.panel_quality_title)} ${state.qualityLabel}",
                                    onClick = {
                                        actions.onOpenPanel(
                                            if (state.isLive) PanelKind.LIVE_QUALITY
                                            else PanelKind.QUALITY,
                                        )
                                    },
                                    enabled = enabled,
                                    size = 56.dp,
                                )
                                if (!state.isLive) {
                                    GlassIconButton(
                                        icon = ImageVector.vectorResource(R.drawable.ic_subtitle),
                                        contentDescription =
                                            if (state.subtitleEnabled)
                                                stringResource(R.string.subtitle_turn_off)
                                            else stringResource(R.string.player_enable_subtitles),
                                        onClick = actions.onToggleSubtitle,
                                        enabled = enabled && state.subtitleAvailable,
                                        selected = state.subtitleEnabled,
                                        size = 56.dp,
                                    )
                                }
                                GlassIconButton(
                                    icon = ImageVector.vectorResource(R.drawable.ic_danmu),
                                    contentDescription =
                                        if (state.danmakuEnabled)
                                            stringResource(R.string.player_disable_danmaku)
                                        else stringResource(R.string.player_enable_danmaku),
                                    onClick = actions.onToggleDanmaku,
                                    enabled = enabled,
                                    selected = state.danmakuEnabled,
                                    size = 56.dp,
                                )
                                GlassIconButton(
                                    icon = Icons.Rounded.Settings,
                                    contentDescription = stringResource(R.string.panel_root_title),
                                    onClick = { actions.onOpenPanel(PanelKind.ROOT) },
                                    enabled = enabled,
                                    size = 56.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
