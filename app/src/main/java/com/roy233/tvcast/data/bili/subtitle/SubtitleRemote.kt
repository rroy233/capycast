package com.roy233.tvcast.data.bili.subtitle

import com.roy233.tvcast.feature.subtitle.domain.SubtitleContent
import com.roy233.tvcast.feature.subtitle.domain.SubtitleCue
import com.roy233.tvcast.feature.subtitle.domain.SubtitleTrack

/** 字幕远程数据源；仓库负责缓存与调度，实现只负责发请求。 */
interface SubtitleRemote {
    /** 拉取视频的全部可用字幕轨道。 */
    suspend fun fetchTracks(content: SubtitleContent): List<SubtitleTrack>

    /** 拉取指定轨道的完整正文。 */
    suspend fun fetchCues(track: SubtitleTrack): List<SubtitleCue>
}
