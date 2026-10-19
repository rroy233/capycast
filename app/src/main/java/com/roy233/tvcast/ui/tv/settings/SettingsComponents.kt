package com.roy233.tvcast.ui.tv.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType

/** 设置页的分组容器：标题说明范围，卡片统一承载组内条目。 */
@Composable
internal fun SettingsSection(title: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth()) {
        BasicText(
            text = title,
            style = TvType.caption.copy(
                color = TvColors.textSecondary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.padding(start = 4.dp, bottom = 9.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color.White.copy(alpha = 0.045f))
                .border(1.dp, Color.White.copy(alpha = 0.09f), shape),
        ) {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                content()
            }
        }
    }
}

/** 展示只读信息；可通过遥控器获取焦点以便逐条导航，但不响应确认操作。 */
@Composable
internal fun SettingsInfoRow(title: String, value: String) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.012f else 1f,
        animationSpec = spring(dampingRatio = 0.84f, stiffness = 420f),
        label = "settings-info-focus",
    )
    val focusShape = RoundedCornerShape(14.dp)
    if (value.length > 34) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(78.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(focusShape)
                .background(if (focused) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                .semantics(mergeDescendants = true) { }
                .focusable(interactionSource = interaction)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            BasicText(
                text = title,
                style = TvType.caption.copy(color = TvColors.textSecondary, fontSize = 17.sp),
                maxLines = 1,
            )
            BasicText(
                text = value,
                style = TvType.body.copy(color = TvColors.textPrimary, fontSize = 18.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(focusShape)
            .background(if (focused) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .semantics(mergeDescendants = true) { }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        BasicText(
            text = title,
            style = TvType.body.copy(color = TvColors.textSecondary, fontSize = 20.sp),
            modifier = Modifier.weight(0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BasicText(
            text = value,
            style = TvType.body.copy(color = TvColors.textPrimary, fontSize = 20.sp),
            modifier = Modifier.weight(1.1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 设置开关整行均可通过遥控器切换，状态同时以文字和滑块位置呈现。 */
@Composable
internal fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val thumbOffset by animateFloatAsState(
        targetValue = if (checked) 30f else 2f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
        label = "settings-toggle-thumb",
    )
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.012f else 1f,
        animationSpec = spring(dampingRatio = 0.84f, stiffness = 420f),
        label = "settings-toggle-focus",
    )
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .focusable(interactionSource = interaction)
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = title,
            style = TvType.body.copy(color = TvColors.textPrimary, fontSize = 21.sp),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .width(62.dp)
                .height(34.dp)
                .clip(CircleShape)
                .background(if (checked) TvColors.pink else Color.White.copy(alpha = 0.20f)),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = thumbOffset.dp)
                    .size(28.dp)
                    .background(Color.White, CircleShape),
            )
        }
        Spacer(Modifier.width(12.dp))
        BasicText(
            text = if (checked) "开启" else "关闭",
            style = TvType.caption.copy(
                color = if (checked) TvColors.textPrimary else TvColors.textSecondary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
            ),
            modifier = Modifier.width(42.dp),
            maxLines = 1,
        )
    }
}

/** 组内分隔线，缩进以对应行文本起始位置。 */
@Composable
internal fun SettingsRowDivider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.08f)),
    )
}
