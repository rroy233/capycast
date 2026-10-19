package com.roy233.tvcast.data.bili

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.roy233.tvcast.core.diagnostics.LogHub
import okhttp3.OkHttpClient
import okhttp3.Request
import com.roy233.tvcast.data.bili.CdnCandidateSelector
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

    /** 播放请求使用的客户端 User-Agent。 */
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 访问 Bilibili Web API 的 HTTP 客户端。 */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 登录后使用的 Cookie 请求头；为空时按匿名用户请求。 */
    @Volatile
    var cookie: String = ""

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
    ) {
        /** 当前视频轨道的展示名称。 */
        val qualityLabel: String
            get() = qnLabel(video.id)

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
                return "${v.width}x${v.height} · $codec"
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
        return "${track.width}x${track.height} · $codec"
    }

    /** 发送带有公共请求头和登录 Cookie 的 GET 请求。 */
    private fun get(url: String): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com")
            .header("Accept", "application/json, text/plain, */*")
        if (cookie.isNotEmpty()) builder.header("Cookie", cookie)
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            return resp.body?.string() ?: error("empty body for $url")
        }
    }

    /** 请求统一 API 并校验业务状态码。 */
    private fun api(url: String): JsonObject {
        val text = get(url)
        val root = JsonParser.parseString(text).asJsonObject
        val code = root.get("code")?.asInt ?: -1
        if (code != 0) {
            val msg = root.get("message")?.asString ?: "unknown"
            error("bilibili code=$code message=$msg")
        }
        return root.getAsJsonObject("data")
    }

    /** 根据 BV 标识解析普通视频的可播放轨道。 */
    fun resolve(bvid: String): PlayInfo {
        val view = api("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
        val aid = view.get("aid").asLong
        val cid = view.get("cid").asLong
        val title = view.get("title")?.asString ?: bvid
        val duration = view.get("duration")?.asLong ?: 0L

        val play = api(playUrl(aid, cid))
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
        val view = runCatching { api("https://api.bilibili.com/x/web-interface/view?aid=$aid") }
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
            "BiliApi",
            "resolveByAidCid aid=$aid cid=$realCid title=${title.take(40)} bvid=$bvid"
        )
        val play = api(playUrl(aid, realCid))
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
            if (!resp.isSuccessful) return ""
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
        val text = get(url)
        val root = JsonParser.parseString(text).asJsonObject
        val code = root.get("code")?.asInt ?: -1
        if (code != 0) {
            val msg = root.get("message")?.asString ?: "unknown"
            error("pgc code=$code message=$msg")
        }
        val result = root.getAsJsonObject("result") ?: error("pgc result missing")
        LogHub.i("BiliApi", "resolvePgc epId=$epId cid=$cid title=${title.take(40)}")
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
            val videos = dash.getAsJsonArray("video")?.map { CdnCandidateSelector.parseTrack(it.asJsonObject) }
                ?: emptyList()
            val audios = dash.getAsJsonArray("audio")?.map { CdnCandidateSelector.parseTrack(it.asJsonObject) }
                ?: emptyList()
            if (videos.isNotEmpty()) {
                val dashDuration = play.get("duration")?.asLong ?: duration
                val video = CdnCandidateSelector.pickVideo(videos)
                val audio = CdnCandidateSelector.pickAudio(audios)
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
                )
            }
        }

        // Very old / non-DASH content: progressive mp4/flv.
        val durl = play.getAsJsonArray("durl")
        if (durl != null && durl.size() > 0) {
            val first = durl[0].asJsonObject
            val url = first.get("url").asString
            val ordered = CdnCandidateSelector.order(
                listOf(url) + CdnCandidateSelector.backupUrls(first)
            )
            val selected = ordered.firstOrNull() ?: url
            val lengthMs = first.get("length")?.asLong ?: 0L
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
            )
        }
        error("no playable stream returned")
    }

    /** 用于端到端播放验证的示例 BV 标识。 */
    const val SAMPLE_BVID = "BV1GJ411x7h7"

    /** 查询当前登录账号的身份信息。 */
    fun nav(): Nav {
        val text = get("https://api.bilibili.com/x/web-interface/nav")
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
