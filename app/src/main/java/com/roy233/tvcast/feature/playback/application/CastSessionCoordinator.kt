package com.roy233.tvcast.feature.playback.application

import android.content.Context
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.data.auth.CastAuth
import com.roy233.tvcast.data.bili.BiliApi
import com.roy233.tvcast.data.bili.LivePlayInfo
import com.roy233.tvcast.data.bili.LivePlayUrlResolver
import com.roy233.tvcast.data.bili.NvaExtDecoder
import com.roy233.tvcast.data.bili.PgcPlaybackException
import com.roy233.tvcast.data.bili.PgcPlaybackResolver
import com.roy233.tvcast.data.bili.RankedLiveSource
import com.roy233.tvcast.feature.playback.model.CastResolution
import com.roy233.tvcast.feature.playback.model.VodSession
import com.roy233.tvcast.feature.playback.presentation.LiveQualityMapper
import com.roy233.tvcast.feature.playback.presentation.TrackOptionMapper
import com.roy233.tvcast.ui.model.PlaybackSpeed
import com.roy233.tvcast.ui.model.RecentCast
import com.roy233.tvcast.ui.model.TvDestination
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock

/** 投屏会话需要宿主提供的解析、播放与界面能力。 */
interface CastSessionHost {
    fun state(): PlaybackUiState
    fun setState(state: PlaybackUiState)
    fun coroutineScope(): CoroutineScope
    fun isResolving(): Boolean
    fun setResolving(resolving: Boolean)
    fun cancelResolve()
    fun setResolveJob(job: Job)
    fun currentInfo(): BiliApi.PlayInfo?
    fun setCurrentForCast(info: BiliApi.PlayInfo?)
    fun setCastActive(active: Boolean)
    fun lastNva(): NvaExtDecoder.NvaExtContent?
    fun setLastNva(nva: NvaExtDecoder.NvaExtContent?)
    fun setLastTitle(title: String)
    fun setRetry(action: (() -> Unit)?)
    fun clearLoginUi()
    fun setPendingPause(paused: Boolean)
    fun reportError(title: String, message: String, code: String, canRetry: Boolean, keepVideo: Boolean)
    fun addRecentRecord(info: BiliApi.PlayInfo)
    fun newHistorySession()
    fun endLiveSession(reason: String)
    fun stopPlaybackForCast(reason: String)
    fun markCastFailed(code: String)
    fun setCastDurationMs(durationMs: Long)
    fun startLiveSession(info: LivePlayInfo, routes: List<RankedLiveSource>, title: String)
    fun authToken(): CastAuth.Token?
    fun appContext(): Context
    fun isEngineInitialized(): Boolean
    fun updateLiveUrl(url: String)
    fun setPendingSeekMs(positionMs: Long)
    fun describeContent(info: BiliApi.PlayInfo): String
    fun startPlaybackBackend(info: BiliApi.PlayInfo, seekToMs: Long): Boolean
    fun onDanmakuClose()
    fun onLiveDanmakuClose()
    fun onLiveDanmakuOpen(roomId: Long)
    fun onVodDanmakuOpen(aid: Long, cid: Long, durationMs: Long, seekToMs: Long)
    fun onDanmakuPlaybackRate(rate: Float)
    fun onDanmakuSettings()
    fun onDanmakuLayout()
    fun applyPlaybackRate(info: BiliApi.PlayInfo)
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
    fun recordRecent(info: BiliApi.PlayInfo) {
        if (!playback.state().rememberPlaybackContent) return
        playback.addRecentRecord(info)
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
            playback.setState(
                playback.state().copy(
                    mode = TvMode.LOADING,
                    castTitle = recent.title,
                    destination = TvDestination.HOME,
                    sidebarVisible = false,
                    helpVisible = false,
                    hudVisible = false,
                ),
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (recent.isPgc && (recent.epId > 0L || recent.seasonId > 0L)) {
                        PgcPlaybackResolver().resolve(
                            epId = recent.epId,
                            seasonId = recent.seasonId,
                            cid = recent.cid,
                            aid = recent.aid,
                            bvid = "",
                            titleFallback = recent.title,
                        )
                    } else {
                        BiliApi.resolveByAidCid(recent.aid, recent.cid, recent.title)
                    }
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
        val state = playback.state()
        playback.setState(
            state.copy(
                mode = TvMode.LOADING,
                showContinue = state.rememberPlaybackContent,
                castTitle = title.ifBlank { "正在投屏" },
                qualityLabel = "",
                qualityDetail = "",
                qualitySource = "",
                audioLabel = "",
                aid = 0L,
                cid = 0L,
                isPlaying = false,
                isBuffering = false,
                isLive = false,
                liveFormat = "",
                liveCdn = "",
                liveBackupCount = 0,
                positionMs = 0,
                durationMs = 0,
                hudVisible = true,
                panel = null,
                stopCastConfirm = false,
                error = null,
                tracks = emptyList(),
                audios = emptyList(),
            ),
        )

        val nva = NvaExtDecoder.decode(longDescription)
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
                "eid=${nva.eid} sid=${nva.sid} room=${nva.roomId} " +
                "qn=${nva.qn} desireQn=${nva.userDesireQn} speed=$desiredSpeed " +
                "ct=${nva.ct} tp=${nva.tp}",
        )
        PlaybackDiagnostics.attribute("cast.aid", nva.aid.toString())
        PlaybackDiagnostics.attribute("cast.cid", nva.cid.toString())
        PlaybackDiagnostics.attribute("cast.bvid", nva.bvid.orEmpty())
        PlaybackDiagnostics.attribute("cast.eid", nva.eid.toString())
        PlaybackDiagnostics.attribute("cast.sid", nva.sid.toString())
        PlaybackDiagnostics.attribute("cast.roomId", nva.roomId.toString())
        PlaybackDiagnostics.attribute("cast.requestQn", nva.qn.toString())
        PlaybackDiagnostics.attribute("cast.desireQn", nva.userDesireQn.toString())
        PlaybackDiagnostics.attribute("cast.desireSpeed", desiredSpeed.toString())
        playback.setState(playback.state().copy(playbackSpeed = desiredSpeed))
        resolve(nva, title)
    }

