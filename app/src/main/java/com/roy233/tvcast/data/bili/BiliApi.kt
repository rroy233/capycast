package com.roy233.tvcast.data.bili

import com.roy233.tvcast.data.auth.BiliSessionProvider

import android.os.SystemClock
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal Bilibili web API client.
 *
 * Quality policy: use the *web* playurl endpoint and only ever select streams
 * that the server actually returned for this account. We never invent a
 * quality or bypass an entitlement: anonymous gets the free ladder, a logged-in
 * account gets exactly what its cookies are entitled to.
 */
object BiliApi {

    /** 日志标签。 */
    private const val TAG = "BiliApi"

    /** 播放请求使用的客户端 User-Agent。 */
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 访问 Bilibili Web API 的 HTTP 客户端。 */
    /** HTTP 请求执行器。 */
    private val httpClient by lazy { BiliHttpClient(client, UA) }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 登录后使用的 Cookie 请求头；为空时按匿名用户请求。 */
    /** 当前 B 站请求使用的 Cookie；由 [BiliSessionProvider] 统一持有。 */
    var cookie: String
        get() = BiliSessionProvider.cookie
        set(value) {
            BiliSessionProvider.cookie = value
        }

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

    /** 保存账号导航接口返回的身份信息。 */
    data class Nav(
        /** 用户名。 */
        val uname: String,
        /** 用户头像地址。 */
        val face: String,
        /** 是否为大会员。 */
        val vip: Boolean,
        /** 账号标识。 */
        val mid: Long,
    )

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

    /**
     * 发送带有公共请求头和登录 Cookie 的 GET 请求。
     *
     * @param url 请求地址。
     * @param purpose 请求用途，用于日志区分。
     */
    private fun get(url: String, purpose: String): String = httpClient.get(url, purpose, cookie)

    /**
     * 发送带有公共请求头和登录 Cookie 的二进制 GET 请求。
     *
     * 用于弹幕 `seg.so` / `view` 这类 protobuf 响应，不能按文本解码。
     *
     * @param url 请求地址。
     * @param purpose 请求用途，用于日志区分。
     */
    fun getBytes(url: String, purpose: String): ByteArray = httpClient.getBytes(url, purpose, cookie)

    /**
     * 请求统一 API 并校验业务状态码。
     *
     * @param url 请求地址。
     * @param purpose 请求用途，用于日志区分。
     */
    private fun api(url: String, purpose: String): JsonObject {
        val text = get(url, purpose)
        val root = JsonParser.parseString(text).asJsonObject
        val code = root.get("code")?.asInt ?: -1
        if (code != 0) {
            val msg = root.get("message")?.asString ?: "unknown"
            LogHub.e(
                TAG,
                "api rejected purpose=$purpose code=$code message=$msg " +
                    "url=${LogRedaction.url(url)}",
            )
            error("bilibili code=$code message=$msg")
        }
        LogHub.d(TAG, "api ok purpose=$purpose")
        val data = root.getAsJsonObject("data")
        if (data == null) {
            LogHub.e(TAG, "api data missing purpose=$purpose url=${LogRedaction.url(url)}")
            error("empty data ($purpose)")
        }
        return data
    }

    /** 根据 BV 标识解析普通视频的可播放轨道。 */
    fun resolve(bvid: String): PlayInfo {
        val view = api("https://api.bilibili.com/x/web-interface/view?bvid=$bvid", "view")
        val aid = view.get("aid").asLong
        val cid = view.get("cid").asLong
        val title = view.get("title")?.asString ?: bvid
        val duration = view.get("duration")?.asLong ?: 0L

        val play = api(playUrl(aid, cid), "playurl")
        return parsePlay(play, aid, cid, title, bvid, duration)
    }

    /** 根据稿件和分P标识解析对应视频的可播放轨道。 */
    fun resolveByAidCid(
        aid: Long,
        cid: Long,
        titleFallback: String = "",
        bvidFallback: String = "",
    ): PlayInfo {
        if (aid <= 0) error("invalid aid=$aid")
        var title = titleFallback
        var bvid = bvidFallback
        var duration = 0L
        var realCid = cid
        var cover = ""
        val view = runCatching {
            api("https://api.bilibili.com/x/web-interface/view?aid=$aid", "view")
        }
            .getOrNull()
        if (view != null) {
            title = view.get("title")?.asString ?: title
            bvid = view.get("bvid")?.asString ?: bvid
            duration = view.get("duration")?.asLong ?: duration
            cover = (view.get("pic")?.asString ?: cover)
                .replace("http://", "https://")
            if (realCid <= 0) realCid = view.get("cid")?.asLong ?: 0L
            val pages = view.getAsJsonArray("pages")
            if (pages != null) {
                for (p in pages) {
                    val po = p.asJsonObject
                    if (po.get("cid")?.asLong == realCid) {
                        duration = po.get("duration")?.asLong ?: duration
                        break
                    }
                }
            }
        }
        if (cover.isBlank()) {
            cover = runCatching { publicCover(aid) }.getOrDefault("")
        }
        if (realCid <= 0) error("invalid cid=$cid")
        LogHub.i(
            TAG,
            "resolveByAidCid aid=$aid cid=$realCid title=${title.take(40)} " +
                "bvid=$bvid duration=${duration}s cover=${cover.isNotBlank()}",
        )
        val play = api(playUrl(aid, realCid), "playurl")
        return parsePlay(play, aid, realCid, title, bvid, duration, cover)
    }

