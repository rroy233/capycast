package com.roy233.tvcast.data.bili

import android.util.Base64
import com.google.gson.Gson
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.feature.cast.domain.CastContent
import com.roy233.tvcast.feature.cast.domain.CastLink
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The phone's DLNA SetAVTransportURI carries the real video identity inside
 * `<upnp:longDescription>` as an encrypted `_nva_ext_` blob. Its payload
 * uses the following encoding sequence:
 *
 *   base64url -> AES/CBC/PKCS7 -> raw deflate -> UTF-8 JSON
 *
 * Key and IV use the default `nva.ext.aes_key` value (`1pzhA828t4i.6Oq@`).
 */
object NvaExtDecoder {

    /** 日志标签。 */
    private const val TAG = "NvaExt"
    /** 默认 AES 密钥及初始化向量文本。 */
    private const val DEFAULT_KEY = "1pzhA828t4i.6Oq@"

    /** JSON 反序列化器。 */
    private val gson = Gson()

    /** 解码 `<upnp:longDescription>` 中的 nva 扩展内容。 */
    fun decode(input: String?): CastContent? {
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
            val decodedPayload = gson.fromJson(json, NvaPayload::class.java)
            val content = CastContent(
                aid = decodedPayload.aid,
                cid = decodedPayload.cid,
                oid = decodedPayload.oid,
                bvid = decodedPayload.bvid,
                qn = decodedPayload.qn,
                userDesireQn = decodedPayload.userDesireQn,
                userDesireSpeed = decodedPayload.userDesireSpeed,
                epId = decodedPayload.eid,
                seasonId = decodedPayload.sid,
                roomId = decodedPayload.roomId,
                contentType = decodedPayload.ct,
                businessSessionId = decodedPayload.bizSessionId,
                link = decodedPayload.nl?.let { CastLink(it.deviceId, it.callback) },
                mediaVersion = decodedPayload.mv,
                transportType = decodedPayload.tp,
            )
            LogHub.i(
                TAG,
                "nva_ext aid=${content.aid} cid=${content.cid} oid=${content.oid} " +
                    "bvid=${content.bvid} qn=${content.qn} epId=${content.epId} seasonId=${content.seasonId} " +
                    "room=${content.roomId}"
            )
            content
        } catch (t: Throwable) {
            LogHub.e(TAG, "nva_ext decode failed: ${t.javaClass.simpleName}: ${t.message}", t)
            null
        }
    }

    /** 协议专属 JSON 结构，仅保留在 data adapter 内。 */
    private data class NvaPayload(
        val aid: Long = 0L,
        val cid: Long = 0L,
        val oid: String? = null,
        val bvid: String? = null,
        val qn: Int = 0,
        val userDesireQn: Int = 0,
        val userDesireSpeed: String? = null,
        val eid: Long = 0L,
        val sid: Long = 0L,
        val roomId: Long = 0L,
        val ct: Int = 0,
        @com.google.gson.annotations.SerializedName("biz_session_id") val bizSessionId: String? = null,
        val nl: NvaLinkPayload? = null,
        val mv: Long = 0L,
        val tp: Int = 0,
    )

    private data class NvaLinkPayload(
        @com.google.gson.annotations.SerializedName("cd") val deviceId: String? = null,
        @com.google.gson.annotations.SerializedName("cb") val callback: String? = null,
    )

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
