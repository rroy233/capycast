package com.roy233.tvcast.ui.designsystem

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.roy233.tvcast.R

private val HOME_BACKGROUND = Color(0xFF09141B)
private val HOME_KEY_LIGHT = Color(0xFFCEF4FF)
private val HOME_FILL_LIGHT = Color(0xFF257C9B)
private val HOME_GLASS_TINT = Color(0xFFD9F2FA)
private val HOME_VIGNETTE = Color(0xFF02080C)
private val FROSTED_GLASS_TOP = Color(0xFFF9FDFF)
private val FROSTED_GLASS_MIDDLE = Color(0xFFD7F2FC)
private val FROSTED_GLASS_BOTTOM = Color(0xFF8DC9E0)
private val TV_WATERMARK = Color(0xFFEAF8FF)

private data class SvgPath(
    val path: Path,
    val strokeWidth: Float?,
)

private data class SvgVector(
    val viewBoxWidth: Float,
    val viewBoxHeight: Float,
    val paths: List<SvgPath>,
) {
    val aspectRatio: Float
        get() = viewBoxWidth / viewBoxHeight
}

private val SVG_VIEWBOX_PATTERN = Regex("\\bviewBox\\s*=\\s*\"([^\"]+)\"")
private val SVG_PATH_PATTERN = Regex(
    "<path\\b([^>]*)>",
    RegexOption.DOT_MATCHES_ALL,
)
private val SVG_PATH_DATA_PATTERN = Regex(
    "\\bd\\s*=\\s*\"([^\"]+)\"",
    RegexOption.DOT_MATCHES_ALL,
)
private val SVG_STROKE_WIDTH_PATTERN = Regex("\\bstroke-width\\s*=\\s*\"([^\"]+)\"")

/** 绘制具有雾化玻璃与体积光的电影感主页背景。 */
@Composable
internal fun HomeAmbientBackground(modifier: Modifier = Modifier) {
    val noise = rememberNoiseBitmap()
    val reduceMotion = rememberReduceMotion()
    val lightPhase: Float
    val beamPhase: Float
    if (reduceMotion) {
        lightPhase = 0.5f
        beamPhase = 0.5f
    } else {
        val transition = rememberInfiniteTransition(label = "cinematic-light")
        val drift by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(32000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "cinematic-light-drift",
        )
        val breath by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(21000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "cinematic-light-breath",
        )
        lightPhase = drift
        beamPhase = breath
    }

    androidx.compose.foundation.Canvas(modifier.fillMaxSize()) {
        drawRect(HOME_BACKGROUND)

        val keyLightCenter = Offset(
            size.width * (1.08f - lightPhase * 0.08f),
            size.height * (-0.17f + lightPhase * 0.10f),
        )
        val keyLightRadius = size.maxDimension * 1.04f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    HOME_KEY_LIGHT.copy(alpha = 0.18f + beamPhase * 0.06f),
                    HOME_KEY_LIGHT.copy(alpha = 0.05f + beamPhase * 0.03f),
                    Color.Transparent,
                ),
                center = keyLightCenter,
                radius = keyLightRadius,
            ),
            radius = keyLightRadius,
            center = keyLightCenter,
        )

        val fillLightCenter = Offset(
            size.width * (-0.20f + lightPhase * 0.08f),
            size.height * (1.12f - lightPhase * 0.08f),
        )
        val fillLightRadius = size.maxDimension * 0.88f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(HOME_FILL_LIGHT.copy(alpha = 0.12f + beamPhase * 0.04f), Color.Transparent),
                center = fillLightCenter,
                radius = fillLightRadius,
            ),
            radius = fillLightRadius,
            center = fillLightCenter,
        )

        val beamOffset = size.width * (beamPhase - 0.5f) * 0.05f
        val lightBeam = Path().apply {
            moveTo(size.width * 0.74f + beamOffset, -size.height * 0.10f)
            lineTo(size.width * 1.18f + beamOffset, -size.height * 0.10f)
            lineTo(size.width * 0.52f + beamOffset, size.height * 1.10f)
            lineTo(size.width * 0.24f + beamOffset, size.height * 1.10f)
            close()
        }
        drawPath(
            path = lightBeam,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    HOME_KEY_LIGHT.copy(alpha = 0.035f),
                    HOME_GLASS_TINT.copy(alpha = 0.075f + beamPhase * 0.07f),
                    Color.Transparent,
                ),
                start = Offset(size.width * 0.86f, 0f),
                end = Offset(size.width * 0.39f, size.height),
            ),
        )
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    HOME_GLASS_TINT.copy(alpha = 0.065f),
                    Color.Transparent,
                    HOME_GLASS_TINT.copy(alpha = 0.02f),
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, HOME_VIGNETTE.copy(alpha = 0.60f)),
                center = size.center,
                radius = size.maxDimension * 0.82f,
            )
        )
        drawRect(
            brush = ShaderBrush(ImageShader(noise, TileMode.Repeated, TileMode.Repeated)),
            alpha = 0.028f,
        )
    }
}

