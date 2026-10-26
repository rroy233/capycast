package com.roy233.tvcast.feature.playback.application

import android.content.Context
import com.roy233.tvcast.data.auth.CastAuth
import com.roy233.tvcast.data.bili.BiliApi
import com.roy233.tvcast.data.bili.LivePlayInfo
import com.roy233.tvcast.data.bili.NvaExtDecoder
import com.roy233.tvcast.data.bili.RankedLiveSource
import com.roy233.tvcast.ui.model.PlaybackUiState
import com.roy233.tvcast.ui.model.TvMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证新投屏替换旧会话，以及无效元数据触发的终止行为。 */
class CastSessionCoordinatorTest {

    private val host = RecordingHost()

    @After
    fun tearDown() {
        host.scope.cancel()
    }

    @Test
    fun `unrecognised cast stops the previous session and reports a non retryable error`() {
        CastSessionCoordinator(host).handleCast(
            uri = "https://example.com/cast",
            title = "测试投屏",
            longDescription = "",
        )

        assertEquals(
            listOf(
                "cancel-resolve",
                "end-live:new-cast",
                "clear-current",
                "stop-playback:new-cast",
                "new-history",
                "cast-active:true",
                "cast-failed:nva-decode-failed",
                "cast-active:false",
            ),
            host.events,
        )
        assertEquals(TvMode.LOADING, host.playbackState.mode)
        assertFalse(host.playbackState.castActive)
        assertEquals("测试投屏", host.playbackState.castTitle)
        assertEquals(
            ErrorReport(
                title = "无法识别这次投屏",
                message = "请更新手机 B 站 App 后重新投屏。",
                code = "NVA_DECODE_FAILED",
                canRetry = false,
                keepVideo = false,
            ),
            host.error,
        )
    }

    private class RecordingHost : CastSessionHost {
        val scope = CoroutineScope(SupervisorJob())
        val events = mutableListOf<String>()
        var playbackState = PlaybackUiState(rememberPlaybackContent = true)
        var error: ErrorReport? = null

        override fun state(): PlaybackUiState = playbackState

        override fun setState(state: PlaybackUiState) {
            playbackState = state
        }

        override fun coroutineScope(): CoroutineScope = scope

        override fun isResolving(): Boolean = false

        override fun setResolving(resolving: Boolean) = Unit

        override fun cancelResolve() {
            events += "cancel-resolve"
        }

        override fun setResolveJob(job: Job) = Unit

        override fun currentInfo(): BiliApi.PlayInfo? = null

        override fun setCurrentForCast(info: BiliApi.PlayInfo?) {
            if (info == null) events += "clear-current"
        }

        override fun setCastActive(active: Boolean) {
            events += "cast-active:$active"
            playbackState = playbackState.copy(castActive = active)
        }

        override fun lastNva(): NvaExtDecoder.NvaExtContent? = null

        override fun setLastNva(nva: NvaExtDecoder.NvaExtContent?) = Unit

        override fun setLastTitle(title: String) = Unit

        override fun setRetry(action: (() -> Unit)?) = Unit

        override fun clearLoginUi() = Unit

        override fun setPendingPause(paused: Boolean) = Unit

        override fun reportError(
            title: String,
            message: String,
            code: String,
            canRetry: Boolean,
            keepVideo: Boolean,
        ) {
            error = ErrorReport(title, message, code, canRetry, keepVideo)
        }

        override fun addRecentRecord(info: BiliApi.PlayInfo) = Unit

        override fun newHistorySession() {
            events += "new-history"
        }

        override fun endLiveSession(reason: String) {
            events += "end-live:$reason"
        }

        override fun stopPlaybackForCast(reason: String) {
            events += "stop-playback:$reason"
        }

        override fun markCastFailed(code: String) {
            events += "cast-failed:$code"
        }

        override fun setCastDurationMs(durationMs: Long) = Unit

        override fun startLiveSession(info: LivePlayInfo, routes: List<RankedLiveSource>, title: String) = Unit

        override fun authToken(): CastAuth.Token? = null

        override fun appContext(): Context = error("unused in this test")

        override fun isEngineInitialized(): Boolean = false

        override fun updateLiveUrl(url: String) = Unit

        override fun setPendingSeekMs(positionMs: Long) = Unit

        override fun describeContent(info: BiliApi.PlayInfo): String = ""

        override fun startPlaybackBackend(info: BiliApi.PlayInfo, seekToMs: Long): Boolean = false

        override fun onDanmakuClose() = Unit

        override fun onLiveDanmakuClose() = Unit

        override fun onLiveDanmakuOpen(roomId: Long) = Unit

        override fun onVodDanmakuOpen(aid: Long, cid: Long, durationMs: Long, seekToMs: Long) = Unit

        override fun onDanmakuPlaybackRate(rate: Float) = Unit

        override fun onDanmakuSettings() = Unit

        override fun onDanmakuLayout() = Unit

        override fun applyPlaybackRate(info: BiliApi.PlayInfo) = Unit

        override fun applyPendingPause() = Unit

        override fun vodSessionCoordinator(): VodPlaybackCoordinator = error("unused in this test")

        override fun liveSessionCoordinator(): LivePlaybackCoordinator = error("unused in this test")
    }

    private data class ErrorReport(
        val title: String,
        val message: String,
        val code: String,
        val canRetry: Boolean,
        val keepVideo: Boolean,
    )
}
