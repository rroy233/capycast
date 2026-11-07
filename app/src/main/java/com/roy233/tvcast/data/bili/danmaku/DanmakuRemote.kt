package com.roy233.tvcast.data.bili.danmaku

import com.roy233.tvcast.feature.danmaku.domain.DmSegment
import com.roy233.tvcast.feature.danmaku.domain.DmViewInfo

/** 弹幕远程数据源。 */
interface DanmakuRemote {
    /** 拉取弹幕元数据。 */
    suspend fun fetchView(cid: Long): DmViewInfo

    /** 拉取一个分段。 */
    suspend fun fetchSegment(aid: Long, cid: Long, segmentIndex: Int): DmSegment
}
