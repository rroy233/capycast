package com.roy233.tvcast.data.bili

import android.content.Context
import com.roy233.tvcast.feature.playback.model.LivePlaybackResolver
import com.roy233.tvcast.feature.playback.model.LivePlayInfo

/** 保持现有直播 playurl 请求参数及候选解析的 B 站适配器。 */
class BiliLivePlaybackResolver(private val context: Context) : LivePlaybackResolver {
    override suspend fun resolve(accessKey: String, roomId: Long, quality: Int?, forceAvc: Boolean): LivePlayInfo =
        if (quality == null) LivePlayUrlResolver.resolve(context, accessKey, roomId)
        else LivePlayUrlResolver.resolve(context, accessKey, roomId, quality, forceAvc)
}
