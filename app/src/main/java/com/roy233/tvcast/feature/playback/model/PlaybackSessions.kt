package com.roy233.tvcast.feature.playback.model

import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.feature.playback.domain.VodRecoveryController

/** 一次投屏请求解析后的内容类型与播放输入。 */
sealed interface CastResolution {
    data class Vod(val playback: RankedVodPlayback) : CastResolution
    data class Live(
        val info: LivePlayInfo,
        val routes: List<RankedLiveSource>,
    ) : CastResolution
}

/** 当前直播线路与同画质故障切换状态。 */
data class LiveSession(
    var stream: LivePlayInfo,
    var routes: MutableList<RankedLiveSource>,
    var active: LiveStreamCandidate,
    val attemptedUrls: MutableSet<String> = mutableSetOf(),
    val downshiftedQualities: MutableSet<Int> = mutableSetOf(),
    var avcFallbackAttempted: Boolean = false,
)

/** 等待播放器确认起播后展示的直播切源提示。 */
data class PendingLiveRouteSwitch(
    val reason: String,
    val from: LiveStreamCandidate?,
    val target: LiveStreamCandidate,
    val firstByteMs: Long?,
    val throughputBytesPerSecond: Long?,
)

/** 当前点播线路、测速结果与失败切换状态。 */
data class VodSession(
    var request: PlaybackRequest,
    var videoRoutes: List<RankedVodRoute>,
    var audioRoutes: List<RankedVodRoute>,
    val attemptedUrls: MutableSet<String> = mutableSetOf(),
    val attemptedAudioUrls: MutableSet<String> = mutableSetOf(),
    val attemptedRepresentations: MutableSet<String> = mutableSetOf(),
    var lastPositionMs: Long = 0L,
    var lastProgressAtMs: Long = 0L,
    val recovery: VodRecoveryController = VodRecoveryController(),
)

/** 等待实际进入播放态后展示的点播线路切换提示。 */
data class PendingVodRouteSwitch(
    val reason: String,
    val fromUrl: String,
    val target: RankedVodRoute,
    val quality: Int,
    val codecId: Int,
    val positionMs: Long,
)
