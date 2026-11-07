package com.roy233.tvcast.feature.subtitle.domain

import java.net.URI

/** 字幕正文地址的规范化与可信域名校验。 */
object SubtitleUrlPolicy {

    /** 只允许 B 站字幕域名。 */
    private val TRUSTED_HOSTS = listOf("hdslb.com", "bilibili.com")

    /** 可信地址的 path 必须包含该片段。 */
    private const val PATH_MARKER = "subtitle"

    /** 把服务端返回的字幕地址补齐为 https 绝对地址。 */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.isEmpty() -> ""
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://", ignoreCase = true) ->
                "https://" + trimmed.removePrefix("http://").removePrefix("HTTP://")
            else -> trimmed
        }
    }

    /** 判断规范化后的地址是否属于可信字幕域名。 */
    fun isTrusted(raw: String): Boolean {
        val normalized = normalize(raw)
        if (normalized.isBlank()) return false
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase().orEmpty()
        if (host.isBlank()) return false
        val trustedHost = TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }
        if (!trustedHost) return false
        return uri.path?.lowercase().orEmpty().contains(PATH_MARKER)
    }
}

/** 依据服务端字段与语言名称判断字幕来源。 */
object SubtitleOriginClassifier {

    /** `ai_status`：无 AI 信息。 */
    private const val AI_STATUS_NONE = 0

    /** `ai_status`：AI 曝光字幕。 */
    private const val AI_STATUS_EXPOSURE = 1

    /** `ai_status`：AI 辅助字幕。 */
    private const val AI_STATUS_ASSIST = 2

    /** 语言名称中出现这些片段时视为 AI 字幕。 */
    private val AI_LABEL_HINTS = listOf("ai", "自动", "机翻", "机器")

    /**
     * 分类字幕来源。
     *
     * @param aiStatus 服务端 `ai_status`。
     * @param aiType 服务端 `ai_type`。
     * @param lanDoc 服务端语言名称。
     */
    fun classify(aiStatus: Int, aiType: Int, lanDoc: String): SubtitleOrigin =
        when {
            aiStatus == AI_STATUS_ASSIST -> SubtitleOrigin.AI_ASSIST
            aiStatus == AI_STATUS_EXPOSURE -> SubtitleOrigin.AI_EXPOSURE
            aiType > 0 -> SubtitleOrigin.AI_UNKNOWN
            AI_LABEL_HINTS.any { lanDoc.contains(it, ignoreCase = true) } ->
                SubtitleOrigin.AI_UNKNOWN
            aiStatus == AI_STATUS_NONE -> SubtitleOrigin.MANUAL
            else -> SubtitleOrigin.AI_UNKNOWN
        }
}

/** 字幕轨道的展示文案。 */
object SubtitleLabels {

    /** 轨道主标题：语言名称，AI 轨道额外标注 AI。 */
    fun trackTitle(track: SubtitleTrack): String {
        val base = track.lanDoc.trim().ifBlank { track.lan.trim() }
        val label = base.ifBlank { "未知字幕" }
        if (!track.origin.isAi) return label
        if (AI_MARKERS.any { label.contains(it, ignoreCase = true) }) return label
        return "$label · AI"
    }

    /** 轨道副标题：来源说明。 */
    fun trackDetail(track: SubtitleTrack): String {
        val origin = if (track.origin.isAi) "AI 字幕" else "人工字幕"
        val language = track.lan.trim()
        val locked = if (track.locked) " · 不可用" else ""
        return if (language.isBlank()) "$origin$locked" else "$language · $origin$locked"
    }

    private val AI_MARKERS = listOf("ai", "自动", "机翻", "机器")
}

/** 字幕轨道的稳定排序规则。 */
object SubtitleTrackOrder {

    /** 中文语言代码前缀。 */
    private const val CHINESE_PREFIX = "zh"

    /**
     * 排序轨道：人工优先、中文优先、用户偏好语言优先，最后按 id 与名称稳定排序。
     *
     * 返回完整列表，不裁剪任何 AI 轨道。
     *
     * @param tracks 服务端返回的轨道。
     * @param preferredLan 本地偏好语言，可为空。
     */
    fun order(tracks: List<SubtitleTrack>, preferredLan: String? = null): List<SubtitleTrack> =
        tracks.sortedWith(
            compareByDescending<SubtitleTrack> { !it.origin.isAi }
                .thenByDescending { it.lan.startsWith(CHINESE_PREFIX, ignoreCase = true) }
                .thenByDescending { matched(it, preferredLan) }
                .thenByDescending { it.id }
                .thenBy { it.lanDoc },
        )

    private fun matched(track: SubtitleTrack, preferredLan: String?): Boolean {
        val preferred = preferredLan?.trim().orEmpty()
        if (preferred.isBlank()) return false
        return track.lan.equals(preferred, ignoreCase = true)
    }
}

/** 默认字幕轨道选择规则。 */
object SubtitleSelection {

    /**
     * 选择默认轨道。
     *
     * 人工字幕始终优先；只有在没有任何可选人工字幕且允许自动 AI 时，才启用 AI 轨道。
     *
     * @param tracks 已排序的轨道列表。
     * @param settings 当前字幕设置。
     */
    fun defaultTrack(tracks: List<SubtitleTrack>, settings: SubtitleSettings): SubtitleTrack? {
        if (!settings.enabled) return null
        val selectable = tracks.filterNot { it.locked }
        selectable
            .firstOrNull { !it.origin.isAi }
            ?.let {
                return it
            }
        return if (settings.autoAiEnabled) selectable.firstOrNull() else null
    }
}

/** 按播放位置解析当前应显示的字幕行。 */
object SubtitleCueLookup {

    /** 同时显示的最大行数。 */
    const val MAX_LINES = 2

    /** 向前回溯的最大条数，避免长时间空隙导致整段扫描。 */
    private const val MAX_LOOKBEHIND = 8

    /**
     * 解析指定位置的字幕行。
     *
     * 命中规则为左闭右开：`startMs <= positionMs < endMs`。
     *
     * @param cues 按开始时间升序排列的字幕。
     * @param positionMs 已应用时间偏移的播放位置。
     * @return 按开始时间排列的字幕正文；无命中时为空。
     */
    fun resolve(cues: List<SubtitleCue>, positionMs: Long): List<String> {
        if (cues.isEmpty()) return emptyList()
        val anchor = lastStartedAt(cues, positionMs)
        if (anchor < 0) return emptyList()
        val lines = ArrayList<String>(MAX_LINES)
        var index = anchor
        var examined = 0
        while (index >= 0 && lines.size < MAX_LINES && examined < MAX_LOOKBEHIND) {
            val cue = cues[index]
            if (positionMs < cue.endMs) lines.add(0, cue.text)
            index--
            examined++
        }
        return lines
    }

    /** 二分定位最后一个开始时间不晚于当前位置的下标。 */
    private fun lastStartedAt(cues: List<SubtitleCue>, positionMs: Long): Int {
        var low = 0
        var high = cues.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}
