package com.roy233.tvcast.data.bili.danmaku

import com.google.gson.JsonParser
import com.roy233.tvcast.core.diagnostics.LogHub
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * B 站直播弹幕长连接二进制协议编解码。
 *
 * 包结构对齐 bilibili-live-ws：4 字节大端总长 + 2 字节头长 + 2 字节协议版本 +
 * 4 字节操作码 + 4 字节序号 + 消息体。protover=2 时消息体为 zlib 压缩的多个子包，
 * 解压后按同一格式递归解析。
 */
object LiveDanmakuProtocol {

    /** 客户端心跳。 */
    const val OP_HEARTBEAT = 2

    /** 服务端心跳回包，携带在线人数。 */
    const val OP_HEARTBEAT_REPLY = 3

    /** 服务端业务消息，弹幕经此下发。 */
    const val OP_MESSAGE = 5

    /** 客户端进房认证。 */
    const val OP_JOIN = 7

    /** 服务端进房回包。 */
    const val OP_JOIN_REPLY = 8

    /** 消息体为明文 JSON。 */
    const val PROTOCOL_PLAIN = 0

    /** 消息体为 4 字节在线人数（仅心跳回包）。 */
    const val PROTOCOL_POPULARITY = 1

    /** 消息体为 zlib 压缩。 */
    const val PROTOCOL_ZLIB = 2

    /** 消息体为 brotli 压缩；web 端当前使用。 */
    const val PROTOCOL_BROTLI = 3

    /** 包头固定长度。 */
    const val HEADER_SIZE = 16

    /** 单个解压包允许的最大字节数，防止异常压缩数据撑爆内存。 */
    private const val MAX_INFLATED_BYTES = 4 * 1024 * 1024

    /** 单个消息允许的最大嵌套层数，防止递归解压死循环。 */
    private const val MAX_DEPTH = 8

    /** 一个已解析的协议包。 */
    data class Packet(
        val op: Int,
        val protocol: Int,
        val body: ByteArray,
    )

    /** 编码心跳包；body 为空的 JSON 对象。 */
    fun encodeHeartbeat(): ByteArray = encode(OP_HEARTBEAT, "{}".toByteArray(Charsets.UTF_8))

    /** 编码进房包；protover 固定为 3（brotli），身份对齐网页端。 */
    fun encodeJoin(roomId: Long, token: String, uid: Long, buvid: String): ByteArray {
        val body = buildString {
            append("{\"uid\":").append(uid)
            append(",\"roomid\":").append(roomId)
            append(",\"protover\":3,\"platform\":\"web\",\"type\":2")
            append(",\"buvid\":\"").append(buvid).append("\"")
            append(",\"key\":\"").append(token).append("\"}")
        }
        return encode(OP_JOIN, body.toByteArray(Charsets.UTF_8))
    }

