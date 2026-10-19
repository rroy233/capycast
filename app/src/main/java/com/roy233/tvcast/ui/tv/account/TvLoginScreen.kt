package com.roy233.tvcast.ui.tv.account

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
 * 显示二维码登录页面和登录状态。
 *
 * @param state 当前登录相关界面状态。
 * @param actions 登录页面可执行的操作回调。
 */
@Composable
internal fun LoginPage(state: TvUiState, actions: TvActions) {
    val skipFocus = remember { FocusRequester() }
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state.sidebarVisible) {
        if (!state.sidebarVisible) {
            var guard = 0
            while (!view.hasFocus() && guard++ < 15) delay(100)
            delay(220)
            var requested = false
            for (i in 0 until 8) {
                if (runCatching { skipFocus.requestFocus() }.getOrDefault(false)) {
                    requested = true
                    break
                }
                delay(180)
            }
            if (!requested) {
                runCatching { focusManager.moveFocus(FocusDirection.Down) }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 500.dp
        val hPad = if (compact) 32.dp else 64.dp
        val vPad = if (compact) 20.dp else 40.dp
        val titleSize = if (compact) 34.sp else 50.sp
        val titleLine = if (compact) 40.sp else 58.sp

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = hPad, vertical = vPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 28.dp),
            ) {
                BasicText(
                    text = "登录 B 站账号",
                    style = TvType.hero.copy(
                        fontSize = titleSize,
                        lineHeight = titleLine,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                BasicText(
                    text = "扫码后按账号权益播放。",
                    style = TvType.body.copy(
                        color = TvColors.textSecondary,
                        fontSize = if (compact) 17.sp else 23.sp,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(22.dp))
                GlassButton(
                    text = "先不登录",
                    onClick = actions.onSkipLogin,
                    compact = true,
                    focusRequester = skipFocus,
                )
            }

            GlassSurface(
                modifier = Modifier
                    .width(if (compact) 300.dp else 380.dp)
                    .height(if (compact) 340.dp else 440.dp),
                tint = Color(0xB30B0B0F),
                borderAlpha = 0.30f,
                highlightAlpha = 0.30f,
                shadow = 36.dp,
                useBackdrop = true,
                blurRadius = 36.dp,
                lensHeight = 12.dp,
                lensAmount = 18.dp,
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val qr = state.loginQr
                    if (qr != null) {
                        QrCodeImage(
                            bitmap = qr,
                            modifier = Modifier.size(if (compact) 180.dp else 240.dp),
                        )
                    } else {
                        LoadingSpinner(Modifier.padding(vertical = 48.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    BasicText(
                        text = state.loginStatus,
                        style = TvType.caption.copy(
                            color = if (state.loginStatus.contains("失败") ||
                                state.loginStatus.contains("异常")
                            ) TvColors.warning else TvColors.textSecondary,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        ),
                        maxLines = 2,
                    )
                    if (state.loginStatus.contains("失败") ||
                        state.loginStatus.contains("异常")
                    ) {
                        Spacer(Modifier.height(10.dp))
                        GlassButton(
                            text = "重试",
                            onClick = actions.onLogin,
                            primary = true,
                            compact = true,
                        )
                    }
                }
            }
        }
    }
}

