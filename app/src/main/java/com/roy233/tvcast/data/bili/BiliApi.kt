package com.roy233.tvcast.data.bili

import com.roy233.tvcast.core.playback.DEFAULT_PLAYBACK_USER_AGENT

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Minimal Bilibili web API client.
 *
 * Quality policy: use the *web* playurl endpoint and only ever select streams
 * that the server actually returned for this account. We never invent a
 * quality or bypass an entitlement: anonymous gets the free ladder, a logged-in
 * account gets exactly what its cookies are entitled to.
 */
object BiliApi {

    /** 播放请求使用的客户端 User-Agent。 */
    const val UA = DEFAULT_PLAYBACK_USER_AGENT

    /** 描述一个可播放媒体轨道。 */
    data class Track(
        /** 画质或音质标识。 */
        val id: Int,
        /** 编码格式标识。 */
        val codecid: Int,
        /** 视频宽度。 */
        val width: Int,
        /** 视频高度。 */
        val height: Int,
        /** 轨道带宽。 */
        val bandwidth: Long,
        /** 主播放地址。 */
        val url: String,
        /** 备用播放地址列表。 */
        val backup: List<String>,
        /** 主地址是否为 PCDN 地址。 */
        val pcdn: Boolean = false,
        /** DASH 表示层声明的 MIME 类型，例如 video/mp4 或 audio/mp4。 */
        val mimeType: String = "",
        /** RFC 6381 编码字符串；它比 codecid 更适合判断 Main10、DV 等能力。 */
        val codecs: String = "",
        /** 服务端或清晰度编号标记该轨道为 HDR。 */
        val isHdr: Boolean = false,
        /** 服务端或清晰度编号标记该轨道为杜比视界。 */
        val isDolbyVision: Boolean = false,
        /** 音频轨道是否来自 dash.dolby.audio。 */
        val isDolbyAudio: Boolean = false,
        /** DASH 初始化数据的字节范围，例如 `0-1023`。 */
        val initializationRange: String = "",
        /** DASH 索引数据的字节范围，例如 `1024-2047`。 */
        val indexRange: String = "",
        /** 从初始化区间提取的 ISO-BMFF PSSH atom，Base64 编码。 */
        val psshBase64: String = "",
    )

    /** 保存一次媒体解析得到的完整播放信息。 */
    data class PlayInfo(
        /** 视频标题。 */
        val title: String,
        /** BV 标识。 */
        val bvid: String,
        /** 稿件标识。 */
        val aid: Long,
        /** 分P标识。 */
        val cid: Long,
        /** 媒体时长，单位为秒。 */
        val durationSec: Long,
        /** 当前选择的视频轨道。 */
        val video: Track,
        /** 当前选择的音频轨道。 */
        val audio: Track?,
        /** 渐进式媒体地址；DASH 媒体为 null。 */
        val progressiveUrl: String?,
        /** 服务端返回的全部视频轨道。 */
        val videos: List<Track> = listOf(video),
        /** 服务端返回的全部音频轨道。 */
        val audios: List<Track> = listOfNotNull(audio),
        /** 视频封面地址。 */
        val cover: String = "",
        /** 当前媒体是否为直播；直播不参与续播、定位和时长逻辑。 */
        val isLive: Boolean = false,
        /** 直播当前线路的容器格式。 */
        val liveFormat: String = "",
        /** 直播当前线路的 CDN 主机标签。 */
        val liveCdn: String = "",
        /** 当前直播线路之外的同画质备用线路数。 */
        val liveBackupCount: Int = 0,
        /** 是否为番剧、电影、电视剧等 PGC 内容。 */
        val isPgc: Boolean = false,
        /** PGC 分集标识。 */
        val epId: Long = 0L,
        /** PGC 季标识。 */
        val seasonId: Long = 0L,
        /** 当前账号是否已购买该内容；由服务端播放响应提供。 */
        val hasPaid: Boolean = false,
        /** 服务端是否将内容标记为 DRM。 */
        val isDrm: Boolean = false,
        /** 服务端是否将当前播放标记为预览。 */
        val isPreview: Boolean = false,
        /** 服务端返回的质量档位。 */
        val actualQuality: Int = video.id,
        /** 服务端返回的可选质量列表。 */
        val acceptQualities: List<Int> = emptyList(),
        /** 服务端 support_formats 中面向用户的画质名称，优先于本地 qn 映射。 */
        val qualityLabels: Map<Int, String> = emptyMap(),
    ) {
        /** 当前视频轨道的展示名称。 */
        val qualityLabel: String
            get() = qualityLabelFor(video.id)

        /** 返回一个视频轨道的服务端画质名称；缺失时保留兼容的 qn 映射。 */
        fun qualityLabelFor(qn: Int): String = qualityLabels[qn].orEmpty().ifBlank { qnLabel(qn) }

        /** 当前音频轨道的展示名称。 */
        val audioLabel: String
            get() = audioLabelFor(audio?.id)

        /** 当前视频轨道的分辨率和编码说明。 */
        val qualityDetail: String
            get() {
                val v = video
                if (v.width <= 0 || v.height <= 0) return "MP4"
                val codec = when (v.codecid) {
                    12 -> "HEVC"
                    13 -> "AV1"
                    7 -> "H.264"
                    else -> "codec ${v.codecid}"
                }
                val dynamicRange = when {
                    v.isDolbyVision -> " · Dolby Vision"
                    v.isHdr -> " · HDR"
                    else -> ""
                }
                return "${v.width}x${v.height} · $codec$dynamicRange"
            }
    }

