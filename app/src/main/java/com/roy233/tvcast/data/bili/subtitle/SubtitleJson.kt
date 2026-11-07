package com.roy233.tvcast.data.bili.subtitle

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.feature.subtitle.domain.SubtitleContent
import com.roy233.tvcast.feature.subtitle.domain.SubtitleCue
import com.roy233.tvcast.feature.subtitle.domain.SubtitleOriginClassifier
import com.roy233.tvcast.feature.subtitle.domain.SubtitleTrack
import com.roy233.tvcast.feature.subtitle.domain.SubtitleUrlPolicy
import kotlin.math.roundToLong

/** 把 B 站字幕 JSON 映射为字幕模型。 */
internal object SubtitleJson {

    /** 日志标签。 */
    private const val TAG = "BiliSubtitle"

    /** 服务端以秒为单位的字幕时间需要换算为该倍率。 */
    private const val MILLIS_PER_SECOND = 1000.0

    /** 无法解析出有效结束时间时的最短持续时长，单位毫秒。 */
    private const val MIN_CUE_DURATION_MS = 100L

    /**
     * 解析 `x/player/wbi/v2` 的字幕轨道列表。
     *
     * 地址不可信的轨道会被丢弃，避免向非 B 站域名发起请求。
     *
     * @param raw 接口响应。
     * @param content 轨道所属内容。
     */
    fun tracks(raw: String, content: SubtitleContent): List<SubtitleTrack> {
        val data =
            runCatching { JsonParser.parseString(raw).asJsonObject.objectOrNull("data") }
                .getOrNull() ?: return emptyList()
        val subtitles =
            data.objectOrNull("subtitle")?.arrayOrNull("subtitles") ?: return emptyList()
        return subtitles.mapNotNull { element ->
            val item = element.asJsonObjectOrNull() ?: return@mapNotNull null
            val url = SubtitleUrlPolicy.normalize(item.string("subtitle_url"))
            if (!SubtitleUrlPolicy.isTrusted(url)) {
                LogHub.w(TAG, "drop untrusted subtitle url host=${LogRedaction.host(url)}")
                return@mapNotNull null
            }
            val lanDoc = item.string("lan_doc")
            SubtitleTrack(
                id = item.long("id"),
                idStr = item.string("id_str"),
                lan = item.string("lan"),
                lanDoc = lanDoc,
                url = url,
                origin =
                    SubtitleOriginClassifier.classify(
                        aiStatus = item.int("ai_status"),
                        aiType = item.int("ai_type"),
                        lanDoc = lanDoc,
                    ),
                type = item.int("type"),
                locked = item.bool("is_lock"),
            )
        }
    }

    /**
     * 解析字幕正文 `body`。
     *
     * 时间单位是秒；空正文与非法区间会被丢弃，结果按开始时间升序排列。
     *
     * @param raw 字幕正文响应。
     */
    fun cues(raw: String): List<SubtitleCue> {
        val body =
            runCatching { JsonParser.parseString(raw).asJsonObject.arrayOrNull("body") }.getOrNull()
                ?: return emptyList()
        return body
            .mapNotNull { element ->
                val item = element.asJsonObjectOrNull() ?: return@mapNotNull null
                val text = item.string("content").trim()
                if (text.isEmpty()) return@mapNotNull null
                val startMs = item.double("from").toMillis().coerceAtLeast(0L)
                val endMs =
                    item.double("to").toMillis().coerceAtLeast(startMs + MIN_CUE_DURATION_MS)
                SubtitleCue(startMs = startMs, endMs = endMs, text = text)
            }
            .sortedBy { it.startMs }
    }

    private fun Double.toMillis(): Long = (this * MILLIS_PER_SECOND).roundToLong()

    private fun JsonObject.string(name: String): String = runCatching {
        get(name)?.asString
    }
        .getOrNull()
        .orEmpty()

    private fun JsonObject.long(name: String): Long =
        runCatching { get(name)?.asLong }.getOrNull() ?: 0L

    private fun JsonObject.int(name: String): Int =
        runCatching { get(name)?.asInt }.getOrNull() ?: 0

    private fun JsonObject.double(name: String): Double =
        runCatching { get(name)?.asDouble }.getOrNull() ?: 0.0

    private fun JsonObject.bool(name: String): Boolean =
        runCatching { get(name)?.asBoolean }.getOrNull() ?: false

    private fun JsonObject.objectOrNull(name: String): JsonObject? = runCatching {
        get(name)?.asJsonObject
    }
        .getOrNull()

    private fun JsonObject.arrayOrNull(name: String): JsonArray? = runCatching {
        get(name)?.asJsonArray
    }
        .getOrNull()

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? = runCatching {
        asJsonObject
    }
        .getOrNull()
}
