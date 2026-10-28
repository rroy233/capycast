package com.roy233.tvcast.data.bili

import android.content.Context
import android.os.SystemClock
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.data.auth.CastAuth
import com.roy233.tvcast.feature.playback.model.LivePlayInfo
import com.roy233.tvcast.feature.playback.model.LiveStreamCandidate
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 使用已登录 TV access_key 解析官方直播流。 */
object LivePlayUrlResolver {

    private const val TAG = "LivePlayUrl"
    private const val API_URL = "https://api.bilibili.com/x/tv/playurl"
    const val HIGHEST_QUALITY = 30_000
    private val STANDARD_QUALITY_LADDER = listOf(10_000, 400, 250, 150, 80)

    /** 返回低于当前实际档位的服务端档位；旧 TV 响应缺少 accept_quality 时仅用标准档位发起探测。 */
    fun fallbackQualities(currentQuality: Int, acceptedQualities: List<Int>): List<Int> {
        val acceptedLower = acceptedQualities.filter { it in 1 until currentQuality }
            .distinct()
            .sortedDescending()
        return acceptedLower.ifEmpty {
            STANDARD_QUALITY_LADDER.filter { it < currentQuality }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 请求账号在指定画质下可用的 TV 直播流。
     *
     * @param context 用于生成官方 TV 请求签名的上下文。
     * @param accessKey TV 登录令牌；不会写入日志。
     * @param roomId 直播间标识，同时作为 TV 协议的 cid 和 object_id。
     * @param quality 请求画质，首次播放使用 [HIGHEST_QUALITY]。
     * @param forceAvc HEVC 解码失败后设置为 true，请求同档 AVC 线路。
     */
    fun resolve(
        context: Context,
        accessKey: String,
        roomId: Long,
        quality: Int = HIGHEST_QUALITY,
        forceAvc: Boolean = false,
    ): LivePlayInfo {
        require(roomId > 0L) { "invalid live roomId=$roomId" }
        require(accessKey.isNotBlank()) { "TV login is required for live playback" }
        val params = CastAuth.signedParams(
            mapOf(
                "access_key" to accessKey,
                "playurl_type" to "4",
                "cid" to roomId.toString(),
                "object_id" to roomId.toString(),
                "ogv_aid" to "",
                "qn" to quality.toString(),
                "fnver" to "0",
                "fnval" to "0",
                "force_host" to "0",
                "from_outside" to "0",
                "drm_tech_type" to "0",
                "is_dolby" to "0",
                "is_h265" to if (forceAvc) "0" else "1",
                "live_drm_types" to "0",
                "live_formats" to "0,2",
                "source_type" to "",
                "preview" to "0",
                "is_global_preview_end" to "0",
                "preview_end" to "0",
                "preview_from_guest" to "0",
                "play_model" to "",
                "is_switch_qn" to "0",
                "disable_ad" to "0",
                "ad_type" to "0",
                "is_portrait" to "0",
                "play_scene" to "0",
                "ad_extra" to "",
                "resource_from" to "bilibili",
            ),
            context,
        )
        val requestUrl = API_URL.toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val safeHost = LogRedaction.host(requestUrl.toString())
        val startedAt = SystemClock.elapsedRealtime()
        LogHub.i(
            TAG,
            "resolve start room=$roomId requestedQn=$quality forceAvc=$forceAvc " +
                "auth=tv-account host=$safeHost",
        )

        val responseText = try {
            val request = Request.Builder()
                .url(requestUrl)
                .header("User-Agent", BiliApi.UA)
                .header("Referer", "https://www.bilibili.com/")
                .header("Accept", "application/json, text/plain, */*")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (!response.isSuccessful) {
                    LogHub.e(
                        TAG,
                        "resolve http failed room=$roomId status=${response.code} " +
                            "bytes=${body.length} ms=$elapsed host=$safeHost",
                    )
                    error("TV live playurl HTTP ${response.code}")
                }
                LogHub.i(
                    TAG,
                    "resolve http done room=$roomId status=${response.code} " +
                        "bytes=${body.length} ms=$elapsed",
                )
                if (body.isBlank()) error("TV live playurl returned an empty body")
                body
            }
        } catch (failure: IOException) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            LogHub.e(
                TAG,
                "resolve network error room=$roomId ms=$elapsed host=$safeHost " +
                    "error=${failure.javaClass.simpleName}",
            )
            throw failure
        }

        val root = runCatching { JsonParser.parseString(responseText).asJsonObject }
            .getOrElse { failure ->
                LogHub.e(TAG, "resolve invalid JSON room=$roomId", failure, stack = true)
                throw IllegalStateException("TV live playurl returned invalid JSON", failure)
            }
        val code = root.int("code", -1)
        if (code != 0) {
            LogHub.e(
                TAG,
                "resolve rejected room=$roomId code=$code host=$safeHost",
            )
            error("TV live playurl code=$code")
        }
        val data = root.getAsJsonObject("data")
            ?: error("TV live playurl response has no data")
        return parse(data, roomId, quality, forceAvc).also { result ->
            LogHub.i(
                TAG,
                "resolve parsed room=$roomId requestedQn=$quality actualQn=${result.quality} " +
                    "acceptedQn=${result.acceptedQualities.joinToString(",")} " +
                    "candidates=${result.candidates.size} " +
                    "formats=${result.candidates.groupingBy { it.format }.eachCount()} " +
                    "codecs=${result.candidates.groupingBy { it.codecName }.eachCount()} " +
                    "hosts=${result.candidates.take(8).joinToString(",") { it.cdnLabel }}",
            )
            if (result.quality < quality) {
                LogHub.w(
                    TAG,
                    "account/server returned lower live quality requested=$quality " +
                        "actual=${result.quality} accepted=${result.acceptedQualities}",
                )
            }
        }
    }

