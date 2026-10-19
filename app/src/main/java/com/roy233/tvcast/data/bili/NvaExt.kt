package com.roy233.tvcast.data.bili

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.roy233.tvcast.core.diagnostics.LogHub
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The phone's DLNA SetAVTransportURI carries the real video identity inside
 * `<upnp:longDescription>` as an encrypted `_nva_ext_` blob.  The official
 * TV projection plugin decrypts it like this (decompiled from
 * `projection/classes.dex`, class `projection.e1` + `projection.b`):
 *
 *   base64url -> AES/CBC/PKCS7 -> raw deflate -> UTF-8 JSON
 *
 * Key and IV are the plugin's default `nva.ext.aes_key`
 * (`1pzhA828t4i.6Oq@`); the value can be overridden by Bilibili's remote
 * config, but every shipped build so far uses this default.
 */
object NvaExtDecoder {

    /** 日志标签。 */
    private const val TAG = "NvaExt"
    /** 默认 AES 密钥及初始化向量文本。 */
    private const val DEFAULT_KEY = "1pzhA828t4i.6Oq@"

    /** JSON 反序列化器。 */
    private val gson = Gson()

    /** 保存 nva 扩展中的设备回调信息。 */
    data class NvaLink(
        /** 设备标识。 */
        @SerializedName("cd") val deviceId: String? = null,
        /** 回调地址或标识。 */
        @SerializedName("cb") val callback: String? = null,
    )

    /** 保存 nva 扩展中携带的内容身份信息。 */
    data class NvaExtContent(
        /** 普通稿件标识。 */
        val aid: Long = 0L,
        /** 普通分P标识。 */
        val cid: Long = 0L,
        /** 原始对象标识。 */
        val oid: String? = null,
        /** BV 标识。 */
        val bvid: String? = null,
        /** 请求的画质标识。 */
        val qn: Int = 0,
        /** 用户期望的画质标识。 */
        val userDesireQn: Int = 0,
        /** 用户期望的播放速度。 */
        val userDesireSpeed: String? = null,
        /** 剧集标识。 */
        val eid: Long = 0L,
        /** 系列标识。 */
        val sid: Long = 0L,
        /** 直播间标识。 */
        val roomId: Long = 0L,
        /** 内容类型标识。 */
        val ct: Int = 0,
        /** 业务会话标识。 */
        @SerializedName("biz_session_id") val bizSessionId: String? = null,
        /** 设备链路信息。 */
        val nl: NvaLink? = null,
        /** 媒体或版本标识。 */
        val mv: Long = 0L,
        /** 投屏类型标识。 */
        val tp: Int = 0,
    ) {
        /** 是否包含普通视频的稿件和分P标识。 */
        val hasUgc: Boolean get() = aid > 0 && cid > 0
        /** 是否表示 PGC 内容且不包含普通视频标识。 */
        val hasPgc: Boolean get() = (eid > 0 || sid > 0) && !hasUgc
        /** 是否表示直播内容且不包含普通视频标识。 */
        val hasLive: Boolean get() = roomId > 0 && !hasUgc
    }

    /** 解码 `<upnp:longDescription>` 中的 nva 扩展内容。 */
    fun decode(input: String?): NvaExtContent? {
        if (input.isNullOrBlank()) return null
        return try {
            val payload = extractPayload(input)
            if (payload.isBlank()) return null
            val normalized = payload.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val encrypted = Base64.decode(padded, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            val key = SecretKeySpec(DEFAULT_KEY.toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec(DEFAULT_KEY.toByteArray(Charsets.UTF_8))
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val compressed = cipher.doFinal(encrypted)
            val json = String(inflate(compressed), Charsets.UTF_8)
            val content = gson.fromJson(json, NvaExtContent::class.java)
            LogHub.i(
                TAG,
                "nva_ext aid=${content.aid} cid=${content.cid} oid=${content.oid} " +
                    "bvid=${content.bvid} qn=${content.qn} eid=${content.eid} sid=${content.sid} " +
                    "room=${content.roomId}"
            )
            content
        } catch (t: Throwable) {
            LogHub.e(TAG, "nva_ext decode failed: ${t.javaClass.simpleName}: ${t.message}", t)
            null
        }
    }

    /** 从 URL 查询参数或裸 base64 文本中提取加密载荷。 */
    private fun extractPayload(input: String): String {
        var s = input.trim()
        val marker = "_nva_ext_="
        val at = s.indexOf(marker)
        if (at >= 0) {
            s = s.substring(at + marker.length)
            val amp = s.indexOf('&')
            if (amp >= 0) s = s.substring(0, amp)
        }
        return s.trim().trim('"', '\'', ' ')
    }

    /** 使用 raw deflate 算法解压数据。 */
    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 2)
        val buf = ByteArray(4096)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }
}
