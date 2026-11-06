package com.roy233.tvcast.ui.tv.account

import android.graphics.Bitmap
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.model.AudioOption
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.feature.cast.domain.RecentCast
import com.roy233.tvcast.ui.model.TrackOption
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvError
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.designsystem.*
import com.roy233.tvcast.ui.tv.RequestFocusWhenReady
import com.roy233.tvcast.ui.tv.focusTarget
import com.roy233.tvcast.ui.tv.rememberFocusTarget
import com.roy233.tvcast.ui.tv.tvModalFocusRegion
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
import androidx.compose.foundation.Image
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
 * 在首页之上显示扫码登录弹窗和登录状态。
 *
 * @param state 当前登录相关界面状态。
 * @param actions 登录页面可执行的操作回调。
 */
@Composable
internal fun LoginDialog(state: TvUiState, actions: TvActions) {
    val closeFocus = rememberFocusTarget()
    val closeInteraction = remember { MutableInteractionSource() }
    val closeFocused by closeInteraction.collectIsFocusedAsState()
    RequestFocusWhenReady(activation = state.loginDialogVisible, target = closeFocus)

    BoxWithConstraints(
        Modifier.fillMaxSize().tvModalFocusRegion(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.52f))
                .pointerInput(Unit) { detectTapGestures { actions.onCloseLogin() } }
        )
        val compact = maxHeight < 500.dp
        val horizontalInset = if (compact) 24.dp else 48.dp
        val cardWidth = minOf(if (compact) 360.dp else 420.dp, maxWidth - horizontalInset * 2)
        val qrSize = if (compact) 200.dp else 252.dp

        GlassSurface(
            modifier = Modifier.width(cardWidth),
            material = GlassMaterial.Panel,
            size = GlassSize.Large,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = if (compact) 18.dp else 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BilibiliTvLogo(
                        modifier = Modifier.size(if (compact) 32.dp else 36.dp),
                        color = TvColors.blue,
                    )
                    Spacer(Modifier.width(10.dp))
                    BasicText(
                        text = "打开bilibili客户端扫码",
                        style = TvType.bodyMedium.copy(fontSize = if (compact) 18.sp else 22.sp),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (closeFocused) 0.18f else 0.06f))
                            .border(
                                1.dp,
                                Color.White.copy(alpha = if (closeFocused) 0.52f else 0.12f),
                                CircleShape,
                            )
                            .focusTarget(closeFocus)
                            .semantics { contentDescription = "关闭登录" }
                            .clickable(
                                interactionSource = closeInteraction,
                                indication = null,
                                role = Role.Button,
                                onClick = actions.onCloseLogin,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(Modifier.height(if (compact) 14.dp else 18.dp))
                val qr = state.loginQr
                Box(Modifier.size(qrSize), contentAlignment = Alignment.Center) {
                    if (qr != null) {
                        QrCodeImage(bitmap = qr, modifier = Modifier.fillMaxSize())
                    } else {
                        LoadingSpinner()
                    }
                }
                Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
                BasicText(
                    text = state.loginStatus,
                    style = TvType.caption.copy(
                        color = if (state.loginStatus.contains("失败") ||
                            state.loginStatus.contains("异常")
                        ) TvColors.warning else TvColors.textSecondary,
                        fontSize = if (compact) 16.sp else 18.sp,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 2,
                )
            }
        }
    }
}

