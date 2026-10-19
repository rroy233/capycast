package com.roy233.tvcast.platform.player

import org.videolan.libvlc.interfaces.IMedia

/** 将 libVLC 的轨道和编码标识转换为稳定的诊断文本。 */
internal object VlcTrackFormat {

    /**
     * 将编码标识转换为可读名称。
     *
     * @param codecid libVLC 的编码标识。
     * @return 编码名称。
     */
    fun codecName(codecid: Int): String = when (codecid) {
        7 -> "H.264"
        12 -> "HEVC"
        13 -> "AV1"
        else -> "codec$codecid"
    }

    /**
     * 将轨道类型转换为可读名称。
     *
     * @param type libVLC 的轨道类型。
     * @return 轨道类型名称。
     */
    fun trackTypeName(type: Int): String = when (type) {
        IMedia.Track.Type.Video -> "video"
        IMedia.Track.Type.Audio -> "audio"
        IMedia.Track.Type.Text -> "text"
        else -> "unknown($type)"
    }
}
