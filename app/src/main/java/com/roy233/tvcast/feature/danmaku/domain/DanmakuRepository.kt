package com.roy233.tvcast.feature.danmaku.domain

/** 弹幕数据缓存端口。 */
interface DanmakuRepository {
    /** 读取元数据；已缓存则直接返回。 */
    fun view(cid: Long): DmViewInfo

    /** 读取分段；已缓存则直接返回。 */
    fun segment(aid: Long, cid: Long, segmentIndex: Int): DmSegment

    /** 已缓存的分段；不存在返回 null。 */
    fun cachedSegment(segmentIndex: Int): DmSegment?

    /** 清空所有缓存。 */
    fun clear()
}
