package com.roy233.tvcast.platform.player

import com.roy233.tvcast.data.bili.BiliApi

/** 为点播内容选择能维持音视频共同时间线的播放后端。 */
internal object VodPlaybackBackendPolicy {

    /**
     * B 站 DASH 将视频和音频拆为独立 fMP4 表示层，交给 Media3 合并后可原子地定位。
     * 直播与服务端只提供单一渐进流的旧片继续使用 VLC。
     */
    fun usesMedia3(info: BiliApi.PlayInfo): Boolean = !info.isLive && info.progressiveUrl == null
}
