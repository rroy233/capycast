package com.roy233.tvcast.feature.playback.model

import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.playback.PlaybackRequest

data class LiveStreamCandidate(
    val url: String,
    val quality: Int,
    val format: String,
    val codecName: String,
    val codecid: Int,
    val width: Int,
    val height: Int,
    val bandwidth: Long,
    val pcdn: Boolean,
    val responseIndex: Int,
) {
    val cdnLabel: String get() = LogRedaction.host(url)
}

data class LivePlayInfo(
    val roomId: Long,
    val requestedQuality: Int,
    val quality: Int,
    val acceptedQualities: List<Int>,
    val candidates: List<LiveStreamCandidate>,
) {
    val qualityCandidates: List<LiveStreamCandidate>
        get() = candidates.filter { it.quality == quality }
            .ifEmpty { candidates.filter { it.quality == 0 } }
}

data class LiveProbeResult(
    val success: Boolean,
    val firstByteMs: Long,
    val throughputBytesPerSecond: Long,
    val bytesRead: Int,
    val failure: String = "",
)

data class RankedLiveSource(
    val candidate: LiveStreamCandidate,
    val result: LiveProbeResult? = null,
)

data class VodProbeResult(
    val success: Boolean,
    val firstByteMs: Long,
    val throughputBytesPerSecond: Long,
    val bytesRead: Int,
    val failure: String = "",
)

data class RankedVodRoute(val url: String, val result: VodProbeResult? = null)

/** 点播媒体和音频线路完成排序后的播放输入。 */
data class RankedVodPlayback(
    val request: PlaybackRequest,
    val videoRoutes: List<RankedVodRoute>,
    val audioRoutes: List<RankedVodRoute>,
)
