package com.roy233.tvcast.data.bili.danmaku

import com.roy233.tvcast.feature.danmaku.domain.DanmakuRepository
import com.roy233.tvcast.feature.danmaku.domain.DanmakuSegmentKey
import com.roy233.tvcast.feature.danmaku.domain.DmSegment
import com.roy233.tvcast.feature.danmaku.domain.DmViewInfo
import com.roy233.tvcast.feature.danmaku.domain.VodDanmakuContent

/**
 * 弹幕数据缓存。
 *
 * 缓存键为内容身份：分段按 `(aid, cid) + 段号`，元数据按 `(aid, cid)`；跨会话保留最近 4 条。 网络请求在锁外执行，避免预取阻塞当前段读取。
 */
class BiliDanmakuRepository(
    private val remote: DanmakuRemote,
) : DanmakuRepository {

    private val segmentCache = LinkedHashMap<DanmakuSegmentKey, DmSegment>(8, 0.75f, true)
    private val viewCache = LinkedHashMap<VodDanmakuContent, DmViewInfo>(8, 0.75f, true)

    /** 读取元数据；已按内容身份缓存则直接返回。 */
    override suspend fun view(content: VodDanmakuContent): DmViewInfo {
        synchronized(viewCache) {
            viewCache[content]?.let {
                return it
            }
        }
        val loaded = remote.fetchView(content.cid)
        synchronized(viewCache) {
            viewCache[content] = loaded
            trim(viewCache)
        }
        return loaded
    }

    /** 读取分段；已按内容身份与段号缓存则直接返回。 */
    override suspend fun segment(key: DanmakuSegmentKey): DmSegment {
        synchronized(segmentCache) {
            segmentCache[key]?.let {
                return it
            }
        }
        val loaded = remote.fetchSegment(key.content.aid, key.content.cid, key.index)
        synchronized(segmentCache) {
            segmentCache[key] = loaded
            trim(segmentCache)
        }
        return loaded
    }

    private fun <K, V> trim(cache: LinkedHashMap<K, V>) {
        while (cache.size > MAX_ENTRIES) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
    }

    private companion object {
        const val MAX_ENTRIES = 4
    }
}
