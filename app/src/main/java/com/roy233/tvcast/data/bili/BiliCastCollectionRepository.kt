package com.roy233.tvcast.data.bili

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.roy233.tvcast.data.auth.BiliSessionReader
import com.roy233.tvcast.feature.cast.domain.CastCollection
import com.roy233.tvcast.feature.cast.domain.CastCollectionRepository
import com.roy233.tvcast.feature.cast.domain.CollectionVideo
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Reads collection metadata independently of playback resolution. */
class BiliCastCollectionRepository(
    private val sessionReader: BiliSessionReader,
    client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build(),
) : CastCollectionRepository {
    private val http = BiliHttpClient(client, BiliApi.UA)

    override suspend fun load(aid: Long): CastCollection? {
        val text =
            http
                .getBytesCancellable(
                    "https://api.bilibili.com/x/web-interface/view?aid=$aid",
                    "cast-collection",
                    sessionReader.cookie(),
                )
                .toString(Charsets.UTF_8)
        val root = JsonParser.parseString(text).asJsonObject
        check(root.get("code")?.asInt == 0) { "collection view API failed" }
        return parseCollection(
            root.getAsJsonObject("data") ?: error("collection view data missing"),
            aid,
        )
    }
}

internal fun parseCollection(data: JsonObject, aid: Long): CastCollection? {
    val season = data.getAsJsonObject("ugc_season") ?: return null
    val sections = season.getAsJsonArray("sections") ?: return null
    val videos = sections.flatMap { section ->
        section.asJsonObject
            .getAsJsonArray("episodes")
            ?.map { episode ->
                val item = episode.asJsonObject
                val arc = item.getAsJsonObject("arc")
                CollectionVideo(
                    aid = item.get("aid")?.asLong ?: 0L,
                    bvid = item.get("bvid")?.asString ?: arc?.get("bvid")?.asString.orEmpty(),
                    cid = item.get("cid")?.asLong ?: 0L,
                    title = item.get("title")?.asString.orEmpty(),
                )
            }
            .orEmpty()
    }
    val pages =
        data.getAsJsonArray("pages")?.map { it.asJsonObject.get("cid")?.asLong ?: 0L }.orEmpty()
    return CastCollection(videos, aid, pages)
}
