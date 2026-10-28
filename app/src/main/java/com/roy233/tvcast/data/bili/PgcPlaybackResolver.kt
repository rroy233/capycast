package com.roy233.tvcast.data.bili

import com.roy233.tvcast.data.bili.pgc.PgcPsshReader

import android.os.SystemClock
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.feature.playback.model.PgcPlaybackFailure as PgcPlaybackException
import com.roy233.tvcast.core.diagnostics.LogRedaction
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Resolves PGC episode metadata and the highest stream actually returned to the active account. */
class PgcPlaybackResolver(
    private val cookieProvider: () -> String = { "" },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private val wbiSigner: WbiSigner = WbiSigner(cookieProvider),
) {
    private val psshReader = PgcPsshReader(client)
    private val metadataResolver = PgcMetadataResolver(::getJson)
    private val playurlFallback = PgcPlayurlFallback(::requestPlayurl)

    fun resolve(
        epId: Long,
        seasonId: Long,
        cid: Long,
        aid: Long,
        bvid: String,
        titleFallback: String,
    ): BiliApi.PlayInfo {
        if (epId <= 0L && seasonId <= 0L) {
            throw PgcPlaybackException("PGC_METADATA_FAILED", "缺少番剧分集或季标识")
        }
        val cookie = cookieProvider()
        val metadata = metadataResolver.loadEpisodeMetadata(epId, seasonId, cid, aid, cookie)
        val resolvedEpId = metadata.episodeId.takeIf { it > 0L } ?: epId
        val resolvedSeasonId = metadata.seasonId.takeIf { it > 0L } ?: seasonId
        val resolvedCid = metadata.cid.takeIf { it > 0L } ?: cid
        val resolvedAid = metadata.aid.takeIf { it > 0L } ?: aid
        val resolvedBvid = metadata.bvid.ifBlank { bvid }
        if (resolvedEpId <= 0L || resolvedCid <= 0L) {
            throw PgcPlaybackException("PGC_METADATA_FAILED", "未能从剧集详情定位有效分集")
        }

        val params = buildPlayurlParams(
            epId = resolvedEpId,
            seasonId = resolvedSeasonId,
            aid = resolvedAid,
            bvid = resolvedBvid,
            cid = resolvedCid,
        )
        val signed = try {
            wbiSigner.sign(params)
        } catch (failure: Exception) {
            LogHub.e(
                TAG,
                "PGC WBI signing failed epId=$resolvedEpId error=${LogRedaction.reason(failure)}",
            )
            throw PgcPlaybackException("PGC_PLAYURL_FAILED", "无法签名番剧播放请求", cause = failure)
        }

        val payload = playurlFallback.resolve(signed, cookie, resolvedEpId)

        val title = metadata.episodeTitle
            .ifBlank { metadata.seasonTitle }
            .ifBlank { titleFallback }
            .ifBlank { "PGC ep$resolvedEpId" }
        val selected = parsePlay(
            play = payload,
            aid = resolvedAid,
            cid = resolvedCid,
            bvid = resolvedBvid,
            epId = resolvedEpId,
            seasonId = resolvedSeasonId,
            title = title,
            cover = metadata.episodeCover.ifBlank { metadata.seasonCover },
            durationMs = metadata.durationMs,
        )
        val protectedInfo = if (selected.isDrm) psshReader.attach(selected) else selected
        LogHub.i(
            TAG,
            "PGC resolved epId=$resolvedEpId seasonId=$resolvedSeasonId aid=$resolvedAid cid=$resolvedCid " +
                "qn=${protectedInfo.video.id} size=${protectedInfo.video.width}x${protectedInfo.video.height} " +
                "codec=${protectedInfo.video.codecid} drm=${protectedInfo.isDrm} preview=${protectedInfo.isPreview} " +
                "hasPaid=${protectedInfo.hasPaid} videoHost=${LogRedaction.host(protectedInfo.video.url)} " +
                "audioHost=${LogRedaction.host(protectedInfo.audio?.url)}",
        )
        return protectedInfo
    }

    private fun requestPlayurl(
        path: String,
        params: Map<String, String>,
        cookie: String,
        purpose: String,
    ): PlayurlEnvelope {
        val url = params.entries.fold("https://api.bilibili.com$path".toHttpUrl()) { builder, (key, value) ->
            builder.newBuilder().addQueryParameter(key, value).build()
        }
        val root = getJson(url, cookie, purpose)
        val code = root.int("code", -1)
        val message = root.string("message").ifBlank { root.string("msg") }
        val play = findPlayPayload(root)
        LogHub.i(
            TAG,
            "PGC playurl response endpoint=$purpose code=$code hasMedia=${play != null} " +
                "keys=${play?.keySet()?.joinToString(",")?.take(160).orEmpty()}",
        )
        return PlayurlEnvelope(code, message, play)
    }

    private fun findPlayPayload(root: JsonObject): JsonObject? {
        val candidates = listOfNotNull(
            root.objectOrNull("result")?.objectOrNull("video_info"),
            root.objectOrNull("result"),
            root.objectOrNull("data")?.objectOrNull("video_info"),
            root.objectOrNull("data"),
        )
        return candidates.firstOrNull(::hasPlayableMedia)
    }

    private fun parsePlay(
        play: JsonObject,
        aid: Long,
        cid: Long,
        bvid: String,
        epId: Long,
        seasonId: Long,
        title: String,
        cover: String,
        durationMs: Long,
    ): BiliApi.PlayInfo {
        val dash = play.objectOrNull("dash")
        val videos = dash?.array("video")?.mapNotNull { it.asObjectOrNull()?.let(CdnCandidateSelector::parseTrack) }
            .orEmpty()
        val audios = buildList {
            dash?.array("audio")?.mapNotNullTo(this) { it.asObjectOrNull()?.let(CdnCandidateSelector::parseTrack) }
            dash?.objectOrNull("dolby")?.array("audio")
                ?.mapNotNullTo(this) { it.asObjectOrNull()?.let { item -> CdnCandidateSelector.parseTrack(item, true) } }
            dash?.objectOrNull("flac")?.array("audio")
                ?.mapNotNullTo(this) { it.asObjectOrNull()?.let { item -> CdnCandidateSelector.parseTrack(item) } }
        }.distinctBy { it.url }
        val isDrm = play.bool("is_drm")
        val isPreview = play.bool("is_preview")
        val hasPaid = play.bool("has_paid")
        val durationSec = when {
            play.long("duration", 0L) > 0L -> play.long("duration", 0L)
            play.long("timelength", 0L) > 0L -> play.long("timelength", 0L) / 1_000L
            play.long("time_length", 0L) > 0L -> play.long("time_length", 0L) / 1_000L
            durationMs > 0L -> durationMs / 1_000L
            else -> 0L
        }

        if (videos.isNotEmpty()) {
            val selectedVideo = selectHighestVideoTrack(videos)
                ?: error("video track list became empty")
            val selectedAudio = CdnCandidateSelector.pickAudio(audios)
            LogHub.i(
                TAG,
                "PGC dash parsed videoTracks=${videos.size} audioTracks=${audios.size} " +
                    "quality=${play.int("quality", selectedVideo.id)} accept=${play.intArray("accept_quality").joinToString(",")}" +
                    " drm=$isDrm preview=$isPreview paid=$hasPaid",
            )
            return BiliApi.PlayInfo(
                title = title,
                bvid = bvid,
                aid = aid,
                cid = cid,
                durationSec = durationSec,
                video = selectedVideo,
                audio = selectedAudio,
                progressiveUrl = null,
                videos = videos,
                audios = audios,
                cover = cover,
                isPgc = true,
                epId = epId,
                seasonId = seasonId,
                hasPaid = hasPaid,
                isDrm = isDrm,
                isPreview = isPreview,
                actualQuality = play.int("quality", selectedVideo.id),
                acceptQualities = play.intArray("accept_quality"),
                qualityLabels = BiliApi.qualityLabelsFrom(play),
            )
        }

        val durl = play.array("durl")
        if (!isDrm && (durl?.size() ?: 0) > 0) {
            val first = durl!!.firstOrNull()?.asObjectOrNull()
                ?: throw PgcPlaybackException("PGC_MEDIA_UNAVAILABLE", "渐进式播放信息为空")
            val primary = first.string("url")
            val candidates = CdnCandidateSelector.order(listOf(primary) + CdnCandidateSelector.backupUrls(first))
            val selected = candidates.firstOrNull().orEmpty()
            if (selected.isBlank()) throw PgcPlaybackException("PGC_MEDIA_UNAVAILABLE", "番剧未返回有效播放地址")
            val lengthMs = first.long("length", 0L)
            val track = BiliApi.Track(
                id = play.int("quality", 0), codecid = 0, width = 0, height = 0,
                bandwidth = 0L, url = selected, backup = candidates.drop(1),
                pcdn = CdnCandidateSelector.isPcdn(selected),
            )
            return BiliApi.PlayInfo(
                title, bvid, aid, cid,
                if (lengthMs > 0L) lengthMs / 1_000L else durationSec,
                track, null, selected,
                videos = listOf(track), audios = emptyList(), cover = cover,
                isPgc = true, epId = epId, seasonId = seasonId,
                hasPaid = hasPaid, isPreview = isPreview,
                actualQuality = play.int("quality", track.id),
                acceptQualities = play.intArray("accept_quality"),
                qualityLabels = BiliApi.qualityLabelsFrom(play),
            )
        }

        val code = if (isPreview) "PGC_PREVIEW_ONLY" else "PGC_MEDIA_UNAVAILABLE"
        throw PgcPlaybackException(
            code,
            if (isPreview) "该剧集当前仅返回预览片段，未取得完整播放流" else "B 站未返回可播放的番剧媒体轨道",
        )
    }

    private fun getJson(url: HttpUrl, cookie: String, purpose: String): JsonObject {
        val started = SystemClock.elapsedRealtime()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BiliApi.UA)
            .header("Referer", WEB_REFERER)
            .header("Origin", WEB_ORIGIN)
            .header("Accept", "application/json, text/plain, */*")
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    LogHub.e(
                        TAG,
                        "PGC request failed purpose=$purpose status=${response.code} " +
                            "elapsed=${SystemClock.elapsedRealtime() - started}ms",
                    )
                    throw PgcPlaybackException(
                        if (purpose == "pgc-season" || purpose == "pgc-season-section") {
                            "PGC_METADATA_FAILED"
                        } else "PGC_PLAYURL_FAILED",
                        "B 站请求失败（HTTP ${response.code}）",
                        httpStatus = response.code,
                    )
                }
                val raw = response.body?.string().orEmpty()
                LogHub.i(
                    TAG,
                    "PGC request complete purpose=$purpose status=${response.code} " +
                        "bytes=${raw.length} elapsed=${SystemClock.elapsedRealtime() - started}ms",
                )
                return try {
                    JsonParser.parseString(raw).asJsonObject
                } catch (failure: Exception) {
                    throw PgcPlaybackException("PGC_RESPONSE_INVALID", "B 站返回的番剧数据格式无效", cause = failure)
                }
            }
        } catch (failure: PgcPlaybackException) {
            throw failure
        } catch (failure: IOException) {
            LogHub.e(TAG, "PGC request I/O error purpose=$purpose error=${LogRedaction.reason(failure)}")
            throw PgcPlaybackException("PGC_NETWORK_FAILED", "番剧网络请求失败", cause = failure)
        }
    }

    companion object {
        private const val TAG = "PgcResolver"
        private const val WEB_REFERER = "https://www.bilibili.com/"
        private const val WEB_ORIGIN = "https://www.bilibili.com"

        internal fun buildPlayurlParams(
            epId: Long,
            seasonId: Long,
            aid: Long,
            bvid: String,
            cid: Long,
        ): Map<String, String> = buildMap {
            put("ep_id", epId.toString())
            put("cid", cid.toString())
            put("qn", "127")
            put("fnval", "12240")
            put("fnver", "0")
            put("fourk", "1")
            put("voice_balance", "1")
            put("gaia_source", "pre-load")
            put("isGaiaAvoided", "true")
            put("web_location", "1315873")
            put("try_look", "1")
            if (seasonId > 0L) put("season_id", seasonId.toString())
            if (aid > 0L) put("avid", aid.toString())
            if (bvid.isNotBlank()) put("bvid", bvid)
        }

        internal fun selectHighestVideoTrack(videos: List<BiliApi.Track>): BiliApi.Track? =
            (videos.filter { CdnCandidateSelector.isSupportedVideoCodec(it.codecid) }.ifEmpty { videos })
                .maxWithOrNull(
                compareBy<BiliApi.Track> { it.id }
                    .thenBy { it.width.toLong() * it.height.toLong() }
                    .thenBy { codecRank(it.codecid) },
                )

        internal fun parsePlayurlPayload(raw: String): PlayurlEnvelope {
            val root = JsonParser.parseString(raw).asJsonObject
            return PlayurlEnvelope(
                code = root.int("code", -1),
                message = root.string("message").ifBlank { root.string("msg") },
                play = findPayload(root),
            )
        }

        private fun findPayload(root: JsonObject): JsonObject? = listOfNotNull(
            root.objectOrNull("result")?.objectOrNull("video_info"),
            root.objectOrNull("result"),
            root.objectOrNull("data")?.objectOrNull("video_info"),
            root.objectOrNull("data"),
        ).firstOrNull(::hasPlayableMedia)

        private fun hasPlayableMedia(candidate: JsonObject): Boolean {
            val dashHasVideo = candidate.objectOrNull("dash")?.array("video")
                ?.any { element ->
                    val track = element.asObjectOrNull() ?: return@any false
                    track.string("baseUrl").ifBlank { track.string("base_url") }.isNotBlank() ||
                        CdnCandidateSelector.backupUrls(track).isNotEmpty()
                }
                ?: false
            val durlHasAddress = candidate.array("durl")?.any { element ->
                element.asObjectOrNull()?.let { segment ->
                    segment.string("url").isNotBlank() || CdnCandidateSelector.backupUrls(segment).isNotEmpty()
                } == true
            } ?: false
            return dashHasVideo || durlHasAddress
        }

        private fun codecRank(codecid: Int): Int = when (codecid) {
            12 -> 3
            13 -> 2
            7 -> 1
            else -> 0
        }
    }

    internal data class PlayurlEnvelope(val code: Int, val message: String, val play: JsonObject?)


}