    /** 将画质标识转换为展示文本。 */
    fun qnLabel(qn: Int): String = when (qn) {
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

    /** 将音轨标识转换为展示文本。 */
    fun audioLabelFor(id: Int?): String = when (id) {
        30250 -> "Dolby Atmos"
        30280 -> "192K"
        30232 -> "132K"
        30216 -> "64K"
        else -> id?.let { "audio=$it" } ?: "no audio"
    }

    /** 根据轨道尺寸和编码生成详情文本。 */
    fun trackDetail(track: Track): String {
        if (track.width <= 0 || track.height <= 0) return "MP4"
        val codec = when (track.codecid) {
            12 -> "HEVC"
            13 -> "AV1"
            7 -> "H.264"
            else -> "codec ${track.codecid}"
        }
        val dynamicRange = when {
            track.isDolbyVision -> " · Dolby Vision"
            track.isHdr -> " · HDR"
            else -> ""
        }
        return "${track.width}x${track.height} · $codec$dynamicRange"
    }

    /** 从服务端画质描述提取稳定的展示名称，兼容大视界等新增命名。 */
    fun qualityLabelsFrom(play: JsonObject): Map<Int, String> = buildMap {
        play.arrayOrNull("support_formats")?.forEach { element ->
            val format = runCatching { element.asJsonObject }.getOrNull() ?: return@forEach
            val quality = runCatching { format.get("quality")?.asInt ?: 0 }.getOrDefault(0)
            val label = qualityLabelFromFormat(format)
            if (quality > 0 && label.isNotBlank()) put(quality, label)
        }
    }

    internal fun qualityLabelFromFormat(format: JsonObject): String = listOf(
        format.stringOrEmpty("new_description"),
        format.stringOrEmpty("display_desc"),
        format.stringOrEmpty("description"),
        format.stringOrEmpty("format"),
    ).firstOrNull(String::isNotBlank).orEmpty()

    private fun JsonObject.stringOrEmpty(name: String): String =
        runCatching { get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty() }.getOrDefault("")

    /** 将 JSON 数组压缩为短文本。 */
    internal fun jsonArrayText(array: com.google.gson.JsonArray?): String =
        array?.joinToString(",") { it.toString() }?.take(160) ?: "-"

    /** 用于端到端播放验证的示例 BV 标识。 */
    const val SAMPLE_BVID = "BV1GJ411x7h7"

}

/**
 * Returns an array-valued response member, treating missing, null, and non-array values as absent.
 * Bilibili uses JSON null for optional members such as dash.dolby.audio.
 */
internal fun JsonObject.arrayOrNull(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

/** Returns an object-valued response member, treating missing and null values as absent. */
internal fun JsonObject.objectOrNull(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject
