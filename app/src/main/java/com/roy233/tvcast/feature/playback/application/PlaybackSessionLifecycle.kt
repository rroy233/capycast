package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.feature.cast.domain.CastContent

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
    fun clearLiveSession(reason: String)
    fun resetPendingPlayback()
    fun isEngineInitialized(): Boolean
    fun stopPlaybackForSession(reason: String)
    fun clearSessionIdentity(clearCastMetadata: Boolean, clearResolving: Boolean)
    fun markCastFailed(code: String)
    fun playerStateSnapshot(): String
    fun runOnMain(action: () -> Unit)
    fun showRetryState(hasCurrent: Boolean)
    fun showDismissedState()
    fun showStoppedState()
    fun showErrorState(title: String, message: String, code: String, canRetry: Boolean)
}

/** 保留错误、重试与用户停止各自的终止顺序。 */
class PlaybackSessionLifecycle(private val host: PlaybackSessionHost) {
    private var retryAction: (() -> Unit)? = null

    fun setRetry(action: (() -> Unit)?) { retryAction = action }

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
        LogHub.i(TAG, "dismiss error; current=${host.hasCurrentRequest()} castActive=${host.isCastActive()}")
        host.finishSessionHistory("dismiss-error")
        host.clearLiveSession("dismiss-error")
        if (host.isEngineInitialized()) host.stopPlaybackForSession("dismiss-error")
        else LogHub.w(TAG, "dismiss error without player engine")
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
        host.clearLiveSession("user-stop")
        host.resetPendingPlayback()
        if (host.isEngineInitialized()) host.stopPlaybackForSession("user-stop")
        else LogHub.w(TAG, "stopCasting without player engine")
        host.clearSessionIdentity(clearCastMetadata = true, clearResolving = true)
        retryAction = null
        runCatching { host.markCastFailed("user-stop") }
        PlaybackDiagnostics.closeSession("user-stop")
        LogHub.setCast("")
        LogHub.setPlay("")
        host.showStoppedState()
    }

    fun showError(title: String, message: String, code: String, canRetry: Boolean, keepVideo: Boolean) {
        host.finishSessionHistory("error:$code")
        val snapshot = if (host.isEngineInitialized()) {
            runCatching { host.playerStateSnapshot() }.getOrDefault("(unavailable)")
        } else "(player engine unavailable)"
        LogHub.e(
            TAG,
            "showError code=$code canRetry=$canRetry keepVideo=$keepVideo " +
                "title=$title message=${LogRedaction.text(message)} snapshot=$snapshot",
        )
        PlaybackDiagnostics.attribute("ui.errorCode", code)
        PlaybackDiagnostics.event("UiError", "code=$code canRetry=$canRetry keepVideo=$keepVideo title=$title")
        if (!keepVideo) {
            host.clearLiveSession("show-error:$code")
            if (host.isEngineInitialized()) host.stopPlaybackForSession("show-error:$code")
            else LogHub.w(TAG, "showError cannot stop playback: player engine unavailable")
            host.clearSessionIdentity(clearCastMetadata = false, clearResolving = false)
        }
        host.runOnMain { host.showErrorState(title, message, code, canRetry) }
    }

    private companion object { const val TAG = "SimpleBili" }
}
