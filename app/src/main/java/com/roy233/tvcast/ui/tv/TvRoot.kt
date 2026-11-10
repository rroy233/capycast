package com.roy233.tvcast.ui.tv

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.feature.display.CalibrationPhase
import com.roy233.tvcast.ui.designsystem.*
import com.roy233.tvcast.ui.designsystem.FrostedBackdrop
import com.roy233.tvcast.ui.designsystem.LocalFrostedBackdrop
import com.roy233.tvcast.ui.designsystem.progressiveBlur
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.tv.account.LoginDialog
import com.roy233.tvcast.ui.tv.home.HomeScreen
import com.roy233.tvcast.ui.tv.navigation.Sidebar
import com.roy233.tvcast.ui.tv.overlay.HelpSheet
import com.roy233.tvcast.ui.tv.overlay.PrivacyAgreementDialog
import com.roy233.tvcast.ui.tv.overlay.PrivacyNoticeDialog
import com.roy233.tvcast.ui.tv.overlay.QuickPanel
import com.roy233.tvcast.ui.tv.player.LoadingScreen
import com.roy233.tvcast.ui.tv.player.PlaybackSeekAccelerator
import com.roy233.tvcast.ui.tv.player.PlayingScreen
import com.roy233.tvcast.ui.tv.settings.CalibrationAdjustScreen
import com.roy233.tvcast.ui.tv.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 背景截取前的等待时长：等背景完成一次绘制。 */
private const val FROST_CAPTURE_DELAY_MS = 160L

/** 封面试图异步加载，稍后再取一次，避免磨砂小于可见背景。 */
private const val FROST_CAPTURE_REFRESH_MS = 900L

/**
 * 渲染电视端根界面并根据状态选择当前页面与全局弹层。
 *
 * @param state 当前界面状态。
 * @param actions 界面操作回调集合。
 */
