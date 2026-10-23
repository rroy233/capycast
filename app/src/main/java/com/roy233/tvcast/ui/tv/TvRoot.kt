package com.roy233.tvcast.ui.tv

import com.roy233.tvcast.ui.tv.home.HomeScreen
import com.roy233.tvcast.ui.tv.navigation.Sidebar
import com.roy233.tvcast.ui.tv.overlay.HelpSheet
import com.roy233.tvcast.ui.tv.overlay.QuickPanel
import com.roy233.tvcast.ui.tv.player.LoadingScreen
import com.roy233.tvcast.ui.tv.player.PlaybackSeekAccelerator
import com.roy233.tvcast.ui.tv.player.PlayingScreen
import com.roy233.tvcast.ui.tv.settings.SettingsScreen

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
import androidx.compose.runtime.remember
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
 * 渲染电视端根界面并根据状态选择当前页面与全局弹层。
 *
 * @param state 当前界面状态。
 * @param actions 界面操作回调集合。
 */
@Composable
fun TvRoot(state: TvUiState, actions: TvActions) {
    val backdrop = rememberLayerBackdrop()
    val ambient = state.mode == TvMode.IDLE
    val modal = state.panel != null || state.error != null ||
        state.stopCastConfirm || state.helpVisible
    val focusManager = LocalFocusManager.current
    val seekAccelerator = remember { PlaybackSeekAccelerator() }
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 72.dp.toPx() }
    // tvOS-style quiet auto-collapse after switching to Bilibili.
    LaunchedEffect(state.mode, state.destination, state.sidebarVisible) {
        if (state.mode == TvMode.IDLE &&
            state.destination == TvDestination.HOME &&
            state.sidebarVisible
        ) {
            delay(5000)
            actions.onCloseSidebar()
        }
    }

    CompositionLocalProvider(
        LocalGlassBackdrop provides if (ambient) backdrop else null
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (state.mode == TvMode.PLAYING) Modifier else Modifier.background(TvColors.black))
                .then(
                    if (state.mode == TvMode.IDLE && !modal && !state.sidebarVisible) {
                        Modifier.pointerInput(state.sidebarVisible) {
                            var total = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onHorizontalDrag = { change, amount ->
                                    total += amount
                                    change.consume()
                                },
                                onDragEnd = {
                                    when {
                                        total > swipeThreshold -> actions.onOpenSidebar()
                                    }
                                },
                            )
                        }
                    } else {
                        Modifier
                    }
                )
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionLeft &&
                        state.mode == TvMode.IDLE &&
                        !state.sidebarVisible &&
                        !modal
                    ) {
                        if (focusManager.moveFocus(FocusDirection.Left)) {
                            true
                        } else {
                            actions.onOpenSidebar()
                            true
                        }
                    } else {
                        TvKeyHandler.handle(event, state, actions, modal, seekAccelerator)
                    }
                }
        ) {
            if (ambient) {
                TvBackground(
                    Modifier
                        .matchParentSize()
                        .layerBackdrop(backdrop)
                )
            } else if (state.mode == TvMode.ERROR && !state.castActive) {
                TvBackground(Modifier.matchParentSize())
            }

            when (state.mode) {
                TvMode.IDLE -> Row(Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        visible = state.sidebarVisible,
                        enter = expandHorizontally(tween(220), Alignment.Start) + fadeIn(tween(180)),
                        exit = shrinkHorizontally(tween(200), Alignment.Start) + fadeOut(tween(160)),
                    ) {
                        Sidebar(state, actions)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        Crossfade(
                            targetState = state.destination,
                            animationSpec = tween(220),
                            label = "tab",
                        ) { destination ->
                            when (destination) {
                                TvDestination.HOME -> HomeScreen(state, actions)
                                TvDestination.SETTINGS -> SettingsScreen(state, actions)
                            }
                        }
                    }
                }

                TvMode.LOADING -> LoadingScreen()

                TvMode.PLAYING -> PlayingScreen(
                    state = state,
                    actions = actions,
                    enabled = !modal,
                    seekAccelerator = seekAccelerator,
                )

                TvMode.ERROR -> if (state.castActive) {
                    PlayingScreen(
                        state = state.copy(hudVisible = false),
                        actions = actions,
                        enabled = false,
                        seekAccelerator = seekAccelerator,
                    )
                }
            }

            if (state.helpVisible) HelpSheet(state, actions)

            if (state.panel != null) QuickPanel(state, actions)
            if (state.error != null) {
                val error = state.error
                GlassAlert(
                    title = error.title,
                    message = error.message,
                    code = error.code.ifBlank { null },
                    primaryLabel = if (error.canRetry) "重试" else "返回",
                    onPrimary = {
                        if (error.canRetry) actions.onRetry() else actions.onDismissError()
                    },
                    secondaryLabel = if (error.canRetry) "返回" else null,
                    onSecondary = if (error.canRetry) actions.onDismissError else null,
                )
            }
            if (state.stopCastConfirm) {
                GlassAlert(
                    title = "停止当前投屏？",
                    message = "电视将停止当前播放并返回主页。",
                    primaryLabel = "停止投屏",
                    onPrimary = actions.onConfirmStopCast,
                    secondaryLabel = "继续观看",
                    onSecondary = actions.onCancelStopCast,
                    destructive = true,
                )
            }

            AnimatedVisibility(
                visible = state.toast != null,
                enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
                exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { it / 2 },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 40.dp),
            ) {
                GlassPill(text = state.toast.orEmpty(), emphasized = true)
            }
        }
    }

    LaunchedEffect(state.toast) {
        if (state.toast != null) {
            delay(2200)
            actions.onClearToast()
        }
    }

    LaunchedEffect(
        state.hudVisible,
        state.isPlaying,
        state.panel,
        state.error,
        state.stopCastConfirm,
        state.mode,
        state.seekPreviewPositionMs,
    ) {
        if (state.mode == TvMode.PLAYING &&
            state.hudVisible &&
            state.isPlaying &&
            state.panel == null &&
            state.error == null &&
            !state.stopCastConfirm &&
            state.seekPreviewPositionMs == null
        ) {
            delay(4000)
            actions.onHideHud()
        }
    }
}
