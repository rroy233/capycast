package com.roy233.tvcast.ui.designsystem

import android.graphics.Bitmap
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvType
import com.roy233.tvcast.ui.tv.tvModalFocusRegion
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
            .background(Color.Black.copy(alpha = 0.58f))
            .tvModalFocusRegion(),
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