@Composable
fun TvRoot(state: TvUiState, actions: TvActions) {
    if (
        state.calibration.phase == CalibrationPhase.ADJUST ||
            state.calibration.phase == CalibrationPhase.CONFIRM
    ) {
        Box(Modifier.fillMaxSize()) {
            CalibrationAdjustScreen(
                state,
                actions,
                interactive = state.calibration.phase == CalibrationPhase.ADJUST,
            )
            if (state.calibration.phase == CalibrationPhase.CONFIRM) {
                GlassAlert(
                    title = "完成画面校准？",
                    message = "已调整四个角。确认后保存，之后播放的视频都会使用这个画面范围。",
                    primaryLabel = "已完成",
                    onPrimary = actions.onFinishCalibration,
                    secondaryLabel = "再看看",
                    onSecondary = actions.onReviewCalibration,
                )
            }
        }
        return
    }
    val backdrop = rememberLayerBackdrop()
    val ambient = state.mode == TvMode.IDLE
    // 浮层与导航菜单共用一条层级：栈顶拥有按键与焦点。
    val modal = state.topOverlay != null || state.sidebarVisible
    val focusManager = LocalFocusManager.current
    val seekAccelerator = remember { PlaybackSeekAccelerator() }
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 72.dp.toPx() }
    val sidebarDepth by
        animateFloatAsState(
            targetValue = if (state.mode == TvMode.IDLE && state.sidebarVisible) 1f else 0f,
            animationSpec = tween(260),
            label = "sidebar-depth",
        )
    val recede = Modifier.graphicsLayer {
        scaleX = 1f - 0.035f * sidebarDepth
        scaleY = 1f - 0.035f * sidebarDepth
        translationX = 16.dp.toPx() * sidebarDepth
    }
    LaunchedEffect(state.mode, state.loginDialogVisible) {
        if (state.loginDialogVisible && state.mode != TvMode.IDLE) actions.onCloseLogin()
    }
    // 只有浮层会夺走焦点而不改栈顶锚点，因此“浮层全部关闭”才需要交还焦点；
    // 菜单有自己的收起动作，已经在收起时把栈顶交还给页面。
    var overlayWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.topOverlay, state.mode) {
        val overlayVisible = state.topOverlay != null
        if (overlayWasVisible && !overlayVisible && state.mode == TvMode.IDLE) {
            actions.onReclaimPageFocus()
        }
        overlayWasVisible = overlayVisible
    }

    val surface = TvKeyHandler.resolveKeySurface(state)
    var previousSurface by remember { mutableStateOf(surface) }
    LaunchedEffect(surface) {
        if (surface != previousSurface) {
            FocusDiagnostics.surfaceChanged(previousSurface, surface)
            previousSurface = surface
        }
    }
    // 层级（目的页、路由、浮层、菜单、栈顶锚点）变化时输出一次快照。
    LaunchedEffect(
        state.destination,
        state.settingsNavigation,
        state.overlayStack,
        state.sidebarVisible,
        state.focusRequest,
    ) {
        FocusDiagnostics.stackChanged(state, reason = "hierarchy")
    }

    // Android 12 以下从既有 LayerBackdrop 取快照，供玻璃表面生成软件模糊。
    // 该图层由背景修饰器维护；根节点不再持有第二份会过期的绘制缓存。
    var frostedBackdrop by remember { mutableStateOf<FrostedBackdrop?>(null) }
    LaunchedEffect(
        ambient,
        state.destination,
        state.mode,
        state.featuredRecent?.cover,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || !ambient) return@LaunchedEffect
        // 等背景完成布局与绘制；封面是异步加载的，所以再多截一次。
        listOf(FROST_CAPTURE_DELAY_MS, FROST_CAPTURE_REFRESH_MS).forEach { wait ->
            delay(wait)
            val snapshot = runCatching { backdrop.graphicsLayer.toImageBitmap() }.getOrNull()
            if (snapshot == null || snapshot.width <= 0 || snapshot.height <= 0) {
                LogHub.w("TvGlass", "frost capture failed for destination=${state.destination}")
                return@forEach
            }
            val blurred =
                withContext(Dispatchers.Default) {
                    FrostedBackdrop(
                        image = snapshot.progressiveBlur(),
                        windowWidth = snapshot.width,
                        windowHeight = snapshot.height,
                    )
                }
            frostedBackdrop = blurred
            LogHub.d(
                "TvGlass",
                "frost ready source=${snapshot.width}x${snapshot.height} " +
                    "blurred=${blurred.image.width}x${blurred.image.height}",
            )
        }
    }

    CompositionLocalProvider(
        LocalGlassBackdrop provides if (ambient) backdrop else null,
        LocalFrostedBackdrop provides frostedBackdrop,
    ) {
        Box(
            Modifier.fillMaxSize()
                .then(
                    if (state.mode == TvMode.PLAYING) Modifier
                    else Modifier.background(TvColors.black),
                )
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
                    },
                )
                .onPreviewKeyEvent { event ->
                    TvKeyHandler.handle(event, state, actions, modal, seekAccelerator) ||
                        handleLeftEdge(event, state, actions, focusManager)
                },
        ) {
            if (ambient) {
                // 首页自己的封面背景绘制在页面内，这里只保留玻璃表面采样的背景层。
                TvBackground(
                    Modifier.matchParentSize().then(recede).layerBackdrop(backdrop),
                )
            } else if (state.mode == TvMode.ERROR && !state.castActive) {
                TvBackground(Modifier.matchParentSize())
            }

            when (state.mode) {
                TvMode.IDLE -> {
                    Crossfade(
                        targetState = state.destination,
                        animationSpec = tween(220),
                        label = "tab",
                        // 菜单显示期间页面整体退出焦点范围，保证隔层不可操作。
                        modifier = recede.focusProperties { canFocus = !state.sidebarVisible },
                    ) { destination ->
                        when (destination) {
                            TvDestination.HOME -> HomeScreen(state, actions)
                            TvDestination.SETTINGS -> SettingsScreen(state, actions)
                        }
                    }
                    AnimatedVisibility(
                        visible = state.sidebarVisible,
                        enter = fadeIn(tween(180)),
                        exit = fadeOut(tween(160)),
                    ) {
                        Box(
                            Modifier.fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.56f))
                                .pointerInput(Unit) {
                                    detectTapGestures { actions.onCloseSidebar() }
                                },
                        )
                    }
                    AnimatedVisibility(
                        visible = state.sidebarVisible,
                        enter =
                            slideInHorizontally(
                                initialOffsetX = { width -> -width },
                                animationSpec = tween(240),
                            ) + fadeIn(tween(180)),
                        exit =
                            slideOutHorizontally(
                                targetOffsetX = { width -> -width },
                                animationSpec = tween(200),
                            ) + fadeOut(tween(160)),
                    ) {
                        Sidebar(state, actions)
                    }
                }

                TvMode.LOADING -> LoadingScreen()

                TvMode.PLAYING ->
                    PlayingScreen(
                        state = state,
                        actions = actions,
                        enabled = !modal,
                        seekAccelerator = seekAccelerator,
                    )

                TvMode.ERROR ->
                    if (state.castActive) {
                        PlayingScreen(
                            state = state.copy(hudVisible = false),
                            actions = actions,
                            enabled = false,
                            seekAccelerator = seekAccelerator,
                        )
                    }
            }

            AnimatedVisibility(
                visible = state.mode == TvMode.IDLE && state.loginDialogVisible,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(160)),
            ) {
                LoginDialog(state, actions)
            }

            if (state.helpVisible) HelpSheet(state, actions)

            if (state.privacyAgreementVisible) PrivacyAgreementDialog(state, actions)
            if (state.privacyNoticeVisible) PrivacyNoticeDialog(state, actions)

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

            when (state.calibration.phase) {
                CalibrationPhase.INTRO ->
                    GlassAlert(
                        title = "校准电视画面？",
                        message = "如果视频超出屏幕边缘，可以用遥控器调整四边。跳过后不会再次自动提示，仍可从设置中打开。",
                        primaryLabel = "开始校准",
                        onPrimary = actions.onStartCalibration,
                        secondaryLabel = "跳过",
                        onSecondary = actions.onCancelCalibration,
                    )
                else -> Unit
            }

            AnimatedVisibility(
                visible = state.toast != null,
                enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
                exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { it / 2 },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 40.dp),
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
        if (
            state.mode == TvMode.PLAYING &&
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

/**
 * 处理“左侧已无本页元素”的边界：先让焦点在页面内左移，无目标时才唤出导航菜单。
 *
 * 这是所有页面共用的一条边界规则，页面本身不参与判断。
 */
private fun handleLeftEdge(
    event: KeyEvent,
    state: TvUiState,
    actions: TvActions,
    focusManager: FocusManager,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key != Key.DirectionLeft) return false
    if (!TvKeyHandler.canOpenMenuFromEdge(state)) return false
    val movedInsidePage = focusManager.moveFocus(FocusDirection.Left)
    FocusDiagnostics.leftEdge(movedInsidePage)
    if (movedInsidePage) return true
    actions.onOpenSidebar()
    return true
}
