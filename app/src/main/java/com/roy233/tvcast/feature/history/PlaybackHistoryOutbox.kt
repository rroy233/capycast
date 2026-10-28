package com.roy233.tvcast.feature.history

/** 暂存尚未送达 B 站的播放记录快照的端口。 */
interface PlaybackHistoryOutbox {
    /** 返回指定账号的待上报快照，按更新时间从旧到新排列。 */
    fun pendingForAccount(mid: Long): List<PlaybackHistoryReport>

    /** 保存或更新同一播放会话的最新进度。 */
    fun upsert(report: PlaybackHistoryReport)

    /** 仅在服务端确认的是当前快照时删除。 */
    fun acknowledge(sessionId: String, updatedAtMs: Long)

    /** 关闭设备级同步开关时删除所有账号的待发送快照。 */
    fun clearAll()
}
