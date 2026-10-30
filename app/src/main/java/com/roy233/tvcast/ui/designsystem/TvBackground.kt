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

/** 绘制低饱和影院式环境背景。 */
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
        drawRect(Color(0xFF090C10))

        // A quiet pool of light gives the empty state depth without coloring the glass.
        val spot = Offset(size.width * (0.68f + 0.02f * t), size.height * 0.20f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF8FA6B5).copy(alpha = 0.12f), Color.Transparent),
                center = spot,
                radius = size.maxDimension * 0.48f,
            ),
            radius = size.maxDimension * 0.48f,
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
