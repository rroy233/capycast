package com.roy233.tvcast.feature.playback.application

import android.content.Context
import android.os.SystemClock
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.data.auth.CastAuth
import com.roy233.tvcast.data.bili.BiliApi
import com.roy233.tvcast.data.bili.LiveCdnCoordinator
import com.roy233.tvcast.data.bili.LivePlayInfo
import com.roy233.tvcast.data.bili.LivePlaybackHealth
import com.roy233.tvcast.data.bili.LivePlaybackStallWatchdog
import com.roy233.tvcast.data.bili.LivePlayUrlResolver
import com.roy233.tvcast.data.bili.LiveStreamCandidate
import com.roy233.tvcast.data.bili.NvaExtDecoder
import com.roy233.tvcast.data.bili.RankedLiveSource
import com.roy233.tvcast.feature.playback.model.LiveSession
import com.roy233.tvcast.feature.playback.model.PendingLiveRouteSwitch
import com.roy233.tvcast.feature.playback.presentation.LiveQualityMapper
import com.roy233.tvcast.ui.model.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 直播会话需要宿主提供的播放能力与界面状态。 */
interface LivePlaybackHost {
    fun state(): PlaybackUiState
    fun setState(state: PlaybackUiState)
    fun currentInfo(): BiliApi.PlayInfo?
    fun setCurrent(info: BiliApi.PlayInfo)
    fun isCastActive(): Boolean
    fun isPlaying(): Boolean
    fun openPlayback(info: BiliApi.PlayInfo, seekToMs: Long): Boolean
    val coroutineScope: CoroutineScope
    fun stateSnapshot(): String
    fun setPendingPause(paused: Boolean)
    fun lastNva(): NvaExtDecoder.NvaExtContent?
    fun lastTitle(): String
    fun setRetry(action: (() -> Unit)?)
    fun resolveLast(nva: NvaExtDecoder.NvaExtContent)
    fun reportError(title: String, message: String, code: String, canRetry: Boolean, keepVideo: Boolean = false)
    fun clearVodPendingSwitch()
    fun setCastDurationMs(durationMs: Long)
    fun authToken(): CastAuth.Token?
    fun appContext(): Context
}

/**
 * 持有直播会话、同画质线路切换与恢复状态。
 *
 * 负责首选线路测速与排序、失败降画质、AVC 回退、备用线路刷新和切源提示。
 */
