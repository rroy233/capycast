package com.roy233.tvcast.core.playback

/** 根据媒体编解码和 DRM 属性选择播放后端。 */
object PlaybackBackendSelector {

    fun select(request: PlaybackRequest): PlaybackBackend = when {
        request.isDrm -> PlaybackBackend.MEDIA3
        request.video.isDolbyVision -> PlaybackBackend.MEDIA3
        request.video.isHdr -> PlaybackBackend.MEDIA3
        else -> PlaybackBackend.VLC
    }

    fun switchReason(request: PlaybackRequest): String = when {
        request.isDrm -> "switch-to-media3-widevine"
        request.video.isDolbyVision -> "switch-to-media3-dolby-vision"
        else -> "switch-to-media3-hdr"
    }
}
