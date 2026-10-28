package com.roy233.tvcast.feature.playback.model

/** 通过登录会话与直播间身份取得播放候选。 */
interface LivePlaybackResolver {
    suspend fun resolve(accessKey: String, roomId: Long, quality: Int? = null, forceAvc: Boolean = false): LivePlayInfo
}