/** Structured failure that keeps the UI message separate from network diagnostics. */
/** Minimal ISO-BMFF PSSH reader. Returns the complete Widevine PSSH atom. */
internal fun JsonObject.array(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

internal fun JsonObject.string(name: String): String =
    runCatching { get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty() }.getOrDefault("")

internal fun JsonObject.long(name: String, fallback: Long): Long =
    runCatching { get(name)?.takeUnless { it.isJsonNull }?.asLong ?: fallback }.getOrDefault(fallback)

internal fun JsonObject.int(name: String, fallback: Int): Int =
    runCatching { get(name)?.takeUnless { it.isJsonNull }?.asInt ?: fallback }.getOrDefault(fallback)

private fun JsonObject.bool(name: String): Boolean {
    val value = get(name)?.takeUnless { it.isJsonNull } ?: return false
    return runCatching {
        when {
            value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean
            else -> value.asString == "1" || value.asString.equals("true", ignoreCase = true)
        }
    }.getOrDefault(false)
}

private fun JsonObject.intArray(name: String): List<Int> = array(name)
    ?.mapNotNull { element -> runCatching { element.asInt }.getOrNull() }
    .orEmpty()

internal fun JsonArray.asObjectOrNull(): JsonObject? =
    firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonElement.asObjectOrNull(): JsonObject? =
    takeIf { it.isJsonObject }?.asJsonObject
