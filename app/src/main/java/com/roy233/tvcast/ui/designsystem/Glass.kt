package com.roy233.tvcast.ui.designsystem

import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvDimens
import com.roy233.tvcast.ui.theme.TvType
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.DpOffset
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch

/** 判断系统是否要求减少动画。 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
}

/** 生成可平铺的噪点位图，用于玻璃表面纹理。 */
@Composable
private fun rememberNoiseBitmap(): ImageBitmap {
    return remember {
        val size = 96
        val pixels = IntArray(size * size)
        val random = java.util.Random(0x5EED)
        for (i in pixels.indices) {
            val a = random.nextInt(46)
            pixels[i] = (a shl 24) or 0x00FFFFFF
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.asImageBitmap()
    }
}

/** 提供当前界面可复用的背景模糊源。 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** 绘制带有玻璃材质、边框、高光和可选背景模糊的容器。 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(TvDimens.cardCorner),
    tint: Color = TvColors.glassTint,
    borderAlpha: Float = 0.28f,
    highlightAlpha: Float = 0.42f,
    shadow: Dp = 24.dp,
    shadowColor: Color = Color.Black,
    backgroundBrush: Brush? = null,
    useBackdrop: Boolean = false,
    drawBackdropHighlight: Boolean = true,
    blurRadius: Dp = 26.dp,
    lensHeight: Dp = 14.dp,
    lensAmount: Dp = 22.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val noise = rememberNoiseBitmap()
    val backdrop = LocalGlassBackdrop.current
    val canUseBackdrop = useBackdrop &&
        backdrop != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val base = modifier
        .shadow(
            elevation = shadow,
            shape = shape,
            clip = false,
            ambientColor = shadowColor,
            spotColor = shadowColor,
        )
        .clip(shape)

    val drawn = if (canUseBackdrop) {
        base.drawBackdrop(
            backdrop = backdrop!!,
            shape = { shape },
            effects = {
                vibrancy()
                blur(blurRadius.toPx())
                lens(
                    refractionHeight = lensHeight.toPx(),
                    refractionAmount = lensAmount.toPx(),
                    depthEffect = true,
                    chromaticAberration = true,
                )
            },
            highlight = if (drawBackdropHighlight) {
                {
                    Highlight(
                        width = 0.7.dp,
                        alpha = 0.72f,
                        style = HighlightStyle.Ambient,
                    )
                }
            } else {
                null
            },
            shadow = if (drawBackdropHighlight) {
                {
                    Shadow(
                        radius = shadow,
                        color = Color.Black.copy(alpha = 0.32f),
                    )
                }
            } else {
                null
            },
            innerShadow = if (drawBackdropHighlight) {
                {
                    InnerShadow(
                        radius = 18.dp,
                        offset = DpOffset(0.dp, 6.dp),
                        color = Color.Black.copy(alpha = 0.20f),
                    )
                }
            } else {
                null
            },
            onDrawSurface = {
                drawGlassSurface(tint, highlightAlpha, noise, backgroundBrush)
            },
        )
    } else {
        base.drawWithContent {
            drawGlassSurface(tint, highlightAlpha, noise, backgroundBrush)
            drawContent()
            drawGlassBorder(shape, borderAlpha)
        }
    }

    Box(
        modifier = drawn,
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** 绘制玻璃表面的颜色、渐变和噪点纹理。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlassSurface(
    tint: Color,
    highlightAlpha: Float,
    noise: ImageBitmap,
    backgroundBrush: Brush?,
) {
    if (backgroundBrush != null) {
        drawRect(backgroundBrush)
    } else {
        drawRect(tint)
    }

    drawRect(
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.13f),
                Color.White.copy(alpha = 0.035f),
                Color.White.copy(alpha = 0.015f),
            )
        )
    )
    drawRect(
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = highlightAlpha * 0.22f),
                Color.Transparent,
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
    )
    drawRect(
        brush = ShaderBrush(
            ImageShader(noise, TileMode.Repeated, TileMode.Repeated)
        ),
        alpha = 0.045f,
    )
    drawRect(
        Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f)),
            startY = size.height * 0.45f,
        )
    )
}

/** 绘制玻璃表面的渐变边框。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlassBorder(
    shape: Shape,
    borderAlpha: Float,
) {
    val outline = shape.createOutline(size, layoutDirection, this)
    val border = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = borderAlpha * 2.2f),
            Color.White.copy(alpha = borderAlpha * 0.55f),
            Color.White.copy(alpha = borderAlpha * 0.18f),
        ),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    drawPath(
        path = Path().apply { addOutline(outline) },
        brush = border,
        style = Stroke(width = TvDimens.glassBorder.toPx()),
    )
}

/** 绘制支持焦点、主色和危险状态的文字按钮。 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
    minWidth: Dp = 0.dp,
    focusRequester: FocusRequester? = null,
) {
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val interactionFocused by interaction.collectIsFocusedAsState()
    var focusObserved by remember { mutableStateOf(false) }
    val focused = interactionFocused || focusObserved
    val targetScale = when {
        pressed -> 0.97f
        focused -> 1.05f
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.82f, stiffness = 380f),
        label = "glass-button-scale",
    )
    val shape = RoundedCornerShape(50)
    val bg = when {
        destructive -> Brush.linearGradient(
            listOf(TvColors.error, Color(0xFFB52B22))
        )
        primary -> Brush.linearGradient(
            listOf(TvColors.pink, Color(0xFFE84A8A), TvColors.violet)
        )
        else -> null
    }

    GlassSurface(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusProperties { canFocus = enabled }
            .onFocusChanged { focusObserved = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (enabled &&
                    event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.DirectionCenter)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable()
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .height(if (compact) 50.dp else TvDimens.buttonHeight)
            .defaultMinSize(minWidth = minWidth),
        shape = shape,
        tint = if (primary || destructive) Color.Transparent else TvColors.glassTint,
        borderAlpha = when {
            focused -> 0.85f
            primary -> 0.50f
            else -> 0.26f
        },
        highlightAlpha = if (focused) 0.70f else 0.42f,
        shadow = if (focused) 26.dp else 10.dp,
        shadowColor = if (focused) Color.White.copy(alpha = 0.16f) else Color.Black,
        backgroundBrush = bg,
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 18.dp else 22.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) Color.White else TvColors.textDisabled,
                    modifier = Modifier.size(if (compact) 18.dp else 21.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            BasicText(
                text = text,
                style = TvType.button.copy(
                    color = if (enabled) Color.White else TvColors.textDisabled,
                    fontSize = if (compact) 17.sp else TvType.button.fontSize,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 绘制支持焦点和键盘操作的圆形图标按钮。 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = TvDimens.iconButtonSize,
    focusRequester: FocusRequester? = null,
) {
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val interactionFocused by interaction.collectIsFocusedAsState()
    var focusObserved by remember { mutableStateOf(false) }
    val focused = interactionFocused || focusObserved
    val targetScale = when {
        pressed -> 0.94f
        focused -> 1.08f
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.82f, stiffness = 380f),
        label = "glass-icon-scale",
    )

    GlassSurface(
        modifier = modifier
            .size(size)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusProperties { canFocus = enabled }
            .onFocusChanged { focusObserved = it.isFocused }
            .semantics { this.contentDescription = contentDescription }
            .onPreviewKeyEvent { event ->
                if (enabled &&
                    event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.DirectionCenter)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable()
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = CircleShape,
        tint = Color(0x33000000),
        borderAlpha = if (focused) 0.95f else 0.28f,
        highlightAlpha = if (focused) 0.75f else 0.44f,
        shadow = if (focused) 26.dp else 10.dp,
        shadowColor = if (focused) Color.White.copy(alpha = 0.20f) else Color.Black,
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) Color.White else TvColors.textDisabled,
            modifier = Modifier.size(size * 0.38f),
        )
    }
}

/** 绘制播放控制栏中的播放或暂停主按钮。 */
@Composable
fun PrimaryPlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    focusRequester: FocusRequester? = null,
) {
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val interactionFocused by interaction.collectIsFocusedAsState()
    var focusObserved by remember { mutableStateOf(false) }
    val focused = interactionFocused || focusObserved
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.94f
            focused -> 1.08f
            else -> 1f
        },
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.82f, stiffness = 380f),
        label = "play-scale",
    )

    Box(
        modifier = modifier
            .size(size)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focusObserved = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.DirectionCenter)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable()
            .shadow(
                elevation = if (focused) 26.dp else 16.dp,
                shape = CircleShape,
                ambientColor = Color.Black,
                spotColor = Color.Black,
            )
            .clip(CircleShape)
            .background(Color.White)
            .border(
                width = 2.dp,
                color = if (focused) TvColors.pink.copy(alpha = 0.95f) else Color.Transparent,
                shape = CircleShape,
            )
            .semantics { contentDescription = if (isPlaying) "暂停" else "播放" }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isPlaying) {
                Icons.Rounded.Pause
            } else {
                Icons.Rounded.PlayArrow
            },
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

