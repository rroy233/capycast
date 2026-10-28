package com.roy233.tvcast.feature.playback.application

import android.content.Context
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.feature.cast.domain.CastContent
import com.roy233.tvcast.feature.playback.model.LivePlayInfo
import com.roy233.tvcast.feature.playback.model.PgcPlaybackFailure
import com.roy233.tvcast.feature.playback.model.RankedLiveSource
import com.roy233.tvcast.feature.playback.model.CastResolution
import com.roy233.tvcast.feature.playback.model.VodSession
import com.roy233.tvcast.ui.model.PlaybackSpeed
import com.roy233.tvcast.feature.cast.domain.RecentCast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock

/** 投屏会话需要宿主提供的解析、播放与界面能力。 */
interface CastSessionHost {
    fun remembersPlaybackContent(): Boolean
    fun playbackSpeed(): Float
    fun showRecentLoading(title: String)
    fun showIncomingCast(title: String)
    fun showResolveLoading(title: String)
    fun showPlayback(request: PlaybackRequest, seekToMs: Long)
    fun setDesiredPlaybackSpeed(speed: Float)
    fun coroutineScope(): CoroutineScope
    fun isResolving(): Boolean
    fun setResolving(resolving: Boolean)
    fun cancelResolve()
    fun setResolveJob(job: Job)
    fun currentRequest(): PlaybackRequest?
    fun setCurrentForCast(request: PlaybackRequest?)
    fun setCastActive(active: Boolean)
    fun decodeCastContent(longDescription: String): CastContent?
    fun lastNva(): CastContent?
    fun setLastNva(nva: CastContent?)
    fun setLastTitle(title: String)
    fun setRetry(action: (() -> Unit)?)
    fun clearLoginUi()
    fun setPendingPause(paused: Boolean)
    fun reportError(title: String, message: String, code: String, canRetry: Boolean, keepVideo: Boolean)
    fun addRecentRecord(request: PlaybackRequest)
    fun newHistorySession()
    fun endLiveSession(reason: String)
    fun stopPlaybackForCast(reason: String)
    fun markCastFailed(code: String)
    fun setCastDurationMs(durationMs: Long)
    fun startLiveSession(info: LivePlayInfo, routes: List<RankedLiveSource>, title: String)
    fun accessKey(): String?
    fun appContext(): Context
    fun isEngineInitialized(): Boolean
    fun updateLiveUrl(url: String)
    fun setPendingSeekMs(positionMs: Long)
    fun describeContent(request: PlaybackRequest): String
    fun startPlaybackBackend(request: PlaybackRequest, seekToMs: Long): Boolean
    fun onDanmakuClose()
    fun onLiveDanmakuClose()
    fun onLiveDanmakuOpen(roomId: Long)
    fun onVodDanmakuOpen(aid: Long, cid: Long, durationMs: Long, seekToMs: Long)
    fun onDanmakuPlaybackRate(rate: Float)
    fun onDanmakuSettings()
    fun onDanmakuLayout()
    fun applyPlaybackRate(request: PlaybackRequest)
    suspend fun resolveLivePlayback(accessKey: String, roomId: Long): LivePlayInfo
    suspend fun resolveVodPlayback(
        aid: Long,
        cid: Long,
        bvid: String,
        title: String,
        epId: Long = 0L,
        seasonId: Long = 0L,
    ): PlaybackRequest
    fun applyPendingPause()
    fun vodSessionCoordinator(): VodPlaybackCoordinator
    fun liveSessionCoordinator(): LivePlaybackCoordinator
}

/**
 * 管理投屏请求、NVA 内容解析、内容分流与播放启动。
 *
 * 负责 DLNA 投屏入口、最近投屏记录、重播流程和解析失败的统一错误分类。
 */
