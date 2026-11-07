package com.roy233.tvcast.feature.danmaku.domain

/** 弹幕数据缓存端口。 */
interface DanmakuRepository {
    /** 读取元数据；已按内容身份缓存则直接返回。 */
    suspend fun view(content: VodDanmakuContent): DmViewInfo

    /** 读取分段；已按内容身份与段号缓存则直接返回。 */
    suspend fun segment(key: DanmakuSegmentKey): DmSegment
}
