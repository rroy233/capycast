package com.roy233.tvcast.data.bili.subtitle

import com.roy233.tvcast.feature.subtitle.domain.SubtitleContent
import com.roy233.tvcast.feature.subtitle.domain.SubtitleCue
import com.roy233.tvcast.feature.subtitle.domain.SubtitleDocument
import com.roy233.tvcast.feature.subtitle.domain.SubtitleRepository
import com.roy233.tvcast.feature.subtitle.domain.SubtitleTrack
import com.roy233.tvcast.feature.subtitle.domain.SubtitleTrackKey

/**
 * 字幕数据缓存。
 *
 * 缓存键为内容身份：轨道列表按 `(aid, cid, ep)`，正文按内容身份加轨道键；跨会话保留最近 4 条。 网络请求在锁外执行，避免一次正文加载阻塞其它读取。
 */
class BiliSubtitleRepository(
    private val remote: SubtitleRemote,
) : SubtitleRepository {

    private val tracksCache = LinkedHashMap<SubtitleContent, List<SubtitleTrack>>(8, 0.75f, true)
    private val cuesCache = LinkedHashMap<CuesKey, List<SubtitleCue>>(8, 0.75f, true)

    /** 拉取视频的全部可用字幕轨道。 */
    override suspend fun tracks(content: SubtitleContent): List<SubtitleTrack> {
        synchronized(tracksCache) {
            tracksCache[content]?.let {
                return it
            }
        }
        val loaded = remote.fetchTracks(content)
        synchronized(tracksCache) {
            tracksCache[content] = loaded
            trim(tracksCache)
        }
        return loaded
    }

    /** 拉取指定轨道的完整正文。 */
    override suspend fun document(
        content: SubtitleContent,
        track: SubtitleTrack,
    ): SubtitleDocument {
        val key = CuesKey(content = content, trackKey = track.key)
        synchronized(cuesCache) {
            cuesCache[key]?.let {
                return SubtitleDocument(trackKey = track.key, cues = it)
            }
        }
        val loaded = remote.fetchCues(track)
        synchronized(cuesCache) {
            cuesCache[key] = loaded
            trim(cuesCache)
        }
        return SubtitleDocument(trackKey = track.key, cues = loaded)
    }

    private fun <K, V> trim(cache: LinkedHashMap<K, V>) {
        while (cache.size > MAX_ENTRIES) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
    }

    /** 正文缓存键。 */
    private data class CuesKey(
        val content: SubtitleContent,
        val trackKey: SubtitleTrackKey,
    )

    private companion object {
        const val MAX_ENTRIES = 4
    }
}