    /** 根据 nva 内容解析播放地址并开始播放。 */
    fun resolve(nva: NvaExtDecoder.NvaExtContent, title: String) {
        playback.cancelResolve()
        playback.endLiveSession("resolve-new-cast")
        playback.setResolving(true)
        playback.setLastNva(nva)
        playback.setLastTitle(title)
        val branch = when {
            nva.hasLive -> "live"
            nva.hasPgc && nva.eid > 0 -> "pgc"
            nva.hasPgc -> "pgc-without-epId"
            nva.hasUgc -> "ugc"
            else -> "none"
        }
        LogHub.i(
            TAG,
            "resolve begin branch=$branch aid=${nva.aid} cid=${nva.cid} " +
                "bvid=${nva.bvid} eid=${nva.eid} room=${nva.roomId}",
        )
        PlaybackDiagnostics.attribute("resolve.branch", branch)
        val state = playback.state()
        playback.setState(
            state.copy(
                mode = TvMode.LOADING,
                showContinue = state.rememberPlaybackContent,
                castTitle = title.ifBlank { "正在投屏" },
                error = null,
                hudVisible = true,
            ),
        )

        playback.setResolveJob(
            playback.coroutineScope().launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        when {
                            nva.hasLive -> {
                                val token = playback.authToken()
                                    ?: error("TV account login is required for live playback")
                                val liveInfo = LivePlayUrlResolver.resolve(
                                    context = playback.appContext(),
                                    accessKey = token.accessKey,
                                    roomId = nva.roomId,
                                )
                                val routes = playback.liveSessionCoordinator()
                                    .cdn.rankInitial(liveInfo.qualityCandidates)
                                if (routes.isEmpty()) {
                                    error("live response has no eligible CDN routes")
                                }
                                CastResolution.Live(liveInfo, routes)
                            }
                            nva.hasPgc -> CastResolution.Vod(
                                playback.vodSessionCoordinator().cdn.rankPlayback(
                                    PgcPlaybackResolver().resolve(
                                        epId = nva.eid,
                                        seasonId = nva.sid,
                                        cid = nva.cid,
                                        aid = nva.aid,
                                        bvid = nva.bvid.orEmpty(),
                                        titleFallback = title,
                                    ),
                                ),
                            )
                            nva.hasUgc -> CastResolution.Vod(
                                playback.vodSessionCoordinator().cdn.rankPlayback(
                                    BiliApi.resolveByAidCid(nva.aid, nva.cid, title, nva.bvid ?: ""),
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
                            val info = ranked.info
                            playback.vodSessionCoordinator().activeSession = VodSession(
                                info = info,
                                videoRoutes = ranked.videoRoutes,
                                audioRoutes = ranked.audioRoutes,
                                lastPositionMs = 0L,
                                lastProgressAtMs = SystemClock.elapsedRealtime(),
                            )
                            playback.vodSessionCoordinator().startBackupRefresh()
                            LogHub.i(
                                TAG,
                                "resolved qn=${info.video.id} ${info.video.width}x${info.video.height} " +
                                    "codec=${info.video.codecid} audio=${info.audio?.id} " +
                                    "duration=${info.durationSec}s videos=${info.videos.size} " +
                                    "audios=${info.audios.size} progressive=${info.progressiveUrl != null} " +
                                    "videoRoutes=${ranked.videoRoutes.size} audioRoutes=${ranked.audioRoutes.size}",
                            )
                            PlaybackDiagnostics.attribute("resolve.videos", info.videos.size.toString())
                            PlaybackDiagnostics.attribute("resolve.audios", info.audios.size.toString())
                            PlaybackDiagnostics.attribute(
                                "resolve.progressive",
                                (info.progressiveUrl != null).toString(),
                            )
                            playback.setCastDurationMs(info.durationSec * 1000)
                            openPlayback(info, 0L)
                        }
                        is CastResolution.Live ->
                            playback.startLiveSession(resolved.info, resolved.routes, title)
                    }
                }.onFailure { t ->
                    val message = t.message.orEmpty()
                    val live = nva.hasLive
                    val pgcError = t as? PgcPlaybackException
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
    fun openPlayback(info: BiliApi.PlayInfo, seekToMs: Long): Boolean {
        if (!playback.isEngineInitialized()) {
            LogHub.e(
                TAG,
                "play ignored: player engine unavailable aid=${info.aid} cid=${info.cid}",
            )
            PlaybackDiagnostics.fail(
                code = "PLAYER_UNAVAILABLE",
                message = "播放器未初始化",
                detail = "aid=${info.aid} cid=${info.cid} title=${info.title}",
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
        playback.setCurrentForCast(info)
        if (info.isLive) playback.updateLiveUrl(info.video.url)
        playback.setCastActive(true)
        playback.setLastTitle(info.title)
        playback.setRetry(null)
        playback.setPendingSeekMs(seekToMs.coerceAtLeast(0L))
        val vodGeneration = if (!info.isLive) {
            playback.vodSessionCoordinator().onOpening(seekToMs.coerceAtLeast(0L))
        } else null
        LogHub.i(
            TAG,
            "play begin qn=${info.video.id} codec=${info.video.codecid} " +
                "${info.video.width}x${info.video.height} audio=${info.audio?.id} " +
                "seek=${seekToMs}ms progressive=${info.progressiveUrl != null} vodGeneration=${vodGeneration ?: "none"}",
        )

        val state = playback.state()
        playback.setState(
            state.copy(
                mode = TvMode.PLAYING,
                showContinue = state.rememberPlaybackContent,
                castTitle = info.title,
                qualityLabel = if (info.isLive) {
                    LiveQualityMapper.label(info.video.id)
                } else {
                    info.qualityLabel
                },
                qualityDetail = if (info.isLive) {
                    info.video.codecs.ifBlank {
                        com.roy233.tvcast.platform.player.VlcTrackFormat.codecName(info.video.codecid)
                    }
                } else {
                    info.qualityDetail
                },
                qualitySource = if (info.isLive) "直播" else if (info.video.id >= 80) "已提升" else "",
                audioLabel = if (info.isLive) "自动音轨" else info.audioLabel,
                aid = info.aid,
                cid = info.cid,
                isPlaying = true,
                isBuffering = false,
                isLive = info.isLive,
                liveFormat = info.liveFormat,
                liveCdn = info.liveCdn,
                liveBackupCount = info.liveBackupCount,
                liveQualities = if (info.isLive) {
                    playback.liveSessionCoordinator().activeSession
                        ?.let { LiveQualityMapper.options(it.stream) }
                        ?: emptyList()
                } else {
                    emptyList()
                },
                positionMs = if (info.isLive) 0L else seekToMs.coerceAtLeast(0L),
                durationMs = if (info.isLive) 0L else info.durationSec * 1000,
                selectedQn = info.video.id,
                selectedAudioId = info.audio?.id ?: 0,
                tracks = TrackOptionMapper.tracks(info),
                audios = TrackOptionMapper.audios(info),
                hudVisible = true,
                panel = null,
                error = null,
                stopCastConfirm = false,
            ),
        )
        if (!info.isLive) recordRecent(info)

        val started = playback.startPlaybackBackend(info, seekToMs)
        if (!started) {
            if (info.isLive) {
                LogHub.e(
                    TAG,
                    "live player open rejected room=${info.cid} qn=${info.video.id} " +
                        "format=${info.liveFormat} codec=${info.video.codecid} " +
                        "host=${LogRedaction.host(info.progressiveUrl)}",
                )
                PlaybackDiagnostics.fail(
                    code = "LIVE_PLAYER_OPEN_FAILED",
                    message = "直播播放器无法打开当前线路",
                    detail = "room=${info.cid} qn=${info.video.id} " +
                        "format=${info.liveFormat} codec=${info.video.codecid} " +
                        "host=${LogRedaction.host(info.progressiveUrl)}",
                )
                return false
            }
            playback.setRetry { openPlayback(info, seekToMs) }
            playback.reportError(
                title = "播放失败",
                message = "无法打开该视频。诊断日志已记录具体原因。",
                code = "PLAYER_OPEN_FAILED",
                canRetry = true,
                keepVideo = false,
            )
            return false
        }

        if (info.isLive) {
            playback.onDanmakuClose()
            playback.onLiveDanmakuOpen(info.cid)
        } else {
            playback.onLiveDanmakuClose()
            playback.onVodDanmakuOpen(info.aid, info.cid, info.durationSec * 1000, seekToMs)
            playback.onDanmakuPlaybackRate(playback.state().playbackSpeed)
            playback.onDanmakuSettings()
            playback.onDanmakuLayout()
        }
        playback.applyPlaybackRate(info)

        playback.applyPendingPause()
        LogHub.setPlay(playback.describeContent(info))
        LogHub.i(TAG, "play opened qn=${info.video.id} ${info.video.width}x${info.video.height}")
        return true
    }

    private companion object {
        /** 日志标签。 */
        const val TAG = "SimpleBili"
    }
}
