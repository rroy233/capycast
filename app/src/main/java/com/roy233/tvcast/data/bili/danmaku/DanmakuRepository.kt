package com.roy233.tvcast.data.bili.danmaku

/**
 * 弹幕数据缓存。
 *
 * 分段按访问顺序保留最近 4 段；元数据保留最近 4 个 cid。
 * 网络请求在锁外执行，避免预取阻塞当前段读取。
 */
class DanmakuRepository(
    private val api: DanmakuApi = DanmakuApi(),
) {

    private val segmentCache = LinkedHashMap<Int, DmSegment>(8, 0.75f, true)
    private val viewCache = LinkedHashMap<Long, DmViewInfo>(8, 0.75f, true)

    /** 读取元数据；已缓存则直接返回。 */
    fun view(cid: Long): DmViewInfo {
        synchronized(viewCache) {
            viewCache[cid]?.let { return it }
        }
        val loaded = api.fetchView(cid)
        synchronized(viewCache) {
            viewCache[cid] = loaded
            trim(viewCache)
        }
        return loaded
    }

    /** 读取分段；已缓存则直接返回。 */
    fun segment(aid: Long, cid: Long, segmentIndex: Int): DmSegment {
        synchronized(segmentCache) {
            segmentCache[segmentIndex]?.let { return it }
        }
        val loaded = api.fetchSegment(aid, cid, segmentIndex)
        synchronized(segmentCache) {
            segmentCache[segmentIndex] = loaded
            trim(segmentCache)
        }
        return loaded
    }

    /** 已缓存的分段；不存在返回 null。 */
    fun cachedSegment(segmentIndex: Int): DmSegment? {
        synchronized(segmentCache) {
            return segmentCache[segmentIndex]
        }
    }

    /** 清空所有缓存。 */
    fun clear() {
        synchronized(segmentCache) { segmentCache.clear() }
        synchronized(viewCache) { viewCache.clear() }
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