    /** 将 `live_stream_v2`、旧版 `live_stream` 或 `durl` 统一展开为线路列表。 */
    fun parse(
        data: JsonObject,
        roomId: Long,
        requestedQuality: Int = HIGHEST_QUALITY,
        forceAvc: Boolean = false,
    ): LivePlayInfo {
        val responseQuality = data.int("quality", 0)
        val accepted = data.array("accept_quality")?.mapNotNull { it.intOrNull() }
            ?.distinct().orEmpty()
        val found = mutableListOf<LiveStreamCandidate>()
        val streamRoots = listOf("live_stream_v2", "live_stream", "durl")
        streamRoots.forEach { key ->
            val element = data.get(key) ?: return@forEach
            collectCandidates(
                element = element,
                inherited = StreamMetadata(quality = responseQuality),
                output = found,
            )
        }

        val candidates = found.distinctBy { it.url }
        if (candidates.isEmpty()) {
            LogHub.e(
                TAG,
                "parse no live routes room=$roomId keys=${data.keySet().joinToString(",")}",
            )
            error("TV live playurl returned no playable routes")
        }

        val actualQuality = responseQuality.takeIf { it > 0 }
            ?: candidates.maxOfOrNull { it.quality }?.takeIf { it > 0 }
            ?: requestedQuality
        val normalized = candidates.map { candidate ->
            if (candidate.quality > 0) candidate else candidate.copy(quality = actualQuality)
        }
        val selected = if (forceAvc) {
            normalized.filter { it.codecid == 7 }.also { avc ->
                if (avc.isEmpty()) {
                    LogHub.e(TAG, "parse requested AVC fallback but no AVC routes exist room=$roomId")
                    error("TV live playurl returned no AVC route for fallback")
                }
            }
        } else {
            normalized
        }
        val selectedQuality = selected.filter { it.quality == actualQuality }
            .ifEmpty { selected.filter { it.quality == 0 } }
        return LivePlayInfo(
            roomId = roomId,
            requestedQuality = requestedQuality,
            quality = actualQuality,
            acceptedQualities = accepted,
            candidates = selectedQuality.ifEmpty { selected },
        )
    }

    private data class StreamMetadata(
        val quality: Int = 0,
        val format: String = "",
        val codecName: String = "",
        val codecid: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        val bandwidth: Long = 0L,
    )

