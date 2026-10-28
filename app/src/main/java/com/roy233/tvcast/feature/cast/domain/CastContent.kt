package com.roy233.tvcast.feature.cast.domain

/** 投屏请求中解析出的内容类型。 */
enum class CastContentKind { LIVE, PGC, UGC, UNKNOWN }

/** 投屏协议携带的设备链路信息。 */
data class CastLink(
    val deviceId: String? = null,
    val callback: String? = null,
)

/**
 * 投屏请求的稳定内容身份。
 *
 * 协议解析器负责构造它；投屏应用层只依赖此领域模型，不了解 B 站的传输格式。
 */
data class CastContent(
    val aid: Long = 0L,
    val cid: Long = 0L,
    val oid: String? = null,
    val bvid: String? = null,
    val qn: Int = 0,
    val userDesireQn: Int = 0,
    val userDesireSpeed: String? = null,
    val epId: Long = 0L,
    val seasonId: Long = 0L,
    val roomId: Long = 0L,
    val contentType: Int = 0,
    val businessSessionId: String? = null,
    val link: CastLink? = null,
    val mediaVersion: Long = 0L,
    val transportType: Int = 0,
) {
    val kind: CastContentKind
        get() = when {
            roomId > 0L -> CastContentKind.LIVE
            epId > 0L || seasonId > 0L -> CastContentKind.PGC
            aid > 0L && cid > 0L -> CastContentKind.UGC
            else -> CastContentKind.UNKNOWN
        }

    val hasUgc: Boolean get() = kind == CastContentKind.UGC
    val hasPgc: Boolean get() = kind == CastContentKind.PGC
    val hasLive: Boolean get() = kind == CastContentKind.LIVE
}