class CastSessionCoordinator(
    private val playback: CastSessionHost,
) {

    /** 将当前播放信息加入最近投屏记录。 */
    fun recordRecent(request: PlaybackRequest) {
        if (!playback.remembersPlaybackContent()) return
        playback.addRecentRecord(request)
    }

    /** 根据最近投屏记录重新解析并开始播放。 */
    fun recast(recent: RecentCast) {
        if (playback.isResolving()) {
            LogHub.w(TAG, "recast ignored: another resolve is running")
            return
        }
        playback.newHistorySession()
        playback.setLastNva(null)
        PlaybackDiagnostics.beginSession("recast")
        PlaybackDiagnostics.attribute("cast.source", "recent")
        PlaybackDiagnostics.attribute("recent.aid", recent.aid.toString())
        PlaybackDiagnostics.attribute("recent.cid", recent.cid.toString())
        PlaybackDiagnostics.attribute("recent.title", recent.title)
        LogHub.i(
            TAG,
            "recast begin aid=${recent.aid} cid=${recent.cid} pgc=${recent.isPgc} " +
                "epId=${recent.epId} seasonId=${recent.seasonId} title=${recent.title.take(40)}",
        )
        playback.coroutineScope().launch {
            playback.setResolving(true)
            playback.showRecentLoading(recent.title)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    playback.resolveVodPlayback(
                        aid = recent.aid,
                        cid = recent.cid,
                        bvid = "",
                        title = recent.title,
                        epId = recent.epId.takeIf { recent.isPgc } ?: 0L,
                        seasonId = recent.seasonId.takeIf { recent.isPgc } ?: 0L,
                    )
                }
            }
            playback.setResolving(false)
            result.onSuccess { openPlayback(it, 0L) }.onFailure {
                LogHub.e(
                    TAG,
                    "recast resolve failed: ${LogRedaction.reason(it)}",
                    it,
                    stack = true,
                )
                PlaybackDiagnostics.fail(
                    code = "RECAST_FAILED",
                    message = it.message ?: "视频信息已失效",
                    detail = "aid=${recent.aid} cid=${recent.cid} " +
                        "reason=${LogRedaction.reason(it)}",
                )
                playback.reportError(
                    title = "无法继续投屏",
                    message = it.message ?: "视频信息已失效，请从手机重新投屏。",
                    code = "RECAST_FAILED",
                    canRetry = false,
                    keepVideo = false,
                )
            }
        }
    }

    /** 处理 DLNA 投屏请求并解析其媒体身份。 */
    fun handleCast(uri: String, title: String, longDescription: String) {
        // 新的 SetAVTransportURI 具有更高优先级，旧播放器事件不应污染新会话。
        playback.cancelResolve()
        playback.endLiveSession("new-cast")
        playback.setCurrentForCast(null)
        playback.stopPlaybackForCast("new-cast")
        playback.newHistorySession()
        PlaybackDiagnostics.beginSession("cast")
        PlaybackDiagnostics.attribute("cast.source", "dlna")
        PlaybackDiagnostics.attribute("cast.uri", LogRedaction.url(uri))
        PlaybackDiagnostics.attribute("cast.title", title)
        PlaybackDiagnostics.attribute(
            "cast.longDescriptionBytes",
            longDescription.length.toString(),
        )
        LogHub.i(
            TAG,
            "cast received title=${title.take(80)} uri=${LogRedaction.url(uri)} " +
                "longDescription=${longDescription.length}B",
        )
        playback.setCastActive(true)
        playback.setLastTitle(title)
        playback.setPendingPause(false)
        playback.setLastNva(null)
        playback.setRetry(null)
        playback.clearLoginUi()
        LogHub.setCast(title)
        playback.showIncomingCast(title)

        val nva = playback.decodeCastContent(longDescription)
        if (nva == null) {
            LogHub.e(TAG, "cast rejected: nva_ext decode failed")
            PlaybackDiagnostics.fail(
                code = "NVA_DECODE_FAILED",
                message = "无法识别这次投屏",
                detail = "longDescriptionBytes=${longDescription.length} " +
                    "metadataUri=${LogRedaction.url(uri)}",
            )
            playback.markCastFailed("nva-decode-failed")
            playback.setCastActive(false)
            playback.reportError(
                title = "无法识别这次投屏",
                message = "请更新手机 B 站 App 后重新投屏。",
                code = "NVA_DECODE_FAILED",
                canRetry = false,
                keepVideo = false,
                )
            return
        }
        playback.setLastNva(nva)
        val desiredSpeed = nva.userDesireSpeed
            ?.toFloatOrNull()
            ?.let(PlaybackSpeed::normalize)
            ?: 1f
        LogHub.i(
            TAG,
            "cast nva aid=${nva.aid} cid=${nva.cid} bvid=${nva.bvid} " +
                "eid=${nva.epId} sid=${nva.seasonId} room=${nva.roomId} " +
                "qn=${nva.qn} desireQn=${nva.userDesireQn} speed=$desiredSpeed " +
                "ct=${nva.contentType} tp=${nva.transportType}",
        )
        PlaybackDiagnostics.attribute("cast.aid", nva.aid.toString())
        PlaybackDiagnostics.attribute("cast.cid", nva.cid.toString())
        PlaybackDiagnostics.attribute("cast.bvid", nva.bvid.orEmpty())
        PlaybackDiagnostics.attribute("cast.eid", nva.epId.toString())
        PlaybackDiagnostics.attribute("cast.sid", nva.seasonId.toString())
        PlaybackDiagnostics.attribute("cast.roomId", nva.roomId.toString())
        PlaybackDiagnostics.attribute("cast.requestQn", nva.qn.toString())
        PlaybackDiagnostics.attribute("cast.desireQn", nva.userDesireQn.toString())
        PlaybackDiagnostics.attribute("cast.desireSpeed", desiredSpeed.toString())
        playback.setDesiredPlaybackSpeed(desiredSpeed)
        resolve(nva, title)
    }

    /** 根据 nva 内容解析播放地址并开始播放。 */
    fun resolve(nva: CastContent, title: String) {
        playback.cancelResolve()
        playback.endLiveSession("resolve-new-cast")
        playback.setResolving(true)
        playback.setLastNva(nva)
        playback.setLastTitle(title)
        val branch = when {
            nva.hasLive -> "live"
            nva.hasPgc && nva.epId > 0 -> "pgc"
            nva.hasPgc -> "pgc-without-epId"
            nva.hasUgc -> "ugc"
            else -> "none"
        }
        LogHub.i(
            TAG,
            "resolve begin branch=$branch aid=${nva.aid} cid=${nva.cid} " +
                "bvid=${nva.bvid} eid=${nva.epId} room=${nva.roomId}",
        )
        PlaybackDiagnostics.attribute("resolve.branch", branch)
        playback.showResolveLoading(title)

        playback.setResolveJob(
            playback.coroutineScope().launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        when {
                            nva.hasLive -> {
                                val accessKey = playback.accessKey()
                                    ?: error("TV account login is required for live playback")
                                val liveInfo = playback.resolveLivePlayback(accessKey, nva.roomId)
                                val routes = playback.liveSessionCoordinator()
                                    .cdn.rankInitial(liveInfo.qualityCandidates)
                                if (routes.isEmpty()) {
                                    error("live response has no eligible CDN routes")
                                }
                                CastResolution.Live(liveInfo, routes)
                            }
                            nva.hasPgc -> CastResolution.Vod(
                                playback.vodSessionCoordinator().cdn.rankPlayback(
                                    playback.resolveVodPlayback(
                                        aid = nva.aid, cid = nva.cid, bvid = nva.bvid.orEmpty(), title = title,
                                        epId = nva.epId, seasonId = nva.seasonId,
                                    ),
                                ),
                            )
                            nva.hasUgc -> CastResolution.Vod(
                                playback.vodSessionCoordinator().cdn.rankPlayback(
                                    playback.resolveVodPlayback(nva.aid, nva.cid, nva.bvid.orEmpty(), title),
                                ),
                            )
                            else ->
                                error("no playable identity in nva_ext")
                        }
                    }
                }
                if (!isActive) return@launch
                playback.setResolving(false)
                result.onSuccess { resolved ->
                    when (resolved) {
                        is CastResolution.Vod -> {
                            val ranked = resolved.playback
                            val request = ranked.request
                            playback.vodSessionCoordinator().activeSession = VodSession(
                                request = request,
                                videoRoutes = ranked.videoRoutes,
                                audioRoutes = ranked.audioRoutes,
                                lastPositionMs = 0L,
                                lastProgressAtMs = SystemClock.elapsedRealtime(),
                            )
                            playback.vodSessionCoordinator().startBackupRefresh()
                            LogHub.i(
                                TAG,
                                "resolved qn=${request.video.id} ${request.video.width}x${request.video.height} " +
                                    "codec=${request.video.codecId} audio=${request.audio?.id} " +
                                    "duration=${request.durationSec}s videos=${request.videos.size} " +
                                    "audios=${request.audios.size} progressive=${request.progressiveUrl != null} " +
                                    "videoRoutes=${ranked.videoRoutes.size} audioRoutes=${ranked.audioRoutes.size}",
                            )
                            PlaybackDiagnostics.attribute("resolve.videos", request.videos.size.toString())
                            PlaybackDiagnostics.attribute("resolve.audios", request.audios.size.toString())
                            PlaybackDiagnostics.attribute(
                                "resolve.progressive",
                                (request.progressiveUrl != null).toString(),
                            )
                            playback.setCastDurationMs(request.durationSec * 1000)
                            openPlayback(request, 0L)
                        }
                        is CastResolution.Live ->
                            playback.startLiveSession(resolved.info, resolved.routes, title)
                    }
                }.onFailure { t ->
                    val message = t.message.orEmpty()
                    val live = nva.hasLive
                    val pgcError = t as? PgcPlaybackFailure
                    val pgc = nva.hasPgc
                    val pgcStatus = pgcError?.let { error ->
                        "httpStatus=${error.httpStatus ?: "none"} apiCode=${error.apiCode ?: "none"}"
                    }.orEmpty()
                    val code = when {
                        pgcError != null -> pgcError.errorCode
                        live -> "LIVE_RESOLVE_FAILED"
                        else -> "RESOLVE_FAILED"
                    }
                    LogHub.e(
                        TAG,
                        "cast resolve failed code=$code $pgcStatus: ${LogRedaction.reason(t)}",
                    )
                    PlaybackDiagnostics.fail(
                        code = code,
                        message = pgcError?.userMessage ?: message.ifBlank { "取流失败" },
                        detail = "branch=$branch aid=${nva.aid} cid=${nva.cid} " +
                            "$pgcStatus reason=${LogRedaction.reason(t)}",
                    )
                    playback.markCastFailed(code)
                    playback.setRetry { resolve(nva, title) }
                    playback.reportError(
                        title = when {
                            live -> "直播取流失败"
                            pgcError?.errorCode == "PGC_LOGIN_REQUIRED" -> "B 站登录失效"
                            pgcError?.errorCode == "PGC_PREVIEW_ONLY" -> "当前仅有预览片段"
                            pgcError?.errorCode == "PGC_DRM_METADATA_INVALID" -> "受保护内容解析失败"
                            pgc -> "番剧或影视取流失败"
                            else -> "取流失败"
                        },
                        message = when {
                            live && message.contains("login", ignoreCase = true) ->
                                "请先在电视端登录 B 站账号后重试。"
                            live -> "未能获取当前账号可播放的直播源，请稍后重试。"
                            pgcError != null -> pgcError.userMessage
                            pgc -> "请检查电视端 B 站登录状态、网络和内容授权，再重试。"
                            else -> "请检查网络或账号权益。"
                        },
                        code = code,
                        canRetry = true,
                        keepVideo = false,
                )
                }
            },
        )
    }

    /** 使用指定播放信息打开播放器并更新界面状态。 */
    fun openPlayback(request: PlaybackRequest, seekToMs: Long): Boolean {
        if (!playback.isEngineInitialized()) {
            LogHub.e(
                TAG,
                "play ignored: player engine unavailable aid=${request.aid} cid=${request.cid}",
            )
            PlaybackDiagnostics.fail(
                code = "PLAYER_UNAVAILABLE",
                message = "播放器未初始化",
                detail = "aid=${request.aid} cid=${request.cid} title=${request.title}",
            )
            playback.reportError(
                title = "播放器不可用",
                message = "应用未能初始化播放器，请重启应用后重试；如仍失败请查看诊断日志。",
                code = "PLAYER_UNAVAILABLE",
                canRetry = false,
                keepVideo = false,
            )
            return false
        }
        playback.setCurrentForCast(request)
        if (request.isLive) playback.updateLiveUrl(request.video.url)
        playback.setCastActive(true)
        playback.setLastTitle(request.title)
        playback.setRetry(null)
        playback.setPendingSeekMs(seekToMs.coerceAtLeast(0L))
        val vodGeneration = if (!request.isLive) {
            playback.vodSessionCoordinator().onOpening(seekToMs.coerceAtLeast(0L))
        } else null
        LogHub.i(
            TAG,
            "play begin qn=${request.video.id} codec=${request.video.codecId} " +
                "${request.video.width}x${request.video.height} audio=${request.audio?.id} " +
                "seek=${seekToMs}ms progressive=${request.progressiveUrl != null} vodGeneration=${vodGeneration ?: "none"}",
        )

        playback.showPlayback(request, seekToMs)
        if (!request.isLive) recordRecent(request)

        val started = playback.startPlaybackBackend(request, seekToMs)
        if (!started) {
            if (request.isLive) {
                LogHub.e(
                    TAG,
                    "live player open rejected room=${request.cid} qn=${request.video.id} " +
                        "format=${request.liveFormat} codec=${request.video.codecId} " +
                        "host=${LogRedaction.host(request.progressiveUrl)}",
                )
                PlaybackDiagnostics.fail(
                    code = "LIVE_PLAYER_OPEN_FAILED",
                    message = "直播播放器无法打开当前线路",
                    detail = "room=${request.cid} qn=${request.video.id} " +
                        "format=${request.liveFormat} codec=${request.video.codecId} " +
                        "host=${LogRedaction.host(request.progressiveUrl)}",
                )
                return false
            }
            playback.setRetry { openPlayback(request, seekToMs) }
            playback.reportError(
                title = "播放失败",
                message = "无法打开该视频。诊断日志已记录具体原因。",
                code = "PLAYER_OPEN_FAILED",
                canRetry = true,
                keepVideo = false,
            )
            return false
        }

        if (request.isLive) {
            playback.onDanmakuClose()
            playback.onLiveDanmakuOpen(request.cid)
        } else {
            playback.onLiveDanmakuClose()
            playback.onVodDanmakuOpen(request.aid, request.cid, request.durationSec * 1000, seekToMs)
            playback.onDanmakuPlaybackRate(playback.playbackSpeed())
            playback.onDanmakuSettings()
            playback.onDanmakuLayout()
        }
        playback.applyPlaybackRate(request)

        playback.applyPendingPause()
        LogHub.setPlay(playback.describeContent(request))
        LogHub.i(TAG, "play opened qn=${request.video.id} ${request.video.width}x${request.video.height}")
        return true
    }

    private companion object {
        /** 日志标签。 */
        const val TAG = "SimpleBili"
    }
}
