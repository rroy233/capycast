package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.core.playback.MediaTrack
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.feature.playback.model.RankedVodRoute
import com.roy233.tvcast.feature.playback.domain.VodPlaybackRules
import com.roy233.tvcast.feature.playback.domain.VodRecoveryController
import com.roy233.tvcast.feature.playback.model.PendingVodRouteSwitch
import com.roy233.tvcast.feature.playback.model.VodSession
import com.roy233.tvcast.feature.playback.routing.CdnRouteRules
import com.roy233.tvcast.feature.playback.routing.VodCdnCoordinator
import com.roy233.tvcast.feature.playback.routing.VodRouteProbe
import com.roy233.tvcast.ui.model.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock

/** 点播会话需要宿主提供的播放能力与界面状态。 */
interface VodPlaybackHost {
    fun state(): PlaybackUiState
    fun setState(state: PlaybackUiState)
    fun currentRequest(): PlaybackRequest?
    fun setCurrent(request: PlaybackRequest)
    fun isCastActive(): Boolean
    fun positionMs(): Long
    fun openPlayback(request: PlaybackRequest, seekToMs: Long): Boolean
    val coroutineScope: CoroutineScope
}

/**
 * 持有当前点播会话、备用线路刷新任务与同表示层恢复状态。
 *
 * 负责缓冲阈值判定、线路切换、表示层回退和播放进度监测。
 */
