package com.roy233.tvcast.data.bili.danmaku

import com.roy233.tvcast.feature.danmaku.domain.DanmakuItem
import com.roy233.tvcast.feature.danmaku.domain.DanmakuSegmentKey
import com.roy233.tvcast.feature.danmaku.domain.DmSegConfig
import com.roy233.tvcast.feature.danmaku.domain.DmSegment
import com.roy233.tvcast.feature.danmaku.domain.DmViewInfo
import com.roy233.tvcast.feature.danmaku.domain.VodDanmakuContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 验证分段缓存以内容身份归属。 */
class BiliDanmakuRepositoryTest {

    private val remote = RecordingRemote()
    private val repository = BiliDanmakuRepository(remote)

    @Test
    fun `same segment index of different videos issues separate requests`() = runBlocking {
        val first = repository.segment(KEY_VIDEO_A)
        val second = repository.segment(KEY_VIDEO_B)

        assertEquals(listOf("1/11/1", "2/22/1"), remote.segmentRequests)
        assertEquals("1/11/1", first.items.single().content)
        assertEquals("2/22/1", second.items.single().content)
    }

    @Test
    fun `returning to a video reuses its cache and never hits another video`() = runBlocking {
        val first = repository.segment(KEY_VIDEO_A)
        repository.segment(KEY_VIDEO_B)

        val again = repository.segment(KEY_VIDEO_A)

        assertEquals(listOf("1/11/1", "2/22/1"), remote.segmentRequests)
        assertSame(first, again)
    }

    private class RecordingRemote : DanmakuRemote {
        /** 按发起顺序记录的 `aid/cid/index`。 */
        val segmentRequests = mutableListOf<String>()

        override suspend fun fetchView(cid: Long): DmViewInfo =
            DmViewInfo(state = 0, count = 0, segConfig = SEG_CONFIG, playerConfig = null)

        override suspend fun fetchSegment(
            aid: Long,
            cid: Long,
            segmentIndex: Int,
        ): DmSegment {
            val id = "$aid/$cid/$segmentIndex"
            segmentRequests += id
            return DmSegment(items = listOf(item(id)), state = 0, aiFlags = emptyMap())
        }

        private fun item(content: String) =
            DanmakuItem(
                id = 0L,
                progressMs = 0,
                mode = SCROLL_MODE,
                fontSize = 25,
                color = 0xFFFFFF,
                midHash = "",
                content = content,
                weight = 0,
                pool = 0,
                attr = 0,
                ctime = 0L,
            )
    }

    private companion object {
        const val SCROLL_MODE = 1
        val VIDEO_A = VodDanmakuContent(aid = 1L, cid = 11L)
        val VIDEO_B = VodDanmakuContent(aid = 2L, cid = 22L)
        val KEY_VIDEO_A = DanmakuSegmentKey(VIDEO_A, index = 1)
        val KEY_VIDEO_B = DanmakuSegmentKey(VIDEO_B, index = 1)
        val SEG_CONFIG = DmSegConfig(pageSizeMs = 360_000L, total = 10)
    }
}
