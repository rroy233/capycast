package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.core.playback.PlaybackEvent
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.core.playback.PlaybackSessionToken
import com.roy233.tvcast.feature.cast.domain.CastContent

/** 画质、线路和解码表示之外的稳定内容身份。 */
data class PlaybackContentIdentity(
    val live: Boolean,
    val aid: Long,
    val cid: Long,
    val bvid: String,
    val epId: Long,
    val seasonId: Long,
) {
    companion object {
        fun from(request: PlaybackRequest): PlaybackContentIdentity =
            PlaybackContentIdentity(
                live = request.isLive,
                aid = request.aid,
                cid = request.cid,
                bvid = request.bvid,
                epId = request.epId,
                seasonId = request.seasonId,
            )
    }
}

/** 一次播放器打开及其内容组件共享的不可变会话上下文。 */
data class PlaybackSessionContext(
    val token: PlaybackSessionToken,
    val identity: PlaybackContentIdentity,
    val request: PlaybackRequest,
    val startPositionMs: Long,
)

/** 由 [PlaybackSessionLifecycle] 独占推进的内容会话状态。 */
sealed interface PlaybackSessionState {
    data object Idle : PlaybackSessionState

    data class Opening(val context: PlaybackSessionContext) : PlaybackSessionState

    data class Active(val context: PlaybackSessionContext) : PlaybackSessionState

    data class Failed(val context: PlaybackSessionContext) : PlaybackSessionState

    data object Destroyed : PlaybackSessionState
}

/** 错误、重试与停止流程需要的会话和展示命令。 */
interface PlaybackSessionHost {
    fun modeForLog(): String

    fun hasCurrentRequest(): Boolean

    fun isCastActive(): Boolean

    fun lastCastContent(): CastContent?

    fun lastCastTitle(): String

    fun resolveCast(content: CastContent, title: String)

    fun cancelResolveJob()

    fun finishSessionHistory(reason: String)

    fun resetPendingPlayback()

    fun clearPendingPause()

    fun isEngineInitialized(): Boolean

    fun stopPlaybackForSession(reason: String)

    /** 成功打开新内容后，一次性启动弹幕、字幕等内容组件。 */
    fun openPlaybackComponents(context: PlaybackSessionContext)

    /** 内容替换或结束时，一次性关闭路由任务及全部内容组件。 */
    fun closePlaybackComponents(reason: String)

    fun clearSessionIdentity(clearCastMetadata: Boolean, clearResolving: Boolean)

    fun markCastFailed(code: String)

    fun playerStateSnapshot(): String

    fun runOnMain(action: () -> Unit)

    fun showRetryState(hasCurrent: Boolean)

    fun showDismissedState()

    fun showStoppedState()

    fun showErrorState(title: String, message: String, code: String, canRetry: Boolean)

    fun flushHistoryPosition()

    fun closeHistory()

    fun showDlnaStoppedState()
}

/** 播放内容、播放器打开尝试及其关联组件的唯一生命周期所有者。 */
class PlaybackSessionLifecycle(private val host: PlaybackSessionHost) {
    private data class ActiveSession(
        val sessionId: Long,
        val identity: PlaybackContentIdentity,
        var attemptId: Long = 0L,
        var context: PlaybackSessionContext? = null,
        var componentsOpen: Boolean = false,
    )

    private var retryAction: (() -> Unit)? = null
    private var lastSessionId = 0L
    private var activeSession: ActiveSession? = null
    private var currentToken: PlaybackSessionToken? = null
    var state: PlaybackSessionState = PlaybackSessionState.Idle
        private set

    /**
     * 开始一次播放器打开。
     *
     * 内容变化会先撤销旧 token 并关闭旧内容组件；同内容线路、画质或解码回退只创建新的 attempt。
     */
    fun beginOpen(request: PlaybackRequest, startPositionMs: Long): PlaybackSessionContext? {
        if (state == PlaybackSessionState.Destroyed) {
            LogHub.w(TAG, "open ignored after destroy aid=${request.aid} cid=${request.cid}")
            return null
        }
        val identity = PlaybackContentIdentity.from(request)
        var session = activeSession
        if (session == null) {
            session = ActiveSession(sessionId = ++lastSessionId, identity = identity)
            activeSession = session
        } else if (session.identity != identity) {
            finishCurrent("content-replaced")
            session = ActiveSession(sessionId = ++lastSessionId, identity = identity)
            activeSession = session
        }
        val token = PlaybackSessionToken(session.sessionId, ++session.attemptId)
        val context =
            PlaybackSessionContext(
                token = token,
                identity = identity,
                request = request,
                startPositionMs = startPositionMs.coerceAtLeast(0L),
            )
        session.context = context
        currentToken = token
        state = PlaybackSessionState.Opening(context)
        LogHub.i(
            TAG,
            "session open begin session=${token.sessionId} attempt=${token.attemptId} " +
                "live=${identity.live} aid=${identity.aid} cid=${identity.cid} " +
                "components=${session.componentsOpen}",
        )
        return context
    }

    /** 提交一次播放器打开结果；只有当前 attempt 可以启动内容组件。 */
    fun completeOpen(context: PlaybackSessionContext, opened: Boolean) {
        val session = activeSession
        if (session == null || currentToken != context.token || session.context !== context) {
            LogHub.i(
                TAG,
                "session open result discarded session=${context.token.sessionId} " +
                    "attempt=${context.token.attemptId} opened=$opened",
            )
            return
        }
        if (!opened) {
            state = PlaybackSessionState.Failed(context)
            return
        }
        if (!session.componentsOpen) {
            session.componentsOpen = true
            try {
                host.openPlaybackComponents(context)
            } catch (failure: Throwable) {
                finishCurrent("component-open-failed")
                stopBackend("component-open-failed")
                throw failure
            }
        }
        state = PlaybackSessionState.Active(context)
        LogHub.i(
            TAG,
            "session open committed session=${context.token.sessionId} " +
                "attempt=${context.token.attemptId}",
        )
    }

