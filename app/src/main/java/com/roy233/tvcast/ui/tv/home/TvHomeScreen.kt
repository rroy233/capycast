package com.roy233.tvcast.ui.tv.home

import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvDimens
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
import com.roy233.tvcast.ui.tv.tvFocusRegion
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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
import com.roy233.tvcast.ui.model.UiFocusAnchor
import com.roy233.tvcast.ui.tv.FocusBinding
import com.roy233.tvcast.ui.tv.focusTrace
import com.roy233.tvcast.ui.tv.RestoreFocus

/**
 * 显示首页内容、登录入口和最近投屏记录。
 *
 * @param state 当前界面状态。
 * @param actions 首页可执行的操作回调。
 */
@Composable
internal fun HomeScreen(state: TvUiState, actions: TvActions) {
    val primaryFocus = remember { FocusRequester() }
    val featured = if (state.showContinue) state.recents.firstOrNull() else null

    // 首页只声明自己能接的锚点；何时请求由统一恢复入口决定。
    RestoreFocus(
        request = state.focusRequest,
        bindings = listOf(
            FocusBinding(anchor = UiFocusAnchor.HOME_PRIMARY, requester = primaryFocus),
        ),
        // 主入口在扫码登录、继续播放、投屏帮助之间切换；按钮被替换后必须重新取回焦点。
        contentKey = listOf(state.loggedIn, featured != null, state.helpVisible),
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 500.dp
        val hPad = if (compact) 32.dp else 64.dp
        val vPad = if (compact) 20.dp else 40.dp
        val titleSize = when {
            compact -> 38.sp
            featured != null -> 48.sp
            else -> 56.sp
        }
        val titleLine = when {
            compact -> 44.sp
            featured != null -> 56.sp
            else -> 64.sp
        }
        val metaSize = if (compact) 17.sp else 23.sp

        Column(
            modifier = Modifier
                .width((maxWidth - hPad * 2).coerceAtMost(TvDimens.pageMaxWidth))
                .fillMaxHeight()
                .align(Alignment.Center)
                .padding(vertical = vPad),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Spacer(Modifier.weight(1f))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulseDot(if (state.loggedIn) TvColors.accent else TvColors.warning, 7.dp)
                BasicText(
                    text = if (state.loggedIn) "等待手机投屏" else "访客模式",
                    style = TvType.caption.copy(
                        color = TvColors.textSecondary,
                        fontSize = if (compact) 16.sp else 20.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))

            BasicText(
                text = featured?.title ?: "Bilibili 投屏",
                style = TvType.hero.copy(
                    fontSize = titleSize,
                    lineHeight = titleLine,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.55f),
                        offset = Offset(0f, 3f),
                        blurRadius = 14f,
                    ),
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.78f),
            )
            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))

            BasicText(
                text = featured?.let {
                    listOf(it.quality, "上次投屏").filter { v -> v.isNotBlank() }.joinToString(" · ")
                } ?: "${state.deviceName} · ${state.roomName}",
                style = TvType.body.copy(
                    color = TvColors.textSecondary,
                    fontSize = metaSize,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(if (compact) 14.dp else 22.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!state.loggedIn) {
                    WhiteButton(
                        text = "扫码登录",
                        onClick = actions.onLogin,
                        focusRequester = primaryFocus,
                        compact = compact,
                    )
                    if (featured != null) {
                        GlassButton(
                            text = "继续播放",
                            onClick = { actions.onRecast(featured) },
                            compact = compact,
                        )
                    }
                } else if (featured != null) {
                    WhiteButton(
                        text = "继续播放",
                        onClick = { actions.onRecast(featured) },
                        focusRequester = primaryFocus,
                        compact = compact,
                    )
                    GlassButton(
                        text = "返回",
                        onClick = actions.onDismissContinue,
                        compact = compact,
                    )
                } else {
                    WhiteButton(
                        text = "投屏帮助",
                        onClick = actions.onShowHelp,
                        focusRequester = primaryFocus,
                        compact = compact,
                    )
                }
            }

            if (!compact && state.showContinue && state.recents.size > 1) {
                Spacer(Modifier.height(26.dp))
                RecentShelf(state.recents, actions)
            }
        }
    }
}


/** 绘制具有雾化玻璃与体积光的首页背景。 */
@Composable
internal fun HomeBackdrop(compact: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        HomeAmbientBackground(Modifier.matchParentSize())
        BilibiliLogo(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = if (compact) 36.dp else 72.dp,
                    bottom = if (compact) 28.dp else 56.dp,
                )
                .width(if (compact) 220.dp else 360.dp),
        )
    }
}

/**
 * 绘制电视端白色主操作按钮。
 *
 * @param text 按钮文本。
 * @param onClick 点击回调。
 * @param focusRequester 用于请求焦点的对象。
 * @param compact 是否使用紧凑尺寸。
 */
@Composable
internal fun WhiteButton(
    text: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    compact: Boolean = false,
) {
    GlassButton(
        text = text,
        onClick = onClick,
        primary = true,
        compact = compact,
        focusRequester = focusRequester,
    )
}

/** 显示最近投屏记录横向列表。 */
@Composable
private fun RecentShelf(recents: List<RecentCast>, actions: TvActions) {
    Column {
        BasicText(
            text = "最近投屏",
            style = TvType.section.copy(fontSize = 22.sp),
        )
        Spacer(Modifier.height(12.dp))
        LazyRow(
            modifier = Modifier.tvFocusRegion(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(
                items = recents.take(8),
                key = { "${it.aid}-${it.cid}" },
            ) { recent ->
                RecentCard(recent, actions)
            }
        }
    }
}

/** 显示单条最近投屏记录卡片。 */
@Composable
private fun RecentCard(recent: RecentCast, actions: TvActions) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
        label = "recent-card",
    )

    Column(
        modifier = Modifier
            .width(240.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(18.dp))
            .border(
                width = 2.dp,
                color = if (focused) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(18.dp),
            )
            .focusTrace("card:${recent.title}")
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { actions.onRecast(recent) },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color(0xFF19232C)),
        ) {
            if (recent.cover.isNotBlank()) {
                AsyncImage(
                    model = recent.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.88f)
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
            ) {
                BasicText(
                    text = recent.title,
                    style = TvType.caption.copy(
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (recent.quality.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    BasicText(
                        text = recent.quality,
                        style = TvType.tiny.copy(color = TvColors.textSecondary, fontSize = 13.sp),
                    )
                }
            }
        }
    }
}

