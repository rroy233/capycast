package com.roy233.tvcast.ui.designsystem


/** 将毫秒数格式化为播放器显示的时间文本。 */
fun formatTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0L) / 1000L
    val h = safe / 3600L
    val m = (safe % 3600L) / 60L
    val s = safe % 60L
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}