    /** 平台事件提交门禁；旧内容或旧打开尝试的迟到事件一律丢弃。 */
    fun acceptEvent(token: PlaybackSessionToken, event: PlaybackEvent): Boolean {
        val accepted = activeSession != null && currentToken == token
        if (!accepted) {
            LogHub.i(
                TAG,
                "playback event discarded event=${event::class.simpleName} " +
                    "session=${token.sessionId} attempt=${token.attemptId}",
            )
            return false
        }
        val context = activeSession?.context ?: return false
        if (
            event == PlaybackEvent.Playing ||
                event == PlaybackEvent.Paused ||
                event is PlaybackEvent.Buffering ||
                event is PlaybackEvent.VideoSizeChanged
        ) {
            state = PlaybackSessionState.Active(context)
        }
        return true
    }

    /** 使当前 token 失效并关闭全部内容组件；没有播放器会话时也会清理预创建的路由任务。 */
    fun finishCurrent(reason: String) {
        val session = activeSession
        activeSession = null
        currentToken = null
        if (state != PlaybackSessionState.Destroyed) state = PlaybackSessionState.Idle
        if (session == null) {
            host.closePlaybackComponents(reason)
            LogHub.i(TAG, "orphan playback components closed reason=$reason")
            return
        }
        session.componentsOpen = false
        host.closePlaybackComponents(reason)
        LogHub.i(
            TAG,
            "session finished session=${session.sessionId} attempt=${session.attemptId} reason=$reason",
        )
    }

    /** 内容替换入口：先结束业务会话，再停止后端，后续新内容重新 beginOpen。 */
    fun stopForReplacement(reason: String) {
        finishCurrent(reason)
        stopBackend(reason)
    }

    /** 停止播放器；终止路径共用同一实现。 */
    private fun stopBackend(reason: String) {
        if (host.isEngineInitialized()) host.stopPlaybackForSession(reason)
        else LogHub.w(TAG, "$reason without player engine")
    }

    private fun stopPlayback(reason: String) {
        finishCurrent(reason)
        stopBackend(reason)
    }

    fun setRetry(action: (() -> Unit)?) {
        retryAction = action
    }

    fun retry() {
        val action = retryAction
        retryAction = null
        LogHub.i(
            TAG,
            "retry requested hasAction=${action != null} hasNva=${host.lastCastContent() != null} " +
                "current=${host.hasCurrentRequest()}",
        )
        if (action != null) {
            host.showRetryState(host.hasCurrentRequest())
            action()
            return
        }
        val nva = host.lastCastContent()
        if (nva != null) host.resolveCast(nva, host.lastCastTitle()) else dismissError()
    }

    fun dismissError() {
        LogHub.i(
            TAG,
            "dismiss error; current=${host.hasCurrentRequest()} castActive=${host.isCastActive()}",
        )
        host.finishSessionHistory("dismiss-error")
        stopPlayback("dismiss-error")
        host.clearSessionIdentity(clearCastMetadata = false, clearResolving = true)
        retryAction = null
        host.showDismissedState()
    }

    fun stopCasting() {
        LogHub.i(
            TAG,
            "stopCasting requested mode=${host.modeForLog()} current=${host.hasCurrentRequest()} " +
                "castActive=${host.isCastActive()}",
        )
        host.cancelResolveJob()
        host.finishSessionHistory("user-stop")
        host.resetPendingPlayback()
        stopPlayback("user-stop")
        host.clearSessionIdentity(clearCastMetadata = true, clearResolving = true)
        retryAction = null
        runCatching { host.markCastFailed("user-stop") }
        PlaybackDiagnostics.closeSession("user-stop")
        LogHub.setCast("")
        LogHub.setPlay("")
        host.showStoppedState()
    }

    fun stopFromDlna() {
        host.clearPendingPause()
        host.finishSessionHistory("dlna-stop")
        stopPlayback("dlna-stop")
        host.clearSessionIdentity(clearCastMetadata = false, clearResolving = false)
        LogHub.setCast("")
        LogHub.setPlay("")
        host.runOnMain(host::showDlnaStoppedState)
    }

    fun destroy() {
        if (host.hasCurrentRequest()) host.flushHistoryPosition()
        host.closeHistory()
        finishCurrent("activity-destroy")
        PlaybackDiagnostics.closeSession("activity-destroyed")
        retryAction = null
        state = PlaybackSessionState.Destroyed
    }

    fun showError(
        title: String,
        message: String,
        code: String,
        canRetry: Boolean,
        keepVideo: Boolean,
    ) {
        host.finishSessionHistory("error:$code")
        val snapshot =
            if (host.isEngineInitialized()) {
                runCatching { host.playerStateSnapshot() }.getOrDefault("(unavailable)")
            } else "(player engine unavailable)"
        LogHub.e(
            TAG,
            "showError code=$code canRetry=$canRetry keepVideo=$keepVideo " +
                "title=$title message=${LogRedaction.text(message)} snapshot=$snapshot",
        )
        PlaybackDiagnostics.attribute("ui.errorCode", code)
        PlaybackDiagnostics.event(
            "UiError",
            "code=$code canRetry=$canRetry keepVideo=$keepVideo title=$title",
        )
        if (!keepVideo) {
            stopPlayback("show-error:$code")
            host.clearSessionIdentity(clearCastMetadata = false, clearResolving = false)
        }
        host.runOnMain { host.showErrorState(title, message, code, canRetry) }
    }

    private companion object {
        const val TAG = "SimpleBili"
    }
}
