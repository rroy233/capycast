package com.roy233.tvcast.ui.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 电视端模态卡片的统一尺寸档位；全屏仅用于遮罩与焦点隔离，不用于卡片本身。 */
enum class TvModalCardKind(
    internal val maxWidth: androidx.compose.ui.unit.Dp,
    internal val widthFraction: Float,
    internal val maxHeight: androidx.compose.ui.unit.Dp,
) {
    STANDARD(maxWidth = 640.dp, widthFraction = 0.72f, maxHeight = 500.dp),
    DOCUMENT(maxWidth = 760.dp, widthFraction = 0.76f, maxHeight = 540.dp),
    PANEL(maxWidth = 660.dp, widthFraction = 0.74f, maxHeight = 500.dp),
}

/** 将可见模态卡片限制在适合电视观看距离的尺寸内。 */
fun Modifier.tvModalCard(kind: TvModalCardKind = TvModalCardKind.STANDARD): Modifier =
    widthIn(max = kind.maxWidth).fillMaxWidth(kind.widthFraction).heightIn(max = kind.maxHeight)
