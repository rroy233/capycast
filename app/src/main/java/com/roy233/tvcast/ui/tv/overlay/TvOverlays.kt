package com.roy233.tvcast.ui.tv.overlay

import com.roy233.tvcast.ui.tv.home.WhiteButton
import com.roy233.tvcast.ui.tv.tvModalFocusRegion

import android.graphics.Bitmap
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.model.AudioOption
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.ui.model.RecentCast
import com.roy233.tvcast.ui.model.TrackOption
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvError
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.designsystem.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay

/**
 * 显示投屏帮助模态层。
 *
 * @param state 当前界面状态。
 * @param actions 帮助层可执行的操作回调。
 */
@Composable
internal fun HelpSheet(state: TvUiState, actions: TvActions) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { focus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.68f))
            .tvModalFocusRegion()
            .pointerInput(Unit) { detectTapGestures { actions.onCloseHelp() } },
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth(0.76f)
                .pointerInput(Unit) {},
            tint = Color(0xD90B0B0F),
            borderAlpha = 0.36f,
            highlightAlpha = 0.30f,
            shadow = 44.dp,
            useBackdrop = true,
            blurRadius = 36.dp,
            lensHeight = 12.dp,
            lensAmount = 18.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 30.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BasicText("如何投屏", style = TvType.section.copy(fontSize = 30.sp))
                HelpStep("1", "手机打开 B 站")
                HelpStep("2", "播放页点右上角 TV")
                HelpStep("3", "选择「${state.deviceName}」")
                Spacer(Modifier.height(4.dp))
                WhiteButton(
                    text = "知道了",
                    onClick = actions.onCloseHelp,
                    focusRequester = focus,
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
            style = TvType.bodyMedium.copy(color = TvColors.pink, fontSize = 20.sp),
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
    val firstFocus = remember { FocusRequester() }
    val panel = state.panel ?: return

    LaunchedEffect(panel) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .tvModalFocusRegion(),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val panelMaxHeight = (maxHeight * 0.88f).coerceAtMost(520.dp)
            GlassSurface(
                modifier = Modifier
                    .widthIn(max = 700.dp)
                    .fillMaxWidth(0.80f)
                    .heightIn(max = panelMaxHeight)
                    .align(Alignment.Center),
                tint = Color(0xF20B0B0F),
                borderAlpha = 0.30f,
                highlightAlpha = 0.24f,
                shadow = 38.dp,
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            text = when (panel) {
                                PanelKind.ROOT -> "播放设置"
                                PanelKind.QUALITY -> "清晰度"
                                PanelKind.AUDIO -> "音轨"
                            },
                            style = TvType.section,
                            modifier = Modifier.weight(1f),
                        )
                        GlassIconButton(
                            icon = Icons.Rounded.Close,
                            contentDescription = "关闭",
                            onClick = actions.onClosePanel,
                            size = 48.dp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        when (panel) {
                            PanelKind.ROOT -> {
                                FocusableRow(
                                    title = "清晰度",
                                    value = state.qualityLabel.ifBlank { "自动" },
                                    onClick = { actions.onOpenPanel(PanelKind.QUALITY) },
                                    focusRequester = firstFocus,
                                )
                                FocusableRow(
                                    title = "音轨",
                                    value = state.audioLabel.ifBlank { "自动" },
                                    onClick = { actions.onOpenPanel(PanelKind.AUDIO) },
                                )
                                BasicText(
                                    text = "aid ${state.aid} · cid ${state.cid}",
                                    style = TvType.caption.copy(
                                        color = TvColors.textTertiary,
                                        fontSize = 15.sp,
                                    ),
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                )
                            }

                            PanelKind.QUALITY -> {
                                state.tracks.forEachIndexed { index, track ->
                                    FocusableRow(
                                        title = track.label,
                                        value = track.detail,
                                        selected = track.qn == state.selectedQn,
                                        onClick = { actions.onSelectQuality(track.qn) },
                                        focusRequester = if (index == 0) firstFocus else null,
                                    )
                                }
                            }

                            PanelKind.AUDIO -> {
                                state.audios.forEachIndexed { index, audio ->
                                    FocusableRow(
                                        title = audio.label,
                                        value = if (audio.id == state.selectedAudioId) "当前" else null,
                                        selected = audio.id == state.selectedAudioId,
                                        onClick = { actions.onSelectAudio(audio.id) },
                                        focusRequester = if (index == 0) firstFocus else null,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    GlassButton(
                        text = "完成",
                        onClick = actions.onClosePanel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

