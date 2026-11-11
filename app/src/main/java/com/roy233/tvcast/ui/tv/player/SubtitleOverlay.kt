package com.roy233.tvcast.ui.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roy233.tvcast.feature.subtitle.domain.SubtitleSettings
import com.roy233.tvcast.ui.theme.TvType

/**
 * 在视频画面底部居中显示当前字幕。
 *
 * 底边距按视口高度比例计算，与弹幕层共用同一播放视口；不参与焦点与点击。
 *
 * @param lines 当前时刻的字幕行，最多两行。
 * @param fontScale 用户字号倍率。
 * @param bottomMarginRatio 底边距比例。
 */
@Composable
internal fun SubtitleOverlay(
    lines: List<String>,
    fontScale: Float,
    bottomMarginRatio: Float,
) {
    if (lines.isEmpty()) return
    val scale = fontScale.coerceIn(SubtitleSettings.MIN_FONT_SCALE, SubtitleSettings.MAX_FONT_SCALE)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bottomInset =
            maxHeight * bottomMarginRatio.coerceIn(0f, SubtitleSettings.MAX_BOTTOM_MARGIN)
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = HORIZONTAL_PADDING)
                    .padding(bottom = bottomInset),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LINE_GAP),
        ) {
            lines.forEach { line ->
                BasicText(
                    text = line,
                    style =
                        TvType.bodyMedium.copy(
                            fontSize = (BASE_FONT_SP * scale).sp,
                            lineHeight = (BASE_LINE_HEIGHT_SP * scale).sp,
                            textAlign = TextAlign.Center,
                            color = Color.White,
                        ),
                    maxLines = MAX_LINES_PER_ITEM,
                    modifier =
                        Modifier.background(
                                color = Color.Black.copy(alpha = BACKGROUND_ALPHA),
                                shape = RoundedCornerShape(CORNER_RADIUS),
                            )
                            .padding(
                                horizontal = ITEM_HORIZONTAL_PADDING,
                                vertical = ITEM_VERTICAL_PADDING,
                            ),
                )
            }
        }
    }
}

/** 基准字号，单位 sp。 */
private const val BASE_FONT_SP = 30f

/** 基准行高，单位 sp。 */
private const val BASE_LINE_HEIGHT_SP = 38f

/** 单条字幕最多显示行数。 */
private const val MAX_LINES_PER_ITEM = 2

/** 字幕背景不透明度。 */
private const val BACKGROUND_ALPHA = 0.55f

/** 字幕左右安全边距。 */
private val HORIZONTAL_PADDING = 48.dp

/** 多行字幕之间的间距。 */
private val LINE_GAP = 6.dp

/** 字幕背景圆角。 */
private val CORNER_RADIUS = 8.dp

/** 单行字幕背景的横向内边距。 */
private val ITEM_HORIZONTAL_PADDING = 12.dp

/** 单行字幕背景的纵向内边距。 */
private val ITEM_VERTICAL_PADDING = 4.dp