    private fun collectCandidates(
        element: JsonElement,
        inherited: StreamMetadata,
        output: MutableList<LiveStreamCandidate>,
    ) {
        if (element.isJsonArray) {
            element.asJsonArray.forEach { collectCandidates(it, inherited, output) }
            return
        }
        if (!element.isJsonObject) return
        val obj = element.asJsonObject
        val metadata = inherited.copy(
            quality = obj.int("current_qn", obj.int("quality", obj.int("qn", inherited.quality))),
            format = obj.string("format_name", obj.string("format", inherited.format)),
            codecName = obj.string("codec_name", obj.string("codec", inherited.codecName)),
            codecid = obj.int("codecid", inherited.codecid),
            width = obj.int("width", inherited.width),
            height = obj.int("height", inherited.height),
            bandwidth = obj.long("bandwidth", inherited.bandwidth),
        )
        val base = obj.string("base_url", obj.string("baseUrl")).orEmpty()
        if (base.isNotBlank()) {
            val urlInfo = obj.get("url_info")?.takeIf { it.isJsonArray }?.asJsonArray
            if (urlInfo == null || urlInfo.size() == 0) {
                addCandidate(base, metadata, output)
            } else {
                urlInfo.forEach { infoElement ->
                    if (!infoElement.isJsonObject) return@forEach
                    val info = infoElement.asJsonObject
                    val host = info.string("host").orEmpty()
                    val extra = info.string("extra").orEmpty()
                    val composed = composeUrl(host, base, extra)
                    if (composed.isNotBlank()) addCandidate(composed, metadata, output)
                }
            }
        }
        obj.string("url")?.takeIf { it.isNotBlank() }?.let { addCandidate(it, metadata, output) }
        val backups = obj.get("backup_url") ?: obj.get("backupUrl")
        backups?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { backup ->
            backup.stringOrNull()?.takeIf { it.isNotBlank() }?.let { addCandidate(it, metadata, output) }
        }

        obj.entrySet().forEach { (key, child) ->
            if (key !in ROUTE_FIELDS) collectCandidates(child, metadata, output)
        }
    }

    private fun addCandidate(
        rawUrl: String,
        metadata: StreamMetadata,
        output: MutableList<LiveStreamCandidate>,
    ) {
        val url = rawUrl.trim().let { if (it.startsWith("//")) "https:$it" else it }
        val host = LogRedaction.host(url)
        if (host == "(invalid)" || host == "(empty)") return
        val path = url.substringBefore('?').lowercase()
        val pathCodec = when {
            "prohevc" in path || "hevc" in path || "h265" in path -> 12
            "proav1" in path || "av1" in path -> 13
            else -> 0
        }
        val codec = metadata.codecName.lowercase()
        val codecid = when {
            pathCodec > 0 -> pathCodec
            metadata.codecid > 0 -> metadata.codecid
            codec.contains("hevc") || codec.contains("h265") || codec.contains("hvc") -> 12
            codec.contains("avc") || codec.contains("h264") -> 7
            else -> 7
        }
        val format = metadata.format.lowercase().ifBlank {
            when {
                path.endsWith(".m3u8") -> "fmp4"
                path.endsWith(".flv") -> "flv"
                else -> "unknown"
            }
        }
        output += LiveStreamCandidate(
            url = url,
            quality = metadata.quality,
            format = format,
            codecName = when (codecid) {
                12 -> "HEVC"
                13 -> "AV1"
                else -> "AVC"
            },
            codecid = codecid,
            width = metadata.width,
            height = metadata.height,
            bandwidth = metadata.bandwidth,
            pcdn = CdnCandidateSelector.isPcdn(url),
            responseIndex = output.size,
        )
    }

    private fun composeUrl(host: String, base: String, extra: String): String {
        if (base.startsWith("https://") || base.startsWith("http://")) {
            return base + extra
        }
        val normalizedHost = when {
            host.startsWith("//") -> "https:$host"
            host.startsWith("http://") || host.startsWith("https://") -> host
            host.isNotBlank() -> "https://$host"
            else -> ""
        }
        if (normalizedHost.isBlank()) return ""
        val path = if (base.startsWith('/')) base else "/$base"
        val query = when {
            extra.isBlank() -> ""
            extra.startsWith('?') || extra.startsWith('&') -> extra
            else -> "?$extra"
        }
        return normalizedHost.trimEnd('/') + path + query
    }

    private fun JsonObject.array(name: String): JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.string(name: String, fallback: String = ""): String =
        get(name)?.stringOrNull() ?: fallback

    private fun JsonObject.int(name: String, fallback: Int = 0): Int =
        get(name)?.intOrNull() ?: fallback

    private fun JsonObject.long(name: String, fallback: Long = 0L): Long =
        get(name)?.longOrNull() ?: fallback

    private fun JsonElement.stringOrNull(): String? = runCatching {
        if (isJsonNull || isJsonObject || isJsonArray) null else asString
    }.getOrNull()

    private fun JsonElement.intOrNull(): Int? = stringOrNull()?.toIntOrNull()

    private fun JsonElement.longOrNull(): Long? = stringOrNull()?.toLongOrNull()

    private val ROUTE_FIELDS = setOf("url_info", "backup_url", "backupUrl")
}
