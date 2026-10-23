package com.roy233.tvcast.platform.player

import org.json.JSONObject
import org.videolan.libvlc.Media
import org.videolan.libvlc.interfaces.IMedia

/**
 * 保存一次播放会话的可变状态。
 *
 * 状态由播放器事件线程、监控任务和远程控制线程共同访问；跨线程读取的字段使用
 * `@Volatile`，其余字段只在主线程读写。将状态从播放器门面中拆出，可以避免
 * 事件记录、监控和报告三个职责各自维护一份互相漂移的计时变量。
 */
internal class VlcPlaybackState {

    /** 当前媒体，保留引用以便读取统计和轨道。 */
    @Volatile
    var currentMedia: Media? = null

    /** 当前是否存在活动的播放监控会话。 */
    @Volatile
    var active: Boolean = false

    /** 当前媒体是否为直播；诊断日志仅对直播地址保留主机名。 */
    @Volatile
    var currentIsLive: Boolean = false

    /** 最近一次打开媒体的单调时刻。 */
    var mediaOpenedAt: Long = 0L

    /** 是否已经收到 Opening 事件。 */
    var openingSeen: Boolean = false

    /** 第一次收到 Opening 事件的时刻。 */
    var openingAt: Long = 0L

    /** 是否已经收到 Playing 事件。 */
    var playingSeen: Boolean = false

    /** 最近一次收到 Playing 事件的时刻。 */
    var playingAt: Long = 0L

    /** 是否已经收到至少一个可用的视频输出。 */
    var voutSeen: Boolean = false

    /** 是否已经把本次会话的视频输出异常交给播放器执行恢复。 */
    var voutRecoveryRequested: Boolean = false

    /** 第一次收到视频输出事件的时刻。 */
    var voutAt: Long = 0L

    /** 第一次 Playing 事件是否已经记录。 */
    var firstPlayingLogged: Boolean = false

    /** 是否已经成功绑定视频视图。 */
    var viewsAttached: Boolean = false

    /** 最近一次播放位置前进的时刻。 */
    @Volatile
    var lastProgressAt: Long = 0L

    /** 最近一次已知的播放位置。 */
    @Volatile
    var lastPositionMs: Long = -1L

    /** 最近一次统计采样的时刻。 */
    var lastStatsAt: Long = 0L

    /** 最近一次时间线位置日志的时刻。 */
    var lastTimeLogAt: Long = 0L

    /** 最近一次缓冲进度分桶。 */
    var lastBufferingBucket: Int = -1

    /** 是否已经提示原生日志为空。 */
    var vlcLogWarningLogged: Boolean = false

    /** 是否已经提示统计信息不可用。 */
    var statsUnavailableLogged: Boolean = false

    /** 是否已经记录第一次统计基线。 */
    var statsFirstLogged: Boolean = false

    /** 上一次统计采样，用于计算增量。 */
    var previousStats: IMedia.Stats? = null

    /** 最近一次统计采样 JSON。 */
    @Volatile
    var lastStats: JSONObject? = null

    /** 已经提示过一次的异常类型。 */
    val warnedOnce: MutableSet<String> = mutableSetOf()

    /** 各类异常最近一次提示的时刻。 */
    val warnedAt: MutableMap<String, Long> = mutableMapOf()

    /** 已经写入诊断报告的媒体轨道键，避免重复快照。 */
    val loggedMediaTracks: MutableSet<String> = mutableSetOf()

    /**
     * 重置一次新播放所需的监控状态。
     *
     * @param openAt 本次打开媒体的单调时刻。
     */
    fun reset(openAt: Long) {
        active = false
        currentIsLive = false
        mediaOpenedAt = openAt
        openingSeen = false
        playingSeen = false
        voutSeen = false
        voutRecoveryRequested = false
        firstPlayingLogged = false
        openingAt = 0L
        playingAt = 0L
        voutAt = 0L
        lastProgressAt = openAt
        lastPositionMs = -1L
        lastStatsAt = 0L
        lastTimeLogAt = 0L
        lastBufferingBucket = -1
        vlcLogWarningLogged = false
        statsUnavailableLogged = false
        statsFirstLogged = false
        previousStats = null
        lastStats = null
        warnedOnce.clear()
        warnedAt.clear()
        loggedMediaTracks.clear()
    }
}
