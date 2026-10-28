package com.roy233.tvcast.feature.playback.application

import android.os.SystemClock
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.feature.playback.model.LivePlayInfo
import com.roy233.tvcast.feature.playback.model.RankedLiveSource
import com.roy233.tvcast.feature.playback.model.RankedVodRoute
import com.roy233.tvcast.feature.playback.model.VodSession
import com.roy233.tvcast.feature.playback.routing.CdnRouteRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 手动画质、音轨和直播画质选择的宿主端口。 */
interface PlaybackSelectionHost {
    val coroutineScope: CoroutineScope
    fun currentRequest(): PlaybackRequest?
    fun isCastActive(): Boolean
    fun positionMs(): Long
    fun vodCoordinator(): VodPlaybackCoordinator
    fun liveCoordinator(): LivePlaybackCoordinator
    fun openSelectedPlayback(request: PlaybackRequest, positionMs: Long): Boolean
    fun liveAccessKey(): String?
    suspend fun resolveLiveQuality(accessKey: String, roomId: Long, quality: Int, forceAvc: Boolean): LivePlayInfo
    fun switchSelectedLiveRoute(target: RankedLiveSource, reason: String, from: com.roy233.tvcast.feature.playback.model.LiveStreamCandidate?): Boolean
    suspend fun onSelectedLiveRouteOpenFailure(reason: String)
    fun onQualitySelected(label: String)
    fun onAudioSelected(label: String)
    fun showSelectionToast(message: String)
}

/** 保留原有选择、线路排序与从当前位置续播时序。 */
class PlaybackSelectionCoordinator(private val host: PlaybackSelectionHost) {
    /** 切换当前视频清晰度并从当前位置继续播放。 */
    fun switchQuality(qn: Int) {
        if (host.currentRequest()?.isLive == true) {
            switchLiveQuality(qn)
            return
        }
        val request = host.currentRequest()
        if (request == null) {
            LogHub.w(TAG, "switchQuality qn=$qn ignored: no current media")
            return
        }
        val track = request.videos.firstOrNull { it.id == qn }
        if (track == null) {
            LogHub.w(
                TAG,
                "switchQuality qn=$qn not found; available=${request.videos.joinToString(",") { it.id.toString() }}",
            )
            return
        }
        if (host.vodCoordinator().switching) return
        val position = host.positionMs()
        LogHub.i(
            TAG,
            "switchQuality qn=$qn codec=${track.codecId} ${track.width}x${track.height} " +
                "host=${LogRedaction.host(track.url)} at=${position}ms",
        )
        PlaybackDiagnostics.event(
            "QualitySwitch",
            "qn=$qn codec=${track.codecId} size=${track.width}x${track.height} " +
                "host=${LogRedaction.host(track.url)} at=${position}ms",
        )
        host.vodCoordinator().switching = true
        host.coroutineScope.launch {
            try {
                val routes = withContext(Dispatchers.IO) { host.vodCoordinator().cdn.rankTrack(track) }
                if (host.currentRequest()?.video?.id != request.video.id || !host.isCastActive()) return@launch
                val route = routes.firstOrNull() ?: RankedVodRoute(track.url)
                val orderedRoutes = routes.ifEmpty { listOf(route) }
                val nextRequest = request.copy(video = track.copy(
                    url = route.url,
                    backupUrls = orderedRoutes.filterNot { it.url == route.url }.map { it.url },
                    isPcdn = CdnRouteRules.isPcdn(route.url),
                ))
                val oldSession = host.vodCoordinator().activeSession
                host.vodCoordinator().activeSession = VodSession(
                    request = nextRequest,
                    videoRoutes = orderedRoutes,
                    audioRoutes = oldSession?.audioRoutes.orEmpty(),
                    lastPositionMs = position,
                    lastProgressAtMs = SystemClock.elapsedRealtime(),
                )
                host.vodCoordinator().startBackupRefresh()
                if (host.openSelectedPlayback(nextRequest, position)) {
                    host.onQualitySelected(nextRequest.qualityLabel)
                }
            } catch (error: Throwable) {
                LogHub.e(TAG, "quality switch failed qn=$qn ${LogRedaction.reason(error)}", error)
                host.showSelectionToast("画质切换失败")
            } finally {
                host.vodCoordinator().switching = false
            }
        }
    }

