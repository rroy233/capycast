package com.roy233.tvcast.data.bili.danmaku

/**
 * 手写的 B 站弹幕 protobuf 解码器。
 *
 * 只依赖标准库，避免引入 protobuf 运行时。支持 `DmSegMobileReply` 与
 * `DmWebViewReply` 中本应用需要的字段；未知字段按 wire type 跳过。
 */
object DanmakuProto {

    /** 单个响应允许的最大字节数。 */
    private const val MAX_BYTES = 8 * 1024 * 1024

    /** 单个分段允许的最大条目数。 */
    private const val MAX_ITEMS = 20_000

    /** 单个字符串字段允许的最大字节数。 */
    private const val MAX_STRING_BYTES = 4 * 1024

    /**
     * 表示输入字节不是合法的 protobuf，或超出资源限制。
     *
     * @param message 具体原因。
     */
    class MalformedException(message: String) : Exception(message)

    /**
     * 解码一个 6 分钟分段。
     *
     * @param bytes `DmSegMobileReply` 原始字节。
     * @return 解码后的分段。
     * @throws MalformedException 输入非法或超出限制。
     */
    fun decodeSegment(bytes: ByteArray): DmSegment {
        requireSize(bytes)
        val reader = Reader(bytes)
        val items = ArrayList<DanmakuItem>()
        val aiFlags = HashMap<Long, Int>()
        var state = 0
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag ushr 3) {
                1 -> {
                    if (tag and 7 != WIRE_LENGTH) throw MalformedException("elems wire")
                    items += decodeItem(reader.readBytes())
                    if (items.size > MAX_ITEMS) throw MalformedException("too many elems")
                }
                2 -> {
                    if (tag and 7 != WIRE_VARINT) throw MalformedException("state wire")
                    state = reader.readVarint().toInt()
                }
                3 -> {
                    if (tag and 7 != WIRE_LENGTH) throw MalformedException("ai_flag wire")
                    decodeAiFlags(reader.readBytes(), aiFlags)
                }
                else -> reader.skip(tag and 7)
            }
        }
        return DmSegment(items = items, state = state, aiFlags = aiFlags)
    }

    /**
     * 解码弹幕元数据。
     *
     * @param bytes `DmWebViewReply` 原始字节。
     * @return 本应用需要的元数据。
     * @throws MalformedException 输入非法或超出限制。
     */
    fun decodeView(bytes: ByteArray): DmViewInfo {
        requireSize(bytes)
        val reader = Reader(bytes)
        var state = 0
        var count = 0L
        var segConfig: DmSegConfig? = null
        var playerConfig: DanmakuPlayerConfig? = null
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag ushr 3) {
                1 -> {
                    if (tag and 7 != WIRE_VARINT) throw MalformedException("view state wire")
                    state = reader.readVarint().toInt()
                }
                4 -> {
                    if (tag and 7 != WIRE_LENGTH) throw MalformedException("dm_sge wire")
                    segConfig = decodeSegConfig(reader.readBytes())
                }
                8 -> {
                    if (tag and 7 != WIRE_VARINT) throw MalformedException("count wire")
                    count = reader.readVarint()
                }
                10 -> {
                    if (tag and 7 != WIRE_LENGTH) throw MalformedException("player_config wire")
                    playerConfig = decodePlayerConfig(reader.readBytes())
                }
                else -> reader.skip(tag and 7)
            }
        }
        return DmViewInfo(
            state = state,
            count = count,
            segConfig = segConfig,
            playerConfig = playerConfig,
        )
    }

    private fun decodeItem(bytes: ByteArray): DanmakuItem {
        val reader = Reader(bytes)
        var id = 0L
        var progress = 0
        var mode = 0
        var fontSize = 0
        var color = 0
        var midHash = ""
        var content = ""
        var weight = 0
        var pool = 0
        var attr = 0
        var ctime = 0L
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag ushr 3) {
                1 -> id = readVarintField(reader, tag, "id")
                2 -> progress = readVarintField(reader, tag, "progress").toInt()
                3 -> mode = readVarintField(reader, tag, "mode").toInt()
                4 -> fontSize = readVarintField(reader, tag, "fontsize").toInt()
                5 -> color = readVarintField(reader, tag, "color").toInt()
                6 -> midHash = reader.readString(tag, "midHash")
                7 -> content = reader.readString(tag, "content")
                8 -> ctime = readVarintField(reader, tag, "ctime")
                9 -> weight = readVarintField(reader, tag, "weight").toInt()
                11 -> pool = readVarintField(reader, tag, "pool").toInt()
                13 -> attr = readVarintField(reader, tag, "attr").toInt()
                else -> reader.skip(tag and 7)
            }
        }
        return DanmakuItem(
            id = id,
            progressMs = progress,
            mode = mode,
            fontSize = fontSize,
            color = color,
            midHash = midHash,
            content = content,
            weight = weight,
            pool = pool,
            attr = attr,
            ctime = ctime,
        )
    }

    private fun decodeAiFlags(bytes: ByteArray, out: MutableMap<Long, Int>) {
        val reader = Reader(bytes)
        while (reader.hasMore()) {
            val tag = reader.readTag()
            if (tag ushr 3 == 1) {
                if (tag and 7 != WIRE_LENGTH) throw MalformedException("dm_flags wire")
                decodeAiFlag(reader.readBytes(), out)
            } else {
                reader.skip(tag and 7)
            }
        }
    }

    private fun decodeAiFlag(bytes: ByteArray, out: MutableMap<Long, Int>) {
        val reader = Reader(bytes)
        var dmid = 0L
        var flag = 0
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag ushr 3) {
                1 -> dmid = readVarintField(reader, tag, "dmid")
                2 -> flag = readVarintField(reader, tag, "flag").toInt()
                else -> reader.skip(tag and 7)
            }
        }
        if (dmid != 0L) out[dmid] = flag
    }

    private fun decodeSegConfig(bytes: ByteArray): DmSegConfig {
        val reader = Reader(bytes)
        var pageSize = 0L
        var total = 0
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag ushr 3) {
                1 -> pageSize = readVarintField(reader, tag, "page_size")
                2 -> total = readVarintField(reader, tag, "total").toInt()
                else -> reader.skip(tag and 7)
            }
        }
        return DmSegConfig(pageSizeMs = pageSize, total = total)
    }

    private fun decodePlayerConfig(bytes: ByteArray): DanmakuPlayerConfig {
        val reader = Reader(bytes)
        var dmSwitch: Boolean? = null
        var aiSwitch: Boolean? = null
        var aiLevel: Int? = null
        var blockTop: Boolean? = null
        var blockScroll: Boolean? = null
        var blockBottom: Boolean? = null
        var blockColor: Boolean? = null
        var blockSpecial: Boolean? = null
        var opacity: Float? = null
        var dmArea: Int? = null
        var speedPlus: Float? = null
        var fontSize: Float? = null
        var preventShade: Boolean? = null
        var dmask: Boolean? = null
        var bold: Boolean? = null
        var fontBorder: Int? = null
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when (tag ushr 3) {
                1 -> dmSwitch = readVarintField(reader, tag, "dm_switch") != 0L
                2 -> aiSwitch = readVarintField(reader, tag, "ai_switch") != 0L
                3 -> aiLevel = readVarintField(reader, tag, "ai_level").toInt()
                4 -> blockTop = readVarintField(reader, tag, "blocktop") != 0L
                5 -> blockScroll = readVarintField(reader, tag, "blockscroll") != 0L
                6 -> blockBottom = readVarintField(reader, tag, "blockbottom") != 0L
                7 -> blockColor = readVarintField(reader, tag, "blockcolor") != 0L
                8 -> blockSpecial = readVarintField(reader, tag, "blockspecial") != 0L
                9 -> preventShade = readVarintField(reader, tag, "preventshade") != 0L
                10 -> dmask = readVarintField(reader, tag, "dmask") != 0L
                11 -> opacity = reader.readFloat(tag, "opacity")
                12 -> dmArea = readVarintField(reader, tag, "dmarea").toInt()
                13 -> speedPlus = reader.readFloat(tag, "speedplus")
                14 -> fontSize = reader.readFloat(tag, "fontsize")
                18 -> bold = readVarintField(reader, tag, "bold") != 0L
                19 -> fontBorder = readVarintField(reader, tag, "fontborder").toInt()
                else -> reader.skip(tag and 7)
            }
        }
        return DanmakuPlayerConfig(
            dmSwitch = dmSwitch,
            aiSwitch = aiSwitch,
            aiLevel = aiLevel,
            blockTop = blockTop,
            blockScroll = blockScroll,
            blockBottom = blockBottom,
            blockColor = blockColor,
            blockSpecial = blockSpecial,
            opacity = opacity,
            dmArea = dmArea,
            speedPlus = speedPlus,
            fontSize = fontSize,
            preventShade = preventShade,
            dmask = dmask,
            bold = bold,
            fontBorder = fontBorder,
        )
    }

    private fun readVarintField(reader: Reader, tag: Int, name: String): Long {
        if (tag and 7 != WIRE_VARINT) throw MalformedException("$name wire")
        return reader.readVarint()
    }

    private fun requireSize(bytes: ByteArray) {
        if (bytes.isEmpty()) throw MalformedException("empty")
        if (bytes.size > MAX_BYTES) throw MalformedException("too large: ${bytes.size}")
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH = 2
    private const val WIRE_FIXED32 = 5

    /** 只读字节游标，所有读取都做边界检查。 */
    private class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun hasMore(): Boolean = pos < bytes.size

        fun readTag(): Int {
            val tag = readVarint()
            if (tag <= 0 || tag > Int.MAX_VALUE) throw MalformedException("bad tag: $tag")
            if (tag ushr 3 == 0L) throw MalformedException("bad field: $tag")
            return tag.toInt()
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (pos >= bytes.size) throw MalformedException("truncated varint")
                val b = bytes[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw MalformedException("varint too long")
        }

        fun readBytes(): ByteArray {
            val length = readLength()
            if (length > bytes.size - pos) throw MalformedException("truncated bytes")
            val out = bytes.copyOfRange(pos, pos + length)
            pos += length
            return out
        }

        fun readString(tag: Int, name: String): String {
            if (tag and 7 != WIRE_LENGTH) throw MalformedException("$name wire")
            val length = readLength()
            if (length > MAX_STRING_BYTES) throw MalformedException("$name too long: $length")
            if (length > bytes.size - pos) throw MalformedException("$name truncated")
            val value = String(bytes, pos, length, Charsets.UTF_8)
            pos += length
            return value
        }

        fun readFloat(tag: Int, name: String): Float {
            if (tag and 7 != WIRE_FIXED32) throw MalformedException("$name wire")
            if (bytes.size - pos < 4) throw MalformedException("$name truncated")
            val bits = (bytes[pos].toInt() and 0xFF) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return Float.fromBits(bits)
        }

        fun skip(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> advance(8)
                WIRE_LENGTH -> advance(readLength())
                WIRE_FIXED32 -> advance(4)
                else -> throw MalformedException("unsupported wire type: $wireType")
            }
        }

        private fun readLength(): Int {
            val length = readVarint()
            if (length < 0 || length > Int.MAX_VALUE) throw MalformedException("bad length: $length")
            return length.toInt()
        }

        private fun advance(count: Int) {
            if (count < 0 || count > bytes.size - pos) throw MalformedException("truncated skip")
            pos += count
        }
    }
}
