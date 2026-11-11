package com.roy233.tvcast.feature.subtitle.domain

/** 字幕渲染端口，实现负责把当前行显示在视频内容区上。 */
interface SubtitleRenderer {
    /** 显示当前时刻的字幕行；空列表表示没有可显示内容。 */
    fun show(lines: List<String>)

    /** 清空字幕。 */
    fun clear()
}
