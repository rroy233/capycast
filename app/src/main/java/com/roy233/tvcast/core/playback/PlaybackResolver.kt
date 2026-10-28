package com.roy233.tvcast.core.playback

/** 将投屏内容身份解析为稳定播放请求的应用端口。 */
interface PlaybackResolver {
    suspend fun resolveVod(
        aid: Long,
        cid: Long,
        bvid: String,
        title: String,
        epId: Long = 0L,
        seasonId: Long = 0L,
    ): PlaybackRequest
}
