package com.roy233.tvcast.data.local

/** 保存最近一次投屏内容的摘要信息。 */
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
