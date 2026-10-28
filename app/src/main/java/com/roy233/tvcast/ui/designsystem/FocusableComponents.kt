package com.roy233.tvcast.ui.designsystem

import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvDimens
import com.roy233.tvcast.ui.theme.TvType
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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
