package com.roy233.tvcast.core.playback

/** 根据媒体交付形态、编解码和 DRM 属性选择播放后端。 */
object PlaybackBackendSelector {

    fun select(request: PlaybackRequest): PlaybackBackend = when {
        // B 站 DASH 把音视频拆为独立 fMP4 表示层。Media3 用同一时间线管理两路
        // 的缓冲与定位，避免 VLC input-slave 在 seek 后发生音视频时钟漂移。
        !request.isLive && request.progressiveUrl == null -> PlaybackBackend.MEDIA3
        request.isDrm -> PlaybackBackend.MEDIA3
        request.video.isDolbyVision -> PlaybackBackend.MEDIA3
        request.video.isHdr -> PlaybackBackend.MEDIA3
        else -> PlaybackBackend.VLC
    }

    fun switchReason(request: PlaybackRequest): String = when {
        request.isDrm -> "switch-to-media3-widevine"
        request.video.isDolbyVision -> "switch-to-media3-dolby-vision"
        request.video.isHdr -> "switch-to-media3-hdr"
        else -> "switch-to-media3-dash"
    }
}