    /** 在不携带账号 Cookie 的情况下查询视频封面。 */
    private fun publicCover(aid: Long): String {
        val request = Request.Builder()
            .url("https://api.bilibili.com/x/web-interface/view?aid=$aid")
            .header("User-Agent", UA)
            .header("Referer", "https://www.bilibili.com/")
            .header("Accept", "application/json, text/plain, */*")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                LogHub.d(TAG, "publicCover failed aid=$aid status=${resp.code}")
                return ""
            }
            val text = resp.body?.string() ?: return ""
            val data = JsonParser.parseString(text).asJsonObject
                .getAsJsonObject("data") ?: return ""
            return data.get("pic")?.asString.orEmpty()
                .replace("http://", "https://")
        }
    }

    /** 根据剧集标识和分P标识解析 PGC/OGV 内容。 */
    fun resolvePgc(epId: Long, cid: Long, title: String): PlayInfo {
        if (epId <= 0) error("invalid epId=$epId")
        val url = "https://api.bilibili.com/pgc/player/web/playurl" +
            "?ep_id=$epId&cid=$cid&qn=127&fnval=4048&fnver=0&fourk=1"
        val text = get(url, "pgc-playurl")
        val root = JsonParser.parseString(text).asJsonObject
        val code = root.get("code")?.asInt ?: -1
        if (code != 0) {
            val msg = root.get("message")?.asString ?: "unknown"
            LogHub.e(TAG, "pgc rejected epId=$epId cid=$cid code=$code message=$msg")
            error("pgc code=$code message=$msg")
        }
        val result = root.getAsJsonObject("result") ?: run {
            LogHub.e(TAG, "pgc result missing epId=$epId cid=$cid")
            error("pgc result missing")
        }
        LogHub.i(
            TAG,
            "resolvePgc epId=$epId cid=$cid title=${title.take(40)} " +
                "isPreview=${root.get("is_preview")?.asBoolean ?: false} " +
                "resultKeys=${result.keySet().joinToString(",")}",
        )
        return parsePlay(result, 0L, cid, title, "", 0L)
    }

    /** 构造普通视频播放接口地址。 */
    private fun playUrl(aid: Long, cid: Long): String =
        "https://api.bilibili.com/x/player/playurl" +
            "?avid=$aid&cid=$cid&qn=127&fnval=4048&fnver=0&fourk=1"

    /** 解析 DASH 或渐进式播放响应并选择可用轨道。 */
    private fun parsePlay(
        play: JsonObject,
        aid: Long,
        cid: Long,
        title: String,
        bvid: String,
        duration: Long,
        cover: String = "",
    ): PlayInfo {
        val dash = play.getAsJsonObject("dash")
        if (dash != null) {
            val videos = dash.arrayOrNull("video")?.map { CdnCandidateSelector.parseTrack(it.asJsonObject) }
                ?: emptyList()
            val baseAudios = dash.arrayOrNull("audio")
                ?.map { CdnCandidateSelector.parseTrack(it.asJsonObject) }
                ?: emptyList()
            val dolbyAudios = dash.objectOrNull("dolby")
                ?.arrayOrNull("audio")
                ?.map { CdnCandidateSelector.parseTrack(it.asJsonObject, isDolbyAudio = true) }
                ?: emptyList()
            val audios = (baseAudios + dolbyAudios).distinctBy { it.url }
            logPlayResponse(play, dash, videos, audios)
            if (videos.isNotEmpty()) {
                val dashDuration = play.get("duration")?.asLong ?: duration
                val video = CdnCandidateSelector.pickVideo(videos)
                val audio = CdnCandidateSelector.pickAudio(audios)
                LogHub.i(
                    TAG,
                    "playurl selected video=${video.id} codec=${video.codecid} " +
                        "${video.width}x${video.height} host=${LogRedaction.host(video.url)} " +
                        "pcdn=${video.pcdn} audio=${audio?.id ?: "none"} " +
                        "audioHost=${LogRedaction.host(audio?.url)}",
                )
                return PlayInfo(
                    title = title,
                    bvid = bvid,
                    aid = aid,
                    cid = cid,
                    durationSec = dashDuration,
                    video = video,
                    audio = audio,
                    progressiveUrl = null,
                    videos = videos,
                    audios = audios,
                    cover = cover,
                    qualityLabels = qualityLabelsFrom(play),
                )
            }
            LogHub.w(TAG, "playurl dash has no video tracks; trying durl fallback")
        }

        // Very old / non-DASH content: progressive mp4/flv.
        val durl = play.arrayOrNull("durl")
        if (durl != null && durl.size() > 0) {
            val first = durl[0].asJsonObject
            val url = first.get("url").asString
            val ordered = CdnCandidateSelector.order(
                listOf(url) + CdnCandidateSelector.backupUrls(first)
            )
            val selected = ordered.firstOrNull() ?: url
            val lengthMs = first.get("length")?.asLong ?: 0L
            LogHub.i(
                TAG,
                "playurl progressive durl=${durl.size()} length=${lengthMs}ms " +
                    "candidates=${ordered.size} host=${LogRedaction.host(selected)} " +
                    "pcdn=${CdnCandidateSelector.isPcdn(selected)}",
            )
            val track = Track(
                0, 0, 0, 0, 0, selected, ordered.drop(1),
                pcdn = CdnCandidateSelector.isPcdn(selected),
            )
            return PlayInfo(
                title = title,
                bvid = bvid,
                aid = aid,
                cid = cid,
                durationSec = if (lengthMs > 0) lengthMs / 1000 else duration,
                video = track,
                audio = null,
                progressiveUrl = selected,
                videos = listOf(track),
                audios = emptyList(),
                cover = cover,
                qualityLabels = qualityLabelsFrom(play),
            )
        }
        LogHub.e(
            TAG,
            "playurl no playable stream keys=${play.keySet().joinToString(",")} " +
                "dash=${dash != null} durl=${durl?.size() ?: 0}",
        )
        error("no playable stream returned")
    }

    /** 记录播放接口返回的画质、编码和会员音轨信息。 */
    private fun logPlayResponse(
        play: JsonObject,
        dash: JsonObject,
        videos: List<Track>,
        audios: List<Track>,
    ) {
        val dolby = dash.objectOrNull("dolby")?.arrayOrNull("audio")?.size()
        val flac = dash.objectOrNull("flac")?.arrayOrNull("audio")?.size()
        LogHub.i(
            TAG,
            "playurl dash video=${videos.size} audio=${audios.size} " +
                "duration=${play.get("duration")?.asLong ?: -1} " +
                "quality=${play.get("quality")?.asInt ?: -1} " +
                "acceptQuality=${jsonArrayText(play.arrayOrNull("accept_quality"))} " +
                "dolbyAudio=${dolby ?: 0} flacAudio=${flac ?: 0} " +
                "isDrm=${play.get("is_drm")?.asBoolean ?: false} " +
                "isPreview=${play.get("is_preview")?.asBoolean ?: false}",
        )
        val formats = play.arrayOrNull("support_formats") ?: return
        val summary = formats.take(12).joinToString(" ") { element ->
            val format = runCatching { element.asJsonObject }.getOrNull()
                ?: return@joinToString "?"
            val codecs = runCatching {
                format.arrayOrNull("codecs")?.joinToString(",") { codec -> codec.asString }
            }.getOrNull()
            val label = qualityLabelFromFormat(format)
            "qn=${format.get("quality")?.asInt ?: -1}" +
                if (label.isBlank()) "" else "[$label]" +
                if (codecs.isNullOrBlank()) "" else "($codecs)"
        }
        LogHub.i(TAG, "playurl supportFormats=$summary")
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

    private fun qualityLabelFromFormat(format: JsonObject): String = listOf(
        format.stringOrEmpty("new_description"),
        format.stringOrEmpty("display_desc"),
        format.stringOrEmpty("description"),
        format.stringOrEmpty("format"),
    ).firstOrNull(String::isNotBlank).orEmpty()

    private fun JsonObject.stringOrEmpty(name: String): String =
        runCatching { get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty() }.getOrDefault("")

    /** 将 JSON 数组压缩为短文本。 */
    private fun jsonArrayText(array: com.google.gson.JsonArray?): String =
        array?.joinToString(",") { it.toString() }?.take(160) ?: "-"

    /** 用于端到端播放验证的示例 BV 标识。 */
    const val SAMPLE_BVID = "BV1GJ411x7h7"

    /** 查询当前登录账号的身份信息。 */
    fun nav(): Nav {
        val text = get("https://api.bilibili.com/x/web-interface/nav", "nav")
        val root = JsonParser.parseString(text).asJsonObject
        val code = root.get("code")?.asInt ?: -1
        if (code != 0) {
            val msg = root.get("message")?.asString ?: "unknown"
            error("nav code=$code message=$msg")
        }
        val data = root.getAsJsonObject("data")
        val vipObject = data.getAsJsonObject("vip")
        val vip = (vipObject?.get("status")?.asInt ?: 0) == 1 ||
            (data.get("vipStatus")?.asInt ?: 0) == 1
        return Nav(
            uname = data.get("uname")?.asString ?: "",
            face = data.get("face")?.asString ?: "",
            vip = vip,
            mid = data.get("mid")?.asLong ?: 0L,
        )
    }
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
