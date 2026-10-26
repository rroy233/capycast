package com.roy233.tvcast.feature.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 验证点播历史内容包含上报所需的身份字段。 */
class PlaybackHistoryContentTest {

    @Test
    fun `vod content preserves all Bilibili identity and scene fields`() {
        val content = PlaybackHistoryContent(
            stableKey = "bvid:BV1",
            bvid = "BV1",
            aid = 101L,
            cid = 202L,
            sid = 303L,
            epid = 404L,
            serialId = 505L,
            type = 3,
            business = "archive",
            isLive = false,
            roomId = 0L,
            livePlayUrl = "",
            scene = "tv_cast",
            fromScene = "dlna",
            fromSpmid = "main.cast",
            resourceFrom = "tv",
        )

        assertEquals("bvid:BV1", content.stableKey)
        assertEquals("BV1", content.bvid)
        assertEquals(101L, content.aid)
        assertEquals(202L, content.cid)
        assertEquals(303L, content.sid)
        assertEquals(404L, content.epid)
        assertEquals(505L, content.serialId)
        assertEquals("tv_cast", content.scene)
        assertEquals("dlna", content.fromScene)
        assertEquals("main.cast", content.fromSpmid)
        assertEquals("tv", content.resourceFrom)
        assertFalse(content.isLive)
    }
}