/**
 * 以磨砂玻璃材质绘制前景 Bilibili 字标。
 */
@Composable
internal fun BilibiliLogo(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val logo = remember(context) { context.readSvgVector(R.raw.bilibili_logo) }
    val noise = rememberNoiseBitmap()
    val reduceMotion = rememberReduceMotion()
    val sheenProgress = if (reduceMotion) {
        -0.32f
    } else {
        val transition = rememberInfiniteTransition(label = "wordmark-sheen")
        val progress by transition.animateFloat(
            initialValue = -0.32f,
            targetValue = 1.32f,
            animationSpec = infiniteRepeatable(
                animation = tween(9000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "wordmark-sheen-position",
        )
        progress
    }

    androidx.compose.foundation.Canvas(modifier.aspectRatio(logo.aspectRatio)) {
        withTransform({
            scale(
                scaleX = size.width / logo.viewBoxWidth,
                scaleY = size.height / logo.viewBoxHeight,
                pivot = Offset.Zero,
            )
        }) {
            val fill = Brush.verticalGradient(
                0f to FROSTED_GLASS_TOP.copy(alpha = 0.52f),
                0.52f to FROSTED_GLASS_MIDDLE.copy(alpha = 0.35f),
                1f to FROSTED_GLASS_BOTTOM.copy(alpha = 0.26f),
                startY = 0f,
                endY = logo.viewBoxHeight,
            )
            val sheenCenter = logo.viewBoxWidth * sheenProgress
            val sheen = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.06f),
                    Color.White.copy(alpha = 0.34f),
                    Color.White.copy(alpha = 0.06f),
                    Color.Transparent,
                ),
                start = Offset(sheenCenter - logo.viewBoxWidth * 0.16f, logo.viewBoxHeight),
                end = Offset(sheenCenter + logo.viewBoxWidth * 0.16f, 0f),
            )
            val logoSize = Size(logo.viewBoxWidth, logo.viewBoxHeight)

            logo.paths.forEach { svgPath ->
                clipPath(svgPath.path) {
                    drawRect(brush = fill, size = logoSize)
                    drawRect(
                        brush = ShaderBrush(ImageShader(noise, TileMode.Repeated, TileMode.Repeated)),
                        size = logoSize,
                        alpha = 0.045f,
                    )
                    drawRect(brush = sheen, size = logoSize)
                }
                drawPath(
                    path = svgPath.path,
                    color = Color.White.copy(alpha = 0.28f),
                    style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

/** 绘制侧边菜单使用的 Bilibili 小电视标识。 */
@Composable
internal fun BilibiliTvLogo(
    modifier: Modifier = Modifier,
    color: Color = TV_WATERMARK,
) {
    Image(
        painter = painterResource(R.drawable.bilibili_tv_logo),
        contentDescription = null,
        colorFilter = ColorFilter.tint(color),
        modifier = modifier,
    )
}

private fun Context.readSvgVector(rawResource: Int): SvgVector {
    val svg = resources.openRawResource(rawResource)
        .bufferedReader()
        .use { it.readText() }
    val viewBox = SVG_VIEWBOX_PATTERN.find(svg)?.groupValues?.get(1)
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.map(String::toFloat)
        ?: error("SVG resource $rawResource must define a viewBox.")
    require(viewBox.size == 4) { "SVG resource $rawResource has an invalid viewBox." }

    val paths = SVG_PATH_PATTERN.findAll(svg).map { match ->
        val attributes = match.groupValues[1]
        val pathData = SVG_PATH_DATA_PATTERN.find(attributes)?.groupValues?.get(1)
            ?: error("SVG resource $rawResource contains a path without data.")
        val strokeWidth = SVG_STROKE_WIDTH_PATTERN.find(attributes)?.groupValues?.get(1)?.toFloat()
        SvgPath(
            path = PathParser().parsePathString(pathData).toPath(),
            strokeWidth = strokeWidth,
        )
    }.toList()
    require(paths.isNotEmpty()) { "SVG resource $rawResource must contain at least one path." }
    return SvgVector(
        viewBoxWidth = viewBox[2],
        viewBoxHeight = viewBox[3],
        paths = paths,
    )
}

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
