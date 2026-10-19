package com.roy233.tvcast.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * tvOS-inspired tokens.
 *
 * The type scale is deliberately restrained: on a TV the hero is large, but
 * controls and labels stay close to the 10-foot minimum rather than turning
 * into oversized Android buttons.
 */
object TvColors {
    /** 基础黑色背景。 */
    val black = Color(0xFF000000)
    /** 页面主表面颜色。 */
    val surface = Color(0xFF08080C)
    /** 提升层级后的表面颜色。 */
    val elevated = Color(0xFF14151A)

    /** 主要文本颜色。 */
    val textPrimary = Color.White
    /** 次要文本颜色。 */
    val textSecondary = Color(0xB8FFFFFF)
    /** 辅助文本颜色。 */
    val textTertiary = Color(0x8AFFFFFF)
    /** 禁用状态文本颜色。 */
    val textDisabled = Color(0x4DFFFFFF)

    /** 品牌粉色。 */
    val pink = Color(0xFFFB7299)
    /** 品牌深粉色。 */
    val pinkDeep = Color(0xFFF25D8A)
    /** 品牌蓝色。 */
    val blue = Color(0xFF00A1D6)
    /** 辅助紫色。 */
    val violet = Color(0xFF8B5CF6)

    /** 错误状态颜色。 */
    val error = Color(0xFFFF453A)
    /** 成功状态颜色。 */
    val success = Color(0xFF30D158)
    /** 警告状态颜色。 */
    val warning = Color(0xFFFFD60A)

    // Transparent enough that the aurora actually reads through the sheet.
    /** 玻璃表面的基础半透明色。 */
    val glassTint = Color(0x2E000000)
    /** 玻璃表面的增强半透明色。 */
    val glassTintStrong = Color(0x4D0B0B0F)
    /** 玻璃表面边框颜色。 */
    val glassBorder = Color(0x66FFFFFF)
    /** 玻璃表面高光颜色。 */
    val glassHighlight = Color(0x99FFFFFF)
}

/** 电视界面使用的文字样式集合。 */
object TvType {
    /** 默认文字字体。 */
    private val family = FontFamily.SansSerif

    /** 首页主标题样式。 */
    val hero = TextStyle(
        fontFamily = family,
        fontSize = 52.sp,
        lineHeight = 58.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.8).sp,
        color = TvColors.textPrimary,
    )

    /** 页面标题样式。 */
    val title = TextStyle(
        fontFamily = family,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.SemiBold,
        color = TvColors.textPrimary,
    )

    /** 区块标题样式。 */
    val section = TextStyle(
        fontFamily = family,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Medium,
        color = TvColors.textPrimary,
    )

    /** 正文样式。 */
    val body = TextStyle(
        fontFamily = family,
        fontSize = 21.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Normal,
        color = TvColors.textPrimary,
    )

    /** 中等字重正文样式。 */
    val bodyMedium = TextStyle(
        fontFamily = family,
        fontSize = 21.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Medium,
        color = TvColors.textPrimary,
    )

    /** 按钮文本样式。 */
    val button = TextStyle(
        fontFamily = family,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
        color = TvColors.textPrimary,
    )

    /** 辅助说明文字样式。 */
    val caption = TextStyle(
        fontFamily = family,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        color = TvColors.textSecondary,
    )

    /** 小号说明文字样式。 */
    val tiny = TextStyle(
        fontFamily = family,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        color = TvColors.textTertiary,
    )
}

/** 电视界面使用的尺寸常量集合。 */
object TvDimens {
    /** 水平方向的安全区域边距，约为 960dp 电视布局宽度的 5%。 */
    val safeHorizontal = 48.dp
    /** 垂直方向的安全区域边距。 */
    val safeVertical = 28.dp
    /** 卡片圆角尺寸。 */
    val cardCorner = 28.dp
    /** 列表行圆角尺寸。 */
    val rowCorner = 14.dp
    /** 玻璃表面边框宽度。 */
    val glassBorder = 1.dp
    /** 标准按钮高度。 */
    val buttonHeight = 58.dp
    /** 图标按钮尺寸。 */
    val iconButtonSize = 56.dp
}
