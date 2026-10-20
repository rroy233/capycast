package com.roy233.tvcast.data.bili.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DanmakuProtoTest {

    private fun resource(name: String): ByteArray {
        return checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "missing test resource: $name"
        }.use { it.readBytes() }
    }

    @Test
    fun decodesSegmentFields() {
        val segment = DanmakuProto.decodeSegment(resource("danmaku_seg_sample.bin"))

        assertEquals(3, segment.items.size)
        assertEquals(0, segment.state)
        assertEquals(emptyMap<Long, Int>(), segment.aiFlags)

        val first = segment.items[0]
        assertEquals(328008371L, first.id)
        assertEquals(40607, first.progressMs)
        assertEquals(1, first.mode)
        assertEquals(25, first.fontSize)
        assertEquals(16750950, first.color)
        assertEquals("eb2789fd", first.midHash)
        assertEquals("前方高能反应(～ o ～)~", first.content)
        assertEquals(9, first.weight)
        assertEquals(1382774695L, first.ctime)

        assertEquals(5, segment.items[2].mode)
    }

    @Test
    fun decodesViewMetadata() {
        val view = DanmakuProto.decodeView(resource("danmaku_view_sample.bin"))

        assertEquals(0, view.state)
        assertEquals(6000L, view.count)
        assertEquals(360000L, view.segConfig?.pageSizeMs)
        assertEquals(100, view.segConfig?.total)
        assertNull(view.playerConfig)
    }

    @Test
    fun rejectsTruncatedInput() {
        val truncated = byteArrayOf(0x0A, 0x05, 0x01)
        assertThrows(DanmakuProto.MalformedException::class.java) {
            DanmakuProto.decodeSegment(truncated)
        }
    }

    @Test
    fun rejectsEmptyInput() {
        assertThrows(DanmakuProto.MalformedException::class.java) {
            DanmakuProto.decodeSegment(ByteArray(0))
        }
    }
}
