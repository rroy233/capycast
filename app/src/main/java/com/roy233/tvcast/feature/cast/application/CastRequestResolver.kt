package com.roy233.tvcast.feature.cast.application

import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.feature.cast.domain.CastContent
import com.roy233.tvcast.feature.playback.model.CastResolution
import com.roy233.tvcast.feature.playback.model.LivePlayInfo
import com.roy233.tvcast.feature.playback.model.RankedLiveSource
import com.roy233.tvcast.feature.playback.model.RankedVodPlayback

/** 按 NVA 媒体身份选择既有直播、PGC 或普通视频解析路径。 */
class CastRequestResolver(
    private val accessKey: () -> String?,
    private val resolveLive: suspend (String, Long) -> LivePlayInfo,
    private val rankLive: suspend (LivePlayInfo) -> List<RankedLiveSource>,
    private val resolveVod: suspend (Long, Long, String, String, Long, Long) -> PlaybackRequest,
    private val rankVod: suspend (PlaybackRequest) -> RankedVodPlayback,
) {
    suspend fun resolve(nva: CastContent, title: String): CastResolution = when {
        nva.hasLive -> {
            val key = accessKey() ?: error("TV account login is required for live playback")
            val info = resolveLive(key, nva.roomId)
            val routes = rankLive(info)
            if (routes.isEmpty()) error("live response has no eligible CDN routes")
            CastResolution.Live(info, routes)
        }
        nva.hasPgc -> CastResolution.Vod(
            rankVod(resolveVod(nva.aid, nva.cid, nva.bvid.orEmpty(), title, nva.epId, nva.seasonId)),
        )
        nva.hasUgc -> CastResolution.Vod(
            rankVod(resolveVod(nva.aid, nva.cid, nva.bvid.orEmpty(), title, 0L, 0L)),
        )
        else -> error("no playable identity in nva_ext")
    }
}
