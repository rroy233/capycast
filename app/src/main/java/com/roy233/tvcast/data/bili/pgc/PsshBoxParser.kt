package com.roy233.tvcast.data.bili.pgc

internal object PsshBoxParser {
    private val WIDEVINE_UUID_BYTES = byteArrayOf(
        0xed.toByte(), 0xef.toByte(), 0x8b.toByte(), 0xa9.toByte(),
        0x79, 0xd6.toByte(), 0x4a, 0xce.toByte(), 0xa3.toByte(), 0xc8.toByte(),
        0x27, 0xdc.toByte(), 0xd5.toByte(), 0x1d, 0x21, 0xed.toByte(),
    )

    fun findWidevinePssh(bytes: ByteArray): ByteArray? {
        // PSSH is commonly nested inside moov, so scan candidate box headers instead of
        // treating the initialization segment as a flat list of top-level boxes.
        for (typeOffset in 4 until bytes.size - 3) {
            if (bytes[typeOffset] != 'p'.code.toByte() ||
                bytes[typeOffset + 1] != 's'.code.toByte() ||
                bytes[typeOffset + 2] != 's'.code.toByte() ||
                bytes[typeOffset + 3] != 'h'.code.toByte()
            ) continue
            val offset = typeOffset - 4
            val size32 = readUInt32(bytes, offset) ?: continue
            val headerSize: Int
            val size: Long
            when (size32) {
                0L -> {
                    size = (bytes.size - offset).toLong()
                    headerSize = 8
                }
                1L -> {
                    if (offset + 16 > bytes.size) continue
                    size = readUInt64(bytes, offset + 8) ?: continue
                    headerSize = 16
                }
                else -> {
                    size = size32
                    headerSize = 8
                }
            }
            if (size < headerSize + 24 || size > bytes.size - offset) continue
            val uuidOffset = offset + headerSize + 4
            val matches = WIDEVINE_UUID_BYTES.indices.all { index ->
                bytes[uuidOffset + index] == WIDEVINE_UUID_BYTES[index]
            }
            if (matches) return bytes.copyOfRange(offset, offset + size.toInt())
        }
        return null
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        return (0..3).fold(0L) { value, index ->
            (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
        }
    }

    private fun readUInt64(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset + 8 > bytes.size) return null
        return (0..7).fold(0L) { value, index ->
            (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
        }
    }
}
