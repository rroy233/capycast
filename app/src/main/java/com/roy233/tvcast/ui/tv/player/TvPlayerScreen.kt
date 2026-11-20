package com.roy233.tvcast.ui.tv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.roy233.tvcast.R
import com.roy233.tvcast.ui.designsystem.*
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.tv.RequestFocusWhenReady
import com.roy233.tvcast.ui.tv.focusTarget
import com.roy233.tvcast.ui.tv.rememberFocusTarget
import kotlinx.coroutines.delay

/** 显示投屏准备阶段的加载界面。 */
@Composable
internal fun LoadingScreen() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(900)
        visible = true
    }
    Box(
        modifier = Modifier.fillMaxSize().background(TvColors.black),
        contentAlignment = Alignment.Center,
    ) {
        if (visible) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LoadingSpinner()
                BasicText(
                    text = stringResource(R.string.player_preparing),
                    style = TvType.caption.copy(color = TvColors.textSecondary),
                )
            }
        }
    }
}

/**
 * 显示播放画面、标题、进度和播放控制项。
 *
 * @param state 当前播放状态。
 * @param actions 播放控制回调。
 * @param enabled 是否允许用户操作控制项。
 */
@Composable
internal fun PlayingScreen(
    state: TvUiState,
    actions: TvActions,
    enabled: Boolean,
    seekAccelerator: PlaybackSeekAccelerator,
) {
    val playFocus = rememberFocusTarget()
    val progressFocus = rememberFocusTarget()
    val videoFocus = rememberFocusTarget()
    val modal = state.panel != null || state.error != null || state.stopCastConfirm
    val promptVisible = state.castAutoplayPrompt && !state.hudVisible && !modal && enabled
    val promptFocus = rememberFocusTarget()
    val displayedPositionMs = state.seekPreviewPositionMs ?: state.positionMs
    val seekingState = rememberUpdatedState(state.seekPreviewPositionMs != null)
    val actionsState = rememberUpdatedState(actions)

    if (enabled && !modal) {
        RequestFocusWhenReady(
            activation =
                listOf(
                    state.hudVisible,
                    state.panel,
                    state.error,
                    state.stopCastConfirm,
                    promptVisible,
                ),
            target =
                if (promptVisible) promptFocus else if (state.hudVisible) playFocus else videoFocus,
        )
    }

    LaunchedEffect(state.seekPreviewPositionMs) {
        if (state.seekPreviewPositionMs != null) {
            delay(SEEK_CONFIRM_DELAY_MS)
            actions.onConfirmSeek()
            seekAccelerator.reset()
        } else {
            seekAccelerator.reset()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .focusTarget(videoFocus)
                .focusable(enabled && !state.hudVisible)
                .playbackTouchControls(
                    enabled = enabled && !state.hudVisible,
                    seekEnabled = !state.isLive,
                    isSeeking = { seekingState.value },
                    seekAccelerator = seekAccelerator,
                    onPreviewSeekBy = { actionsState.value.onPreviewSeekBy(it) },
                    onConfirmSeek = { actionsState.value.onConfirmSeek() },
                    onShowHub = { actionsState.value.onShowHud() },
                ),
        )

        if (state.isBuffering) {
            LoadingSpinner(Modifier.align(Alignment.Center))
        }

        SubtitleOverlay(
            lines = state.subtitleLines,
            fontScale = state.subtitleFontScale,
            bottomMarginRatio = state.subtitleBottomMarginRatio,
        )

        PlaybackHud(
            state = state,
            actions = actions,
            enabled = enabled,
            displayedPositionMs = displayedPositionMs,
            progressFocus = progressFocus,
            playFocus = playFocus,
            seekAccelerator = seekAccelerator,
        )

        if (promptVisible) {
            AutoplayPrompt(
                focusTarget = promptFocus,
                onAnswer = actions.onAutoplayAnswer,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 40.dp),
            )
        }

        AnimatedVisibility(
            visible = !state.isLive && state.seekPreviewPositionMs != null && !state.hudVisible,
            enter = fadeIn(tween(160)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp),
        ) {
            SeekPreviewOverlay(
                positionMs = displayedPositionMs,
                durationMs = state.durationMs,
            )
        }
    }
}

private const val AUTOPLAY_PROMPT_DURATION_MS = 5_000

@Composable
private fun AutoplayPrompt(
    focusTarget: com.roy233.tvcast.ui.tv.FocusTarget,
    onAnswer: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val remaining = remember { Animatable(1f) }
    val answer = rememberUpdatedState(onAnswer)
    LaunchedEffect(Unit) {
        remaining.animateTo(0f, tween(AUTOPLAY_PROMPT_DURATION_MS, easing = LinearEasing))
        answer.value(false)
    }
    DisposableEffect(Unit) {
        onDispose { answer.value(false) }
    }
    GlassSurface(modifier = modifier.widthIn(min = 260.dp), material = GlassMaterial.Hud) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BasicText(
                stringResource(R.string.cast_collection_prompt),
                style = TvType.body.copy(color = TvColors.textPrimary),
            )
            CountdownGlassButton(
                text = stringResource(R.string.cast_enable_autoplay),
                remaining = remaining.value,
                onClick = { answer.value(true) },
                focusTarget = focusTarget,
            )
        }
    }
}