    /** 按用户选择重新解析并播放指定直播画质。 */
    fun switchLiveQuality(qn: Int) {
        val session = host.liveCoordinator().activeSession ?: return
        if (!host.isCastActive()) return
        if (qn == session.stream.quality) {
            host.showSelectionToast("已是当前画质")
            return
        }
        if (host.liveCoordinator().switching) return
        host.liveCoordinator().switching = true
        host.coroutineScope.launch {
            try {
                val token = host.liveAccessKey()
                    ?: error("TV login expired during live quality switch")
                val forceAvc = session.active.codecid == 7
                val resolved = withContext(Dispatchers.IO) {
                    val live = host.resolveLiveQuality(token, session.stream.roomId, qn, forceAvc)
                    live to host.liveCoordinator().cdn.rankInitial(live.qualityCandidates)
                }
                if (host.liveCoordinator().activeSession !== session || !host.isCastActive()) return@launch
                if (resolved.second.isEmpty()) error("live quality switch returned no routes")
                val actual = resolved.first
                val old = session.active
                session.stream = actual
                session.routes = resolved.second.toMutableList()
                session.attemptedUrls.clear()
                session.avcFallbackAttempted = forceAvc
                session.downshiftedQualities.clear()
                val next = session.routes.firstOrNull { it.candidate.quality == actual.quality }
                    ?: session.routes.first()
                PlaybackDiagnostics.event(
                    "LiveQualitySwitch",
                    "requested=$qn actual=${actual.quality} " +
                        "from=${old.cdnLabel}/${old.format}/${old.codecName} " +
                        "to=${next.candidate.cdnLabel}/${next.candidate.format}/" +
                        "${next.candidate.codecName}",
                )
                LogHub.i(
                    TAG,
                    "live quality switch requested=$qn actual=${actual.quality} " +
                        "selected=${next.candidate.cdnLabel}/${next.candidate.format}/" +
                        "${next.candidate.codecName}",
                )
                if (!host.switchSelectedLiveRoute(next, "user-quality", old)) {
                    host.onSelectedLiveRouteOpenFailure("quality-switch-open-failed")
                }
            } catch (failure: Throwable) {
                LogHub.e(
                    TAG,
                    "live quality switch failed qn=$qn " +
                        "error=${failure.javaClass.simpleName}",
                )
                PlaybackDiagnostics.event(
                    "LiveQualitySwitchFailed",
                    "requested=$qn error=${failure.javaClass.simpleName}",
                )
                host.showSelectionToast("画质切换失败")
            } finally {
                host.liveCoordinator().switching = false
            }
        }
    }

    /** 切换当前音轨并从当前位置继续播放。 */
    fun switchAudio(id: Int) {
        if (host.currentRequest()?.isLive == true) {
            LogHub.w(TAG, "manual audio switch ignored for live id=$id")
            return
        }
        val request = host.currentRequest()
        if (request == null) {
            LogHub.w(TAG, "switchAudio id=$id ignored: no current media")
            return
        }
        val track = request.audios.firstOrNull { it.id == id }
        if (track == null) {
            LogHub.w(
                TAG,
                "switchAudio id=$id not found; available=${request.audios.joinToString(",") { it.id.toString() }}",
            )
            return
        }
        val position = host.positionMs()
        LogHub.i(
            TAG,
            "switchAudio id=$id host=${LogRedaction.host(track.url)} at=${position}ms",
        )
        PlaybackDiagnostics.event(
            "AudioSwitch",
            "id=$id host=${LogRedaction.host(track.url)} at=${position}ms",
        )
        if (host.vodCoordinator().switching) return
        host.vodCoordinator().switching = true
        host.coroutineScope.launch {
            try {
                val routes = withContext(Dispatchers.IO) { host.vodCoordinator().cdn.rankTrack(track) }
                if (host.currentRequest()?.video?.id != request.video.id || !host.isCastActive()) return@launch
                val route = routes.firstOrNull() ?: RankedVodRoute(track.url)
                val orderedRoutes = routes.ifEmpty { listOf(route) }
                val nextRequest = request.copy(audio = track.copy(
                    url = route.url,
                    backupUrls = orderedRoutes.filterNot { it.url == route.url }.map { it.url },
                    isPcdn = CdnRouteRules.isPcdn(route.url),
                ))
                host.vodCoordinator().activeSession?.let { session ->
                    session.request = nextRequest
                    session.audioRoutes = orderedRoutes
                    session.attemptedAudioUrls.clear()
                }
                if (host.openSelectedPlayback(nextRequest, position)) {
                    host.onAudioSelected(nextRequest.audioLabel)
                }
            } catch (error: Throwable) {
                LogHub.e(TAG, "audio switch failed id=$id ${LogRedaction.reason(error)}", error)
                host.showSelectionToast("音轨切换失败")
            } finally {
                host.vodCoordinator().switching = false
            }
        }
    }

    private companion object { const val TAG = "SimpleBili" }
}