    /** 将操作码与消息体打包为客户端上行帧。 */
    fun encode(op: Int, body: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + body.size)
        writeInt32(out, 0, HEADER_SIZE + body.size)
        writeInt16(out, 4, HEADER_SIZE)
        writeInt16(out, 6, PROTOCOL_PLAIN)
        writeInt32(out, 8, op)
        writeInt32(out, 12, 1)
        body.copyInto(out, HEADER_SIZE)
        return out
    }

    /**
     * 将一段服务端下行字节拆为若干协议包，递归解压 zlib 子包。
     *
     * @return 解出的所有明文业务包。
     */
    fun decode(bytes: ByteArray): List<Packet> = decode(bytes, 0)

    private fun decode(bytes: ByteArray, depth: Int): List<Packet> {
        if (depth > MAX_DEPTH) return emptyList()
        val result = mutableListOf<Packet>()
        var offset = 0
        while (offset + HEADER_SIZE <= bytes.size) {
            val size = readInt32(bytes, offset)
            if (size < HEADER_SIZE || offset + size > bytes.size) break
            val protocol = readInt16(bytes, offset + 6)
            val op = readInt32(bytes, offset + 8)
            val body = bytes.copyOfRange(offset + HEADER_SIZE, offset + size)
            offset += size
            when (protocol) {
                PROTOCOL_PLAIN, PROTOCOL_POPULARITY -> result += Packet(op, protocol, body)
                PROTOCOL_ZLIB -> result += decode(inflate(body), depth + 1)
                PROTOCOL_BROTLI -> result += decode(brotliDecompress(body), depth + 1)
                else -> LogHub.w(TAG, "unknown protocol=$protocol op=$op")
            }
        }
        return result
    }

    /**
     * 将一条 `DANMU_MSG` 业务包解析为弹幕条目。
     *
     * 字段索引对齐网页端协议：`info[1]` 为文本，`info[0]` 为属性数组，
     * 依次含 mode/fontSize/color/userHash/dmid。无进度时间轴，[DanmakuItem.progressMs]
     * 由调用方按实时时钟填充。
     *
     * @return 非弹幕消息或解析失败时返回 null。
     */
    fun parseDanmaku(body: ByteArray): DanmakuItem? {
        val root = try {
            JsonParser.parseString(String(body, Charsets.UTF_8)).asJsonObject
        } catch (_: Exception) {
            return null
        }
        if (root.get("cmd")?.asString != "DANMU_MSG") return null
        val info = root.getAsJsonArray("info") ?: return null
        if (info.size() < 2) return null
        val content = info.get(1)?.asString ?: return null
        if (content.isEmpty()) return null

        val attr = info.get(0)?.asJsonArray
        val mode = (attr?.get(1)?.asInt ?: 1).let { if (it in 1..9) it else 1 }
        val fontSize = (attr?.get(2)?.asInt ?: 25).let { if (it in 1..72) it else 25 }
        val color = attr?.get(3)?.asInt ?: 0xFFFFFF
        // info[0][5] 是前端用作弹幕 ID 的随机数，可用于去重；info[0][7] 是用户 hash。
        val id = attr?.get(5)?.asLong ?: 0L
        val midHash = attr?.get(7)?.asString.orEmpty()
        val ctime = attr?.get(4)?.asLong ?: 0L
        return DanmakuItem(
            id = id,
            progressMs = 0,
            mode = mode,
            fontSize = fontSize,
            color = color,
            midHash = midHash,
            content = content,
            weight = 0,
            pool = 0,
            attr = 0,
            ctime = ctime,
        )
    }

    /** zlib 解压；输入不符合 zlib 格式时返回空。 */
    private fun inflate(compressed: ByteArray): ByteArray {
        if (compressed.isEmpty()) return ByteArray(0)
        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            val output = ByteArrayOutputStream(compressed.size.coerceAtMost(MAX_INFLATED_BYTES))
            val buffer = ByteArray(8192)
            while (!inflater.finished() && output.size() < MAX_INFLATED_BYTES) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    output.write(buffer, 0, count)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                }
            }
            output.toByteArray()
        } catch (_: Exception) {
            LogHub.w(TAG, "zlib inflate failed")
            ByteArray(0)
        } finally {
            inflater.end()
        }
    }

    /** brotli 解压。 */
    private fun brotliDecompress(compressed: ByteArray): ByteArray {
        if (compressed.isEmpty()) return ByteArray(0)
        return try {
            val output = ByteArrayOutputStream(compressed.size.coerceAtMost(MAX_INFLATED_BYTES))
            BrotliInputStream(ByteArrayInputStream(compressed)).use { brotli ->
                val buffer = ByteArray(8192)
                while (output.size() < MAX_INFLATED_BYTES) {
                    val count = brotli.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        } catch (_: Exception) {
            LogHub.w(TAG, "brotli decompress failed")
            ByteArray(0)
        }
    }

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

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private const val TAG = "LiveDanmakuProtocol"
}
