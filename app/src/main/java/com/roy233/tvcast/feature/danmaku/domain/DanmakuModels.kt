package com.roy233.tvcast.feature.danmaku.domain

/**
 * 一条视频弹幕。
 *
 * 字段含义与 B 站 `DanmakuElem` 对齐；`progressMs` 是毫秒。
 *
 * @property id 弹幕 dmid，用于去重和举报。
 * @property progressMs 出现时间，单位毫秒。
 * @property mode 1/2/3 右→左滚动，4 底部，5 顶部，6 左→右，7/8/9 特殊。
 * @property fontSize 18 小 / 25 标准 / 36 大。
 * @property color RGB888。
 * @property midHash 发送者 mid 的 hash，用于屏蔽用户。
 * @property content 文本。
 * @property weight 智能云屏蔽权重，0–10。
 * @property pool 0 普通 / 1 字幕 / 2 特殊。
 * @property attr 属性位：bit0 保护、bit1 直播、bit2 高赞。
 * @property ctime 发送时间戳，单位秒。
 */
data class DanmakuItem(
    val id: Long,
    val progressMs: Int,
    val mode: Int,
    val fontSize: Int,
    val color: Int,
    val midHash: String,
    val content: String,
    val weight: Int,
    val pool: Int,
    val attr: Int,
    val ctime: Long,
)

/**
 * 一个 6 分钟分段（`DmSegMobileReply`）的解码结果。
 *
 * @property items 弹幕条目。
 * @property state 0 未关闭 / 1 已关闭。
 * @property aiFlags 按 dmid 索引的智能云屏蔽评分。
 */
data class DmSegment(
    val items: List<DanmakuItem>,
    val state: Int,
    val aiFlags: Map<Long, Int>,
)

/**
 * 分段配置（`DmSegConfig`）。
 *
 * @property pageSizeMs 每段时长，通常 360000。
 * @property total 分段总数。
 */
data class DmSegConfig(
    val pageSizeMs: Long,
    val total: Int,
)

/**
 * 用户弹幕播放配置（`DanmuWebPlayerConfig`）。
 *
 * 字段为 null 表示服务端没有返回，调用方应回退到本地/默认值。
 *
 * @property dmSwitch 是否开启弹幕。
 * @property aiSwitch 是否开启智能云屏蔽。
 * @property aiLevel 智能云屏蔽等级，0 表示默认等级 3，-1 表示关闭。
 * @property blockTop 是否屏蔽顶部弹幕。
 * @property blockScroll 是否屏蔽滚动弹幕。
 * @property blockBottom 是否屏蔽底部弹幕。
 * @property blockColor 是否屏蔽彩色弹幕。
 * @property blockSpecial 是否屏蔽特殊弹幕。
 * @property opacity 不透明度，0–1。
 * @property dmArea 显示区域，0/25/50/75/100。
 * @property speedPlus 速度倍率，0.4–1.6。
 * @property fontSize 字号倍率，0.4–1.6。
 * @property preventShade 是否防挡底部 15%。
 * @property dmask 是否启用智能防挡蒙版。
 * @property bold 是否加粗。
 * @property fontBorder 描边类型：0 重墨 / 1 描边 / 2 45° 投影。
 */
data class DanmakuPlayerConfig(
    val dmSwitch: Boolean? = null,
    val aiSwitch: Boolean? = null,
    val aiLevel: Int? = null,
    val blockTop: Boolean? = null,
    val blockScroll: Boolean? = null,
    val blockBottom: Boolean? = null,
    val blockColor: Boolean? = null,
    val blockSpecial: Boolean? = null,
    val opacity: Float? = null,
    val dmArea: Int? = null,
    val speedPlus: Float? = null,
    val fontSize: Float? = null,
    val preventShade: Boolean? = null,
    val dmask: Boolean? = null,
    val bold: Boolean? = null,
    val fontBorder: Int? = null,
)

/**
 * 弹幕元数据（`DmWebViewReply`）中本应用需要的字段。
 *
 * @property state 0 未关闭 / 1 已关闭。
 * @property count 弹幕总数。
 * @property segConfig 分段配置。
 * @property playerConfig 用户弹幕播放配置。
 */
data class DmViewInfo(
    val state: Int,
    val count: Long,
    val segConfig: DmSegConfig?,
    val playerConfig: DanmakuPlayerConfig?,
)
