package com.roy233.tvcast.data.bili

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.feature.playback.model.PgcPlaybackFailure as PgcPlaybackException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 查询季和分集元数据，保持原有分集匹配及 section 回退顺序。 */
internal class PgcMetadataResolver(
    private val getJson: (HttpUrl, String, String) -> JsonObject,
) {
    private companion object {
        const val TAG = "PgcResolver"
        const val SEASON_PATH = "https://api.bilibili.com/pgc/view/web/season"
        const val SEASON_SECTION_PATH = "https://api.bilibili.com/pgc/web/season/section"
    }

    fun loadEpisodeMetadata(
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

}

internal data class EpisodeMetadata(
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

