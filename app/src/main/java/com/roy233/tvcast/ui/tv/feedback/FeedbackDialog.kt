package com.roy233.tvcast.ui.tv.feedback

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roy233.tvcast.feature.feedback.FeedbackChannel
import com.roy233.tvcast.feature.feedback.FeedbackUiState
import com.roy233.tvcast.ui.designsystem.GlassIconButton
import com.roy233.tvcast.ui.designsystem.GlassMaterial
import com.roy233.tvcast.ui.designsystem.GlassSize
import com.roy233.tvcast.ui.designsystem.GlassSurface
import com.roy233.tvcast.ui.designsystem.LoadingSpinner
import com.roy233.tvcast.ui.designsystem.TvModalCardKind
import com.roy233.tvcast.ui.designsystem.TvModalDialog
import com.roy233.tvcast.ui.designsystem.rememberReduceMotion
import com.roy233.tvcast.ui.designsystem.tvModalCard
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.tv.FocusTarget
import com.roy233.tvcast.ui.tv.RequestFocusWhenReady
import com.roy233.tvcast.ui.tv.focusTarget
import com.roy233.tvcast.ui.tv.rememberFocusTarget

private val FeedbackCardHeight = 390.dp
private val FeedbackQrSize = 196.dp
private val FeedbackChannelRowWidth = 216.dp
private val FeedbackChannelThumbWidth = 102.dp

