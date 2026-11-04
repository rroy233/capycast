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
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow

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

/** 统一液态玻璃的使用场景，页面仅选择材质而不自行拼装渲染参数。 */
enum class GlassMaterial {
    /** 悬浮导航与侧边菜单。 */
    Navigation,
    /** 弹窗、登录卡片和设置面板。 */
    Panel,
    /** 按钮、胶囊和轻量操作控件。 */
    Control,
    /** 播放 HUD；不尝试采样独立的视频 Surface。 */
    Hud,
}

/** 按控件短边缩放玻璃的模糊和透镜强度。 */
enum class GlassSize(val effectScale: Float) {
    Compact(0.72f),
    Standard(1f),
    Large(1.22f),
}

private data class GlassMaterialSpec(
    val tint: Color,
    val borderAlpha: Float,
    val highlightAlpha: Float,
    val shadow: Dp,
    val blurRadius: Dp,
    val supportsBackdrop: Boolean,
)

private fun GlassMaterial.specification(): GlassMaterialSpec = when (this) {
    GlassMaterial.Navigation -> GlassMaterialSpec(
        tint = Color(0xD6151D26),
        borderAlpha = 0.38f,
        highlightAlpha = 0.24f,
        shadow = 30.dp,
        blurRadius = 42.dp,
        supportsBackdrop = true,
    )
    GlassMaterial.Panel -> GlassMaterialSpec(
        tint = Color(0x70151D26),
        borderAlpha = 0.32f,
        highlightAlpha = 0.34f,
        shadow = 24.dp,
        blurRadius = 26.dp,
        supportsBackdrop = true,
    )
    GlassMaterial.Control -> GlassMaterialSpec(
        tint = Color(0x35E7EBF5),
        borderAlpha = 0.24f,
        highlightAlpha = 0.28f,
        shadow = 8.dp,
        blurRadius = 16.dp,
        supportsBackdrop = true,
    )
    GlassMaterial.Hud -> GlassMaterialSpec(
        // Video is composed on a separate Surface; use a translucent dark frost for white controls.
        tint = Color(0x5C18202C),
        borderAlpha = 0.36f,
        highlightAlpha = 0.38f,
        shadow = 30.dp,
        blurRadius = 0.dp,
        supportsBackdrop = false,
    )
}

/** 绘制带有玻璃材质、边框、高光和可选背景模糊的容器。 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    material: GlassMaterial = GlassMaterial.Control,
    size: GlassSize = GlassSize.Standard,
    shape: Shape = RoundedCornerShape(TvDimens.cardCorner),
    accentColor: Color? = null,
    emphasized: Boolean = false,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val noise = rememberNoiseBitmap()
    val backdrop = LocalGlassBackdrop.current
    val spec = material.specification()
    val tint = accentColor?.copy(alpha = if (emphasized) 0.18f else 0.12f) ?: spec.tint
    val borderAlpha = if (emphasized) (spec.borderAlpha + 0.22f).coerceAtMost(0.70f) else spec.borderAlpha
    val highlightAlpha = if (emphasized) (spec.highlightAlpha + 0.18f).coerceAtMost(0.60f) else spec.highlightAlpha
    val shadow = if (emphasized) spec.shadow + 5.dp else spec.shadow
    val canUseBackdrop = spec.supportsBackdrop &&
        backdrop != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val base = modifier
        .shadow(
            elevation = shadow,
            shape = shape,
            clip = false,
            ambientColor = Color.Black,
            spotColor = Color.Black,
        )
        .clip(shape)

    val drawn = if (canUseBackdrop) {
        base.drawBackdrop(
            backdrop = backdrop!!,
            shape = { shape },
            effects = {
                vibrancy()
                blur((spec.blurRadius * size.effectScale).toPx())
            },
            highlight = {
                Highlight(width = 0.7.dp, alpha = highlightAlpha, style = HighlightStyle.Ambient)
            },
            shadow = null,
            innerShadow = {
                InnerShadow(radius = 12.dp, offset = DpOffset(0.dp, 3.dp), color = Color.Black.copy(alpha = 0.08f))
            },
            onDrawSurface = {
                drawGlassSurface(tint, highlightAlpha, noise)
            },
        )
    } else {
        base.drawWithContent {
            drawGlassSurface(tint, highlightAlpha, noise)
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
) {
    drawRect(tint)

    drawRect(
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.055f),
                Color.White.copy(alpha = 0.012f),
                Color.Transparent,
            )
        )
    )
    drawRect(
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = highlightAlpha * 0.12f),
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
        alpha = 0.018f,
    )
    drawRect(
        Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.07f)),
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
