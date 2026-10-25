package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.data.bili.BiliApi
import com.roy233.tvcast.data.bili.CdnCandidateSelector
import com.roy233.tvcast.data.bili.RankedVodRoute
import com.roy233.tvcast.data.bili.VodCdnCoordinator
import com.roy233.tvcast.data.bili.VodRecoveryController
import com.roy233.tvcast.feature.playback.domain.VodPlaybackRules
import com.roy233.tvcast.feature.playback.model.PendingVodRouteSwitch
import com.roy233.tvcast.feature.playback.model.VodSession
import com.roy233.tvcast.ui.model.TvUiState
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
    fun state(): TvUiState
    fun setState(state: TvUiState)
    fun currentInfo(): BiliApi.PlayInfo?
    fun setCurrent(info: BiliApi.PlayInfo)
    fun isCastActive(): Boolean
    fun positionMs(): Long
    fun openPlayback(info: BiliApi.PlayInfo, seekToMs: Long): Boolean
    val coroutineScope: CoroutineScope
}

/**
 * 持有当前点播会话、备用线路刷新任务与同表示层恢复状态。
 *
 * 负责缓冲阈值判定、线路切换、表示层回退和播放进度监测。
 */
class VodPlaybackCoordinator(
    private val playback: VodPlaybackHost,
) {

    /** 当前点播会话；为空表示没有进行中的点播内容。 */
    var activeSession: VodSession? = null

    /** 是否正在执行线路或表示层切换。 */
    var switching: Boolean = false

    /** 等待播放确认的线路切换信息。 */
    var pendingSwitch: PendingVodRouteSwitch? = null

    /** 点播线路探测与切换协调器。 */
    val cdn = VodCdnCoordinator()

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
    fun onOpening(positionMs: Long) {
        activeSession?.recovery?.onOpening(positionMs, SystemClock.elapsedRealtime())
    }

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
        val host = LogRedaction.host(session.info.video.url)
        when {
            update.failure != null -> {
                LogHub.w(
                    TAG,
                    "VOD rebuffer recovery required failure=${update.failure} count=${session.recovery.rebufferCount} " +
                        "duration=${update.durationMs}ms qn=${session.info.video.id} host=$host " +
                        session.recovery.snapshot(),
                )
                handleFailure(VodPlaybackRules.recoveryReason(update.failure))
            }
            update.countedRebuffer -> LogHub.w(
                TAG,
                "VOD severe rebuffer count=${session.recovery.rebufferCount} duration=${update.durationMs}ms " +
                    "qn=${session.info.video.id} host=$host ${session.recovery.snapshot()}",
            )
            update.ignored && buffering -> LogHub.d(
                TAG,
                "VOD buffering ignored during ${update.phase} qn=${session.info.video.id} host=$host " +
                    session.recovery.snapshot(),
            )
        }
    }

    /** 自动恢复网络/缓冲故障時切到同一画质与表示层的下一条 CDN。 */
    fun handleFailure(reason: String, preferCodecFallback: Boolean = false): Boolean {
        if (playback.currentInfo()?.isLive == true || !playback.isCastActive() || switching) return false
        val session = activeSession ?: return false
        val info = playback.currentInfo() ?: return false
        pendingSwitch?.let { pending ->
            LogHub.e(
                TAG,
                "VOD CDN switch failed reason=${pending.reason} qn=${pending.quality} " +
                    "host=${LogRedaction.host(pending.target.url)} nextReason=$reason",
            )
            pendingSwitch = null
        }
        if (preferCodecFallback && scheduleVodRepresentationFallback(reason, session, info)) return true
        if (preferCodecFallback) {
            session.attemptedRepresentations += VodPlaybackRules.representationKey(info.video)
            LogHub.w(
                TAG,
                "VOD decoder fallback unavailable qn=${info.video.id} codec=${info.video.codecid}; " +
                    "will try network routes or report the player error",
            )
        }
        val fromUrl = info.video.url
        session.attemptedUrls += fromUrl
        val next = cdn.nextSameRepresentation(
            ordered = session.videoRoutes,
            activeUrl = fromUrl,
            attemptedUrls = session.attemptedUrls,
            allowPcdn = VodPlaybackRules.isSubstantiveVodFailure(reason),
        ) ?: run {
            LogHub.e(
                TAG,
                "VOD CDN switch exhausted reason=$reason qn=${info.video.id} " +
                    "attempted=${session.attemptedUrls.size} routes=${session.videoRoutes.size}",
            )
            return scheduleVodRepresentationFallback(reason, session, info)
        }
        val activeAudio = info.audio
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
        val nextTrack = cdn.applyRoute(info.video, next, session.videoRoutes)
        val nextInfo = info.copy(video = nextTrack, audio = nextAudio)
        pendingSwitch = PendingVodRouteSwitch(
            reason = reason,
            fromUrl = fromUrl,
            target = next,
            quality = info.video.id,
            codecId = info.video.codecid,
            positionMs = position,
        )
        val opened = runCatching { playback.openPlayback(nextInfo, position) }
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
        session.info = nextInfo
        session.lastPositionMs = position
        session.lastProgressAtMs = SystemClock.elapsedRealtime()
        LogHub.i(
            TAG,
            "VOD CDN switch opened reason=$reason qn=${info.video.id} " +
                "from=${LogRedaction.host(fromUrl)} to=${LogRedaction.host(next.url)} position=${position}ms",
        )
        return true
    }

    /** 解码失败或同档线路耗尽后，先切到同档备用编码，再试响应实际返回的下一档。 */
    fun scheduleVodRepresentationFallback(
        reason: String,
        session: VodSession,
        info: BiliApi.PlayInfo,
    ): Boolean {
        session.attemptedRepresentations += VodPlaybackRules.representationKey(info.video)
        val candidate = nextVodRepresentation(info, session.attemptedRepresentations) ?: return false
        if (switching) return false
        switching = true
        session.recovery.onSwitching()
        val position = playback.positionMs()
        LogHub.w(
            TAG,
            "VOD representation fallback scheduled reason=$reason fromQn=${info.video.id} " +
                "fromCodec=${info.video.codecid} toQn=${candidate.id} toCodec=${candidate.codecid} " +
                "host=${LogRedaction.host(candidate.url)} position=${position}ms",
        )
        playback.coroutineScope.launch {
            var opened = false
            try {
                val routes = withContext(Dispatchers.IO) { cdn.rankTrack(candidate) }
                    .ifEmpty { listOf(RankedVodRoute(candidate.url)) }
                if (activeSession !== session || playback.currentInfo()?.video?.url != info.video.url || !playback.isCastActive()) return@launch
                val selected = cdn.applyRoute(candidate, routes.first(), routes)
                val nextInfo = info.copy(video = selected)
                val previousRoutes = session.videoRoutes
                val previousInfo = session.info
                session.info = nextInfo
                session.videoRoutes = routes
                session.attemptedUrls.clear()
                session.lastPositionMs = position
                session.lastProgressAtMs = SystemClock.elapsedRealtime()
                pendingSwitch = null
                opened = playback.openPlayback(nextInfo, position)
                if (!opened) {
                    session.info = previousInfo
                    session.videoRoutes = previousRoutes
                    session.attemptedRepresentations += VodPlaybackRules.representationKey(candidate)
                    playback.setCurrent(previousInfo)
                    LogHub.e(
                        TAG,
                        "VOD representation fallback open failed qn=${candidate.id} codec=${candidate.codecid} " +
                            "host=${LogRedaction.host(candidate.url)}",
                    )
                } else {
                    LogHub.i(
                        TAG,
                        "VOD representation fallback opened reason=$reason qn=${candidate.id} " +
                            "codec=${candidate.codecid} host=${LogRedaction.host(selected.url)} " +
                            "position=${position}ms",
                    )
                    PlaybackDiagnostics.event(
                        "VodRepresentationFallback",
                        "reason=$reason qn=${candidate.id} codec=${candidate.codecid} " +
                            "host=${LogRedaction.host(selected.url)} position=${position}ms",
                    )
                }
            } catch (failure: Exception) {
                LogHub.e(
                    TAG,
                    "VOD representation fallback failed qn=${candidate.id} codec=${candidate.codecid} " +
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
        info: BiliApi.PlayInfo,
        attempted: Set<String>,
    ): BiliApi.Track? {
        val currentTrack = info.video
        val remaining = info.videos.filter { track ->
            VodPlaybackRules.representationKey(track) !in attempted && track.url.isNotBlank() &&
                CdnCandidateSelector.isSupportedVideoCodec(track.codecid)
        }
        val sameQuality = remaining.filter { it.id == currentTrack.id }
        if (sameQuality.isNotEmpty()) return sameQuality.maxByOrNull(VodPlaybackRules::videoCodecRank)

        val currentRank = info.acceptQualities.indexOf(currentTrack.id)
        val lowerQuality = remaining.filter { track ->
            val rank = info.acceptQualities.indexOf(track.id)
            if (currentRank >= 0 && rank >= 0) rank > currentRank else track.id < currentTrack.id
        }
        return if (currentRank >= 0) {
            lowerQuality.minWithOrNull(
                compareBy<BiliApi.Track> { info.acceptQualities.indexOf(it.id).takeIf { rank -> rank >= 0 } ?: Int.MAX_VALUE }
                    .thenByDescending(VodPlaybackRules::videoCodecRank),
            )
        } else {
            lowerQuality.maxWithOrNull(compareBy<BiliApi.Track> { it.id }.thenBy(VodPlaybackRules::videoCodecRank))
        }
    }

    fun VodPlaybackRules.representationKey(track: BiliApi.Track): String =
        VodPlaybackRules.representationKey(track)

    fun VodPlaybackRules.videoCodecRank(track: BiliApi.Track): Int =
        VodPlaybackRules.videoCodecRank(track)

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
            "pcdn=${CdnCandidateSelector.isPcdn(switched.target.url)} " +
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
                "VOD playback stabilized qn=${session.info.video.id} " +
                    "host=${LogRedaction.host(session.info.video.url)} ${session.recovery.snapshot()}",
            )
            completeRouteSwitch()
        }
        if (!activelyPlaying && !playback.state().isBuffering && !playback.state().isPlaying) {
            return
        }
        session.recovery.onTick(now)?.let { failure ->
            LogHub.e(
                TAG,
                "VOD recovery timeout failure=$failure qn=${session.info.video.id} " +
                    "host=${LogRedaction.host(session.info.video.url)} ${session.recovery.snapshot()}",
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
                "VOD playback stalled for ${VOD_STALL_TIMEOUT_MS}ms qn=${session.info.video.id} " +
                    "position=${positionMs}ms host=${LogRedaction.host(session.info.video.url)}",
            )
            PlaybackDiagnostics.event(
                "VodPlaybackStalled",
                "duration=${VOD_STALL_TIMEOUT_MS}ms qn=${session.info.video.id} position=${positionMs}ms " +
                    "host=${LogRedaction.host(session.info.video.url)}",
            )
            session.lastProgressAtMs = now
            handleFailure("playback-time-stalled-5s")
        }
    }

    fun VodPlaybackRules.recoveryReason(failure: VodRecoveryController.Failure): String =
        VodPlaybackRules.recoveryReason(failure)

    /** 只有播放器错误或已经经过稳定期验证的恢复故障才允许消耗 PCDN 候选。 */
    fun VodPlaybackRules.isSubstantiveVodFailure(reason: String): Boolean =
        VodPlaybackRules.isSubstantiveVodFailure(reason)

    /** 每 30 秒刷新备用线路排序，不更改当前播放地址。 */
    fun startBackupRefresh() {
        backupRefreshJob?.cancel()
        backupRefreshJob = playback.coroutineScope.launch {
            while (isActive) {
                delay(VOD_BACKUP_REFRESH_INTERVAL_MS)
                val session = activeSession ?: return@launch
                val activeUrl = session.info.video.url
                val refreshed = runCatching {
                    withContext(Dispatchers.IO) {
                        cdn.refreshBackups(
                            track = session.info.video,
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
        /** 点播备用线路评分刷新周期。 */
        private const val VOD_BACKUP_REFRESH_INTERVAL_MS = 30_000L
        /** 点播播放位置无前进时触发故障切源的时间阈值。 */
        private const val VOD_STALL_TIMEOUT_MS = 5_000L
        /** 日志标签。 */
        const val TAG = "SimpleBili"
    }
}
