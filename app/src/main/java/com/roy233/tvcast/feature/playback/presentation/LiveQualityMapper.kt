package com.roy233.tvcast.feature.playback.presentation

import com.roy233.tvcast.data.bili.LivePlayInfo
import com.roy233.tvcast.data.bili.RankedLiveSource
import com.roy233.tvcast.ui.model.LiveQualityOption

/**
 * 直播画质名称与线路排序的展示映射。
 *
 * 全部为纯函数，名称、过滤条件与排序键与原 `MainActivity` 实现保持一致。
 */
object LiveQualityMapper {

    /** 直播 qn 与点播 qn 数值体系不同，使用直播清晰度名称。 */
    fun label(qn: Int): String = when (qn) {
        10_000 -> "原画"
        400 -> "蓝光"
        250 -> "超清"
        150 -> "高清"
        80 -> "流畅"
        else -> "直播 qn=$qn"
    }

    /** 直播可选画质档位，取服务端公布的可用档位并映射为友好名称。 */
    fun options(stream: LivePlayInfo): List<LiveQualityOption> {
        val available = (stream.acceptedQualities + stream.quality)
            .distinct()
            .sortedDescending()
        return available.mapNotNull { qn ->
            val name = label(qn)
            if (name.startsWith("直播 qn=")) null else LiveQualityOption(qn = qn, label = name)
        }
    }

    /** 直播线路探测结果的排序：成功优先、首包耗时、吞吐，最后回退到候选响应顺序。 */
    fun probeOrder(): Comparator<RankedLiveSource> =
        compareBy<RankedLiveSource> { it.result?.success != true }
            .thenBy { it.result?.firstByteMs ?: Long.MAX_VALUE }
            .thenByDescending { it.result?.throughputBytesPerSecond ?: 0L }
            .thenBy { it.candidate.responseIndex }
}
