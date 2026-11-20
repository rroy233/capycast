package com.roy233.tvcast.feature.cast.domain

/** A collection item uses video identity; pages retain their own cid. */
data class CollectionVideo(val aid: Long, val bvid: String, val cid: Long, val title: String)

data class CastCollection(
    val videos: List<CollectionVideo>,
    val currentAid: Long,
    val pages: List<Long>,
) {
    fun next(aid: Long, cid: Long): CollectionVideo? {
        if (aid != currentAid) return null
        val current = videos.indexOfFirst { it.aid == aid }
        if (current < 0) return null
        val page = pages.indexOf(cid)
        if (page < 0) return null
        if (page >= 0 && page + 1 < pages.size) {
            return videos[current].copy(cid = pages[page + 1])
        }
        return videos.getOrNull(current + 1)?.takeIf { it.aid > 0 && it.cid > 0 }
    }
}

fun interface CastCollectionRepository {
    suspend fun load(aid: Long): CastCollection?
}
