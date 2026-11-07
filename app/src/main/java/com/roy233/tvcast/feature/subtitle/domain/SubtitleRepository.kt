package com.roy233.tvcast.feature.subtitle.domain

/** 字幕数据缓存端口。 */
interface SubtitleRepository {
    /** 读取某个视频的全部可用字幕轨道，含人工与 AI。 */
    suspend fun tracks(content: SubtitleContent): List<SubtitleTrack>

    /** 读取指定轨道的完整正文。 */
    suspend fun document(content: SubtitleContent, track: SubtitleTrack): SubtitleDocument
}
