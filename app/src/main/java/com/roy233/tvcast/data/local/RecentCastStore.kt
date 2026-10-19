package com.roy233.tvcast.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.roy233.tvcast.data.bili.BiliApi
import com.roy233.tvcast.ui.model.RecentCast

/**
 * 持久化用户可见的最近投屏记录。
 *
 * @param context 用于访问应用偏好的上下文。
 */
class RecentCastStore(context: Context) {

    /** 用于序列化投屏记录的 JSON 转换器。 */
    private val gson = Gson()
    /** 保存投屏记录的偏好存储。 */
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取最近投屏记录，读取失败时返回空列表。 */
    fun load(): List<RecentCast> = runCatching {
        val raw = prefs.getString(KEY_ITEMS, "") ?: return emptyList()
        val type = object : TypeToken<List<RecentCast>>() {}.type
        (gson.fromJson<List<RecentCast>>(raw, type) ?: emptyList()).take(MAX_ITEMS)
    }.getOrDefault(emptyList())

    /**
     * 将一次播放信息加入最近投屏记录并持久化。
     *
     * @param current 当前记录列表。
     * @param info 待加入的播放信息。
     * @return 去重并限制数量后的最新记录列表。
     */
    fun add(current: List<RecentCast>, info: BiliApi.PlayInfo): List<RecentCast> {
        if (info.aid <= 0 || info.cid <= 0 || info.title.isBlank()) return current
        val entry = RecentCast(
            aid = info.aid,
            cid = info.cid,
            title = info.title,
            cover = info.cover,
            quality = info.qualityLabel,
            at = System.currentTimeMillis(),
        )
        val list = (listOf(entry) + current.filterNot {
            it.aid == entry.aid && it.cid == entry.cid
        }).take(MAX_ITEMS)
        runCatching { prefs.edit().putString(KEY_ITEMS, gson.toJson(list)).apply() }
        return list
    }

    /** 清除所有已保存的最近投屏记录。 */
    fun clear() {
        prefs.edit().remove(KEY_ITEMS).apply()
    }

    private companion object {
        /** 偏好文件名。 */
        const val PREFS = "recents"
        /** 记录列表使用的偏好键。 */
        const val KEY_ITEMS = "items"
        /** 最多保存的记录数。 */
        const val MAX_ITEMS = 8
    }
}
