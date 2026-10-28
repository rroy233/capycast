package com.roy233.tvcast.ui.tv.home

import com.roy233.tvcast.ui.tv.account.LoginPage

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
import androidx.compose.foundation.Canvas
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
 * 显示首页内容、登录入口和最近投屏记录。
 *
 * @param state 当前界面状态。
 * @param actions 首页可执行的操作回调。
 */
@Composable
internal fun HomeScreen(state: TvUiState, actions: TvActions) {
    val primaryFocus = remember { FocusRequester() }
    val featured = if (state.showContinue) state.recents.firstOrNull() else null

    val view = LocalView.current
    LaunchedEffect(state.sidebarVisible, state.helpVisible, state.showContinue) {
        if (!state.sidebarVisible && !state.helpVisible) {
            var guard = 0
            while (!view.hasFocus() && guard++ < 15) delay(100)
            delay(220)
            for (i in 0 until 8) {
                if (runCatching { primaryFocus.requestFocus() }.getOrDefault(false)) break
                delay(180)
            }
        }
    }

    if (!state.loggedIn && !state.loginSkipped) {
        LoginPage(state, actions)
        return
    }

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

        HomeBackdrop(featured, compact)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = hPad, vertical = vPad),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Spacer(Modifier.weight(1f))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulseDot(if (state.loggedIn) TvColors.pink else TvColors.warning, 7.dp)
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
                if (featured != null) {
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
                } else if (!state.loggedIn) {
                    WhiteButton(
                        text = "扫码登录",
                        onClick = actions.onLogin,
                        focusRequester = primaryFocus,
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


/** 根据当前推荐内容绘制首页背景。 */
@Composable
private fun HomeBackdrop(featured: RecentCast?, compact: Boolean) {
    Box(Modifier.fillMaxSize()) {
        if (featured != null && featured.cover.isNotBlank()) {
            AsyncImage(
                model = featured.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            TvBackground(Modifier.matchParentSize())
            HeroArtwork(compact)
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.28f),
                        0.45f to Color.Black.copy(alpha = 0.42f),
                        1f to Color.Black.copy(alpha = 0.94f),
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.88f),
                        0.52f to Color.Black.copy(alpha = 0.55f),
                        0.82f to Color.Transparent,
                    )
                )
        )
    }
}

