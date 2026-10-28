package com.roy233.tvcast.feature.cast.domain

/**
 * 用户可重新发起投屏的内容摘要。
 *
 * 此模型属于投屏领域；本地存储仅负责将其序列化和恢复，界面仅负责展示它。
 */
data class RecentCast(
    val aid: Long,
    val cid: Long,
    val title: String,
    val cover: String = "",
    val quality: String = "",
    /** 是否为番剧、电影、电视剧等 PGC 内容。 */
    val isPgc: Boolean = false,
    /** PGC 分集标识；重播时必须保留以继续使用 PGC 播放协议。 */
    val epId: Long = 0L,
    /** PGC 季标识。 */
    val seasonId: Long = 0L,
    val at: Long = 0L,
)
