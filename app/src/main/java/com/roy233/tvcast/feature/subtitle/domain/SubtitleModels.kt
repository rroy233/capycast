package com.roy233.tvcast.feature.subtitle.domain

/**
 * 点播字幕的数据身份。
 *
 * 字幕轨道与正文只属于一个视频；跨会话复用缓存必须按完整身份比较， 不能只比较 cid。
 *
 * @property aid 稿件 avid；未知时为 0。
 * @property cid 视频 cid。
 * @property bvid 稿件 bvid；未知时为空。轨道接口以 bvid + cid 定位视频。
 * @property epId 番剧分集标识；非番剧为 0。
 */
data class SubtitleContent(
    val aid: Long,
    val cid: Long,
    val bvid: String = "",
    val epId: Long = 0L,
)

/** 字幕轨道的来源，用于区分人工字幕与 AI 字幕。 */
enum class SubtitleOrigin {
    /** 人工制作的字幕。 */
    MANUAL,
    /** AI 辅助生成的字幕（`ai_status` = Assist）。 */
    AI_ASSIST,
    /** AI 曝光用字幕（`ai_status` = Exposure）。 */
    AI_EXPOSURE,
    /** 带 AI 标记但服务端未给出明确状态。 */
    AI_UNKNOWN;

    /** 是否为 AI 生成的字幕。 */
    val isAi: Boolean
        get() = this != MANUAL
}

/**
 * 一条可选字幕轨道的身份。
 *
 * 语言代码可能在不同轨道间重复，因此键里同时包含服务端 id 与规范化后的正文地址。
 *
 * @property id 服务端字幕 id。
 * @property idStr 服务端字幕字符串 id。
 * @property lan 语言代码，例如 `zh-Hans`。
 * @property url 规范化后的正文地址。
 */
data class SubtitleTrackKey(
    val id: Long,
    val idStr: String,
    val lan: String,
    val url: String,
)

/**
 * 一条可选字幕轨道。
 *
 * @property id 服务端字幕 id。
 * @property idStr 服务端字幕字符串 id。
 * @property lan 语言代码。
 * @property lanDoc 服务端语言名称。
 * @property url 规范化后的正文地址。
 * @property origin 字幕来源。
 * @property type 服务端轨道类型。
 * @property locked 服务端标记为不可选择的轨道。
 */
data class SubtitleTrack(
    val id: Long,
    val idStr: String,
    val lan: String,
    val lanDoc: String,
    val url: String,
    val origin: SubtitleOrigin,
    val type: Int,
    val locked: Boolean,
) {
    /** 轨道身份。 */
    val key: SubtitleTrackKey
        get() = SubtitleTrackKey(id = id, idStr = idStr, lan = lan, url = url)
}

/**
 * 一条按时间轴出现的字幕。
 *
 * @property startMs 出现时间，单位毫秒。
 * @property endMs 消失时间，单位毫秒，始终大于 [startMs]。
 * @property text 字幕正文。
 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * 一条轨道解析后的完整正文。
 *
 * @property trackKey 所属轨道。
 * @property cues 按 [SubtitleCue.startMs] 升序排列的字幕。
 */
data class SubtitleDocument(
    val trackKey: SubtitleTrackKey,
    val cues: List<SubtitleCue>,
)