class VodPlaybackCoordinator(
    private val playback: VodPlaybackHost,
    routeProbe: VodRouteProbe,
) {

    /** 当前点播会话；为空表示没有进行中的点播内容。 */
    var activeSession: VodSession? = null

    /** 是否正在执行线路或表示层切换。 */
    var switching: Boolean = false

    /** 等待播放确认的线路切换信息。 */
    var pendingSwitch: PendingVodRouteSwitch? = null

    /** 点播线路探测与切换协调器。 */
    val cdn = VodCdnCoordinator(routeProbe)

    /** 备用线路评分刷新任务。 */
    private var backupRefreshJob: Job? = null

    /** 播放器确认进入播放态后重置恢复计时。 */
    fun onPlaying() {
        val session = activeSession ?: return
        session.recovery.onPlaying(playback.positionMs(), SystemClock.elapsedRealtime())
        session.lastPositionMs = playback.positionMs()
        session.lastProgressAtMs = SystemClock.elapsedRealtime()
    }

    /** 记录一次定位，供恢复状态机跟踪。 */
    fun onSeek(positionMs: Long) {
        activeSession?.recovery?.onSeek(positionMs, SystemClock.elapsedRealtime())
    }

    /** 记录一次播放器打开动作。 */
    fun onOpening(positionMs: Long): Long? =
        activeSession?.recovery?.onOpening(positionMs, SystemClock.elapsedRealtime())

    /** 结束当前点播会话并取消后台任务。 */
    fun clear() {
        backupRefreshJob?.cancel()
        backupRefreshJob = null
        activeSession = null
        switching = false
        pendingSwitch = null
    }

    /**
     * 将播放器缓冲事件交给恢复状态机。初始化和定位阶段的缓冲只保留诊断，稳定
     * 播放后持续达到阈值的两次缓冲才会触发线路恢复。
     */
    fun observeBuffering(buffering: Boolean) {
        val session = activeSession ?: return
        val update = session.recovery.onBufferingChanged(buffering, SystemClock.elapsedRealtime())
        val host = LogRedaction.host(session.request.video.url)
        when {
            update.failure != null -> {
                LogHub.w(
                    TAG,
                    "VOD rebuffer recovery required failure=${update.failure} count=${session.recovery.rebufferCount} " +
                        "duration=${update.durationMs}ms qn=${session.request.video.id} host=$host " +
                        session.recovery.snapshot(),
                )
                handleFailure(VodPlaybackRules.recoveryReason(update.failure))
            }
            update.countedRebuffer -> LogHub.w(
                TAG,
                "VOD severe rebuffer count=${session.recovery.rebufferCount} duration=${update.durationMs}ms " +
                "qn=${session.request.video.id} host=$host ${session.recovery.snapshot()}",
            )
            update.ignored && buffering -> LogHub.d(
                TAG,
                "VOD buffering ignored during ${update.phase} qn=${session.request.video.id} host=$host " +
                    session.recovery.snapshot(),
            )
        }
    }

    /** 自动恢复网络/缓冲故障時切到同一画质与表示层的下一条 CDN。 */
    fun handleFailure(reason: String, preferCodecFallback: Boolean = false): Boolean {
        if (playback.currentRequest()?.isLive == true || !playback.isCastActive() || switching) return false
        val session = activeSession ?: return false
        val request = playback.currentRequest() ?: return false
        pendingSwitch?.let { pending ->
            LogHub.e(
                TAG,
                "VOD CDN switch failed reason=${pending.reason} qn=${pending.quality} " +
                    "host=${LogRedaction.host(pending.target.url)} nextReason=$reason",
            )
            pendingSwitch = null
        }
        if (preferCodecFallback && scheduleVodRepresentationFallback(reason, session, request)) return true
        if (preferCodecFallback) {
            session.attemptedRepresentations += VodPlaybackRules.representationKey(request.video)
            LogHub.w(
                TAG,
                "VOD decoder fallback unavailable qn=${request.video.id} codec=${request.video.codecId}; " +
                    "will try network routes or report the player error",
            )
        }
        val fromUrl = request.video.url
        session.attemptedUrls += fromUrl
        val next = cdn.nextSameRepresentation(
            ordered = session.videoRoutes,
            activeUrl = fromUrl,
            attemptedUrls = session.attemptedUrls,
            allowPcdn = VodPlaybackRules.isSubstantiveVodFailure(reason),
        ) ?: run {
            LogHub.e(
                TAG,
                "VOD CDN switch exhausted reason=$reason qn=${request.video.id} " +
                    "attempted=${session.attemptedUrls.size} routes=${session.videoRoutes.size}",
            )
            return scheduleVodRepresentationFallback(reason, session, request)
        }
        val activeAudio = request.audio
        if (activeAudio != null) session.attemptedAudioUrls += activeAudio.url
        val nextAudioRoute = activeAudio?.let { audio ->
            session.audioRoutes.firstOrNull { it.url != audio.url && it.url !in session.attemptedAudioUrls }
        }
        val nextAudio = if (activeAudio != null && nextAudioRoute != null) {
            cdn.applyRoute(activeAudio, nextAudioRoute, session.audioRoutes)
        } else {
            activeAudio
        }
        switching = true
        session.recovery.onSwitching()
        val position = playback.positionMs()
        val nextTrack = cdn.applyRoute(request.video, next, session.videoRoutes)
        val nextRequest = request.copy(video = nextTrack, audio = nextAudio)
        pendingSwitch = PendingVodRouteSwitch(
            reason = reason,
            fromUrl = fromUrl,
            target = next,
            quality = request.video.id,
            codecId = request.video.codecId,
            positionMs = position,
        )
        val opened = runCatching { playback.openPlayback(nextRequest, position) }
            .onFailure { error ->
                LogHub.e(TAG, "VOD CDN switch open failed reason=$reason", error, stack = true)
            }
            .getOrDefault(false)
        if (!opened) {
            switching = false
            pendingSwitch = null
            session.attemptedUrls += next.url
            return false
        }
        session.request = nextRequest
        session.lastPositionMs = position
        session.lastProgressAtMs = SystemClock.elapsedRealtime()
        LogHub.i(
            TAG,
            "VOD CDN switch opened reason=$reason qn=${request.video.id} " +
                "from=${LogRedaction.host(fromUrl)} to=${LogRedaction.host(next.url)} position=${position}ms",
        )
        return true
    }

    /** 解码失败或同档线路耗尽后，先切到同档备用编码，再试响应实际返回的下一档。 */
    fun scheduleVodRepresentationFallback(
        reason: String,
        session: VodSession,
        request: PlaybackRequest,
    ): Boolean {
        session.attemptedRepresentations += VodPlaybackRules.representationKey(request.video)
        val candidate = nextVodRepresentation(request, session.attemptedRepresentations) ?: return false
        if (switching) return false
        switching = true
        session.recovery.onSwitching()
        val position = playback.positionMs()
        LogHub.w(
            TAG,
            "VOD representation fallback scheduled reason=$reason fromQn=${request.video.id} " +
                "fromCodec=${request.video.codecId} toQn=${candidate.id} toCodec=${candidate.codecId} " +
                "host=${LogRedaction.host(candidate.url)} position=${position}ms",
        )
        playback.coroutineScope.launch {
            var opened = false
            try {
                val routes = withContext(Dispatchers.IO) { cdn.rankTrack(candidate) }
                    .ifEmpty { listOf(RankedVodRoute(candidate.url)) }
                if (activeSession !== session || playback.currentRequest()?.video?.url != request.video.url || !playback.isCastActive()) return@launch
                val selected = cdn.applyRoute(candidate, routes.first(), routes)
                val nextRequest = request.copy(video = selected)
                val previousRoutes = session.videoRoutes
                val previousRequest = session.request
                session.request = nextRequest
                session.videoRoutes = routes
                session.attemptedUrls.clear()
                session.lastPositionMs = position
                session.lastProgressAtMs = SystemClock.elapsedRealtime()
                pendingSwitch = null
                opened = playback.openPlayback(nextRequest, position)
                if (!opened) {
                    session.request = previousRequest
                    session.videoRoutes = previousRoutes
                    session.attemptedRepresentations += VodPlaybackRules.representationKey(candidate)
                    playback.setCurrent(previousRequest)
                    LogHub.e(
                        TAG,
                        "VOD representation fallback open failed qn=${candidate.id} codec=${candidate.codecId} " +
                            "host=${LogRedaction.host(candidate.url)}",
                    )
                } else {
                    LogHub.i(
                        TAG,
                        "VOD representation fallback opened reason=$reason qn=${candidate.id} " +
                            "codec=${candidate.codecId} host=${LogRedaction.host(selected.url)} " +
                            "position=${position}ms",
                    )
                    PlaybackDiagnostics.event(
                        "VodRepresentationFallback",
                        "reason=$reason qn=${candidate.id} codec=${candidate.codecId} " +
                            "host=${LogRedaction.host(selected.url)} position=${position}ms",
                    )
                }
            } catch (failure: Exception) {
                LogHub.e(
                    TAG,
                    "VOD representation fallback failed qn=${candidate.id} codec=${candidate.codecId} " +
                        "reason=${LogRedaction.reason(failure)}",
                )
            } finally {
                if (!opened) {
                    switching = false
                }
                if (!opened && activeSession === session && playback.isCastActive()) {
                    handleFailure("representation-fallback-open-failed")
                }
            }
        }
        return true
    }

    /** 优先同 qn 的另一种编码；再依据服务端 accept_quality 顺序选择更低画质。 */
    fun nextVodRepresentation(
        request: PlaybackRequest,
        attempted: Set<String>,
    ): MediaTrack? {
        val currentTrack = request.video
        val remaining = request.videos.filter { track ->
            VodPlaybackRules.representationKey(track) !in attempted && track.url.isNotBlank() &&
                track.codecId in SUPPORTED_VIDEO_CODECS
        }
        val sameQuality = remaining.filter { it.id == currentTrack.id }
        if (sameQuality.isNotEmpty()) return sameQuality.maxByOrNull {
            VodPlaybackRules.videoCodecRank(it)
        }

        val currentRank = request.acceptedQualities.indexOf(currentTrack.id)
        val lowerQuality = remaining.filter { track ->
            val rank = request.acceptedQualities.indexOf(track.id)
            if (currentRank >= 0 && rank >= 0) rank > currentRank else track.id < currentTrack.id
        }
        return if (currentRank >= 0) {
            lowerQuality.minWithOrNull(
                compareBy<MediaTrack> { request.acceptedQualities.indexOf(it.id).takeIf { rank -> rank >= 0 } ?: Int.MAX_VALUE }
                    .thenByDescending { VodPlaybackRules.videoCodecRank(it) },
            )
        } else {
            lowerQuality.maxWithOrNull(
                compareBy<MediaTrack> { it.id }
                    .thenBy { VodPlaybackRules.videoCodecRank(it) },
            )
        }
    }

    /** 稳定播放确认后才结束切源事务并提示用户。 */
    fun completeRouteSwitch() {
        val switched = pendingSwitch
        if (switched == null) {
            switching = false
            return
        }
        pendingSwitch = null
        switching = false
        val host = LogRedaction.host(switched.target.url)
        val probe = switched.target.result
        val detail = "reason=${switched.reason} qn=${switched.quality} codec=${switched.codecId} " +
            "from=${LogRedaction.host(switched.fromUrl)} to=$host " +
            "pcdn=${CdnRouteRules.isPcdn(switched.target.url)} " +
            "ttfb=${probe?.firstByteMs ?: -1}ms throughput=${probe?.throughputBytesPerSecond ?: 0}Bps " +
            "position=${switched.positionMs}ms"
        LogHub.i(TAG, "VOD CDN switch succeeded $detail")
        PlaybackDiagnostics.event("VodRouteSwitchSuccess", detail)
        playback.setState(playback.state().copy(toast = "播放线路已切换：$host"))
    }

    /** 检测稳定播放态下的无进度，并处理长期打开/缓冲超时。 */
    fun observeProgress(positionMs: Long, activelyPlaying: Boolean) {
        val session = activeSession ?: return
        val now = SystemClock.elapsedRealtime()
        val progress = session.recovery.onProgress(positionMs, activelyPlaying, now)
        if (progress.becameStable) {
            LogHub.i(
                TAG,
                "VOD playback stabilized qn=${session.request.video.id} " +
                    "host=${LogRedaction.host(session.request.video.url)} ${session.recovery.snapshot()}",
            )
            completeRouteSwitch()
        }
        if (!activelyPlaying && !playback.state().isBuffering && !playback.state().isPlaying) {
            return
        }
        session.recovery.onTick(now)?.let { failure ->
            LogHub.e(
                TAG,
                "VOD recovery timeout failure=$failure qn=${session.request.video.id} " +
                    "host=${LogRedaction.host(session.request.video.url)} ${session.recovery.snapshot()}",
            )
            handleFailure(VodPlaybackRules.recoveryReason(failure))
            return
        }
        if (progress.phase != VodRecoveryController.Phase.STABLE || !activelyPlaying) {
            session.lastPositionMs = positionMs
            session.lastProgressAtMs = now
            return
        }
        if (positionMs > session.lastPositionMs) {
            session.lastPositionMs = positionMs
            session.lastProgressAtMs = now
            return
        }
        if (now - session.lastProgressAtMs >= VOD_STALL_TIMEOUT_MS) {
            LogHub.e(
                TAG,
                "VOD playback stalled for ${VOD_STALL_TIMEOUT_MS}ms qn=${session.request.video.id} " +
                    "position=${positionMs}ms host=${LogRedaction.host(session.request.video.url)}",
            )
            PlaybackDiagnostics.event(
                "VodPlaybackStalled",
                "duration=${VOD_STALL_TIMEOUT_MS}ms qn=${session.request.video.id} position=${positionMs}ms " +
                    "host=${LogRedaction.host(session.request.video.url)}",
            )
            session.lastProgressAtMs = now
            handleFailure("playback-time-stalled-5s")
        }
    }

    /** 只有播放器错误或已经经过稳定期验证的恢复故障才允许消耗 PCDN 候选。 */
    /** 每 30 秒刷新备用线路排序，不更改当前播放地址。 */
    fun startBackupRefresh() {
        backupRefreshJob?.cancel()
        backupRefreshJob = playback.coroutineScope.launch {
            while (isActive) {
                delay(VOD_BACKUP_REFRESH_INTERVAL_MS)
                val session = activeSession ?: return@launch
                val activeUrl = session.request.video.url
                val refreshed = runCatching {
                    withContext(Dispatchers.IO) {
                        cdn.refreshBackups(
                            track = session.request.video,
                            activeUrl = activeUrl,
                            excludedUrls = session.attemptedUrls,
                        )
                    }
                }.onFailure { error ->
                    LogHub.e(TAG, "VOD backup CDN refresh failed ${LogRedaction.reason(error)}", error)
                }.getOrDefault(emptyList())
                if (activeSession !== session || refreshed.isEmpty()) continue
                val active = session.videoRoutes.firstOrNull { it.url == activeUrl }
                session.videoRoutes = refreshed + listOfNotNull(active)
                    .filterNot { route -> refreshed.any { it.url == route.url } }
                LogHub.i(
                    TAG,
                    "VOD backup CDN ranking refreshed active=${LogRedaction.host(activeUrl)} " +
                        "routes=${session.videoRoutes.take(8).joinToString(",") { LogRedaction.host(it.url) }}",
                )
            }
        }
    }

    private companion object {
        val SUPPORTED_VIDEO_CODECS = setOf(7, 12, 13)
        /** 点播备用线路评分刷新周期。 */
        private const val VOD_BACKUP_REFRESH_INTERVAL_MS = 30_000L
        /** 点播播放位置无前进时触发故障切源的时间阈值。 */
        private const val VOD_STALL_TIMEOUT_MS = 5_000L
        /** 日志标签。 */
        const val TAG = "SimpleBili"
    }
}
