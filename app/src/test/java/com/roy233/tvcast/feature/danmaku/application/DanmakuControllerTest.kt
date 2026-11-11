package com.roy233.tvcast.feature.danmaku.application

import com.roy233.tvcast.feature.danmaku.domain.AiShieldLevel
import com.roy233.tvcast.feature.danmaku.domain.DanmakuItem
import com.roy233.tvcast.feature.danmaku.domain.DanmakuRenderer
import com.roy233.tvcast.feature.danmaku.domain.DanmakuRepository
import com.roy233.tvcast.feature.danmaku.domain.DanmakuSegmentKey
import com.roy233.tvcast.feature.danmaku.domain.DanmakuSettings
import com.roy233.tvcast.feature.danmaku.domain.DmSegConfig
import com.roy233.tvcast.feature.danmaku.domain.DmSegment
import com.roy233.tvcast.feature.danmaku.domain.DmViewInfo
import com.roy233.tvcast.feature.danmaku.domain.MediaClock
import com.roy233.tvcast.feature.danmaku.domain.VodDanmakuContent
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
import org.junit.Test

/** 验证弹幕结果只由发起它的会话提交：换片之后到达的旧结果必须被丢弃。 */
class DanmakuControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val renderer = RecordingRenderer()
    private val repository = LateSegmentRepository()
    private val controller = DanmakuController(scope, renderer, MediaClock(), repository)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `open aligns the renderer timeline to the start position`() = runBlocking {
        controller.open(VIDEO_A, DURATION_MS, 12_000L)

        assertEquals(listOf(12_000L), renderer.seeks)
    }

    @Test
    fun `segment arriving after another video opened is discarded`() = runBlocking {
        controller.open(VIDEO_A, DURATION_MS, 0L)
        controller.open(VIDEO_B, DURATION_MS, 0L)

        assertEquals(listOf("2/22/1"), renderer.rendered())

        repository.completeLateSegment()

        assertEquals(listOf("2/22/1"), renderer.rendered())
    }

    /** 模拟无法被取消的旧分段请求。 */
    private class LateSegmentRepository : DanmakuRepository {
        private var lateContinuation: Continuation<DmSegment>? = null

        override suspend fun view(content: VodDanmakuContent): DmViewInfo =
            DmViewInfo(state = 0, count = 0, segConfig = SEG_CONFIG, playerConfig = null)

        override suspend fun segment(key: DanmakuSegmentKey): DmSegment {
            if (key.content == VIDEO_A) {
                return suspendCoroutine { continuation -> lateContinuation = continuation }
            }
            return segmentOf(key)
        }

        /** 让旧会话的分段在换片之后返回。 */
        fun completeLateSegment() {
            val continuation = lateContinuation ?: return
            lateContinuation = null
            continuation.resume(segmentOf(KEY_VIDEO_A))
        }
    }

    /** 记录渲染器收到的分段内容。 */
    private class RecordingRenderer : DanmakuRenderer {
        private val segments = mutableListOf<List<String>>()

        /** 渲染器收到的时间轴定位请求。 */
        val seeks = mutableListOf<Long>()

        /** 按提交顺序返回每个分段的文本内容。 */
        fun rendered(): List<String> = segments.map { items -> items.single() }

        override fun setContentHeight(heightPx: Int) = Unit

        override fun prepare() = Unit

        override fun applySettings(settings: DanmakuSettings) = Unit

        override fun replaceSegment(items: List<DanmakuItem>, level: AiShieldLevel) {
            segments += items.map { it.content }
        }

        override fun addSegment(items: List<DanmakuItem>, level: AiShieldLevel) {
            segments += items.map { it.content }
        }

        override fun pause() = Unit

        override fun resume() = Unit

        override fun seekTo(mediaMs: Long) {
            seeks += mediaMs
        }

        override fun clear() = Unit

        override fun release() = Unit
    }

    private companion object {
        const val DURATION_MS = 600_000L
        const val SCROLL_MODE = 1
        val VIDEO_A = VodDanmakuContent(aid = 1L, cid = 11L)
        val VIDEO_B = VodDanmakuContent(aid = 2L, cid = 22L)
        val KEY_VIDEO_A = DanmakuSegmentKey(VIDEO_A, index = 1)
        val SEG_CONFIG = DmSegConfig(pageSizeMs = 360_000L, total = 10)

        fun segmentOf(key: DanmakuSegmentKey): DmSegment =
            DmSegment(
                items =
                    listOf(
                        DanmakuItem(
                            id = 0L,
                            progressMs = 0,
                            mode = SCROLL_MODE,
                            fontSize = 25,
                            color = 0xFFFFFF,
                            midHash = "",
                            content = "${key.content.aid}/${key.content.cid}/${key.index}",
                            weight = 0,
                            pool = 0,
                            attr = 0,
                            ctime = 0L,
                        ),
                    ),
                state = 0,
                aiFlags = emptyMap(),
            )
    }
}
