package com.roy233.tvcast.ui.tv.player

import android.os.SystemClock
import android.graphics.Bitmap
import com.roy233.tvcast.R
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.ui.model.PlaybackSpeed
import com.roy233.tvcast.feature.cast.domain.RecentCast
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.res.vectorResource
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 显示投屏准备阶段的加载界面。 */
@Composable
internal fun LoadingScreen() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(900)
        visible = true
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.black),
        contentAlignment = Alignment.Center,
    ) {
        if (visible) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LoadingSpinner()
                BasicText(
                    text = "正在准备投屏…",
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
    val playFocus = remember { FocusRequester() }
    val progressFocus = remember { FocusRequester() }
    val videoFocus = remember { FocusRequester() }
    val modal = state.panel != null || state.error != null || state.stopCastConfirm
    val displayedPositionMs = state.seekPreviewPositionMs ?: state.positionMs
    val seekingState = rememberUpdatedState(state.seekPreviewPositionMs != null)
    val actionsState = rememberUpdatedState(actions)

    LaunchedEffect(state.hudVisible, modal, enabled) {
        if (!enabled || modal) return@LaunchedEffect
        if (state.hudVisible) {
            delay(80)
            runCatching { playFocus.requestFocus() }
        } else {
            runCatching { videoFocus.requestFocus() }
        }
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
            Modifier
                .fillMaxSize()
                .focusRequester(videoFocus)
                .focusable(enabled && !state.hudVisible)
                .playbackTouchControls(
                    enabled = enabled && !state.hudVisible,
                    seekEnabled = !state.isLive,
                    isSeeking = { seekingState.value },
                    seekAccelerator = seekAccelerator,
                    onPreviewSeekBy = { actionsState.value.onPreviewSeekBy(it) },
                    onConfirmSeek = { actionsState.value.onConfirmSeek() },
                    onShowHub = { actionsState.value.onShowHud() },
                )
        )

        if (state.isBuffering) {
            LoadingSpinner(Modifier.align(Alignment.Center))
        }

        PlaybackHud(
            state = state,
            actions = actions,
            enabled = enabled,
            displayedPositionMs = displayedPositionMs,
            progressFocus = progressFocus,
            playFocus = playFocus,
            seekAccelerator = seekAccelerator,
        )

        AnimatedVisibility(
            visible = !state.isLive && state.seekPreviewPositionMs != null && !state.hudVisible,
            enter = fadeIn(tween(160)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { it / 3 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp),
        ) {
            SeekPreviewOverlay(
                positionMs = displayedPositionMs,
                durationMs = state.durationMs,
            )
        }
    }
}
