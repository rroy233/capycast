package com.roy233.tvcast.data.bili

import com.roy233.tvcast.data.bili.pgc.PsshBoxParser

import android.os.SystemClock
import android.util.Base64
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
        val metadata = loadEpisodeMetadata(epId, seasonId, cid, aid, cookie)
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

        val v2 = requestPlayurl(V2_PLAYURL_PATH, signed, cookie, "pgc-playurl-v2")
        val payload = when {
            v2.code == 0 && v2.play != null -> v2.play
            v2.code == 0 || v2.code in V2_LEGACY_FALLBACK_CODES -> {
                LogHub.w(
                    TAG,
                    "PGC v2 response requires legacy fallback epId=$resolvedEpId " +
                        "code=${v2.code} hasPayload=${v2.play != null}",
                )
                val legacy = requestPlayurl(LEGACY_PLAYURL_PATH, signed, cookie, "pgc-playurl-legacy")
                if (legacy.code != 0) {
                    throw playurlFailure("legacy", resolvedEpId, legacy.code, legacy.message)
                }
                legacy.play ?: throw PgcPlaybackException(
                    noPlayableCode(legacy.play),
                    "B 站未返回可解析的番剧播放信息",
                    apiCode = legacy.code,
                )
            }
            else -> throw playurlFailure("v2", resolvedEpId, v2.code, v2.message)
        }

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
        val protectedInfo = if (selected.isDrm) attachPssh(selected) else selected
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

    private fun loadEpisodeMetadata(
        epId: Long,
        seasonId: Long,
        cid: Long,
        aid: Long,
        cookie: String,
    ): EpisodeMetadata {
        val season = getJson(
            SEASON_PATH.toHttpUrl().newBuilder().apply {
                if (epId > 0L) addQueryParameter("ep_id", epId.toString())
                else addQueryParameter("season_id", seasonId.toString())
            }.build(),
            cookie,
            "pgc-season",
        )
        val code = season.int("code", -1)
        if (code != 0) {
            LogHub.e(TAG, "PGC season rejected epId=$epId seasonId=$seasonId code=$code")
            throw PgcPlaybackException("PGC_METADATA_FAILED", "B 站未返回番剧详情", apiCode = code)
        }
        val rootData = season.objectOrNull("result") ?: season.objectOrNull("data")
            ?: throw PgcPlaybackException("PGC_METADATA_FAILED", "番剧详情缺少数据")
        val episodeList = episodesFrom(rootData)
        val selectedFromSeason = when {
            epId > 0L -> episodeList.firstOrNull { it.episodeId == epId }
            cid > 0L -> episodeList.firstOrNull { it.cid == cid }
            aid > 0L -> episodeList.firstOrNull { it.aid == aid }
            else -> null
        }
        val directEpisode = rootData.objectOrNull("episode")
            ?: rootData.objectOrNull("ep_info")
            ?: rootData.objectOrNull("episode_info")
        val direct = directEpisode?.let(::episodeFrom)
        val selectedDirect = direct?.takeIf { candidate ->
            when {
                epId > 0L -> candidate.episodeId == epId
                cid > 0L -> candidate.cid == cid
                aid > 0L -> candidate.aid == aid
                else -> false
            }
        }
        val episode = selectedFromSeason ?: selectedDirect ?: run {
            val resolvedSeason = rootData.long("season_id", seasonId)
            if (resolvedSeason <= 0L) null else loadSeasonSections(resolvedSeason, cookie).firstOrNull { candidate ->
                when {
                    epId > 0L -> candidate.episodeId == epId
                    cid > 0L -> candidate.cid == cid
                    aid > 0L -> candidate.aid == aid
                    else -> false
                }
            }
        }
        if (episode == null) {
            LogHub.e(
                TAG,
                "PGC episode not found epId=$epId seasonId=${rootData.long("season_id", seasonId)} " +
                    "cid=$cid aid=$aid episodes=${episodeList.size}",
            )
            throw PgcPlaybackException("PGC_METADATA_FAILED", "无法从番剧详情定位投屏分集")
        }
        return EpisodeMetadata(
            episodeId = episode.episodeId,
            seasonId = rootData.long("season_id", seasonId),
            aid = episode.aid,
            cid = episode.cid,
            bvid = episode.bvid,
            episodeTitle = listOf(episode.title, episode.longTitle).filter(String::isNotBlank).joinToString(" "),
            seasonTitle = rootData.string("title"),
            episodeCover = episode.cover,
            seasonCover = rootData.string("cover"),
            durationMs = episode.durationMs,
        )
    }

    private fun loadSeasonSections(seasonId: Long, cookie: String): List<EpisodeMetadata> {
        val url = "$SEASON_SECTION_PATH?season_id=$seasonId".toHttpUrl()
        val root = getJson(url, cookie, "pgc-season-section")
        val code = root.int("code", -1)
        if (code != 0) {
            LogHub.w(TAG, "PGC season section unavailable seasonId=$seasonId code=$code")
            return emptyList()
        }
        val result = root.objectOrNull("result") ?: root.objectOrNull("data") ?: return emptyList()
        val sections = mutableListOf<JsonObject>()
        result.objectOrNull("main_section")?.array("episodes")?.mapNotNullTo(sections) { it.asObjectOrNull() }
        result.array("section")?.forEach { section ->
            section.asObjectOrNull()?.array("episodes")?.mapNotNullTo(sections) { it.asObjectOrNull() }
        }
        return sections.map(::episodeFrom)
    }

    private fun episodesFrom(root: JsonObject): List<EpisodeMetadata> =
        root.array("episodes")?.mapNotNull { element -> element.asObjectOrNull()?.let(::episodeFrom) }.orEmpty()

    private fun episodeFrom(json: JsonObject) = EpisodeMetadata(
        episodeId = json.long("id", json.long("ep_id", 0L)),
        seasonId = json.long("season_id", 0L),
        aid = json.long("aid", 0L),
        cid = json.long("cid", 0L),
        bvid = json.string("bvid"),
        title = json.string("title"),
        longTitle = json.string("long_title"),
        cover = json.string("cover").replace("http://", "https://"),
        durationMs = json.long("duration", 0L),
    )

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

    private fun attachPssh(info: BiliApi.PlayInfo): BiliApi.PlayInfo {
        val tracks = listOfNotNull(info.video, info.audio)
        val enriched = tracks.map { track ->
            if (track.initializationRange.isBlank()) return@map track
            val pssh = fetchPssh(track)
            track.copy(psshBase64 = pssh)
        }
        val video = enriched.firstOrNull() ?: info.video
        val audio = enriched.getOrNull(1)
        if (enriched.none { it.psshBase64.isNotBlank() }) {
            throw PgcPlaybackException(
                "PGC_DRM_METADATA_INVALID",
                "受保护媒体缺少 Widevine 初始化数据",
            )
        }
        return info.copy(video = video, audio = audio)
    }

    private fun fetchPssh(track: BiliApi.Track): String {
        val range = parseByteRange(track.initializationRange)
            ?: throw PgcPlaybackException("PGC_DRM_METADATA_INVALID", "Widevine 初始化范围无效")
        if (range.second - range.first + 1L > MAX_INIT_RANGE_BYTES) {
            throw PgcPlaybackException("PGC_DRM_METADATA_INVALID", "Widevine 初始化数据范围过大")
        }
        val request = Request.Builder()
            .url(track.url)
            .header("User-Agent", BiliApi.UA)
            .header("Referer", WEB_REFERER)
            .header("Origin", WEB_ORIGIN)
            .header("Range", "bytes=${range.first}-${range.second}")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code != 206 && !response.isSuccessful) {
                    LogHub.e(
                        TAG,
                        "PGC DRM initialization range failed status=${response.code} " +
                            "host=${LogRedaction.host(track.url)}",
                    )
                    throw PgcPlaybackException("PGC_DRM_METADATA_INVALID", "读取 Widevine 初始化数据失败")
                }
                val bytes = response.body?.source()?.readByteArray(MAX_INIT_RANGE_BYTES + 1L)
                    ?: throw PgcPlaybackException("PGC_DRM_METADATA_INVALID", "Widevine 初始化数据为空")
                if (bytes.size.toLong() > MAX_INIT_RANGE_BYTES) {
                    throw PgcPlaybackException("PGC_DRM_METADATA_INVALID", "Widevine 初始化数据超出限制")
                }
                val pssh = PsshBoxParser.findWidevinePssh(bytes)
                if (pssh == null) {
                    LogHub.e(TAG, "PGC DRM init data has no Widevine PSSH host=${LogRedaction.host(track.url)}")
                    return ""
                }
                return Base64.encodeToString(pssh, Base64.NO_WRAP)
            }
        } catch (failure: PgcPlaybackException) {
            throw failure
        } catch (failure: Exception) {
            LogHub.e(
                TAG,
                "PGC DRM initialization read error=${LogRedaction.reason(failure)} " +
                    "host=${LogRedaction.host(track.url)}",
            )
            throw PgcPlaybackException("PGC_DRM_METADATA_INVALID", "读取 Widevine 初始化数据失败", cause = failure)
        }
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

    private fun playurlFailure(endpoint: String, epId: Long, code: Int, message: String) =
        PgcPlaybackException(
            errorCode = if (code == -101 || code == -111) "PGC_LOGIN_REQUIRED" else "PGC_PLAYURL_FAILED",
            userMessage = when (code) {
                -101, -111 -> "请重新登录 B 站账号后重试"
                -10403 -> "该番剧在当前地区不可播放"
                else -> "B 站拒绝了番剧播放请求（code=$code）"
            },
            apiCode = code,
        ).also {
            LogHub.e(
                TAG,
                "PGC playurl rejected endpoint=$endpoint epId=$epId code=$code " +
                    "message=${LogRedaction.text(message).take(120)}",
            )
        }

    private fun noPlayableCode(play: JsonObject?) =
        if (play?.bool("is_preview") == true) "PGC_PREVIEW_ONLY" else "PGC_MEDIA_UNAVAILABLE"

    companion object {
        private const val TAG = "PgcResolver"
        private const val SEASON_PATH = "https://api.bilibili.com/pgc/view/web/season"
        private const val SEASON_SECTION_PATH = "https://api.bilibili.com/pgc/web/season/section"
        private const val V2_PLAYURL_PATH = "/pgc/player/web/v2/playurl"
        private const val LEGACY_PLAYURL_PATH = "/pgc/player/web/playurl"
        private const val WEB_REFERER = "https://www.bilibili.com/"
        private const val WEB_ORIGIN = "https://www.bilibili.com"
        private const val MAX_INIT_RANGE_BYTES = 1024L * 1024L
        private val V2_LEGACY_FALLBACK_CODES = setOf(-400, -404)

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

        private fun parseByteRange(raw: String): Pair<Long, Long>? {
            val parts = raw.trim().split('-', limit = 2)
            if (parts.size != 2) return null
            val start = parts[0].toLongOrNull() ?: return null
            val end = parts[1].toLongOrNull() ?: return null
            return (start to end).takeIf { start >= 0L && end >= start }
        }

        private fun codecRank(codecid: Int): Int = when (codecid) {
            12 -> 3
            13 -> 2
            7 -> 1
            else -> 0
        }
    }

    internal data class PlayurlEnvelope(val code: Int, val message: String, val play: JsonObject?)

    private data class EpisodeMetadata(
        val episodeId: Long,
        val seasonId: Long,
        val aid: Long,
        val cid: Long,
        val bvid: String,
        val title: String = "",
        val longTitle: String = "",
        val episodeTitle: String = "",
        val seasonTitle: String = "",
        val cover: String = "",
        val episodeCover: String = "",
        val seasonCover: String = "",
        val durationMs: Long = 0L,
    )

    private data class SeasonEpisode(
        val episodeId: Long,
        val seasonId: Long,
        val aid: Long,
        val cid: Long,
        val bvid: String,
        val title: String,
        val longTitle: String,
        val cover: String,
        val durationMs: Long,
    )
}

/** Structured failure that keeps the UI message separate from network diagnostics. */
/** Minimal ISO-BMFF PSSH reader. Returns the complete Widevine PSSH atom. */
private fun JsonObject.array(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.string(name: String): String =
    runCatching { get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty() }.getOrDefault("")

private fun JsonObject.long(name: String, fallback: Long): Long =
    runCatching { get(name)?.takeUnless { it.isJsonNull }?.asLong ?: fallback }.getOrDefault(fallback)

private fun JsonObject.int(name: String, fallback: Int): Int =
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

private fun JsonArray.asObjectOrNull(): JsonObject? =
    firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonElement.asObjectOrNull(): JsonObject? =
    takeIf { it.isJsonObject }?.asJsonObject
