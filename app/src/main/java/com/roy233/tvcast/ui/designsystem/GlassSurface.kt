package com.roy233.tvcast.ui.designsystem

import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import com.roy233.tvcast.ui.theme.TvColors
import com.roy233.tvcast.ui.theme.TvDimens
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
internal fun rememberNoiseBitmap(): ImageBitmap {
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
