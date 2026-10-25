package com.roy233.tvcast.data.bili

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.roy233.tvcast.core.diagnostics.LogRedaction
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 调用 B 站 Web 播放历史接口及 TV 直播在线心跳接口。 */
class PlaybackHistoryApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
) {

    /** 按 Web 播放心跳协议上报点播播放进度。 */
    fun reportProgress(auth: WebHistoryAuth, report: PlaybackHistoryReport) {
        val fields = linkedMapOf(
            "cid" to report.cid.toString(),
            "mid" to auth.mid.toString(),
            "played_time" to report.progressSeconds.coerceAtLeast(0L).toString(),
            "real_played_time" to report.playDurationSeconds.coerceAtLeast(0L).toString(),
            "realtime" to report.playDurationSeconds.coerceAtLeast(0L).toString(),
            "start_ts" to (report.startTsSec.takeIf { it > 0L }
                ?: (report.updatedAtMs / 1_000L)).coerceAtLeast(0L).toString(),
            "type" to report.type.toString(),
            "dt" to "2",
            "outer" to "0",
            "play_type" to if (report.progressSeconds <= 0L && report.playDurationSeconds <= 0L) "1" else "0",
            "spmid" to "333.788.0.0",
            "from_spmid" to report.fromSpmid,
            "refer_url" to report.bvid.takeIf(String::isNotBlank)?.let { "https://www.bilibili.com/video/$it" }.orEmpty(),
            "csrf" to auth.csrf,
        )
        report.aid.takeIf { it > 0L }?.let { fields["aid"] = it.toString() }
        report.bvid.takeIf(String::isNotBlank)?.let { fields["bvid"] = it }
        report.sid.takeIf { it > 0L }?.let { fields["sid"] = it.toString() }
        report.epid.takeIf { it > 0L }?.let { fields["epid"] = it.toString() }
        post(
            path = "/x/click-interface/web/heartbeat",
            fields = fields,
            cookie = auth.cookie,
            referer = fields.getValue("refer_url").ifBlank { "https://www.bilibili.com/" },
            origin = "https://www.bilibili.com",
        )
    }

    /** 登录后进入直播间时登记观看记录；直播没有可恢复的点播进度。 */
    fun reportLiveEntry(auth: WebHistoryAuth, roomId: Long) {
        post(
            path = "/xlive/web-room/v1/index/roomEntryAction",
            fields = linkedMapOf(
                "room_id" to roomId.toString(),
                "platform" to "pc",
                "csrf" to auth.csrf,
                "csrf_token" to auth.csrf,
                "visit_id" to "",
            ),
            cookie = auth.cookie,
            baseUrl = "https://api.live.bilibili.com",
            referer = "https://live.bilibili.com/$roomId",
            origin = "https://live.bilibili.com",
        )
    }

    /** 发送一次官方 TV 直播心跳，并返回服务端更新的签名状态。 */
    fun reportLiveHeartbeat(
        accessKey: String,
        request: LiveHeartbeatRequest,
    ): LiveHeartbeatResponse {
        val fields = linkedMapOf(
            "access_key" to accessKey,
            "build" to request.build.toString(),
            "buvid" to request.buvid,
            "room_id" to request.roomId.toString(),
            "play_url" to request.playUrl,
            "auty" to "-2",
            "udse" to "1",
            "tils" to request.timestampMs.toString(),
            "reye" to request.reye,
            "urcs" to request.urcs.joinToString(","),
            "edno" to request.edno,
            "daqs" to request.sequence.toString(),
            "p2p" to "0",
            "caton" to "",
            "nsti" to liveHeartbeatSignature(request),
        )
        val data = post("/x/tv/live/heartbeat/report", fields).getAsJsonObject("data")
            ?: throw IOException("live heartbeat response omitted data")
        return LiveHeartbeatResponse(
            edno = data.stringOrEmpty("edno"),
            reye = data.stringOrEmpty("reye"),
            urcs = data.getAsJsonArray("urcs")?.mapNotNull { it.asIntOrNull() }.orEmpty(),
            intervalSeconds = data.intOrZero("ufhud"),
        )
    }

    private fun post(
        path: String,
        fields: Map<String, String>,
        cookie: String? = null,
        baseUrl: String = "https://api.bilibili.com",
        referer: String = "https://www.bilibili.com/",
        origin: String? = null,
    ): JsonObject {
        val form = FormBody.Builder().apply {
            fields.forEach { (name, value) -> add(name, value) }
        }.build()
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header("User-Agent", BiliApi.UA)
            .header("Referer", referer)
            .apply {
                origin?.let { header("Origin", it) }
                cookie?.takeIf(String::isNotBlank)?.let { header("Cookie", it) }
            }
            .post(form)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("History API HTTP ${response.code} path=$path")
            }
            val body = response.body?.string().orEmpty()
            val root = runCatching { JsonParser.parseString(body).asJsonObject }
                .getOrElse { throw IOException("History API returned invalid JSON path=$path", it) }
            val code = runCatching { root.get("code")?.asInt }.getOrNull()
                ?: throw IOException("History API response omitted code path=$path")
            if (code != 0) {
                val message = sanitizeServerMessage(root.stringOrEmpty("message")).take(160)
                throw PlaybackHistoryApiException(path = path, code = code, message = message)
            }
            return root
        }
    }

    private fun liveHeartbeatSignature(request: LiveHeartbeatRequest): String {
        if (request.sequence <= 0 || request.urcs.isEmpty()) return ""
        val payload = """{"auty":-2,"daqs":${request.sequence},"edno":"${request.edno.jsonEscape()}","play_url":"${request.playUrl.jsonEscape()}","room_id":${request.roomId},"tils":${request.timestampMs},"udse":1}"""
        var digestInput = payload.toByteArray(Charsets.UTF_8)
        var result = ""
        for (algorithm in request.urcs) {
            val algorithmName = when (algorithm) {
                0 -> "SHA-256"
                1 -> "SHA-512"
                2 -> "SHA-224"
                else -> return ""
            }
            result = runCatching {
                MessageDigest.getInstance(algorithmName).digest(digestInput).toHex()
            }.getOrElse { return "" }
            digestInput = result.toByteArray(Charsets.UTF_8)
        }
        return result
    }

    private fun String.jsonEscape(): String = buildString(length) {
        for (character in this@jsonEscape) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun sanitizeServerMessage(message: String): String = LogRedaction.text(message)
        .replace(SENSITIVE_VALUE_PATTERN) { match -> "${match.groupValues[1]}=***" }

    private fun JsonObject.stringOrEmpty(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.intOrZero(name: String): Int =
        runCatching { get(name)?.takeUnless { it.isJsonNull }?.asInt ?: 0 }.getOrDefault(0)

    private fun com.google.gson.JsonElement.asIntOrNull(): Int? =
        runCatching { asInt }.getOrNull()

    private companion object {
        val SENSITIVE_VALUE_PATTERN = Regex(
            "(?i)\\b(access_key|access_token|refresh_token|buvid3?|play_url|sessdata|bili_jct|csrf)\\s*[:=]\\s*[^,\\s&]+",
        )
    }
}

/** 一条待同步的历史记录快照；不包含 access_key、Cookie 或 CSRF。 */
data class PlaybackHistoryReport(
    val sessionId: String,
    val accountMid: Long,
    val aid: Long,
    val cid: Long,
    val sid: Long,
    val epid: Long,
    val serialId: Long,
    val type: Int,
    val business: String,
    val progressSeconds: Long,
    val playDurationSeconds: Long,
    val isInteracted: Boolean,
    val bvid: String = "",
    val durationSeconds: Long = 0L,
    val startTsSec: Long = 0L,
    /** null 表示旧版本队列中的点播心跳记录。 */
    val reportKind: String? = null,
    val scene: String = "",
    val fromScene: String = "",
    val fromSpmid: String = "",
    val resourceFrom: String = "",
    val updatedAtMs: Long,
)

/** 仅保留在内存中的 Web 登录态，不写入历史队列或日志。 */
data class WebHistoryAuth(
    val mid: Long,
    val cookie: String,
    val csrf: String,
)

/** 当前直播心跳所需的请求数据。 */
data class LiveHeartbeatRequest(
    val build: Int,
    val buvid: String,
    val roomId: Long,
    val playUrl: String,
    val timestampMs: Long,
    val reye: String,
    val urcs: List<Int>,
    val edno: String,
    val sequence: Int,
)

/** 直播心跳响应携带的下次间隔与签名状态。 */
data class LiveHeartbeatResponse(
    val edno: String,
    val reye: String,
    val urcs: List<Int>,
    val intervalSeconds: Int,
)

/** 包含服务端错误码但不携带请求凭据的接口异常。 */
class PlaybackHistoryApiException(
    val path: String,
    val code: Int,
    message: String,
) : IOException("History API rejected request path=$path code=$code message=$message")
