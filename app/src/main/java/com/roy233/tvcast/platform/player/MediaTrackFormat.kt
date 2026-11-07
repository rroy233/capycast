package com.roy233.tvcast.platform.player

/** 将服务端轨道编码标识转换为稳定的诊断文本。 */
internal object MediaTrackFormat {
    fun codecName(codecId: Int): String =
        when (codecId) {
            7 -> "H.264"
            12 -> "HEVC"
            13 -> "AV1"
            else -> "codec$codecId"
        }
}
