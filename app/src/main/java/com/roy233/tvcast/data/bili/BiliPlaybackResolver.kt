package com.roy233.tvcast.data.bili

import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.core.playback.PlaybackResolver
import com.roy233.tvcast.data.bili.mapper.BiliPlaybackMapper

/** B 站普通视频与 PGC 协议的播放请求 adapter。 */
class BiliPlaybackResolver(
    private val pgcResolver: PgcPlaybackResolver = PgcPlaybackResolver(),
) : PlaybackResolver {
    override suspend fun resolveVod(
        aid: Long,
        cid: Long,
        bvid: String,
        title: String,
        epId: Long,
        seasonId: Long,
    ): PlaybackRequest {
        val response = if (epId > 0L || seasonId > 0L) {
            pgcResolver.resolve(epId, seasonId, cid, aid, bvid, title)
        } else {
            BiliApi.resolveByAidCid(aid, cid, title, bvid)
        }
        return BiliPlaybackMapper.map(response)
    }
}