/** 绘制带有可选状态圆点的胶囊形标签。 */
@Composable
fun GlassPill(
    text: String,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    emphasized: Boolean = false,
) {
    GlassSurface(
        modifier = modifier,
        shape = CircleShape,
        tint = if (emphasized) Color(0x4D000000) else Color(0x26000000),
        borderAlpha = if (emphasized) 0.42f else 0.20f,
        highlightAlpha = 0.30f,
        shadow = 6.dp,
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (dotColor != null) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(dotColor, CircleShape)
                )
            }
            BasicText(
                text = text,
                style = TvType.caption.copy(
                    color = if (emphasized) TvColors.textPrimary else TvColors.textSecondary,
                    fontSize = 15.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 绘制支持焦点、选中状态和滚动定位的设置行。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusableRow(
    title: String,
    value: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    destructive: Boolean = false,
    showChevron: Boolean = true,
    focusRequester: FocusRequester? = null,
    rowHeight: Dp = 58.dp,
) {
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scope = rememberCoroutineScope()
    val bring = remember { BringIntoViewRequester() }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.012f else 1f,
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.85f, stiffness = 420f),
        label = "row-scale",
    )
    val shape = RoundedCornerShape(TvDimens.rowCorner)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(
                when {
                    focused && destructive -> TvColors.error.copy(alpha = 0.20f)
                    focused -> Color.White.copy(alpha = 0.13f)
                    pressed -> Color.White.copy(alpha = 0.06f)
                    selected -> Color.White.copy(alpha = 0.06f)
                    else -> Color.Transparent
                }
            )
            .bringIntoViewRequester(bring)
            .focusProperties { canFocus = enabled }
            .onFocusChanged {
                if (it.isFocused) {
                    scope.launch { bring.bringIntoView() }
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = title,
            style = TvType.body.copy(
                color = when {
                    !enabled -> TvColors.textDisabled
                    destructive -> if (focused) Color.White else TvColors.error
                    else -> TvColors.textPrimary
                }
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            BasicText(
                text = value,
                style = TvType.caption.copy(color = TvColors.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = TvColors.pink,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(20.dp),
            )
        } else if (showChevron) {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = TvColors.textTertiary,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(20.dp),
            )
        }
    }
}

/** 绘制设置页面中的标题和值。 */
@Composable
fun SettingsInfoRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = title,
            style = TvType.body.copy(color = TvColors.textSecondary, fontSize = 18.sp),
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        BasicText(
            text = value,
            style = TvType.caption.copy(color = TvColors.textTertiary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 绘制带主操作和可选次操作的错误提示层。 */
@Composable
fun GlassAlert(
    title: String,
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    code: String? = null,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { primaryFocus.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.58f)),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth(0.82f),
            tint = Color(0xB30B0B0F),
            borderAlpha = 0.48f,
            highlightAlpha = 0.50f,
            shadow = 40.dp,
            useBackdrop = true,
            blurRadius = 30.dp,
            lensHeight = 12.dp,
            lensAmount = 18.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 32.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BasicText(
                    text = title,
                    style = TvType.title.copy(fontSize = 28.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    text = message,
                    style = TvType.body.copy(color = TvColors.textSecondary, fontSize = 19.sp),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!code.isNullOrBlank()) {
                    BasicText(
                        text = code,
                        style = TvType.tiny.copy(color = TvColors.textTertiary),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (secondaryLabel != null && onSecondary != null) {
                        GlassButton(
                            text = secondaryLabel,
                            onClick = onSecondary,
                            enabled = true,
                        )
                    }
                    GlassButton(
                        text = primaryLabel,
                        onClick = onPrimary,
                        primary = !destructive,
                        destructive = destructive,
                        focusRequester = primaryFocus,
                    )
                }
            }
        }
    }
}

/** 绘制加载指示器。 */
@Composable
fun LoadingSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(38.dp),
        color = Color.White,
        strokeWidth = 2.5.dp,
    )
}

