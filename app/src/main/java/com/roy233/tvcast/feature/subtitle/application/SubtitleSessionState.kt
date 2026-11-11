package com.roy233.tvcast.feature.subtitle.application

import com.roy233.tvcast.feature.subtitle.domain.SubtitleContent
import com.roy233.tvcast.feature.subtitle.domain.SubtitleSettings
import com.roy233.tvcast.feature.subtitle.domain.SubtitleTrack

/** 字幕会话的可用状态。 */
enum class SubtitleAvailability {
    /** 正在拉取轨道列表。 */
    LOADING,
    /** 轨道列表可用。 */
    READY,
    /** 接口成功但没有可用轨道。 */
    EMPTY,
    /** 请求失败，播放继续，仅字幕不可用。 */
    UNAVAILABLE,
}

/**
 * 字幕会话当前状态，由宿主映射为界面状态。
 *
 * @property content 会话所属内容。
 * @property tracks 已排序的完整轨道列表。
 * @property selected 当前选中的轨道；空表示字幕关闭。
 * @property availability 轨道可用状态。
 * @property enabled 设置中的字幕开关。
 */
data class SubtitleSessionState(
    val content: SubtitleContent,
    val tracks: List<SubtitleTrack>,
    val selected: SubtitleTrack?,
    val availability: SubtitleAvailability,
    val enabled: Boolean,
)

/** 字幕会话对宿主的输出端口。 */
interface SubtitleSessionHost {
    /** 发布会话状态；宿主负责把它投影为界面状态。 */
    fun publishSubtitleSession(state: SubtitleSessionState)

    /** 持久化用户操作产生的字幕设置。 */
    fun persistSubtitleSettings(settings: SubtitleSettings)
}
