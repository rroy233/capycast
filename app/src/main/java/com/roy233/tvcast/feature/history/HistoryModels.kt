package com.roy233.tvcast.feature.history

import java.io.IOException

/** 一条待同步的历史记录快照；不包含 access_key、Cookie 或 CSRF。 */
data class PlaybackHistoryReport(
    val sessionId: String,
    val accountMid: Long,
    val aid: Long,
    val cid: Long,
    val sid: Long,
    val epid: Long,
    val serialId: Long,
    val type: Int,
    val business: String,
    val progressSeconds: Long,
    val playDurationSeconds: Long,
    val isInteracted: Boolean,
    val bvid: String = "",
    val durationSeconds: Long = 0L,
    val startTsSec: Long = 0L,
    /** null 表示旧版本队列中的点播心跳记录。 */
    val reportKind: String? = null,
    val scene: String = "",
    val fromScene: String = "",
    val fromSpmid: String = "",
    val resourceFrom: String = "",
    val updatedAtMs: Long,
)

/** 仅保留在内存中的 Web 登录态，不写入历史队列或日志。 */
data class WebHistoryAuth(
    val mid: Long,
    val cookie: String,
    val csrf: String,
)

/** 当前直播心跳所需的请求数据。 */
data class LiveHeartbeatRequest(
    val build: Int,
    val buvid: String,
    val roomId: Long,
    val playUrl: String,
    val timestampMs: Long,
    val reye: String,
    val urcs: List<Int>,
    val edno: String,
    val sequence: Int,
)

/** 直播心跳响应携带的下次间隔与签名状态。 */
data class LiveHeartbeatResponse(
    val edno: String,
    val reye: String,
    val urcs: List<Int>,
    val intervalSeconds: Int,
)

/** 包含服务端错误码但不携带请求凭据的接口异常。 */
class PlaybackHistoryApiException(
    val path: String,
    val code: Int,
    message: String,
) : IOException("History API rejected request path=$path code=$code message=$message")
