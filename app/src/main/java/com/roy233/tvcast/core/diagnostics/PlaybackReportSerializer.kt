package com.roy233.tvcast.core.diagnostics

/** 生成诊断报告中的事件文本。 */
internal object PlaybackReportSerializer {

    /**
     * 生成一条带相对时间与会话状态的事件行。
     *
     * @param elapsedMs 会话开始以来的毫秒数。
     * @param name 事件名称。
     * @param detail 事件细节，会被脱敏并截断。
     * @param maxDetail 细节保留的最大字符数。
     * @return 可直接写入报告的事件行。
     */
    fun formatEvent(elapsedMs: Long, name: String, detail: String, maxDetail: Int): String {
        val safeDetail = LogRedaction.text(detail).take(maxDetail)
        return "+${elapsedMs}ms $name" + if (safeDetail.isBlank()) "" else " $safeDetail"
    }
}
