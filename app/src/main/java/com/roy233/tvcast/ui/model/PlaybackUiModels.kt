package com.roy233.tvcast.ui.model

/** 表示电视端播放器当前所处的显示与播放模式。 */
enum class TvMode { IDLE, LOADING, PLAYING, ERROR }

/** 表示播放控制面板的层级。 */
enum class PanelKind { ROOT, QUALITY, AUDIO, SPEED, DANMAKU, LIVE_QUALITY }

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

/**
 * 描述可展示给用户的错误信息及其恢复能力。
 *
 * @property title 错误标题。
 * @property message 错误详情。
 * @property code 可选的错误代码。
 * @property canRetry 是否允许用户执行重试。
 */
data class TvError(
    val title: String,
    val message: String,
    val code: String = "",
    val canRetry: Boolean = true,
)

/**
 * 描述一个可选的视频清晰度。
 *
 * @property qn 清晰度数值标识。
 * @property label 面向用户展示的名称。
 * @property detail 清晰度补充说明。
 * @property codecid 视频编码标识。
 */
data class TrackOption(
    val qn: Int,
    val label: String,
    val detail: String,
    val codecid: Int,
)

/**
 * 描述一个可选的直播画质档位。
 *
 * @property qn 直播画质数值标识。
 * @property label 面向用户展示的名称，例如“原画”。
 */
data class LiveQualityOption(
    val qn: Int,
    val label: String,
)

/**
 * 描述一个可选的音轨。
 *
 * @property id 音轨标识。
 * @property label 面向用户展示的名称。
 */
data class AudioOption(
    val id: Int,
    val label: String,
)
