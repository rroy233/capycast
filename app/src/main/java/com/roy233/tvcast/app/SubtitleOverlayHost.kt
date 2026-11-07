package com.roy233.tvcast.app

import com.roy233.tvcast.feature.subtitle.domain.SubtitleRenderer

/**
 * 字幕行到界面的宿主适配器。
 *
 * 只在内容变化时回调，避免同步驱动器每次采样都触发界面更新。
 *
 * @param onLines 界面层写入字幕行；空列表表示清屏。
 */
class SubtitleOverlayHost(
    private val onLines: (List<String>) -> Unit,
) : SubtitleRenderer {

    private var lines: List<String> = emptyList()

    override fun show(lines: List<String>) {
        if (this.lines == lines) return
        this.lines = lines
        onLines(lines)
    }

    override fun clear() {
        if (lines.isEmpty()) return
        lines = emptyList()
        onLines(emptyList())
    }
}
