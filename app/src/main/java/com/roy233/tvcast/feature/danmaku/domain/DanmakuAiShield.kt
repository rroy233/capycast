package com.roy233.tvcast.feature.danmaku.domain

import kotlin.math.ceil

/**
 * 智能云屏蔽档位。
 *
 * 按分段内 `weight` 排名隐藏最低的一部分，而不是使用固定的 0–10 阈值：
 * 实测 web 接口的 `weight` 是 8–11，固定 3/6/9 基本不会生效。
 *
 * @property hiddenRatio 隐藏比例，0 表示关闭。
 */
enum class AiShieldLevel(val hiddenRatio: Double) {
    OFF(0.0),
    LOW(0.10),
    MEDIUM(0.30),
    HIGH(0.60),
}

/**
 * 智能云屏蔽过滤器。
 *
 * B 站服务端给每条弹幕一个 AI 权重（`DanmakuElem.weight`），本类按权重
 * 从低到高隐藏指定比例。同一分段内权重相同的条目按 dmid 稳定排序，
 * 保证结果可复现。
 */
object DanmakuAiShield {

    /**
     * 过滤一个分段。
     *
     * @param items 原始条目。
     * @param level 档位。
     * @return 过滤后的条目；[AiShieldLevel.OFF] 时原样返回。
     */
    fun filter(items: List<DanmakuItem>, level: AiShieldLevel): List<DanmakuItem> {
        if (level == AiShieldLevel.OFF || items.isEmpty()) return items
        val hideCount = ceil(items.size * level.hiddenRatio).toInt().coerceIn(0, items.size)
        if (hideCount == 0) return items

        val hidden = HashSet<Long>(hideCount)
        items.asSequence()
            .sortedWith(compareBy<DanmakuItem> { it.weight }.thenBy { it.id })
            .take(hideCount)
            .forEach { hidden += it.id }

        return items.filterNot { it.id in hidden }
    }
}
