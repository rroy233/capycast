package com.roy233.tvcast.feature.subtitle.presentation

import com.roy233.tvcast.feature.subtitle.application.SubtitleAvailability
import com.roy233.tvcast.feature.subtitle.application.SubtitleSessionState
import com.roy233.tvcast.feature.subtitle.domain.SubtitleLabels
import com.roy233.tvcast.ui.model.SubtitleTrackOption

/** 把字幕会话状态映射为界面选项，保持既有顺序不变。 */
object SubtitleTrackMapper {

    /** 生成面板中的轨道列表。 */
    fun options(state: SubtitleSessionState): List<SubtitleTrackOption> =
        state.tracks.map { track ->
            SubtitleTrackOption(
                key = track.key,
                label = SubtitleLabels.trackTitle(track),
                detail = SubtitleLabels.trackDetail(track),
                ai = track.origin.isAi,
                locked = track.locked,
                selected = state.selected?.key == track.key,
            )
        }

    /** 播放设置面板中显示的字幕摘要。 */
    fun summary(state: SubtitleSessionState): String {
        state.selected?.let {
            return SubtitleLabels.trackTitle(it)
        }
        return when {
            !state.enabled -> "已关闭"
            state.availability == SubtitleAvailability.LOADING -> "加载中"
            state.availability == SubtitleAvailability.UNAVAILABLE -> "不可用"
            state.availability == SubtitleAvailability.EMPTY -> "无字幕"
            state.tracks.isEmpty() -> "无字幕"
            else -> "关闭"
        }
    }
}
