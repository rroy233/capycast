package com.roy233.tvcast.feature.playback.presentation

/** 播放器支持的标准倍速档位与显示格式。 */
object PlaybackSpeed {
    val options: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    /** 将输入约束到受支持的标准档位；未知值回退到正常速度。 */
    fun normalize(value: Float): Float = options.firstOrNull { option ->
        kotlin.math.abs(option - value) < MATCH_EPSILON
    } ?: NORMAL

    /** 返回 Hub 使用的倍速标签。 */
    fun label(value: Float): String = "${normalize(value).stripTrailingZero()}×"

    private fun Float.stripTrailingZero(): String =
        if (this % 1f == 0f) toInt().toString() else toString()

    private const val NORMAL = 1f
    private const val MATCH_EPSILON = 0.001f
}
