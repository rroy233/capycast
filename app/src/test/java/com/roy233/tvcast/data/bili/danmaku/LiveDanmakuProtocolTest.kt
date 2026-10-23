package com.roy233.tvcast.data.bili.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class LiveDanmakuProtocolTest {

    @Test
    fun `encodes heartbeat with op 2 and empty json body`() {
        val frame = LiveDanmakuProtocol.encodeHeartbeat()

        assertEquals(LiveDanmakuProtocol.HEADER_SIZE + 2, frame.size)
        assertEquals(LiveDanmakuProtocol.HEADER_SIZE + 2, readInt32(frame, 0))
        assertEquals(LiveDanmakuProtocol.HEADER_SIZE, readInt16(frame, 4))
        assertEquals(LiveDanmakuProtocol.PROTOCOL_PLAIN, readInt16(frame, 6))
        assertEquals(LiveDanmakuProtocol.OP_HEARTBEAT, readInt32(frame, 8))
        assertEquals(1, readInt32(frame, 12))
        assertEquals("{}", String(frame, LiveDanmakuProtocol.HEADER_SIZE, 2, Charsets.UTF_8))
    }

    @Test
    fun `encodes join with web identity brotli protocol and buvid`() {
        val frame = LiveDanmakuProtocol.encodeJoin(roomId = 23459804L, token = "secret-token", uid = 0L, buvid = "buvid-abc")

        assertEquals(LiveDanmakuProtocol.OP_JOIN, readInt32(frame, 8))
        val body = String(frame, LiveDanmakuProtocol.HEADER_SIZE, frame.size - LiveDanmakuProtocol.HEADER_SIZE, Charsets.UTF_8)
        assertTrue(body.contains("\"roomid\":23459804"))
        assertTrue(body.contains("\"protover\":3"))
        assertTrue(body.contains("\"platform\":\"web\""))
        assertTrue(body.contains("\"buvid\":\"buvid-abc\""))
        assertTrue(body.contains("\"key\":\"secret-token\""))
    }

    @Test
    fun `decodes plain message packet`() {
        val inner = LiveDanmakuProtocol.encode(5, """{"cmd":"DANMU_MSG","info":[[""],"hi"]}""".toByteArray())

        val packets = LiveDanmakuProtocol.decode(inner)

        assertEquals(1, packets.size)
        assertEquals(LiveDanmakuProtocol.OP_MESSAGE, packets[0].op)
        assertEquals(LiveDanmakuProtocol.PROTOCOL_PLAIN, packets[0].protocol)
    }

    @Test
    fun `decodes zlib wrapped packet and concatenated frames`() {
        val inner = LiveDanmakuProtocol.encode(5, """{"cmd":"DANMU_MSG","info":[[""],"hi"]}""".toByteArray())
        val compressed = deflate(inner)
        val outer = wrap(protocol = LiveDanmakuProtocol.PROTOCOL_ZLIB, op = LiveDanmakuProtocol.OP_MESSAGE, body = compressed)
        val second = LiveDanmakuProtocol.encode(LiveDanmakuProtocol.OP_HEARTBEAT_REPLY, byteArrayOf(0, 0, 0, 42))
        val stream = outer + second

        val packets = LiveDanmakuProtocol.decode(stream)

        assertEquals(2, packets.size)
        assertEquals(LiveDanmakuProtocol.OP_MESSAGE, packets[0].op)
        assertEquals("""{"cmd":"DANMU_MSG","info":[[""],"hi"]}""", String(packets[0].body))
        assertEquals(LiveDanmakuProtocol.OP_HEARTBEAT_REPLY, packets[1].op)
    }

    @Test
    fun `parses danmu msg into item with text color and mode`() {
        val body = """
            {"cmd":"DANMU_MSG","info":[
              [0,4,25,16711680,1720000000000,987654321,"","hash-123"],
              "前方高能",
              [123,"someone",0,0,0,10000,1,""]
            ]}
        """.trimIndent().toByteArray()

        val item = LiveDanmakuProtocol.parseDanmaku(body)

        assertNotNull(item)
        item!!
        assertEquals("前方高能", item.content)
        assertEquals(4, item.mode)
        assertEquals(25, item.fontSize)
        assertEquals(16711680, item.color)
        assertEquals("hash-123", item.midHash)
        assertEquals(987654321L, item.id)
        assertEquals(0, item.progressMs)
    }

    @Test
    fun `ignores non danmu messages and malformed json`() {
        assertNull(LiveDanmakuProtocol.parseDanmaku("""{"cmd":"SEND_GIFT"}""".toByteArray()))
        assertNull(LiveDanmakuProtocol.parseDanmaku("not-json".toByteArray()))
        assertNull(
            LiveDanmakuProtocol.parseDanmaku("""{"cmd":"DANMU_MSG","info":["only-one"]}""".toByteArray()),
        )
    }

    private fun wrap(protocol: Int, op: Int, body: ByteArray): ByteArray {
        val out = ByteArray(LiveDanmakuProtocol.HEADER_SIZE + body.size)
        writeInt32(out, 0, LiveDanmakuProtocol.HEADER_SIZE + body.size)
        writeInt16(out, 4, LiveDanmakuProtocol.HEADER_SIZE)
        writeInt16(out, 6, protocol)
        writeInt32(out, 8, op)
        writeInt32(out, 12, 1)
        body.copyInto(out, LiveDanmakuProtocol.HEADER_SIZE)
        return out
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(512)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun writeInt32(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
    }

    private fun writeInt16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value ushr 8).toByte()
        out[offset + 1] = value.toByte()
    }
}
