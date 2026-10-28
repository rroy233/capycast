package com.roy233.tvcast.data.bili

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.data.auth.BiliSessionReader
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 普通视频元数据、playurl 与轨道解析的会话限定仓库。 */
class BiliVideoRepository(
    private val sessionReader: BiliSessionReader,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    private val http = BiliHttpClient(client, BiliApi.UA)
    private fun get(url: String, purpose: String): String = http.get(url, purpose, sessionReader.cookie())

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
    fun resolve(bvid: String): BiliApi.PlayInfo {
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
    ): BiliApi.PlayInfo {
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
            .header("User-Agent", BiliApi.UA)
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
    ): BiliApi.PlayInfo {
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
                return BiliApi.PlayInfo(
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
                    qualityLabels = BiliApi.qualityLabelsFrom(play),
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
            val track = BiliApi.Track(
                0, 0, 0, 0, 0, selected, ordered.drop(1),
                pcdn = CdnCandidateSelector.isPcdn(selected),
            )
            return BiliApi.PlayInfo(
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
                qualityLabels = BiliApi.qualityLabelsFrom(play),
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
        videos: List<BiliApi.Track>,
        audios: List<BiliApi.Track>,
    ) {
        val dolby = dash.objectOrNull("dolby")?.arrayOrNull("audio")?.size()
        val flac = dash.objectOrNull("flac")?.arrayOrNull("audio")?.size()
        LogHub.i(
            TAG,
            "playurl dash video=${videos.size} audio=${audios.size} " +
                "duration=${play.get("duration")?.asLong ?: -1} " +
                "quality=${play.get("quality")?.asInt ?: -1} " +
                "acceptQuality=${BiliApi.jsonArrayText(play.arrayOrNull("accept_quality"))} " +
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
            val label = BiliApi.qualityLabelFromFormat(format)
            "qn=${format.get("quality")?.asInt ?: -1}" +
                if (label.isBlank()) "" else "[$label]" +
                if (codecs.isNullOrBlank()) "" else "($codecs)"
        }
        LogHub.i(TAG, "playurl supportFormats=$summary")
    }

    private companion object { const val TAG = "BiliApi" }
}
