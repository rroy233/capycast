package com.roy233.tvcast.feature.subtitle.domain

/**
 * 字幕显示设置。
 *
 * 只在本地保存显示偏好；具体视频的轨道选择不持久化，避免换片后错误恢复。
 *
 * @property enabled 是否显示字幕。
 * @property autoAiEnabled 没有人工字幕时是否自动启用 AI 字幕。
 * @property fontScale 字号倍率。
 * @property bottomMarginRatio 底边距占视口高度的比例。
 * @property offsetMs 时间偏移，正数表示延后显示。
 */
data class SubtitleSettings(
    val enabled: Boolean = true,
    val autoAiEnabled: Boolean = false,
    val fontScale: Float = 1f,
    val bottomMarginRatio: Float = 0.06f,
    val offsetMs: Long = 0L,
) {
    /** 收敛到合法范围，非法输入回退到默认值。 */
    fun sanitized(): SubtitleSettings =
        copy(
            fontScale =
                if (fontScale > 0f) fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE) else 1f,
            bottomMarginRatio =
                if (bottomMarginRatio > 0f) {
                    bottomMarginRatio.coerceIn(MIN_BOTTOM_MARGIN, MAX_BOTTOM_MARGIN)
                } else {
                    DEFAULT_BOTTOM_MARGIN
                },
            offsetMs = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS),
        )

    companion object {
        /** 最小字号倍率。 */
        const val MIN_FONT_SCALE = 0.6f
        /** 最大字号倍率。 */
        const val MAX_FONT_SCALE = 2f
        /** 默认底边距比例。 */
        const val DEFAULT_BOTTOM_MARGIN = 0.06f
        /** 最小底边距比例。 */
        const val MIN_BOTTOM_MARGIN = 0.02f
        /** 最大底边距比例。 */
        const val MAX_BOTTOM_MARGIN = 0.3f
        /** 时间偏移上限，单位毫秒。 */
        const val MAX_OFFSET_MS = 5_000L
    }
}