/** 按照电视安全区呈现的横向问题反馈卡片。 */
@Composable
internal fun FeedbackDialog(state: TvUiState, actions: TvActions) {
    val feedback = state.feedback
    val channelFocus = rememberFocusTarget()
    val closeFocus = rememberFocusTarget()
    RequestFocusWhenReady(activation = feedback.visible, target = channelFocus)

    TvModalDialog(
        onDismissRequest = actions.onCloseFeedback,
        dismissOnClickOutside = true,
    ) {
        GlassSurface(
            modifier = Modifier.tvModalCard(TvModalCardKind.FEEDBACK).height(FeedbackCardHeight),
            material = GlassMaterial.Panel,
            size = GlassSize.Large,
        ) {
            Box(Modifier.fillMaxSize()) {
                FeedbackHeader(
                    closeFocus = closeFocus,
                    onClose = actions.onCloseFeedback,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                Row(
                    modifier =
                        Modifier.fillMaxSize()
                            .padding(start = 34.dp, top = 70.dp, end = 34.dp, bottom = 30.dp),
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FeedbackQrColumn(
                        feedback = feedback,
                        channelFocus = channelFocus,
                        onSelectChannel = actions.onSelectFeedbackChannel,
                    )
                    FeedbackDetails(
                        feedback = feedback,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedbackHeader(
    closeFocus: FocusTarget,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().height(62.dp)) {
        androidx.compose.foundation.text.BasicText(
            text = "问题反馈",
            style = TvType.section.copy(fontSize = 29.sp),
            modifier = Modifier.align(Alignment.Center),
        )
        GlassIconButton(
            icon = Icons.Rounded.Close,
            contentDescription = "关闭反馈",
            onClick = onClose,
            size = 40.dp,
            focusTarget = closeFocus,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp),
        )
    }
}

@Composable
private fun FeedbackQrColumn(
    feedback: FeedbackUiState,
    channelFocus: FocusTarget,
    onSelectChannel: (FeedbackChannel) -> Unit,
) {
    Column(
        modifier = Modifier.width(FeedbackChannelRowWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AnimatedFeedbackQr(feedback = feedback, size = FeedbackQrSize)
        FeedbackChannelSlider(
            selected = feedback.channel,
            focusTarget = channelFocus,
            onSelect = onSelectChannel,
        )
    }
}

@Composable
private fun FeedbackChannelSlider(
    selected: FeedbackChannel,
    focusTarget: FocusTarget,
    onSelect: (FeedbackChannel) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val reduceMotion = rememberReduceMotion()
    val thumbOffset by
        animateDpAsState(
            targetValue = if (selected == FeedbackChannel.GITHUB) 3.dp else 111.dp,
            animationSpec =
                if (reduceMotion) snap() else spring(dampingRatio = 0.82f, stiffness = 420f),
            label = "feedback-channel-thumb",
        )

    GlassSurface(
        modifier =
            Modifier.width(FeedbackChannelRowWidth)
                .height(50.dp)
                .semantics { contentDescription = "反馈渠道：${selected.label}" }
                .focusTarget(focusTarget)
                .focusable(interactionSource = interaction)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            onSelect(FeedbackChannel.GITHUB)
                            true
                        }

                        Key.DirectionRight -> {
                            onSelect(FeedbackChannel.EMAIL)
                            true
                        }

                        Key.DirectionCenter,
                        Key.Enter,
                        Key.NumPadEnter -> {
                            onSelect(
                                if (selected == FeedbackChannel.GITHUB) {
                                    FeedbackChannel.EMAIL
                                } else {
                                    FeedbackChannel.GITHUB
                                },
                            )
                            true
                        }

                        else -> false
                    }
                },
        material = GlassMaterial.Control,
        size = GlassSize.Compact,
        shape = RoundedCornerShape(14.dp),
        emphasized = focused,
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier =
                    Modifier.align(Alignment.CenterStart)
                        .offset(x = thumbOffset)
                        .width(FeedbackChannelThumbWidth)
                        .height(44.dp)
                        .background(TvColors.accent.copy(alpha = 0.48f), RoundedCornerShape(11.dp)),
            )
            Row(Modifier.fillMaxSize()) {
                FeedbackChannelLabel(
                    channel = FeedbackChannel.GITHUB,
                    selected = selected == FeedbackChannel.GITHUB,
                )
                FeedbackChannelLabel(
                    channel = FeedbackChannel.EMAIL,
                    selected = selected == FeedbackChannel.EMAIL,
                )
            }
            Row(Modifier.matchParentSize()) {
                Box(
                    modifier =
                        Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                            detectTapGestures { onSelect(FeedbackChannel.GITHUB) }
                        },
                )
                Box(
                    modifier =
                        Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                            detectTapGestures { onSelect(FeedbackChannel.EMAIL) }
                        },
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.FeedbackChannelLabel(
    channel: FeedbackChannel,
    selected: Boolean,
) {
    Box(
        modifier = Modifier.weight(1f).fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.text.BasicText(
            text = channel.label,
            style =
                TvType.button.copy(
                    color = if (selected) Color.White else TvColors.textSecondary,
                ),
        )
    }
}

@Composable
private fun AnimatedFeedbackQr(feedback: FeedbackUiState, size: Dp) {
    val reduceMotion = rememberReduceMotion()
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(18.dp)).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = feedback.channel,
            transitionSpec = {
                if (reduceMotion) {
                    fadeIn(snap()) togetherWith fadeOut(snap())
                } else {
                    fadeIn(tween(140)) togetherWith fadeOut(tween(140))
                }
            },
            label = "feedback-qr",
        ) { channel ->
            val bitmap = feedback.qrCodes[channel]
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    bitmap != null ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "${channel.label} 问题反馈二维码",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )

                    feedback.isGenerating -> LoadingSpinner(color = TvColors.accent)

                    else ->
                        androidx.compose.foundation.text.BasicText(
                            text = feedback.errorMessage.orEmpty(),
                            style =
                                TvType.caption.copy(
                                    color = TvColors.error,
                                    textAlign = TextAlign.Center,
                                ),
                            modifier = Modifier.padding(16.dp),
                        )
                }
            }
        }
    }
}

@Composable
private fun FeedbackDetails(feedback: FeedbackUiState, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        AnimatedContent(
            targetState = feedback.channel,
            transitionSpec = {
                if (reduceMotion) {
                    fadeIn(snap()) togetherWith fadeOut(snap())
                } else {
                    fadeIn(tween(140)) togetherWith fadeOut(tween(140))
                }
            },
            label = "feedback-prompt",
        ) { channel ->
            androidx.compose.foundation.text.BasicText(
                text = channel.prompt,
                style = TvType.bodyMedium.copy(fontSize = 23.sp, lineHeight = 33.sp),
                modifier = Modifier.padding(top = 42.dp),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            FeedbackInfoRow(label = "软件版本", value = feedback.versionLabel)
        }
    }
}

@Composable
private fun FeedbackInfoRow(label: String, value: String) {
    androidx.compose.foundation.text.BasicText(
        text = "$label：$value",
        style = TvType.caption.copy(color = TvColors.textSecondary, fontSize = 16.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
