package com.roy233.tvcast.feature.history

import android.os.SystemClock
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.data.auth.AuthTokenStore
import com.roy233.tvcast.data.bili.LiveHeartbeatRequest
import com.roy233.tvcast.data.bili.LiveHeartbeatResponse
import com.roy233.tvcast.data.bili.PlaybackHistoryApi
import com.roy233.tvcast.data.bili.PlaybackHistoryReport
import com.roy233.tvcast.data.local.PlaybackHistoryOutbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.max

/** 负责将电视投屏的点播与直播进度同步到当前 B 站账号。 */
class PlaybackHistoryCoordinator(
    private val scope: CoroutineScope,
    private val authTokenStore: AuthTokenStore,
    private val outbox: PlaybackHistoryOutbox,
    private val api: PlaybackHistoryApi = PlaybackHistoryApi(),
    private val isEnabled: () -> Boolean,
    private val buildNumber: Int,
) {

    private var currentContent: PlaybackHistoryContent? = null
    private var activeSession: Session? = null
    private var isPlaying = false
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L
    private var flushJob: Job? = null
    private var heartbeatJob: Job? = null
    private var retryJob: Job? = scope.launch {
        while (isActive) {
            delay(RETRY_INTERVAL_MS)
            authTokenStore.load()?.let { retryPendingForAccount(it.mid) }
        }
    }

    /** 一次新的投屏请求会结束上一会话，即使两次投屏身份相同。 */
    fun onNewCast() {
        finish("new-cast")
        currentContent = null
        isPlaying = false
        lastPositionMs = 0L
        lastDurationMs = 0L
    }

    /** 播放器确认进入播放态后启动或恢复本次历史会话。 */
    fun onPlaying(content: PlaybackHistoryContent, positionMs: Long, durationMs: Long) {
        currentContent = content
        lastPositionMs = positionMs.coerceAtLeast(0L)
        lastDurationMs = durationMs.coerceAtLeast(0L)
        isPlaying = true
        ensureSession(content)
        activeSession?.let { session ->
            if (session.segmentStartedAtMs == null) {
                session.segmentStartedAtMs = SystemClock.elapsedRealtime()
            }
        }
        if (content.isLive) startLiveHeartbeatIfNeeded()
    }

    /** 播放器暂停时立刻保存并发送最新进度。 */
    fun onPaused(positionMs: Long, durationMs: Long) {
        updatePosition(positionMs, durationMs)
        isPlaying = false
        activeSession?.let { closePlaySegment(it) }
        stopLiveHeartbeat()
        reportActive("paused")
    }

    /** 由播放器轮询回调更新位置，并在 30 秒边界同步一次进度。 */
    fun onPosition(positionMs: Long, durationMs: Long, playing: Boolean) {
        updatePosition(positionMs, durationMs)
        if (playing && !isPlaying) {
            isPlaying = true
            activeSession?.let { session ->
                session.segmentStartedAtMs = SystemClock.elapsedRealtime()
            }
            currentContent?.takeIf { isEnabled() }?.let(::ensureSession)
            if (currentContent?.isLive == true) startLiveHeartbeatIfNeeded()
        } else if (!playing && isPlaying) {
            isPlaying = false
            activeSession?.let { closePlaySegment(it) }
            stopLiveHeartbeat()
        }

        val session = activeSession ?: return
        if (!isEnabled()) return
        if (SystemClock.elapsedRealtime() - session.lastReportedAtMs >= REPORT_INTERVAL_MS) {
            reportActive("periodic")
        }
    }

    /** 切换直播 CDN 后更新心跳所用的播放地址。 */
    fun updateLiveUrl(url: String) {
        val content = currentContent ?: return
        if (content.isLive && url.isNotBlank()) currentContent = content.copy(livePlayUrl = url)
    }

    /** 播放结束、停止、错误或 Activity 销毁时发送最后一次快照。 */
    fun finish(reason: String, ended: Boolean = false) {
        val session = activeSession ?: run {
            stopLiveHeartbeat()
            return
        }
        closePlaySegment(session)
        isPlaying = false
        stopLiveHeartbeat()
        if (isEnabled()) reportActive(reason, ended)
        LogHub.i(
            TAG,
            "history session finished reason=$reason live=${session.content.isLive} " +
                "type=${session.content.type} aid=${session.content.aid} cid=${session.content.cid} " +
                "position=${lastPositionMs}ms played=${playedDurationMs(session)}ms",
        )
        activeSession = null
    }

    /** 关闭同步时停止直播心跳并清除当前账号尚未发送的数据。 */
    fun onEnabledChanged(enabled: Boolean, accountMid: Long) {
        if (!enabled) {
            stopLiveHeartbeat()
            flushJob?.cancel()
            flushJob = null
            activeSession = null
            outbox.clearAll()
            LogHub.i(TAG, "playback history sync disabled account=$accountMid; all pending snapshots cleared")
            return
        }
        LogHub.i(TAG, "playback history sync enabled account=$accountMid")
        authTokenStore.load()?.let { retryPendingForAccount(it.mid) }
        if (isPlaying) currentContent?.let(::ensureSession)
    }

    /** 令牌就绪后重试该账号的队列，并接管当前仍在播放的媒体。 */
    fun onAccountAvailable(mid: Long) {
        retryPendingForAccount(mid)
        if (isPlaying) currentContent?.let(::ensureSession)
    }

    /** 退出登录时封存旧账号进度，同时保留播放器状态供下一账号接管。 */
    fun onAccountLoggedOut(playing: Boolean, positionMs: Long, durationMs: Long) {
        updatePosition(positionMs, durationMs)
        finish("logout")
        isPlaying = playing
        if (playing) activeSession = null
    }

    /** 登录或启动时只重试与当前账号匹配的快照。 */
    fun retryPendingForAccount(mid: Long) {
        if (!isEnabled() || mid <= 0L || flushJob?.isActive == true) return
        val token = authTokenStore.load() ?: return
        if (token.mid != mid) {
            LogHub.w(TAG, "history retry skipped: token account changed requested=$mid current=${token.mid}")
            return
        }
        flushJob = scope.launch {
            while (isActive && isEnabled()) {
                val report = outbox.pendingForAccount(mid).firstOrNull() ?: break
                val currentToken = authTokenStore.load()
                if (currentToken == null || currentToken.mid != report.accountMid) {
                    LogHub.w(TAG, "history retry stopped: no matching account for snapshot=${report.sessionId}")
                    break
                }
                try {
                    withContext(Dispatchers.IO) {
                        api.reportProgress(currentToken.accessKey, report)
                    }
                    outbox.acknowledge(report.sessionId, report.updatedAtMs)
                    LogHub.i(
                        TAG,
                        "history report delivered session=${report.sessionId} type=${report.type} " +
                            "aid=${report.aid} cid=${report.cid} progress=${report.progressSeconds}s " +
                            "played=${report.playDurationSeconds}s",
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    LogHub.e(
                        TAG,
                        "history report failed session=${report.sessionId} type=${report.type} " +
                            "aid=${report.aid} cid=${report.cid}: ${LogRedaction.reason(error)}",
                        error,
                    )
                    break
                }
            }
        }
    }

    /** Activity 销毁前将当前快照入队，并停止后台任务。 */
    fun close() {
        finish("activity-destroy")
        stopLiveHeartbeat()
        retryJob?.cancel()
        retryJob = null
    }

    private fun ensureSession(content: PlaybackHistoryContent) {
        if (!isEnabled()) return
        val token = authTokenStore.load() ?: run {
            LogHub.w(TAG, "history session skipped: TV login required stableKey=${content.stableKey}")
            return
        }
        val current = activeSession
        if (current != null && current.stableKey == content.stableKey && current.accountMid == token.mid) {
            current.content = content
            return
        }
        val wasPlaying = isPlaying
        if (current != null) finish("content-changed")
        isPlaying = wasPlaying
        val session = Session(
            id = UUID.randomUUID().toString(),
            stableKey = content.stableKey,
            accountMid = token.mid,
            content = content,
            lastReportedAtMs = SystemClock.elapsedRealtime(),
            segmentStartedAtMs = if (isPlaying) SystemClock.elapsedRealtime() else null,
        )
        activeSession = session
        LogHub.i(
            TAG,
            "history session started live=${content.isLive} type=${content.type} " +
                "aid=${content.aid} cid=${content.cid} sid=${content.sid} epid=${content.epid} " +
                "account=${token.mid}",
        )
        reportActive("started")
        if (content.isLive && isPlaying) startLiveHeartbeatIfNeeded()
    }

    private fun updatePosition(positionMs: Long, durationMs: Long) {
        lastPositionMs = positionMs.coerceAtLeast(0L)
        lastDurationMs = durationMs.coerceAtLeast(0L)
    }

    private fun reportActive(reason: String, ended: Boolean = false) {
        val session = activeSession ?: return
        if (!isEnabled()) return
        val token = authTokenStore.load()
        if (token == null || token.mid != session.accountMid) {
            LogHub.w(TAG, "history snapshot queued without matching token reason=$reason account=${session.accountMid}")
        }
        val content = session.content
        val nearEnd = lastDurationMs > 0L && lastDurationMs - lastPositionMs <= COMPLETION_WINDOW_MS
        val progress = when {
            content.isLive -> 0L
            ended || nearEnd -> -1L
            else -> lastPositionMs / MILLIS_PER_SECOND
        }
        val report = PlaybackHistoryReport(
            sessionId = session.id,
            accountMid = session.accountMid,
            aid = content.aid,
            cid = content.cid,
            sid = content.sid,
            epid = content.epid,
            serialId = content.serialId,
            type = content.type,
            business = content.business,
            progressSeconds = progress,
            playDurationSeconds = playedDurationMs(session) / MILLIS_PER_SECOND,
            isInteracted = true,
            scene = content.scene,
            fromScene = content.fromScene,
            fromSpmid = content.fromSpmid,
            resourceFrom = content.resourceFrom,
            updatedAtMs = max(System.currentTimeMillis(), session.lastSnapshotAtMs + 1L),
        )
        session.lastSnapshotAtMs = report.updatedAtMs
        outbox.upsert(report)
        session.lastReportedAtMs = SystemClock.elapsedRealtime()
        LogHub.d(
            TAG,
            "history snapshot queued reason=$reason session=${session.id} live=${content.isLive} " +
                "type=${content.type} aid=${content.aid} cid=${content.cid} progress=${progress}s " +
                "played=${report.playDurationSeconds}s",
        )
        retryPendingForAccount(session.accountMid)
    }

    private fun closePlaySegment(session: Session) {
        val startedAtMs = session.segmentStartedAtMs ?: return
        session.playedDurationMs += max(0L, SystemClock.elapsedRealtime() - startedAtMs)
        session.segmentStartedAtMs = null
    }

    private fun playedDurationMs(session: Session): Long {
        val currentSegment = session.segmentStartedAtMs?.let {
            max(0L, SystemClock.elapsedRealtime() - it)
        } ?: 0L
        return session.playedDurationMs + currentSegment
    }

    private fun startLiveHeartbeatIfNeeded() {
        val session = activeSession ?: return
        if (!session.content.isLive || heartbeatJob?.isActive == true || !isEnabled()) return
        heartbeatJob = scope.launch {
            while (isActive && isPlaying && activeSession?.id == session.id && isEnabled()) {
                val content = currentContent ?: session.content
                val token = authTokenStore.load()
                if (token == null || token.mid != session.accountMid) {
                    LogHub.w(TAG, "live heartbeat stopped: no matching login account=${session.accountMid}")
                    break
                }
                val sequence = session.heartbeatSequence
                val now = System.currentTimeMillis()
                val request = LiveHeartbeatRequest(
                    build = buildNumber,
                    buvid = token.cookie.extractCookie("buvid3"),
                    roomId = content.roomId,
                    playUrl = content.livePlayUrl,
                    timestampMs = now,
                    reye = session.heartbeatReye,
                    urcs = session.heartbeatUrcs,
                    edno = session.heartbeatEdno,
                    sequence = sequence,
                )
                try {
                    val response = withContext(Dispatchers.IO) {
                        api.reportLiveHeartbeat(token.accessKey, request)
                    }
                    applyHeartbeatResponse(session, response)
                    val interval = max(response.intervalSeconds, MIN_LIVE_HEARTBEAT_INTERVAL_SECONDS)
                    LogHub.d(
                        TAG,
                        "live heartbeat delivered room=${content.roomId} sequence=$sequence " +
                            "url=${LogRedaction.host(content.livePlayUrl)} next=${interval}s",
                    )
                    delay(interval * MILLIS_PER_SECOND)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    val firstAttempt = sequence == 0
                    session.heartbeatSequence++
                    val retrySeconds = if (firstAttempt) FIRST_HEARTBEAT_RETRY_SECONDS else
                        MIN_LIVE_HEARTBEAT_INTERVAL_SECONDS
                    LogHub.e(
                        TAG,
                        "live heartbeat failed room=${content.roomId} sequence=$sequence " +
                            "url=${LogRedaction.host(content.livePlayUrl)} retry=${retrySeconds}s: " +
                            LogRedaction.reason(error),
                        error,
                    )
                    delay(retrySeconds * MILLIS_PER_SECOND)
                }
            }
        }
    }

    private fun applyHeartbeatResponse(session: Session, response: LiveHeartbeatResponse) {
        session.heartbeatSequence++
        session.heartbeatEdno = response.edno
        session.heartbeatReye = response.reye
        session.heartbeatUrcs = response.urcs
    }

    private fun stopLiveHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun String.extractCookie(name: String): String = split(';')
        .asSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        .orEmpty()

    private data class Session(
        val id: String,
        val stableKey: String,
        val accountMid: Long,
        var content: PlaybackHistoryContent,
        var lastReportedAtMs: Long,
        var segmentStartedAtMs: Long?,
        var playedDurationMs: Long = 0L,
        var lastSnapshotAtMs: Long = 0L,
        var heartbeatSequence: Int = 0,
        var heartbeatEdno: String = "",
        var heartbeatReye: String = "",
        var heartbeatUrcs: List<Int> = emptyList(),
    )

    private companion object {
        const val TAG = "PlaybackHistory"
        const val REPORT_INTERVAL_MS = 30_000L
        const val RETRY_INTERVAL_MS = 60_000L
        const val COMPLETION_WINDOW_MS = 5_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val MIN_LIVE_HEARTBEAT_INTERVAL_SECONDS = 15
        const val FIRST_HEARTBEAT_RETRY_SECONDS = 60
    }
}

/** 一次投屏解析出的历史身份与直播心跳信息。 */
data class PlaybackHistoryContent(
    val stableKey: String,
    val aid: Long,
    val cid: Long,
    val sid: Long,
    val epid: Long,
    val serialId: Long,
    val type: Int,
    val business: String,
    val isLive: Boolean,
    val roomId: Long,
    val livePlayUrl: String,
    val scene: String = "",
    val fromScene: String = "",
    val fromSpmid: String = "",
    val resourceFrom: String = "",
)
