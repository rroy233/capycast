package com.roy233.tvcast.ui.tv.home

import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvDimens
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.model.AudioOption
import com.roy233.tvcast.ui.model.PanelKind
import com.roy233.tvcast.feature.cast.domain.RecentCast
import com.roy233.tvcast.ui.model.TrackOption
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.ui.model.TvActions
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.platform.testTag
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
import com.roy233.tvcast.ui.tv.FocusTarget
import com.roy233.tvcast.ui.tv.focusTarget
import com.roy233.tvcast.ui.tv.rememberFocusTarget

/** 视口高度低于此值时，货架使用更小的卡片，保证与标题、按钮同屏。 */
private val SHELF_COMPACT_HEIGHT = 620.dp

/** 货架卡片宽度：标准与紧凑两档，高度由 16:9 推出。 */
private val SHELF_CARD_WIDTH = 240.dp
private val SHELF_CARD_WIDTH_COMPACT = 200.dp
private const val MAX_RECENT_CARDS = 8

/**
 * 显示首页内容、登录入口和最近投屏记录。
 *
 * @param state 当前界面状态。
 * @param actions 首页可执行的操作回调。
 */
@Composable
internal fun HomeScreen(state: TvUiState, actions: TvActions) {
    val primaryFocus = rememberFocusTarget()
    val secondaryFocus = rememberFocusTarget()
    val cardTargets = remember { mutableMapOf<String, FocusTarget>() }
    val visibleRecents = state.recents.take(MAX_RECENT_CARDS)
    val recentFocusTargets = visibleRecents.map { recent ->
        cardTargets.getOrPut(recent.focusKey()) { FocusTarget() }
    }
    // 大标题、背景与主按钮都跟随同一个字段：最近投屏里被聚焦的那条。
    val featured = state.featuredRecent

    // 首页只声明自己能接的锚点；何时请求由统一恢复入口决定。
    RestoreFocus(
        request = state.focusRequest,
        bindings = listOf(
            FocusBinding(anchor = UiFocusAnchor.HOME_PRIMARY, target = primaryFocus),
        ),
        // 主入口在扫码登录、继续播放、投屏帮助之间切换；按钮被替换后必须重新取回焦点。
        contentKey = listOf(state.loggedIn, featured != null, state.helpVisible),
    )

    val density = LocalDensity.current
    val windowWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }.toInt()
    val windowHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }.toInt()

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
        // 货架是固定槽位：先给它高度，可伸缩的只有上方文字区，焦点目标不会被挤出视口。
        val shelfShown = state.showContinue && visibleRecents.size > 1
        val firstRecentFocus = recentFocusTargets.firstOrNull()
        val secondaryActionShown = featured != null
        val shelfCardWidth = if (compact || maxHeight < SHELF_COMPACT_HEIGHT) {
            SHELF_CARD_WIDTH_COMPACT
        } else {
            SHELF_CARD_WIDTH
        }
        val titleLines = if (shelfShown && !compact) 1 else 2
        // 布局自检：任何可聚焦区域被挤出视口，都会在 /log 里以 W/TvLayout 出现。
        LaunchedEffect(maxWidth, maxHeight, shelfShown, shelfCardWidth) {
            LogHub.d(
                "TvLayout",
                "home viewport=${maxWidth.value.toInt()}x${maxHeight.value.toInt()}dp " +
                    "compact=$compact shelf=$shelfShown card=${shelfCardWidth.value.toInt()}dp " +
                    "titleLines=$titleLines",
            )
        }

        Column(
            modifier = Modifier
                .width((maxWidth - hPad * 2).coerceAtMost(TvDimens.pageMaxWidth))
                .fillMaxHeight()
                .align(Alignment.Center)
                .padding(vertical = vPad)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    LogHub.d(
                        "TvLayout",
                        "home content bounds=(${bounds.left.toInt()},${bounds.top.toInt()})-" +
                            "(${bounds.right.toInt()},${bounds.bottom.toInt()}) " +
                            "viewportPx=${windowWidthPx}x${windowHeightPx}",
                    )
                },
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.BottomStart,
            ) {
                Column {
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

                    Crossfade(
                        targetState = featured,
                        animationSpec = tween(260),
                        label = "home-hero",
                    ) { hero ->
                        Column {
                            BasicText(
                                text = hero?.title ?: "Bilibili 投屏",
                                style = TvType.hero.copy(
                                    fontSize = titleSize,
                                    lineHeight = titleLine,
                                    shadow = Shadow(
                                        color = Color.Black.copy(alpha = 0.55f),
                                        offset = Offset(0f, 3f),
                                        blurRadius = 14f,
                                    ),
                                ),
                                maxLines = titleLines,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(0.78f),
                            )
                            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                            BasicText(
                                text = hero?.let {
                                    listOf(it.quality, "上次投屏")
                                        .filter { value -> value.isNotBlank() }
                                        .joinToString(" · ")
                                } ?: "${state.deviceName} · ${state.roomName}",
                                style = TvType.body.copy(
                                    color = TvColors.textSecondary,
                                    fontSize = metaSize,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(if (compact) 14.dp else 22.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!state.loggedIn) {
                    WhiteButton(
                        text = "扫码登录",
                        onClick = actions.onLogin,
                        modifier = Modifier.focusProperties {
                            left = FocusRequester.Cancel
                            if (secondaryActionShown) right = secondaryFocus.requester
                            if (shelfShown && firstRecentFocus != null) down = firstRecentFocus.requester
                        },
                        focusTarget = primaryFocus,
                        onFocused = actions.onFocusHomeControl,
                        compact = compact,
                    )
                    if (featured != null) {
                        GlassButton(
                            text = "继续播放",
                            onClick = { actions.onRecast(featured) },
                            modifier = Modifier.focusProperties {
                                left = primaryFocus.requester
                                if (shelfShown && firstRecentFocus != null) down = firstRecentFocus.requester
                            },
                            focusTarget = secondaryFocus,
                            onFocused = actions.onFocusHomeControl,
                            compact = compact,
                        )
                    }
                } else if (featured != null) {
                    WhiteButton(
                        text = "继续播放",
                        onClick = { actions.onRecast(featured) },
                        modifier = Modifier.focusProperties {
                            left = FocusRequester.Cancel
                            right = secondaryFocus.requester
                            if (shelfShown && firstRecentFocus != null) down = firstRecentFocus.requester
                        },
                        focusTarget = primaryFocus,
                        onFocused = actions.onFocusHomeControl,
                        compact = compact,
                    )
                    GlassButton(
                        text = "返回",
                        onClick = actions.onDismissContinue,
                        modifier = Modifier.focusProperties {
                            left = primaryFocus.requester
                            if (shelfShown && firstRecentFocus != null) down = firstRecentFocus.requester
                        },
                        focusTarget = secondaryFocus,
                        onFocused = actions.onFocusHomeControl,
                        compact = compact,
                    )
                } else {
                    WhiteButton(
                        text = "投屏帮助",
                        onClick = actions.onShowHelp,
                        modifier = Modifier.focusProperties { left = FocusRequester.Cancel },
                        focusTarget = primaryFocus,
                        onFocused = actions.onFocusHomeControl,
                        compact = compact,
                    )
                }
            }

            if (shelfShown) {
                Spacer(Modifier.height(if (compact) 14.dp else 20.dp))
                RecentShelf(
                    recents = visibleRecents,
                    focusTargets = recentFocusTargets,
                    primaryFocus = primaryFocus,
                    cardWidth = shelfCardWidth,
                    actions = actions,
                )
            }
        }
    }
}


/**
 * 绘制首页背景：继续投屏时展示该视频的封面，并按焦点切换淡入淡出。
 *
 * @param featured 当前展示的最近投屏；为空时退回环境光背景。
 */
@Composable
internal fun HomeBackdrop(featured: RecentCast?, compact: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        HomeAmbientBackground(Modifier.matchParentSize())
        Crossfade(
            targetState = featured?.cover?.takeIf { it.isNotBlank() },
            animationSpec = tween(420),
            label = "home-backdrop-cover",
        ) { cover ->
            if (cover != null) {
                Box(Modifier.matchParentSize()) {
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                    // 双层压暗：纵向保证底部文字可读，横向保证左侧大标题可读。
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.26f),
                                    0.45f to Color.Black.copy(alpha = 0.48f),
                                    1f to Color.Black.copy(alpha = 0.94f),
                                )
                            )
                    )
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.horizontalGradient(
                                    0f to Color.Black.copy(alpha = 0.70f),
                                    0.55f to Color.Black.copy(alpha = 0.30f),
                                    1f to Color.Transparent,
                                )
                            )
                    )
                }
            }
        }
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
 * @param focusTarget 可恢复焦点目标；提供时会等待实际布局完成再请求焦点。
 * @param compact 是否使用紧凑尺寸。
 */
