package com.roy233.tvcast.feature.playback.application

import android.os.SystemClock
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.core.playback.MediaTrack
import com.roy233.tvcast.core.playback.PlaybackEvent
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.core.playback.PlaybackResolver
import com.roy233.tvcast.core.playback.VideoTrackCapability
import com.roy233.tvcast.core.playback.VideoTrackSupport
import com.roy233.tvcast.feature.playback.domain.VodPlaybackRules
import com.roy233.tvcast.feature.playback.domain.VodRecoveryController
import com.roy233.tvcast.feature.playback.model.PendingVodRouteSwitch
import com.roy233.tvcast.feature.playback.model.RankedVodPlayback
import com.roy233.tvcast.feature.playback.model.RankedVodRoute
import com.roy233.tvcast.feature.playback.model.VodAssetRefreshState
import com.roy233.tvcast.feature.playback.model.VodSession
import com.roy233.tvcast.feature.playback.routing.CdnRouteRules
import com.roy233.tvcast.feature.playback.routing.VodCdnCoordinator
import com.roy233.tvcast.feature.playback.routing.VodRouteProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 点播会话需要宿主提供的播放能力与界面状态。 */
interface VodPlaybackHost {
    fun showRouteToast(message: String)

    fun isPausedWithoutBuffering(): Boolean

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
    private val resolver: PlaybackResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val videoCapability: VideoTrackCapability = VideoTrackCapability {
        VideoTrackSupport.UNKNOWN
    },
) {

    /** 当前点播会话；为空表示没有进行中的点播内容。 */
    var activeSession: VodSession? = null

    /** 是否正在执行线路或表示层切换。 */
    var switching: Boolean = false

    /** 等待播放确认的线路切换信息。 */
    var pendingSwitch: PendingVodRouteSwitch? = null

    private data class FrameDropTrial(
        val session: VodSession,
        val previousRequest: PlaybackRequest,
        val previousRoutes: List<RankedVodRoute>,
        val previousAttempts: Set<String>,
        val positionMs: Long,
    )

    private var frameDropTrial: FrameDropTrial? = null

    /** 点播线路探测与切换协调器。 */
    val cdn = VodCdnCoordinator(routeProbe)

    /** 只为非受保护 SDR DASH 调整选轨，其他播放路径保持原行为。 */
    private fun usesCapabilitySelection(request: PlaybackRequest): Boolean =
        !request.isLive &&
            !request.isDrm &&
            request.progressiveUrl == null &&
            !request.video.isHdr &&
            !request.video.isDolbyVision

    fun selectVideo(request: PlaybackRequest, quality: Int): MediaTrack? {
        val candidates = request.videos.filter { it.id == quality }
        val original = candidates.firstOrNull() ?: return null
        if (!usesCapabilitySelection(request) || original.isHdr || original.isDolbyVision)
            return original
        val preferred =
            candidates.firstOrNull {
                VodPlaybackRules.representationKey(it) ==
                    VodPlaybackRules.representationKey(request.video)
            } ?: original
        val ordered =
            (listOf(preferred) + candidates.filterNot { it == preferred }).filter {
                it.url.isNotBlank() &&
                    it.codecId in SUPPORTED_VIDEO_CODECS &&
                    it.isHdr == preferred.isHdr &&
                    it.isDolbyVision == preferred.isDolbyVision
            }
        val assessed = ordered.map { track -> track to videoCapability.support(track) }
        LogHub.i(
            TAG,
            "VOD capability qn=$quality candidates=" +
                assessed.joinToString { (track, support) ->
                    "${track.codecId}:${track.codecs}:$support"
                },
        )
        val selected = VodPlaybackRules.rankRepresentations(assessed).firstOrNull()
        LogHub.i(TAG, "VOD capability selected qn=$quality codec=${selected?.codecId ?: -1}")
        return selected
    }

    suspend fun rankInitialPlayback(request: PlaybackRequest): RankedVodPlayback {
        if (!usesCapabilitySelection(request)) return cdn.rankPlayback(request)
        val video =
            selectVideo(request, request.video.id)
                ?: error("No hardware video decoder available for requested quality")
        return cdn.rankPlayback(request.copy(video = video))
    }

    /** 备用线路评分刷新任务。 */
    private var backupRefreshJob: Job? = null

    /** 当前点播资源的 HTTP 403 刷新任务。 */
    private var assetRefreshJob: Job? = null

    /** 播放器确认进入播放态后重置恢复计时。 */
    fun onPlaying() {
        val session = activeSession ?: return
        session.recovery.onPlaying(playback.positionMs(), SystemClock.elapsedRealtime())
        session.lastPositionMs = playback.positionMs()
        session.lastProgressAtMs = SystemClock.elapsedRealtime()
    }

    /** 记录一次定位，供恢复状态机跟踪。 */
    fun onSeek(positionMs: Long) {
        activeSession?.frameDropMonitor?.reset()
        activeSession?.recovery?.onSeek(positionMs, SystemClock.elapsedRealtime())
    }

    /** 记录一次播放器打开动作。 */
    fun onOpening(positionMs: Long): Long? {
        activeSession?.frameDropMonitor?.reset()
        return activeSession?.recovery?.onOpening(positionMs, SystemClock.elapsedRealtime())
    }

    fun observeVideoFrames(sample: PlaybackEvent.VideoFrameSample?) {
        val session = activeSession ?: return
        val request = playback.currentRequest() ?: return
        if (sample == null || switching || !usesCapabilitySelection(request)) {
            session.frameDropMonitor.reset()
            return
        }
        val window = session.frameDropMonitor.observe(sample) ?: return
        LogHub.i(
            TAG,
            "VOD frame window qn=${request.video.id} codec=${request.video.codecId} " +
                "frames=${window.frames} dropped=${window.dropped} severe=${window.severe}",
        )
        if (!window.sustained) return
        session.frameDropMonitor.reset()
        session.frameDropAttempts += VodPlaybackRules.representationKey(request.video)
        val candidates =
            request.videos.filter {
                it.id == request.video.id &&
                    it.url.isNotBlank() &&
                    it.codecId != request.video.codecId &&
                    it.isHdr == request.video.isHdr &&
                    it.isDolbyVision == request.video.isDolbyVision &&
                    VodPlaybackRules.representationKey(it) !in session.frameDropAttempts
            }
        val assessed = candidates.map { it to videoCapability.support(it) }
        LogHub.i(
            TAG,
            "VOD frame recovery candidates qn=${request.video.id} " +
                assessed.joinToString { (track, support) -> "${track.codecId}:$support" },
        )
        val next =
            VodPlaybackRules.rankRepresentations(assessed, allowExceeds = false).firstOrNull()
        if (next == null) {
            if (!session.frameDropExhaustedNotified) {
                playback.showRouteToast("当前画质播放不流畅，暂无其他可用编码")
                session.frameDropExhaustedNotified = true
            }
            return
        }
        session.frameDropAttempts += VodPlaybackRules.representationKey(next)
        scheduleVodRepresentationFallback("sustained-frame-drops", session, request, next)
    }

    /** 结束当前点播会话并取消后台任务。 */
    fun clear() {
        assetRefreshJob?.cancel()
        assetRefreshJob = null
        backupRefreshJob?.cancel()
        backupRefreshJob = null
        activeSession = null
        switching = false
        pendingSwitch = null
        frameDropTrial = null
    }

    private fun rollbackFrameDropTrial(): Boolean {
        val trial = frameDropTrial ?: return false
        frameDropTrial = null
        if (activeSession !== trial.session || !playback.isCastActive()) return false
        switching = false
        pendingSwitch = null
        trial.session.request = trial.previousRequest
        trial.session.videoRoutes = trial.previousRoutes
        trial.session.attemptedUrls.clear()
        trial.session.attemptedUrls.addAll(trial.previousAttempts)
        playback.setCurrent(trial.previousRequest)
        val position = playback.positionMs().takeIf { it > 0L } ?: trial.positionMs
        val restored = runCatching {
            playback.openPlayback(trial.previousRequest, position)
        }
            .onFailure { LogHub.e(TAG, "VOD frame recovery restore failed", it, stack = true) }
            .getOrDefault(false)
        playback.showRouteToast(if (restored) "备用编码不可用，已恢复原画质" else "备用编码切换失败")
        return restored
    }

    /** 当前点播媒体返回 HTTP 403 时单飞重新解析整套播放地址。 */
    fun handleSourceLoadFailure(httpStatus: Int?): Boolean {
        if (httpStatus != HTTP_FORBIDDEN) return false
        if (playback.currentRequest()?.isLive == true || !playback.isCastActive()) return false
        val session = activeSession ?: return false
        val request = playback.currentRequest() ?: return false
        when (session.assetRefreshState) {
            VodAssetRefreshState.RESOLVING -> {
                LogHub.d(TAG, "VOD playurl refresh coalesced status=$httpStatus")
                return true
            }
            VodAssetRefreshState.AWAITING_STABLE -> {
                LogHub.w(
                    TAG,
                    "VOD playurl refresh suppressed before stable playback status=$httpStatus",
                )
                return false
            }
            VodAssetRefreshState.IDLE -> Unit
        }

        session.assetRefreshState = VodAssetRefreshState.RESOLVING
        val previousRequest = request
        val previousVideoRoutes = session.videoRoutes
        val previousAudioRoutes = session.audioRoutes
        LogHub.i(
            TAG,
            "VOD playurl refresh begin status=$httpStatus qn=${request.video.id} " +
                "codec=${request.video.codecId} audio=${request.audio?.id}",
        )
        assetRefreshJob =
            playback.coroutineScope.launch {
                val result =
                    try {
                        val ranked =
                            withContext(ioDispatcher) {
                                val fresh =
                                    resolver.resolveVod(
                                        aid = request.aid,
                                        cid = request.cid,
                                        bvid = request.bvid,
                                        title = request.title,
                                        epId = request.epId,
                                        seasonId = request.seasonId,
                                    )
                                cdn.rankPlayback(VodPlaybackRules.restoreSelection(fresh, request))
                            }
                        Result.success(ranked)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Result.failure(failure)
                    }
                if (!isActive) return@launch
                assetRefreshJob = null
                if (activeSession !== session || !playback.isCastActive()) {
                    LogHub.i(TAG, "VOD playurl refresh discarded because session changed")
                    return@launch
                }
                result
                    .onSuccess { ranked ->
                        val position = playback.positionMs()
                        session.assetRefreshState = VodAssetRefreshState.AWAITING_STABLE
                        session.request = ranked.request
                        session.videoRoutes = ranked.videoRoutes
                        session.audioRoutes = ranked.audioRoutes
                        session.attemptedUrls.clear()
                        session.attemptedAudioUrls.clear()
                        session.lastPositionMs = position
                        session.lastProgressAtMs = SystemClock.elapsedRealtime()
                        pendingSwitch = null
                        switching = true
                        session.recovery.onSwitching()
                        val opened = playback.openPlayback(ranked.request, position)
                        if (!opened) {
                            session.request = previousRequest
                            session.videoRoutes = previousVideoRoutes
                            session.audioRoutes = previousAudioRoutes
                            playback.setCurrent(previousRequest)
                            LogHub.e(
                                TAG,
                                "VOD playurl refresh open rejected; restoring previous request",
                            )
                            playback.openPlayback(previousRequest, position)
                            return@onSuccess
                        }
                        LogHub.i(
                            TAG,
                            "VOD playurl refresh opened qn=${ranked.request.video.id} " +
                                "codec=${ranked.request.video.codecId} audio=${ranked.request.audio?.id} " +
                                "position=${position}ms host=${LogRedaction.host(ranked.request.video.url)}",
                        )
                        PlaybackDiagnostics.event(
                            "VodPlayurlRefresh",
                            "status=$httpStatus qn=${ranked.request.video.id} " +
                                "codec=${ranked.request.video.codecId} position=${position}ms",
                        )
                    }
                    .onFailure { failure ->
                        session.assetRefreshState = VodAssetRefreshState.AWAITING_STABLE
                        LogHub.e(
                            TAG,
                            "VOD playurl refresh failed ${LogRedaction.reason(failure)}",
                            failure,
                        )
                        PlaybackDiagnostics.fail(
                            code = "VOD_PLAYURL_REFRESH_FAILED",
                            message = "播放地址刷新失败",
                            detail = "status=$httpStatus reason=${LogRedaction.reason(failure)}",
                        )
                    }
            }
        return true
    }

    /** 将播放器缓冲事件交给恢复状态机。初始化和定位阶段的缓冲只保留诊断，稳定 播放后持续达到阈值的两次缓冲才会触发线路恢复。 */
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
            update.countedRebuffer ->
                LogHub.w(
                    TAG,
                    "VOD severe rebuffer count=${session.recovery.rebufferCount} duration=${update.durationMs}ms " +
                        "qn=${session.request.video.id} host=$host ${session.recovery.snapshot()}",
                )
            update.ignored && buffering ->
                LogHub.d(
                    TAG,
                    "VOD buffering ignored during ${update.phase} qn=${session.request.video.id} host=$host " +
                        session.recovery.snapshot(),
                )
        }
    }

    /** 自动恢复网络/缓冲故障時切到同一画质与表示层的下一条 CDN。 */
    fun handleFailure(reason: String, preferCodecFallback: Boolean = false): Boolean {
        if (frameDropTrial != null) return rollbackFrameDropTrial()
        if (playback.currentRequest()?.isLive == true || !playback.isCastActive() || switching)
            return false
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
        if (preferCodecFallback && scheduleVodRepresentationFallback(reason, session, request))
            return true
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
        val next =
            cdn.nextSameRepresentation(
                ordered = session.videoRoutes,
                activeUrl = fromUrl,
                attemptedUrls = session.attemptedUrls,
                allowPcdn = VodPlaybackRules.isSubstantiveVodFailure(reason),
            )
                ?: run {
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
            session.audioRoutes.firstOrNull {
                it.url != audio.url && it.url !in session.attemptedAudioUrls
            }
        }
        val nextAudio =
            if (activeAudio != null && nextAudioRoute != null) {
                cdn.applyRoute(activeAudio, nextAudioRoute, session.audioRoutes)
            } else {
                activeAudio
            }
        switching = true
        session.recovery.onSwitching()
        val position = playback.positionMs()
        val nextTrack = cdn.applyRoute(request.video, next, session.videoRoutes)
        val nextRequest = request.copy(video = nextTrack, audio = nextAudio)
        pendingSwitch =
            PendingVodRouteSwitch(
                reason = reason,
                fromUrl = fromUrl,
                target = next,
                quality = request.video.id,
                codecId = request.video.codecId,
                positionMs = position,
            )
        val opened = runCatching {
            playback.openPlayback(nextRequest, position)
        }
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
        selectedCandidate: MediaTrack? = null,
    ): Boolean {
        if (reason != "sustained-frame-drops") {
            session.attemptedRepresentations += VodPlaybackRules.representationKey(request.video)
        }
        val candidate =
            selectedCandidate
                ?: nextVodRepresentation(request, session.attemptedRepresentations)
                ?: return false
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
                val routes =
                    withContext(Dispatchers.IO) { cdn.rankTrack(candidate) }
                        .ifEmpty { listOf(RankedVodRoute(candidate.url)) }
                if (
                    activeSession !== session ||
                        playback.currentRequest()?.video?.url != request.video.url ||
                        !playback.isCastActive()
                )
                    return@launch
                val selected = cdn.applyRoute(candidate, routes.first(), routes)
                val nextRequest = request.copy(video = selected)
                val previousRoutes = session.videoRoutes
                val previousRequest = session.request
                if (reason == "sustained-frame-drops") {
                    frameDropTrial =
                        FrameDropTrial(
                            session,
                            previousRequest,
                            previousRoutes,
                            session.attemptedUrls.toSet(),
                            position,
                        )
                }
                session.request = nextRequest
                session.videoRoutes = routes
                session.attemptedUrls.clear()
                session.lastPositionMs = position
                session.lastProgressAtMs = SystemClock.elapsedRealtime()
                pendingSwitch = null
                opened = playback.openPlayback(nextRequest, position)
                if (!opened) {
                    if (reason == "sustained-frame-drops") {
                        rollbackFrameDropTrial()
                        return@launch
                    }
                    session.request = previousRequest
                    session.videoRoutes = previousRoutes
                    if (reason != "sustained-frame-drops") {
                        session.attemptedRepresentations +=
                            VodPlaybackRules.representationKey(candidate)
                    }
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
                if (reason == "sustained-frame-drops") rollbackFrameDropTrial()
            } finally {
                if (!opened) {
                    switching = false
                }
                if (
                    !opened &&
                        reason != "sustained-frame-drops" &&
                        activeSession === session &&
                        playback.isCastActive()
                ) {
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
        val remaining =
            request.videos.filter { track ->
                VodPlaybackRules.representationKey(track) !in attempted &&
                    track.url.isNotBlank() &&
                    track.codecId in SUPPORTED_VIDEO_CODECS
            }
        val sameQuality =
            remaining
                .filter { it.id == currentTrack.id }
                .sortedByDescending(VodPlaybackRules::videoCodecRank)
        if (sameQuality.isNotEmpty()) {
            if (!usesCapabilitySelection(request)) return sameQuality.first()
            val ranked =
                VodPlaybackRules.rankRepresentations(
                    sameQuality.map { it to videoCapability.support(it) },
                )
            if (ranked.isNotEmpty()) return ranked.first()
        }

        val currentRank = request.acceptedQualities.indexOf(currentTrack.id)
        val lowerQuality = remaining.filter { track ->
            val rank = request.acceptedQualities.indexOf(track.id)
            if (currentRank >= 0 && rank >= 0) rank > currentRank else track.id < currentTrack.id
        }
        val ordered =
            if (currentRank >= 0) {
                lowerQuality.sortedWith(
                    compareBy<MediaTrack> {
                            request.acceptedQualities.indexOf(it.id).takeIf { rank -> rank >= 0 }
                                ?: Int.MAX_VALUE
                        }
                        .thenByDescending { VodPlaybackRules.videoCodecRank(it) },
                )
            } else {
                lowerQuality.sortedWith(
                    compareByDescending<MediaTrack> { it.id }
                        .thenByDescending { VodPlaybackRules.videoCodecRank(it) },
                )
            }
        if (!usesCapabilitySelection(request)) return ordered.firstOrNull()
        return ordered
            .groupBy { it.id }
            .values
            .firstNotNullOfOrNull { quality ->
                VodPlaybackRules.rankRepresentations(
                        quality.map { it to videoCapability.support(it) },
                    )
                    .firstOrNull()
            }
    }

    /** 稳定播放确认后才结束切源事务并提示用户。 */
    fun completeRouteSwitch() {
        frameDropTrial = null
        val switched = pendingSwitch
        if (switched == null) {
            switching = false
            return
        }
        pendingSwitch = null
        switching = false
        val host = LogRedaction.host(switched.target.url)
        val probe = switched.target.result
        val detail =
            "reason=${switched.reason} qn=${switched.quality} codec=${switched.codecId} " +
                "from=${LogRedaction.host(switched.fromUrl)} to=$host " +
                "pcdn=${CdnRouteRules.isPcdn(switched.target.url)} " +
                "ttfb=${probe?.firstByteMs ?: -1}ms throughput=${probe?.throughputBytesPerSecond ?: 0}Bps " +
                "position=${switched.positionMs}ms"
        LogHub.i(TAG, "VOD CDN switch succeeded $detail")
        PlaybackDiagnostics.event("VodRouteSwitchSuccess", detail)
        playback.showRouteToast("播放线路已切换：$host")
    }

    /** 检测稳定播放态下的无进度，并处理长期打开/缓冲超时。 */
    fun observeProgress(positionMs: Long, activelyPlaying: Boolean) {
        val session = activeSession ?: return
        val now = SystemClock.elapsedRealtime()
        val progress = session.recovery.onProgress(positionMs, activelyPlaying, now)
        if (progress.becameStable) {
            if (session.assetRefreshState == VodAssetRefreshState.AWAITING_STABLE) {
                session.assetRefreshState = VodAssetRefreshState.IDLE
                LogHub.i(
                    TAG,
                    "VOD playurl refresh stabilized qn=${session.request.video.id} " +
                        "host=${LogRedaction.host(session.request.video.url)}",
                )
            }
            LogHub.i(
                TAG,
                "VOD playback stabilized qn=${session.request.video.id} " +
                    "host=${LogRedaction.host(session.request.video.url)} ${session.recovery.snapshot()}",
            )
            completeRouteSwitch()
        }
        if (!activelyPlaying && playback.isPausedWithoutBuffering()) {
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
        backupRefreshJob =
            playback.coroutineScope.launch {
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
                    }
                        .onFailure { error ->
                            LogHub.e(
                                TAG,
                                "VOD backup CDN refresh failed ${LogRedaction.reason(error)}",
                                error,
                            )
                        }
                        .getOrDefault(emptyList())
                    if (activeSession !== session || refreshed.isEmpty()) continue
                    val active = session.videoRoutes.firstOrNull { it.url == activeUrl }
                    session.videoRoutes =
                        refreshed +
                            listOfNotNull(active).filterNot { route ->
                                refreshed.any { it.url == route.url }
                            }
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
        /** B 站签名媒体地址失效时返回的 HTTP 状态码。 */
        private const val HTTP_FORBIDDEN = 403
        /** 日志标签。 */
        const val TAG = "SimpleBili"
    }
}
