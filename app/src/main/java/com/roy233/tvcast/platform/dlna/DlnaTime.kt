package com.roy233.tvcast.platform.dlna

import java.util.Locale

/** 提供 DLNA 时间文本与毫秒值之间的转换。 */
object DlnaTime {

    /**
     * 将毫秒数格式化为 `HH:mm:ss`。
     *
     * @param ms 待格式化的毫秒数。
     * @return 固定格式的时间文本。
     */
    fun format(ms: Long): String {
        val total = (ms.coerceAtLeast(0L)) / 1000L
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            total / 3600,
            (total % 3600) / 60,
            total % 60,
        )
    }

    /**
     * 解析 `HH:mm:ss`、`mm:ss` 或秒数文本。
     *
     * @param text 待解析的时间文本。
     * @return 解析得到的毫秒数，输入为空或无效时返回 0。
     */
    fun parse(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val nums = text.trim().split(':').map { it.toDoubleOrNull() ?: 0.0 }
        val sec = when (nums.size) {
            3 -> nums[0] * 3600 + nums[1] * 60 + nums[2]
            2 -> nums[0] * 60 + nums[1]
            1 -> nums[0]
            else -> 0.0
        }
        return (sec * 1000).toLong().coerceAtLeast(0L)
    }

    /** 从 DIDL-Lite 元数据的 duration 属性中解析媒体时长。 */
    fun parseDidlDuration(metadata: String): Long {
        val m = Regex("duration=\"([^\"]+)\"").find(metadata) ?: return 0L
        return parse(m.groupValues[1])
    }
}
