package com.roy233.tvcast.core.danmaku

/**
 * 弹幕显示设置。
 *
 * 本期过滤只有智能云屏蔽；其余字段都是显示参数，不涉及类型/关键词/用户过滤。
 *
 * @property enabled 是否显示弹幕。
 * @property aiShield 智能云屏蔽档位。
 * @property opacity 不透明度，0.2–1.0。
 * @property fontScale 字号缩放，0.5–2.0。
 * @property durationSec 滚动弹幕穿过屏幕的媒体时长，单位秒。
 * @property areaRatio 显示区域占视频内容高度的比例，0.25–1.0。
 * @property maxOnScreen 同屏最大条数；0 表示不限制。
 */
data class DanmakuSettings(
    val enabled: Boolean = true,
    val aiShield: AiShieldLevel = AiShieldLevel.OFF,
    val opacity: Float = 1f,
    val fontScale: Float = 1f,
    val durationSec: Float = 8f,
    val areaRatio: Float = 0.25f,
    val maxOnScreen: Int = 60,
)
