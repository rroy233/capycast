package com.roy233.tvcast.ui.tv.navigation

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
import com.roy233.tvcast.ui.tv.tvFocusRegion
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
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
 * 显示电视端主导航侧边栏。
 *
 * @param state 当前页面状态。
 * @param actions 导航操作回调。
 * @param onSidebarFocusChanged 侧边栏焦点变化回调。
 */
@Composable
internal fun Sidebar(
    state: TvUiState,
    actions: TvActions,
) {
    val homeFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }

    val view = LocalView.current
    LaunchedEffect(state.destination) {
        var guard = 0
        while (!view.hasFocus() && guard++ < 15) delay(100)
        delay(340)
        runCatching {
            if (state.destination == TvDestination.SETTINGS) {
                settingsFocus.requestFocus()
            } else {
                homeFocus.requestFocus()
            }
        }
    }

    GlassSurface(
        modifier = Modifier
            .fillMaxHeight()
            .width(208.dp)
            .tvFocusRegion(),
        shape = RoundedCornerShape(0.dp),
        tint = Color(0xB30B0B0F),
        borderAlpha = 0f,
        highlightAlpha = 0.10f,
        shadow = 0.dp,
        useBackdrop = true,
        drawBackdropHighlight = false,
        blurRadius = 40.dp,
        lensHeight = 8.dp,
        lensAmount = 12.dp,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(11.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(com.roy233.tvcast.R.mipmap.ic_launcher),
                        contentDescription = "CapyCast Logo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    BasicText(
                        text = "CapyCast",
                        style = TvType.bodyMedium.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    BasicText(
                        text = "v${state.version}",
                        style = TvType.caption.copy(
                            color = TvColors.textTertiary,
                            fontSize = 12.sp,
                        ),
                        modifier = Modifier.align(Alignment.End),
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.height(26.dp))

            SidebarItem(
                title = "Bilibili 投屏",
                icon = Icons.Rounded.Cast,
                selected = state.destination == TvDestination.HOME,
                onClick = {
                    if (state.destination == TvDestination.HOME) {
                        actions.onCloseSidebar()
                    } else {
                        actions.onNavigateHome()
                    }
                },
                focusRequester = homeFocus,
            )
            Spacer(Modifier.height(4.dp))
            SidebarItem(
                title = "设置",
                icon = Icons.Rounded.Settings,
                selected = state.destination == TvDestination.SETTINGS,
                onClick = {
                    if (state.destination == TvDestination.SETTINGS) {
                        actions.onCloseSidebar()
                    } else {
                        actions.onNavigateSettings()
                    }
                },
                focusRequester = settingsFocus,
                badge = state.update.availableUpdate != null,
            )

            Spacer(Modifier.weight(1f))
        }

        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(1.dp)
                .background(Color.White.copy(alpha = 0.06f))
        )
    }
}

/** 绘制单个侧边栏导航项。 */
@Composable
private fun SidebarItem(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    badge: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val interactionFocused by interaction.collectIsFocusedAsState()
    val focused = interactionFocused
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.02f else 1f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f),
        label = "sidebar-item",
    )
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(focusRequester)
            .clip(shape)
            .background(
                when {
                    selected -> Brush.linearGradient(listOf(TvColors.pink, TvColors.pinkDeep))
                    focused -> Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.05f))
                    )
                    else -> Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                }
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White.copy(alpha = 0.75f) else Color.Transparent,
                shape = shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) Color.White else Color.White.copy(alpha = 0.70f),
            modifier = Modifier.size(20.dp),
        )
        BasicText(
            text = title,
            style = TvType.bodyMedium.copy(
                color = if (selected || focused) Color.White else TvColors.textSecondary,
                fontSize = 20.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (badge) {
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(TvColors.error)
                    .border(1.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                    .semantics { contentDescription = "有新版本" },
            )
        }
    }
}

