package com.roy233.tvcast.data.bili

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PsshBoxParserTest {

    @Test
    fun `finds widevine pssh nested inside moov initialization box`() {
        val pssh = widevinePssh()
        val moov = box("moov", pssh)

        assertArrayEquals(pssh, PsshBoxParser.findWidevinePssh(moov))
    }

    @Test
    fun `returns null for an unrelated or truncated pssh box`() {
        val truncated = byteArrayOf(0, 0, 0, 8, 'p'.code.toByte(), 's'.code.toByte(), 's'.code.toByte(), 'h'.code.toByte())

        assertNull(PsshBoxParser.findWidevinePssh(truncated))
        assertNull(PsshBoxParser.findWidevinePssh(box("free", byteArrayOf(1, 2, 3))))
    }

    private fun widevinePssh(): ByteArray {
        val systemId = byteArrayOf(
            0xed.toByte(), 0xef.toByte(), 0x8b.toByte(), 0xa9.toByte(),
            0x79, 0xd6.toByte(), 0x4a, 0xce.toByte(), 0xa3.toByte(), 0xc8.toByte(),
            0x27, 0xdc.toByte(), 0xd5.toByte(), 0x1d, 0x21, 0xed.toByte(),
        )
        val payload = byteArrayOf(1, 2, 3, 4)
        val data = ByteArray(4 + systemId.size + 4 + payload.size)
        systemId.copyInto(data, 4)
        writeInt(data, 4 + systemId.size, payload.size)
        payload.copyInto(data, 4 + systemId.size + 4)
        return box("pssh", data)
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val box = ByteArray(8 + payload.size)
        writeInt(box, 0, box.size)
        type.toByteArray(Charsets.US_ASCII).copyInto(box, 4)
        payload.copyInto(box, 8)
        return box
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