@Composable
internal fun WhiteButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    focusTarget: FocusTarget? = null,
    onFocused: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    GlassButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        primary = true,
        compact = compact,
        focusRequester = focusRequester,
        focusTarget = focusTarget,
        onFocused = onFocused,
    )
}

/** 显示最近投屏记录横向列表。 */
@Composable
private fun RecentShelf(
    recents: List<RecentCast>,
    focusTargets: List<FocusTarget>,
    primaryFocus: FocusTarget,
    cardWidth: Dp,
    actions: TvActions,
) {
    Column {
        BasicText(
            text = "最近投屏",
            style = TvType.section.copy(fontSize = 22.sp),
        )
        Spacer(Modifier.height(12.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(
                items = recents,
                key = { _, recent -> recent.focusKey() },
            ) { index, recent ->
                RecentCard(
                    recent = recent,
                    focusTarget = focusTargets[index],
                    modifier = Modifier.focusProperties {
                        up = primaryFocus.requester
                        left = if (index == 0) {
                            FocusRequester.Cancel
                        } else {
                            focusTargets[index - 1].requester
                        }
                        if (index < focusTargets.lastIndex) right = focusTargets[index + 1].requester
                    },
                    width = cardWidth,
                    actions = actions,
                )
            }
        }
    }
}

/** 显示单条最近投屏记录卡片。 */
@Composable
private fun RecentCard(
    recent: RecentCast,
    focusTarget: FocusTarget,
    modifier: Modifier,
    width: Dp,
    actions: TvActions,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // 焦点落到卡片即切换大标题与背景；点击才真正开始投屏。
    LaunchedEffect(focused) {
        if (focused) actions.onFocusRecent(recent.aid)
    }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
        label = "recent-card",
    )

    Column(
        modifier = Modifier
            .width(width)
            .then(modifier)
            .focusTarget(focusTarget)
            .testTag("recent-card-${recent.focusKey()}")
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(18.dp))
            .border(
                width = 2.dp,
                color = when {
                    focused -> Color.White.copy(alpha = 0.92f)
                    else -> Color.White.copy(alpha = 0.10f)
                },
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

private fun RecentCast.focusKey(): String = "$aid-$cid"

