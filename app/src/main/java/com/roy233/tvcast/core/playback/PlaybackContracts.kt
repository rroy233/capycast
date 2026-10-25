package com.roy233.tvcast.core.playback

/** 与当前 B 站播放请求一致的 HTTP User-Agent。 */
const val DEFAULT_PLAYBACK_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/** 播放器侧统一使用的媒体轨道。数据层负责把服务端 DTO 映射到此模型。 */
data class MediaTrack(
    val id: Int,
    val codecId: Int,
    val width: Int,
    val height: Int,
    val bandwidth: Long,
    val url: String,
    val backupUrls: List<String>,
    val isPcdn: Boolean = false,
    val mimeType: String = "",
    val codecs: String = "",
    val isHdr: Boolean = false,
    val isDolbyVision: Boolean = false,
    val isDolbyAudio: Boolean = false,
    val initializationRange: String = "",
    val indexRange: String = "",
    val psshBase64: String = "",
)

/** B 站播放信息映射后的播放器无关输入，逐项保留原始播放元数据。 */
data class PlaybackRequest(
    val title: String,
    val bvid: String,
    val aid: Long,
    val cid: Long,
    val durationSec: Long,
    val video: MediaTrack,
    val audio: MediaTrack?,
    val progressiveUrl: String?,
    val videos: List<MediaTrack>,
    val audios: List<MediaTrack>,
    val cover: String,
    val isLive: Boolean,
    val liveFormat: String,
    val liveCdn: String,
    val liveBackupCount: Int,
    val isPgc: Boolean,
    val epId: Long,
    val seasonId: Long,
    val hasPaid: Boolean,
    val isDrm: Boolean,
    val isPreview: Boolean,
    val actualQuality: Int,
    val acceptedQualities: List<Int>,
    val qualityLabels: Map<Int, String>,
    val userAgent: String,
) {
    val qualityLabel: String
        get() = qualityLabels[video.id].orEmpty().ifBlank { qnLabel(video.id) }

    val audioLabel: String
        get() = when (audio?.id) {
            30250 -> "Dolby Atmos"
            30280 -> "192K"
            30232 -> "132K"
            30216 -> "64K"
            else -> audio?.id?.let { "audio=$it" } ?: "no audio"
        }

    private fun qnLabel(qn: Int): String = when (qn) {
        127 -> "8K"
        126 -> "Dolby"
        125 -> "HDR"
        120 -> "4K"
        116 -> "1080P60"
        112 -> "1080P+"
        80 -> "1080P"
        74 -> "720P60"
        64 -> "720P"
        32 -> "480P"
        16 -> "360P"
        10000 -> "原画"
        400 -> "蓝光"
        250 -> "超清"
        150 -> "高清"
        else -> "qn=$qn"
    }
}

/** 后端能力快照，供调用方处理后端差异而无需引用具体 SDK。 */
data class PlaybackCapabilities(
    val supportsDrm: Boolean,
    val supportsDolbyVision: Boolean,
    val supportsLiveStatistics: Boolean,
    val supportsTrackDiagnostics: Boolean,
)

/** 统一播放状态快照。未知时长和位置均使用非负值，未知时长为 0。 */
data class PlaybackSnapshot(
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val volume: Int,
    val backend: PlaybackBackend,
)

/** 播放器平台事件的稳定表示。 */
sealed interface PlaybackEvent {
    data object Playing : PlaybackEvent
    data object Paused : PlaybackEvent
    data object Stopped : PlaybackEvent
    data object Ended : PlaybackEvent
    data class Buffering(val percent: Float) : PlaybackEvent
    data class Failed(val code: String, val message: String, val cause: Throwable? = null) : PlaybackEvent
    data class VideoSizeChanged(val width: Int, val height: Int) : PlaybackEvent
}

/** 播放器无关的媒体失败信息。 */
class PlaybackFailure(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