class LivePlaybackCoordinator(
    private val playback: LivePlaybackHost,
) {

    /** 当前直播会话；为空表示没有进行中的直播。 */
    var activeSession: LiveSession? = null

    /** 是否正在执行线路切换。 */
    var switching: Boolean = false

    /** 等待起播确认的切源信息。 */
    var pendingSwitch: PendingLiveRouteSwitch? = null

    /** 直播线路测速与排序器。 */
    val cdn = LiveCdnCoordinator()

    /** 直播缓冲阈值统计。 */
    val health = LivePlaybackHealth()

    /** 直播卡顿看门狗。 */
    val stallWatchdog = LivePlaybackStallWatchdog()

    /** 备用线路评分刷新任务。 */
    private var backupRefreshJob: Job? = null

    /** 结束直播会话并重置恢复状态。 */
    fun clear() {
        backupRefreshJob?.cancel()
        backupRefreshJob = null
        activeSession = null
        switching = false
        pendingSwitch = null
        health.reset()
        stallWatchdog.reset()
    }

    /** 采用首次线路测速结果启动直播，并记录备用线路状态。 */
    fun start(
        stream: LivePlayInfo,
        routes: List<RankedLiveSource>,
        title: String,
    ) {
        val first = routes.firstOrNull { it.candidate.quality == stream.quality }
            ?: routes.firstOrNull()
            ?: run {
                playback.reportError(
                    title = "直播线路不可用",
                    message = "TV 接口没有返回可播放的直播线路。",
                    code = "LIVE_NO_CANDIDATE",
                    canRetry = true,
                )
                return
            }
        backupRefreshJob?.cancel()
        health.reset()
        switching = false
        pendingSwitch = null
        playback.clearVodPendingSwitch()
        stallWatchdog.reset()
        val session = LiveSession(
            stream = stream,
            routes = routes.toMutableList(),
            active = first.candidate,
        )
        session.attemptedUrls += first.candidate.url
        activeSession = session
        playback.setCastDurationMs(0L)
        PlaybackDiagnostics.attribute("live.roomId", stream.roomId.toString())
        PlaybackDiagnostics.attribute("live.requestedQn", stream.requestedQuality.toString())
        PlaybackDiagnostics.attribute("live.actualQn", stream.quality.toString())
        PlaybackDiagnostics.attribute(
            "live.acceptedQn",
            stream.acceptedQualities.joinToString(","),
        )
        PlaybackDiagnostics.attribute("live.format", first.candidate.format)
        PlaybackDiagnostics.attribute("live.codec", first.candidate.codecName)
        PlaybackDiagnostics.attribute("live.cdn", first.candidate.cdnLabel)
        PlaybackDiagnostics.attribute("live.backupCount", (routes.size - 1).coerceAtLeast(0).toString())
        routes.forEachIndexed { index, route ->
            PlaybackDiagnostics.track(
                "live-route",
                mapOf(
                    "index" to index.toString(),
                    "quality" to route.candidate.quality.toString(),
                    "format" to route.candidate.format,
                    "codec" to route.candidate.codecName,
                    "host" to route.candidate.cdnLabel,
                    "pcdn" to route.candidate.pcdn.toString(),
                    "probe" to (route.result?.let {
                        "${it.success}:${it.firstByteMs}ms:${it.throughputBytesPerSecond}Bps"
                    } ?: "unprobed"),
                    "selected" to (route.candidate.url == first.candidate.url).toString(),
                ),
            )
        }
        LogHub.i(
            TAG,
            "live start room=${stream.roomId} requestedQn=${stream.requestedQuality} " +
                "actualQn=${stream.quality} accepted=${stream.acceptedQualities} " +
                "selected=${first.candidate.cdnLabel}/${first.candidate.format}/" +
                "${first.candidate.codecName} routes=${routes.size}",
        )
        if (first.candidate.pcdn) {
            LogHub.w(TAG, "live PCDN fallback selected because no official CDN route is available")
            PlaybackDiagnostics.event(
                "LivePcdnFallback",
                "room=${stream.roomId} quality=${stream.quality} host=${first.candidate.cdnLabel}",
            )
        }
        val started = playback.openPlayback(livePlayInfo(session, first.candidate, title), 0L)
        if (started) startBackupRefresh(session)
        else playback.coroutineScope.launch { handleLiveRouteOpenFailure("initial-open-failed") }
    }

    /** 将一条直播源适配到共用播放器模型；直播时长、音轨和续播身份均为空。 */
    fun livePlayInfo(
        session: LiveSession,
        candidate: LiveStreamCandidate,
        title: String,
    ): BiliApi.PlayInfo {
        val backups = session.routes.asSequence()
            .map { it.candidate }
            .filter { it.url != candidate.url && it.quality == candidate.quality }
            .map { it.url }
            .distinct()
            .toList()
        val track = BiliApi.Track(
            id = candidate.quality,
            codecid = candidate.codecid,
            width = candidate.width,
            height = candidate.height,
            bandwidth = candidate.bandwidth,
            url = candidate.url,
            backup = backups,
            pcdn = candidate.pcdn,
            mimeType = if (candidate.format == "flv") "video/x-flv" else "video/mp4",
            codecs = candidate.codecName,
        )
        return BiliApi.PlayInfo(
            title = title.ifBlank { "直播间 ${session.stream.roomId}" },
            bvid = "",
            aid = 0L,
            cid = session.stream.roomId,
            durationSec = 0L,
            video = track,
            audio = null,
            progressiveUrl = candidate.url,
            videos = listOf(track),
            audios = emptyList(),
            isLive = true,
            liveFormat = candidate.format,
            liveCdn = candidate.cdnLabel,
            liveBackupCount = backups.size,
        )
    }

    /** 每三十秒后台重测最多三条备用线路，更新后续切源排序但不动当前播放。 */
    fun startBackupRefresh(session: LiveSession) {
        backupRefreshJob?.cancel()
        backupRefreshJob = playback.coroutineScope.launch {
            while (isActive && activeSession === session) {
                delay(LIVE_BACKUP_REFRESH_INTERVAL_MS)
                if (!isActive || activeSession !== session || !playback.isCastActive()) break
                val activeUrl = session.active.url
                val refresh = withContext(Dispatchers.IO) {
                    cdn.refreshBackups(
                        candidates = session.stream.qualityCandidates,
                        excludedUrls = session.attemptedUrls + activeUrl,
                    )
                }
                if (activeSession !== session) break
                if (refresh.isEmpty()) continue
                val refreshedByUrl = refresh.associateBy { it.candidate.url }
                val remaining = session.routes
                    .filterNot { it.candidate.url == activeUrl }
                    .map { refreshedByUrl[it.candidate.url] ?: it }
                val official = remaining.filterNot { it.candidate.pcdn }
                val pcNodes = remaining.filter { it.candidate.pcdn }
                val successful = official.filter { it.result?.success == true }
                    .sortedWith(LiveQualityMapper.probeOrder())
                val unmeasured = official.filter { it.result == null }
                val failed = official.filter { it.result?.success == false }
                    .sortedWith(LiveQualityMapper.probeOrder())
                val successfulPcdn = pcNodes.filter { it.result?.success == true }
                    .sortedWith(LiveQualityMapper.probeOrder())
                val unmeasuredPcdn = pcNodes.filter { it.result == null }
                val failedPcdn = pcNodes.filter { it.result?.success == false }
                    .sortedWith(LiveQualityMapper.probeOrder())
                session.routes = (
                    successful + unmeasured + successfulPcdn + unmeasuredPcdn +
                        failed + failedPcdn
                    ).toMutableList()
                session.routes.add(0, RankedLiveSource(session.active))
                playback.setCurrent(livePlayInfo(session, session.active, playback.lastTitle()))
                playback.setState(playback.state().copy(liveBackupCount = playback.currentInfo()?.liveBackupCount ?: 0))
                PlaybackDiagnostics.attribute(
                    "live.backupCount",
                    (session.routes.size - 1).coerceAtLeast(0).toString(),
                )
                PlaybackDiagnostics.event(
                    "LiveBackupRefresh",
                    refresh.joinToString(";") { route ->
                        "host=${route.candidate.cdnLabel} format=${route.candidate.format} " +
                            "codec=${route.candidate.codecName} " +
                            "firstByte=${route.result?.firstByteMs ?: -1}ms " +
                            "throughput=${route.result?.throughputBytesPerSecond ?: 0}Bps " +
                            "success=${route.result?.success}"
                    },
                )
                LogHub.i(
                    TAG,
                    "live backup refresh room=${session.stream.roomId} " +
                        "active=${session.active.cdnLabel} " +
                        "ranked=${refresh.joinToString(",") { it.candidate.cdnLabel }} " +
                        "backupCount=${session.routes.size - 1}",
                )
            }
        }
    }

    /** 播放器的同步 open 失败时立即试下一条同画质线路。 */
    suspend fun handleLiveRouteOpenFailure(reason: String) {
        val session = activeSession ?: return
        try {
            while (activeSession === session && playback.isCastActive()) {
                val next = cdn.nextSameQuality(
                    ordered = session.routes,
                    quality = session.stream.quality,
                    attemptedUrls = session.attemptedUrls,
                )
                if (next == null) {
                    continueFallback(reason)
                    return
                }
                if (switchRoute(next, reason, null)) return
                LogHub.e(
                    TAG,
                    "live candidate open failed room=${session.stream.roomId} " +
                        "quality=${session.stream.quality} host=${next.candidate.cdnLabel} " +
                        "format=${next.candidate.format} codec=${next.candidate.codecName}",
                )
            }
        } catch (failure: Throwable) {
            LogHub.e(
                TAG,
                "live route open recovery failed room=${session.stream.roomId} " +
                    "error=${failure.javaClass.simpleName}",
            )
            fail("LIVE_ROUTE_OPEN_FAILED", "直播线路无法打开")
        }
    }

    /** 处理播放器运行期错误、缓冲或停滞并切换同档线路。 */
    fun handleFailure(reason: String) {
        if (switching || activeSession == null || !playback.isCastActive()) return
        switching = true
        playback.coroutineScope.launch {
            try {
                val session = activeSession ?: return@launch
                val failed = session.active
                val snapshot = runCatching { playback.stateSnapshot() }
                    .getOrDefault("(snapshot unavailable)")
                LogHub.e(
                    TAG,
                    "live playback failure room=${session.stream.roomId} reason=$reason " +
                        "quality=${session.stream.quality} format=${failed.format} " +
                        "codec=${failed.codecName} host=${failed.cdnLabel} snapshot=$snapshot",
                )
                PlaybackDiagnostics.event(
                    "LiveRouteFailure",
                    "room=${session.stream.roomId} reason=$reason quality=${session.stream.quality} " +
                        "format=${failed.format} codec=${failed.codecName} " +
                        "host=${failed.cdnLabel} failure=player-event",
                )
                pendingSwitch = null
                val sameCodec = session.routes.firstOrNull { route ->
                    route.candidate.quality == session.stream.quality &&
                        route.candidate.codecid == failed.codecid &&
                        route.candidate.url !in session.attemptedUrls
                }
                if (sameCodec != null) {
                    if (!switchRoute(sameCodec, reason, failed)) {
                        handleLiveRouteOpenFailure("route-open-failed:$reason")
                    }
                } else {
                    continueFallback(reason)
                }
            } catch (failure: Throwable) {
                LogHub.e(
                    TAG,
                    "live route switch failed reason=$reason " +
                        "error=${failure.javaClass.simpleName}",
                )
                PlaybackDiagnostics.fail(
                    code = "LIVE_ROUTE_SWITCH_FAILED",
                    message = "直播线路切换失败",
                    detail = "reason=$reason error=${failure.javaClass.simpleName}",
                )
                fail("LIVE_ROUTE_SWITCH_FAILED", "直播线路切换失败")
            } finally {
                switching = false
            }
        }
    }

    /** 切换到已排序的同档线路，并保留用户当前的暂停状态。 */
    fun switchRoute(
        target: RankedLiveSource,
        reason: String,
        from: LiveStreamCandidate?,
    ): Boolean {
        val session = activeSession ?: return false
        if (target.candidate.url in session.attemptedUrls) return false
        val prior = from ?: session.active
        session.attemptedUrls += target.candidate.url
        session.active = target.candidate
        stallWatchdog.reset()
        pendingSwitch = PendingLiveRouteSwitch(
            reason = reason,
            from = prior,
            target = target.candidate,
            firstByteMs = target.result?.firstByteMs,
            throughputBytesPerSecond = target.result?.throughputBytesPerSecond,
        )
        val stayPaused = !playback.state().isPlaying && !playback.state().isBuffering && !playback.isPlaying()
        playback.setPendingPause(stayPaused)
        val info = livePlayInfo(session, target.candidate, playback.lastTitle())
        LogHub.i(
            TAG,
            "live switch attempt reason=$reason room=${session.stream.roomId} " +
                "quality=${session.stream.quality} " +
                "from=${prior.cdnLabel}/${prior.format}/${prior.codecName} " +
                "to=${target.candidate.cdnLabel}/${target.candidate.format}/" +
                "${target.candidate.codecName} firstByte=${target.result?.firstByteMs ?: -1}ms " +
                "throughput=${target.result?.throughputBytesPerSecond ?: 0}Bps " +
                "failure=${target.result?.failure.orEmpty()}",
        )
        PlaybackDiagnostics.event(
            "LiveRouteSwitchAttempt",
            "reason=$reason room=${session.stream.roomId} quality=${session.stream.quality} " +
                "from=${prior.cdnLabel}/${prior.format}/${prior.codecName} " +
                "to=${target.candidate.cdnLabel}/${target.candidate.format}/" +
                "${target.candidate.codecName} firstByte=${target.result?.firstByteMs ?: -1}ms " +
                "throughput=${target.result?.throughputBytesPerSecond ?: 0}Bps " +
                "failure=${target.result?.failure.orEmpty()}",
        )
        if (target.candidate.pcdn) {
            LogHub.w(
                TAG,
                "live PCDN fallback activated after official routes exhausted " +
                    "host=${target.candidate.cdnLabel} quality=${session.stream.quality}",
            )
            PlaybackDiagnostics.event(
                "LivePcdnFallback",
                "reason=$reason room=${session.stream.roomId} " +
                    "quality=${session.stream.quality} host=${target.candidate.cdnLabel}",
            )
        }
        val started = playback.openPlayback(info, 0L)
        if (!started) {
            pendingSwitch = null
            PlaybackDiagnostics.event(
                "LiveRouteOpenFailure",
                "room=${session.stream.roomId} reason=$reason " +
                    "quality=${session.stream.quality} format=${target.candidate.format} " +
                    "codec=${target.candidate.codecName} host=${target.candidate.cdnLabel}",
            )
        }
        return started
    }

    /** 没有同档线路时先尝试 HEVC→AVC，再按服务端公布的档位逐级重新解析。 */
    suspend fun continueFallback(reason: String) {
        val session = activeSession ?: return
        val active = session.active
        if (active.codecid == 12 && !session.avcFallbackAttempted) {
            session.avcFallbackAttempted = true
            resolveFallback(session, session.stream.quality, forceAvc = true, reason = "hevc-fallback:$reason")
            return
        }
        val lowerQuality = LivePlayUrlResolver.fallbackQualities(
            currentQuality = session.stream.quality,
            acceptedQualities = session.stream.acceptedQualities,
        ).firstOrNull { it !in session.downshiftedQualities }
        if (lowerQuality != null) {
            session.downshiftedQualities += lowerQuality
            resolveFallback(session, lowerQuality, forceAvc = active.codecid == 7, reason = "quality-downshift:$reason")
            return
        }
        fail(
            code = "LIVE_SOURCES_EXHAUSTED",
            message = "当前账号画质下的直播线路均不可用。",
        )
    }

    /** 重新请求 AVC 或服务端认可的较低档位，并在新候选中完成首播选路。 */
    suspend fun resolveFallback(
        session: LiveSession,
        quality: Int,
        forceAvc: Boolean,
        reason: String,
    ) {
        val token = playback.authToken() ?: error("TV login expired during live route fallback")
        val resolved = withContext(Dispatchers.IO) {
            val live = LivePlayUrlResolver.resolve(
                context = playback.appContext(),
                accessKey = token.accessKey,
                roomId = session.stream.roomId,
                quality = quality,
                forceAvc = forceAvc,
            )
            live to cdn.rankInitial(live.qualityCandidates)
        }
        if (activeSession !== session || !playback.isCastActive()) return
        if (resolved.second.isEmpty()) error("live fallback returned no routes")
        val actual = resolved.first
        if (quality < session.stream.quality && actual.quality >= session.stream.quality) {
            val nextQuality = LivePlayUrlResolver.fallbackQualities(
                currentQuality = session.stream.quality,
                acceptedQualities = session.stream.acceptedQualities,
            ).firstOrNull { it !in session.downshiftedQualities }
            LogHub.w(
                TAG,
                "live quality fallback request not applied room=${session.stream.roomId} " +
                    "requested=$quality actual=${actual.quality} next=${nextQuality ?: "none"}",
            )
            PlaybackDiagnostics.event(
                "LiveQualityFallbackRejected",
                "room=${session.stream.roomId} requested=$quality actual=${actual.quality} " +
                    "next=${nextQuality ?: "none"}",
            )
            if (nextQuality != null) {
                session.downshiftedQualities += nextQuality
                resolveFallback(
                    session = session,
                    quality = nextQuality,
                    forceAvc = session.active.codecid == 7,
                    reason = "quality-downshift:$reason",
                )
            } else {
                fail(
                    "LIVE_QUALITY_DOWNGRADE_REJECTED",
                    "服务器未提供更低画质的直播流。",
                )
            }
            return
        }
        val old = session.active
        session.stream = actual
        session.routes = resolved.second.toMutableList()
        session.attemptedUrls.clear()
        session.avcFallbackAttempted = forceAvc
        val next = session.routes.firstOrNull { it.candidate.quality == actual.quality }
            ?: session.routes.first()
        PlaybackDiagnostics.event(
            "LiveQualityFallback",
            "reason=$reason room=${actual.roomId} requested=${actual.requestedQuality} " +
                "actual=${actual.quality} from=${old.cdnLabel}/${old.format}/${old.codecName} " +
                "to=${next.candidate.cdnLabel}/${next.candidate.format}/" +
                "${next.candidate.codecName}",
        )
        LogHub.w(
            TAG,
            "live re-resolved reason=$reason requested=${actual.requestedQuality} " +
                "actual=${actual.quality} forceAvc=$forceAvc " +
                "selected=${next.candidate.cdnLabel}/${next.candidate.format}/" +
                "${next.candidate.codecName}",
        )
        if (!switchRoute(next, reason, old)) {
            handleLiveRouteOpenFailure("fallback-open-failed:$reason")
        }
    }

    /** 线路和已公布的降级档位均耗尽时展示可重试错误。 */
    fun fail(code: String, message: String) {
        val session = activeSession ?: return
        LogHub.e(
            TAG,
            "live playback exhausted room=${session.stream.roomId} qn=${session.stream.quality} " +
                "attempted=${session.attemptedUrls.size} reason=$code",
        )
        PlaybackDiagnostics.fail(
            code = code,
            message = message,
            detail = "room=${session.stream.roomId} quality=${session.stream.quality} " +
                "attempted=${session.attemptedUrls.size} active=${session.active.cdnLabel}/" +
                "${session.active.format}/${session.active.codecName}",
        )
        val nva = playback.lastNva()
        if (nva != null) playback.setRetry { playback.resolveLast(nva) }
        playback.reportError(
            title = "直播播放失败",
            message = message,
            code = code,
            canRetry = nva != null,
        )
    }

    /** 线路成功进入 Playing 后记录结果并复用现有右下角 toast。 */
    fun completeRouteSwitch() {
        val switched = pendingSwitch ?: return
        pendingSwitch = null
        val session = activeSession ?: return
        LogHub.i(
            TAG,
            "live switch succeeded reason=${switched.reason} room=${session.stream.roomId} " +
                "quality=${session.stream.quality} " +
                "from=${switched.from?.cdnLabel ?: "(initial)"}/" +
                "${switched.from?.format.orEmpty()}/${switched.from?.codecName.orEmpty()} " +
                "to=${switched.target.cdnLabel}/${switched.target.format}/" +
                "${switched.target.codecName} firstByte=${switched.firstByteMs ?: -1}ms " +
                "throughput=${switched.throughputBytesPerSecond ?: 0}Bps",
        )
        PlaybackDiagnostics.event(
            "LiveRouteSwitchSuccess",
            "reason=${switched.reason} room=${session.stream.roomId} " +
                "quality=${session.stream.quality} " +
                "from=${switched.from?.cdnLabel ?: "(initial)"} " +
                "to=${switched.target.cdnLabel}/${switched.target.format}/" +
                "${switched.target.codecName} firstByte=${switched.firstByteMs ?: -1}ms " +
                "throughput=${switched.throughputBytesPerSecond ?: 0}Bps",
        )
        playback.setState(
            playback.state().copy(
                toast = if (switched.reason.startsWith("user-quality")) {
                    "已切换至 ${LiveQualityMapper.label(session.stream.quality)}"
                } else {
                    "已自动切换至更流畅线路"
                },
            ),
        )
    }


    private companion object {
        /** 直播备用线路评分刷新周期。 */
        private const val LIVE_BACKUP_REFRESH_INTERVAL_MS = 30_000L
        /** 直播播放位置无前进时触发故障切源的时间阈值。 */
        private const val LIVE_STALL_TIMEOUT_MS = 5_000L
        /** 日志标签。 */
        const val TAG = "SimpleBili"
    }
}
