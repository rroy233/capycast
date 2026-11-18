package com.roy233.tvcast.feature.subtitle.presentation

import com.roy233.tvcast.feature.subtitle.application.SubtitleAvailability
import com.roy233.tvcast.feature.subtitle.application.SubtitleSessionState
import com.roy233.tvcast.feature.subtitle.domain.SubtitleLabels
import com.roy233.tvcast.ui.model.SubtitleSummary
import com.roy233.tvcast.ui.model.SubtitleSummaryKind
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
    fun summary(state: SubtitleSessionState): SubtitleSummary {
        state.selected?.let {
            return SubtitleSummary(SubtitleSummaryKind.SELECTED, SubtitleLabels.trackTitle(it))
        }
        val kind =
            when {
                !state.enabled -> SubtitleSummaryKind.DISABLED
                state.availability == SubtitleAvailability.LOADING -> SubtitleSummaryKind.LOADING
                state.availability == SubtitleAvailability.UNAVAILABLE ->
                    SubtitleSummaryKind.UNAVAILABLE
                state.availability == SubtitleAvailability.EMPTY || state.tracks.isEmpty() ->
                    SubtitleSummaryKind.EMPTY
                else -> SubtitleSummaryKind.OFF
            }
        return SubtitleSummary(kind)
    }
}
