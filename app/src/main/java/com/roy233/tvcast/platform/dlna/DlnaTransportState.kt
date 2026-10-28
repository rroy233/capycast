package com.roy233.tvcast.platform.dlna

/** 保存 DLNA 渲染器对外报告的传输状态。 */
internal class DlnaTransportState {
    /** 当前传输状态。 */
    @Volatile var transport: String = "NO_MEDIA_PRESENT"
    /** 当前媒体地址。 */
    @Volatile var uri: String = ""
    /** 当前 DIDL-Lite 元数据。 */
    @Volatile var metadata: String = ""
    /** 当前媒体标题。 */
    @Volatile var title: String = ""
    /** 当前媒体时长，单位为毫秒。 */
    @Volatile var durationMs: Long = 0L
    /** 当前音量值。 */
    @Volatile var volume: Int = 50
    /** 当前是否静音。 */
    @Volatile var mute: Boolean = false
}
