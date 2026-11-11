package com.roy233.tvcast.feature.subtitle.application

import com.roy233.tvcast.feature.subtitle.domain.SubtitleContent
import com.roy233.tvcast.feature.subtitle.domain.SubtitleCue
import com.roy233.tvcast.feature.subtitle.domain.SubtitleDocument
import com.roy233.tvcast.feature.subtitle.domain.SubtitleOrigin
import com.roy233.tvcast.feature.subtitle.domain.SubtitleRenderer
import com.roy233.tvcast.feature.subtitle.domain.SubtitleRepository
import com.roy233.tvcast.feature.subtitle.domain.SubtitleSettings
import com.roy233.tvcast.feature.subtitle.domain.SubtitleTrack
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证字幕会话归属、延迟结果丢弃与跳转后立即刷新。 */
class SubtitleControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val renderer = RecordingRenderer()
    private val repository = GatedRepository()
    private val host = RecordingHost()
    private val controller = SubtitleController(scope, renderer, repository, host)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `body arriving after another video opened is discarded`() = runBlocking {
        controller.open(VIDEO_A)
        controller.open(VIDEO_B)
        controller.onPlayerTime(1_000L)

        assertEquals(listOf(listOf("b")), renderer.shown)

        repository.completeLateBody()

        assertEquals(listOf(listOf("b")), renderer.shown)
    }

    @Test
    fun `position sample resolves the cue after a jump`() = runBlocking {
        controller.open(VIDEO_B)

        controller.onPlayerTime(0L)
        assertEquals(emptyList<String>(), renderer.lastLines())

        controller.onPlayerTime(1_000L)

        assertEquals(listOf("b"), renderer.lastLines())
    }

    @Test
    fun `closing subtitles clears the overlay and persists the setting`() = runBlocking {
        controller.open(VIDEO_B)
        controller.onPlayerTime(1_000L)
        assertEquals(listOf("b"), renderer.lastLines())

        controller.selectTrack(null)

        assertEquals(emptyList<String>(), renderer.lastLines())
        assertFalse(controller.currentSettings().enabled)
        assertNull(host.lastState?.selected)
        assertFalse(host.persisted.last().enabled)
    }

    @Test
    fun `toggling subtitles off clears the overlay and back on restores the default track`() =
        runBlocking {
            controller.open(VIDEO_B)
            controller.onPlayerTime(1_000L)
            assertEquals(listOf("b"), renderer.lastLines())

            controller.setEnabled(false)

            assertEquals(emptyList<String>(), renderer.lastLines())
            assertNull(host.lastState?.selected)
            assertFalse(controller.currentSettings().enabled)

            controller.setEnabled(true)

            assertTrue(controller.currentSettings().enabled)
            assertEquals(listOf("b"), renderer.lastLines())
            assertEquals(listOf(false, true), host.persisted.map { it.enabled })
        }

    /** 模拟无法被取消的旧正文请求。 */
    private class GatedRepository : SubtitleRepository {
        private var lateContinuation: Continuation<SubtitleDocument>? = null

        override suspend fun tracks(content: SubtitleContent): List<SubtitleTrack> =
            listOf(if (content == VIDEO_A) TRACK_A else TRACK_B)

        override suspend fun document(
            content: SubtitleContent,
            track: SubtitleTrack,
        ): SubtitleDocument {
            if (content == VIDEO_A) {
                return suspendCoroutine { continuation -> lateContinuation = continuation }
            }
            return documentOf(content, track)
        }

        /** 让旧会话的正文在换片之后返回。 */
        fun completeLateBody() {
            val continuation = lateContinuation ?: return
            lateContinuation = null
            continuation.resume(documentOf(VIDEO_A, TRACK_A))
        }
    }

    private class RecordingRenderer : SubtitleRenderer {
        /** 每次实际显示的字幕行。 */
        val shown = mutableListOf<List<String>>()

        private var displayed: List<String> = emptyList()

        /** 最近一次显示的字幕行。 */
        fun lastLines(): List<String> = displayed

        override fun show(lines: List<String>) {
            displayed = lines
            shown += lines
        }

        override fun clear() {
            displayed = emptyList()
        }
    }

    private class RecordingHost : SubtitleSessionHost {
        /** 最近一次发布的会话状态。 */
        var lastState: SubtitleSessionState? = null

        /** 持久化过的设置。 */
        val persisted = mutableListOf<SubtitleSettings>()

        override fun publishSubtitleSession(state: SubtitleSessionState) {
            lastState = state
        }

        override fun persistSubtitleSettings(settings: SubtitleSettings) {
            persisted += settings
        }
    }

    private companion object {
        const val TEXT_A = "a"
        const val TEXT_B = "b"
        val VIDEO_A = SubtitleContent(aid = 1L, cid = 11L)
        val VIDEO_B = SubtitleContent(aid = 2L, cid = 22L)
        val TRACK_A = track(id = 1L, text = TEXT_A)
        val TRACK_B = track(id = 2L, text = TEXT_B)

        fun track(id: Long, text: String): SubtitleTrack =
            SubtitleTrack(
                id = id,
                idStr = id.toString(),
                lan = text,
                lanDoc = text,
                url = "https://aisubtitle.hdslb.com/bfs/subtitle/$id.json",
                origin = SubtitleOrigin.MANUAL,
                type = 0,
                locked = false,
            )

        fun documentOf(content: SubtitleContent, track: SubtitleTrack): SubtitleDocument =
            SubtitleDocument(
                trackKey = track.key,
                cues =
                    listOf(
                        SubtitleCue(
                            startMs = 1_000L,
                            endMs = 2_000L,
                            text = if (content == VIDEO_A) TEXT_A else TEXT_B,
                        ),
                    ),
            )
    }
}
