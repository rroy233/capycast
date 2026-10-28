package com.roy233.tvcast.ui.designsystem

import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvDimens
import com.roy233.tvcast.ui.theme.TvType
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    selected: Boolean = false,
    selectedColor: Color = TvColors.pink,
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
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = CircleShape,
        tint = if (selected) selectedColor.copy(alpha = 0.30f) else Color(0x33000000),
        borderAlpha = when {
            focused -> 0.95f
            selected -> 0.72f
            else -> 0.28f
        },
        highlightAlpha = when {
            focused -> 0.75f
            selected -> 0.60f
            else -> 0.44f
        },
        shadow = if (focused) 26.dp else 10.dp,
        shadowColor = when {
            focused -> Color.White.copy(alpha = 0.20f)
            selected -> selectedColor.copy(alpha = 0.34f)
            else -> Color.Black
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = when {
                !enabled -> TvColors.textDisabled
                selected -> selectedColor
                else -> Color.White
            },
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

/** 绘制加载指示器。 */
@Composable
fun LoadingSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(38.dp),
        color = Color.White,
        strokeWidth = 2.5.dp,
    )
}
