package com.roy233.tvcast.ui.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode

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