/** 绘制无推荐内容时显示的电视与投屏插画。 */
@Composable
private fun HeroArtwork(compact: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val tvW = size.width * if (compact) 0.56f else 0.46f
        val tvH = tvW * 0.56f
        val tvLeft = size.width * if (compact) 0.36f else 0.47f
        val tvTop = size.height * if (compact) 0.15f else 0.11f
        val corner = CornerRadius(if (compact) 18.dp.toPx() else 26.dp.toPx())
        val inset = if (compact) 6.dp.toPx() else 9.dp.toPx()

        // Light spilling from behind the screen.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(TvColors.pink.copy(alpha = 0.20f), Color.Transparent),
                center = Offset(tvLeft + tvW * 0.36f, tvTop + tvH * 0.32f),
                radius = tvW * 0.72f,
            ),
            radius = tvW * 0.72f,
            center = Offset(tvLeft + tvW * 0.36f, tvTop + tvH * 0.32f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(TvColors.blue.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(tvLeft + tvW * 0.78f, tvTop + tvH * 0.74f),
                radius = tvW * 0.62f,
            ),
            radius = tvW * 0.62f,
            center = Offset(tvLeft + tvW * 0.78f, tvTop + tvH * 0.74f),
        )

        // Soft shadow / bezel.
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.45f),
            topLeft = Offset(tvLeft + 12.dp.toPx(), tvTop + 22.dp.toPx()),
            size = Size(tvW, tvH),
            cornerRadius = corner,
        )
        drawRoundRect(
            brush = Brush.linearGradient(
                listOf(Color(0xFF151018), Color(0xFF070A10))
            ),
            topLeft = Offset(tvLeft, tvTop),
            size = Size(tvW, tvH),
            cornerRadius = corner,
        )

        val sx = tvLeft + inset
        val sy = tvTop + inset
        val sw = tvW - inset * 2
        val sh = tvH - inset * 2
        clipRect(sx, sy, sx + sw, sy + sh) {
            drawRect(
                brush = Brush.linearGradient(
                    listOf(Color(0xFF250B18), Color(0xFF0A1220))
                ),
                topLeft = Offset(sx, sy),
                size = Size(sw, sh),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(TvColors.pink.copy(alpha = 0.42f), Color.Transparent),
                    center = Offset(sx + sw * 0.24f, sy + sh * 0.38f),
                    radius = sw * 0.52f,
                ),
                radius = sw * 0.52f,
                center = Offset(sx + sw * 0.24f, sy + sh * 0.38f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(TvColors.blue.copy(alpha = 0.36f), Color.Transparent),
                    center = Offset(sx + sw * 0.80f, sy + sh * 0.70f),
                    radius = sw * 0.52f,
                ),
                radius = sw * 0.52f,
                center = Offset(sx + sw * 0.80f, sy + sh * 0.70f),
            )

            // A restrained play glyph in the screen, not a logo.
            val cx = sx + sw * 0.53f
            val cy = sy + sh * 0.48f
            val ph = sh * 0.16f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx - ph * 0.45f, cy - ph * 0.55f)
                lineTo(cx + ph * 0.55f, cy)
                lineTo(cx - ph * 0.45f, cy + ph * 0.55f)
                close()
            }
            drawPath(path, Color.White.copy(alpha = 0.07f))

            // Glass reflection.
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.02f),
                        Color.Transparent,
                    ),
                    start = Offset(sx, sy + sh * 0.10f),
                    end = Offset(sx + sw, sy + sh * 0.55f),
                ),
                topLeft = Offset(sx, sy),
                size = Size(sw, sh * 0.55f),
            )
            for (i in 1..7) {
                val y = sy + sh * (i / 8f)
                drawLine(
                    color = Color.White.copy(alpha = 0.025f),
                    start = Offset(sx, y),
                    end = Offset(sx + sw, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }

        drawRoundRect(
            color = Color.White.copy(alpha = 0.13f),
            topLeft = Offset(tvLeft, tvTop),
            size = Size(tvW, tvH),
            cornerRadius = corner,
            style = Stroke(1.2.dp.toPx()),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.06f),
            topLeft = Offset(tvLeft + inset, tvTop + inset),
            size = Size(sw, sh),
            cornerRadius = CornerRadius((corner.x - inset).coerceAtLeast(0f)),
            style = Stroke(1.dp.toPx()),
        )

        // TV stand.
        drawRoundRect(
            color = Color(0xFF0B0B10),
            topLeft = Offset(tvLeft + tvW * 0.38f, tvTop + tvH),
            size = Size(tvW * 0.24f, 8.dp.toPx()),
            cornerRadius = CornerRadius(4.dp.toPx()),
        )

        // Phone casting into the screen, with visible signal arcs.
        val phoneW = tvW * 0.09f
        val phoneH = phoneW * 1.85f
        val phoneLeft = tvLeft + tvW * 0.86f
        val phoneTop = tvTop + tvH * 0.67f
        drawRoundRect(
            color = Color(0xE60A0A0F),
            topLeft = Offset(phoneLeft, phoneTop),
            size = Size(phoneW, phoneH),
            cornerRadius = CornerRadius(9.dp.toPx()),
        )
        drawRoundRect(
            brush = Brush.linearGradient(
                listOf(TvColors.pink.copy(alpha = 0.55f), TvColors.violet.copy(alpha = 0.35f))
            ),
            topLeft = Offset(phoneLeft + 3.dp.toPx(), phoneTop + 8.dp.toPx()),
            size = Size(phoneW - 6.dp.toPx(), phoneH - 16.dp.toPx()),
            cornerRadius = CornerRadius(6.dp.toPx()),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.30f),
            topLeft = Offset(phoneLeft, phoneTop),
            size = Size(phoneW, phoneH),
            cornerRadius = CornerRadius(9.dp.toPx()),
            style = Stroke(1.dp.toPx()),
        )
        for (i in 1..3) {
            drawArc(
                color = Color.White.copy(alpha = 0.20f - i * 0.035f),
                startAngle = 205f,
                sweepAngle = 70f,
                useCenter = false,
                topLeft = Offset(phoneLeft - phoneW * i * 0.9f, phoneTop - phoneH * 0.10f),
                size = Size(phoneW * i * 1.8f, phoneH * 0.85f),
                style = Stroke(1.1.dp.toPx()),
            )
        }
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
    val interaction = remember { MutableInteractionSource() }
    val interactionFocused by interaction.collectIsFocusedAsState()
    var focusObserved by remember { mutableStateOf(false) }
    val focused = interactionFocused || focusObserved
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
        label = "white-button",
    )

    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(focusRequester)
            .onFocusChanged { focusObserved = it.isFocused }
            .shadow(18.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CircleShape)
            .background(Color.White)
            .border(
                width = 3.dp,
                color = if (focused) TvColors.pink else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .height(if (compact) 52.dp else 62.dp)
            .padding(horizontal = if (compact) 24.dp else 30.dp)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = TvType.button.copy(
                color = Color.Black,
                fontSize = if (compact) 18.sp else 22.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 1,
        )
    }
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
                .background(
                    Brush.linearGradient(
                        listOf(TvColors.pink.copy(alpha = 0.35f), TvColors.blue.copy(alpha = 0.3f))
                    )
                ),
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

