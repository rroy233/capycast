package com.roy233.tvcast.ui.tv.navigation

import android.graphics.Bitmap
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.model.AudioOption
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.feature.cast.domain.RecentCast
import com.roy233.tvcast.ui.model.TrackOption
import com.roy233.tvcast.ui.model.TvActions
import com.roy233.tvcast.ui.model.TvError
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.TvUiState
import com.roy233.tvcast.ui.designsystem.*
import com.roy233.tvcast.ui.model.MENU_ENTRIES
import com.roy233.tvcast.ui.model.MenuEntry
import com.roy233.tvcast.ui.tv.FocusBinding
import com.roy233.tvcast.ui.tv.FocusDiagnostics
import com.roy233.tvcast.ui.tv.RestoreFocus
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
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

private val COMPACT_SCREEN_WIDTH = 720.dp
private val SIDEBAR_MIN_WIDTH = 248.dp
private val SIDEBAR_MAX_WIDTH = 300.dp

private fun sidebarWidthFor(maxWidth: Dp): Dp = when {
    maxWidth < COMPACT_SCREEN_WIDTH -> maxWidth * 0.42f
    else -> maxWidth * 0.285f
}.coerceIn(SIDEBAR_MIN_WIDTH, SIDEBAR_MAX_WIDTH)

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
    val entryRequesters = remember(homeFocus, settingsFocus) {
        mapOf(
            MenuEntry.HOME to homeFocus,
            MenuEntry.SETTINGS to settingsFocus,
        )
    }

    // 菜单只声明条目与锚点，焦点时机由统一的恢复入口负责。
    RestoreFocus(
        request = state.focusRequest,
        bindings = MENU_ENTRIES.map { entry ->
            FocusBinding(anchor = entry.anchor, requester = entryRequesters.getValue(entry))
        },
    )

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val compact = maxWidth < COMPACT_SCREEN_WIDTH || maxHeight < 500.dp
        val sidebarWidth = sidebarWidthFor(maxWidth)

        GlassSurface(
            modifier = Modifier
                .padding(start = if (compact) 24.dp else 48.dp)
                .width(sidebarWidth)
                .heightIn(max = 420.dp)
                .wrapContentHeight()
                .testTag("sidebar-panel"),
            material = GlassMaterial.Navigation,
            size = GlassSize.Large,
            shape = RoundedCornerShape(if (compact) 28.dp else 36.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 20.dp,
                        vertical = 22.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                            painter = painterResource(com.roy233.tvcast.R.drawable.capycast_brandmark),
                            contentDescription = "CapyCast Logo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
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
                            maxLines = 1,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                MENU_ENTRIES.forEach { entry ->
                    SidebarItem(
                        entry = entry,
                        selected = state.destination == entry.destination,
                        onClick = {
                            FocusDiagnostics.menuItemActivated(entry, state.destination.name)
                            if (state.destination == entry.destination) {
                                actions.onCloseSidebar()
                            } else {
                                entry.navigate(actions)
                            }
                        },
                        focusRequester = entryRequesters.getValue(entry),
                        badge = entry == MenuEntry.SETTINGS &&
                            state.update.availableUpdate != null,
                    )
                }
            }
        }
    }
}

/** 把菜单条目映射到目的页导航命令。 */
private fun MenuEntry.navigate(actions: TvActions) = when (this) {
    MenuEntry.HOME -> actions.onNavigateHome()
    MenuEntry.SETTINGS -> actions.onNavigateSettings()
}

/** 绘制单个侧边栏导航项。 */
@Composable
private fun SidebarItem(
    entry: MenuEntry,
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
    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(
                Color.White.copy(
                    alpha = when {
                        focused -> 0.16f
                        selected -> 0.10f
                        else -> 0.03f
                    }
                )
            )
            .border(
                1.dp,
                Color.White.copy(alpha = if (focused) 0.48f else if (selected) 0.18f else 0.06f),
                shape,
            )
            .focusRequester(focusRequester)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MenuEntryIcon(entry, if (selected) Color.White else Color.White.copy(alpha = 0.78f))
            BasicText(
                text = entry.title,
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
}

/** 绘制菜单条目的图标；图标是条目的展示细节，与焦点和跳转无关。 */
@Composable
private fun MenuEntryIcon(entry: MenuEntry, tint: Color) {
    when (entry) {
        MenuEntry.HOME -> BilibiliTvLogo(
            modifier = Modifier.size(30.dp),
            color = tint,
        )

        MenuEntry.SETTINGS -> Icon(
            imageVector = Icons.Rounded.Settings,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(30.dp),
        )
    }
}
