package com.roy233.tvcast.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.feature.history.PlaybackHistoryOutbox
import com.roy233.tvcast.feature.history.PlaybackHistoryReport

/** 暂存尚未送达 B 站的播放记录快照，记录中不保存账号凭据。 */
class PersistentPlaybackHistoryOutbox(context: Context) : PlaybackHistoryOutbox {

    private val gson = Gson()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    /** 返回指定账号的待上报快照，按更新时间从旧到新排列。 */
    override fun pendingForAccount(mid: Long): List<PlaybackHistoryReport> = synchronized(lock) {
        loadLocked().filter { it.accountMid == mid }.sortedBy { it.updatedAtMs }
    }

    /** 保存或更新同一播放会话的最新进度。 */
    override fun upsert(report: PlaybackHistoryReport) = synchronized(lock) {
        val existing = loadLocked().filterNot { it.sessionId == report.sessionId }
        val updated = (existing + report)
            .sortedByDescending { it.updatedAtMs }
            .take(MAX_ITEMS)
        if (updated.none { it.sessionId == report.sessionId }) {
            LogHub.w(TAG, "history outbox full; oldest snapshot was evicted")
        }
        persistLocked(updated)
    }

    /** 仅在服务端确认的是当前快照时删除；更新中的新进度会继续保留。 */
    override fun acknowledge(sessionId: String, updatedAtMs: Long) = synchronized(lock) {
        val remaining = loadLocked().filterNot {
            it.sessionId == sessionId && it.updatedAtMs == updatedAtMs
        }
        persistLocked(remaining)
    }

    /** 关闭设备级同步开关时删除所有账号的待发送快照。 */
    override fun clearAll() = synchronized(lock) {
        persistLocked(emptyList())
    }

    private fun loadLocked(): List<PlaybackHistoryReport> = runCatching {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        val type = object : TypeToken<List<PlaybackHistoryReport>>() {}.type
        gson.fromJson<List<PlaybackHistoryReport>>(raw, type)
            .orEmpty()
            .map(::normalizeLegacy)
            .take(MAX_ITEMS)
    }.onFailure { error ->
        LogHub.e(TAG, "history outbox read failed: ${error.javaClass.simpleName}", error)
        prefs.edit().remove(KEY_ITEMS).apply()
    }.getOrDefault(emptyList())

    private fun persistLocked(items: List<PlaybackHistoryReport>) {
        runCatching {
            if (items.isEmpty()) {
                prefs.edit().remove(KEY_ITEMS).apply()
            } else {
                prefs.edit().putString(KEY_ITEMS, gson.toJson(items)).apply()
            }
        }.onFailure { error ->
            LogHub.e(TAG, "history outbox write failed: ${error.javaClass.simpleName}", error)
        }
    }

    /**
     * Gson 绕过 Kotlin 默认参数。升级后读取旧队列时，新增的非空字符串字段会变为
     * null；这里重建快照，使旧记录继续可发送，并避免重试线程被空指针阻塞。
     */
    private fun normalizeLegacy(report: PlaybackHistoryReport): PlaybackHistoryReport =
        PlaybackHistoryReport(
            sessionId = report.sessionId,
            accountMid = report.accountMid,
            aid = report.aid,
            cid = report.cid,
            sid = report.sid,
            epid = report.epid,
            serialId = report.serialId,
            type = report.type,
            business = report.business.orEmptyCompat(),
            progressSeconds = report.progressSeconds,
            playDurationSeconds = report.playDurationSeconds,
            isInteracted = report.isInteracted,
            bvid = report.bvid.orEmptyCompat(),
            durationSeconds = report.durationSeconds,
            startTsSec = report.startTsSec,
            reportKind = report.reportKind,
            scene = report.scene.orEmptyCompat(),
            fromScene = report.fromScene.orEmptyCompat(),
            fromSpmid = report.fromSpmid.orEmptyCompat(),
            resourceFrom = report.resourceFrom.orEmptyCompat(),
            updatedAtMs = report.updatedAtMs,
        )

    private fun String?.orEmptyCompat(): String = this ?: ""

    private companion object {
        const val TAG = "PlaybackHistoryOutbox"
        const val PREFS = "playback_history_outbox"
        const val KEY_ITEMS = "items"
        const val MAX_ITEMS = 50
    }
}
