package com.roy233.tvcast.feature.cast.application

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.feature.cast.domain.CastContent
import com.roy233.tvcast.feature.cast.domain.RecentCast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 最近投屏记录与重新解析所需的窄端口。 */
interface RecentCastHost {
    fun remembersPlaybackContent(): Boolean
    fun addRecentRecord(request: PlaybackRequest)
    fun isResolving(): Boolean
    fun newHistorySession()
    fun setLastNva(nva: CastContent?)
    fun coroutineScope(): CoroutineScope
    fun setResolving(resolving: Boolean)
    fun showRecentLoading(title: String)
    suspend fun resolveVodPlayback(
        aid: Long, cid: Long, bvid: String, title: String,
        epId: Long = 0L, seasonId: Long = 0L,
    ): PlaybackRequest
    fun reportError(title: String, message: String, code: String, canRetry: Boolean, keepVideo: Boolean)
}

/** 维护最近投屏记录，并以原投屏身份重新取得播放地址。 */
class RecentCastCoordinator(
    private val host: RecentCastHost,
    private val openPlayback: (PlaybackRequest, Long) -> Boolean,
) {
    fun record(request: PlaybackRequest) {
        if (host.remembersPlaybackContent()) host.addRecentRecord(request)
    }

    fun recast(recent: RecentCast) {
        if (host.isResolving()) {
            LogHub.w(TAG, "recast ignored: another resolve is running")
            return
        }
        host.newHistorySession()
        host.setLastNva(null)
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
        host.coroutineScope().launch {
            host.setResolving(true)
            host.showRecentLoading(recent.title)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    host.resolveVodPlayback(
                        aid = recent.aid,
                        cid = recent.cid,
                        bvid = "",
                        title = recent.title,
                        epId = recent.epId.takeIf { recent.isPgc } ?: 0L,
                        seasonId = recent.seasonId.takeIf { recent.isPgc } ?: 0L,
                    )
                }
            }
            host.setResolving(false)
            result.onSuccess { openPlayback(it, 0L) }.onFailure {
                LogHub.e(TAG, "recast resolve failed: ${LogRedaction.reason(it)}", it, stack = true)
                PlaybackDiagnostics.fail(
                    code = "RECAST_FAILED",
                    message = it.message ?: "视频信息已失效",
                    detail = "aid=${recent.aid} cid=${recent.cid} reason=${LogRedaction.reason(it)}",
                )
                host.reportError(
                    title = "无法继续投屏",
                    message = it.message ?: "视频信息已失效，请从手机重新投屏。",
                    code = "RECAST_FAILED",
                    canRetry = false,
                    keepVideo = false,
                )
            }
        }
    }

    private companion object { const val TAG = "SimpleBili" }
}