/** 绘制登录二维码位图。 */
@Composable
fun QrCodeImage(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "登录二维码",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}

/** 绘制带有呼吸动画的状态圆点。 */
@Composable
fun PulseDot(
    color: Color = TvColors.pink,
    size: Dp = 7.dp,
) {
    val reduceMotion = rememberReduceMotion()
    val alpha = if (reduceMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "pulse")
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse-alpha",
        )
        value
    }
    Box(
        Modifier
            .size(size)
            .background(color.copy(alpha = alpha), CircleShape)
    )
}

/** 绘制电视端环境背景和动态光晕。 */
@Composable
fun TvBackground(modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val noise = rememberNoiseBitmap()
    val t = if (reduceMotion) {
        0.5f
    } else {
        val transition = rememberInfiniteTransition(label = "ambient")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(28000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ambient-position",
        )
        value
    }

    androidx.compose.foundation.Canvas(modifier.fillMaxSize()) {
        drawRect(Color(0xFF06060A))

        val pinkCenter = Offset(size.width * (0.10f + 0.05f * t), size.height * 0.10f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x59FB7299), Color(0x22FB7299), Color.Transparent),
                center = pinkCenter,
                radius = size.maxDimension * 0.95f,
            ),
            radius = size.maxDimension * 0.95f,
            center = pinkCenter,
        )

        val blueCenter = Offset(size.width * (0.92f - 0.04f * t), size.height * 0.84f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x4D00A1D6), Color(0x1A00A1D6), Color.Transparent),
                center = blueCenter,
                radius = size.maxDimension * 0.72f,
            ),
            radius = size.maxDimension * 0.72f,
            center = blueCenter,
        )

        val violetCenter = Offset(size.width * 0.58f, size.height * (0.28f + 0.08f * t))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x338B5CF6), Color.Transparent),
                center = violetCenter,
                radius = size.maxDimension * 0.55f,
            ),
            radius = size.maxDimension * 0.55f,
            center = violetCenter,
        )

        // A soft cinematic spotlight behind the casting card.
        val spot = Offset(size.width * 0.72f, size.height * 0.18f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
                center = spot,
                radius = size.maxDimension * 0.42f,
            ),
            radius = size.maxDimension * 0.42f,
            center = spot,
        )

        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                center = size.center,
                radius = size.maxDimension * 0.78f,
            )
        )

        drawRect(
            brush = ShaderBrush(
                ImageShader(noise, TileMode.Repeated, TileMode.Repeated)
            ),
            alpha = 0.05f,
        )
    }
}

/** 将毫秒数格式化为播放器显示的时间文本。 */
fun formatTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0L) / 1000L
    val h = safe / 3600L
    val m = (safe % 3600L) / 60L
    val s = safe % 60L
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}
