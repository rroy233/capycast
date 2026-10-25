package com.roy233.tvcast.feature.playback.presentation

import com.roy233.tvcast.data.bili.BiliApi
import com.roy233.tvcast.ui.model.AudioOption
import com.roy233.tvcast.ui.model.TrackOption

/** 将已解析的播放轨道转换为电视端选择项。 */
object TrackOptionMapper {

    /**
     * 构造去重并排序后的视频清晰度选项。
     *
     * @param info 已解析的播放信息。
     * @return 可供电视端选择的清晰度列表。
     */
    fun tracks(info: BiliApi.PlayInfo): List<TrackOption> {
        val seen = mutableSetOf<Int>()
        val options = info.videos
            .filter { it.codecid == 7 || it.codecid == 12 }
            .sortedWith(
                compareByDescending<BiliApi.Track> { it.id }
                    .thenByDescending { it.width.toLong() * it.height }
                    .thenBy { if (it.codecid == 7) 0 else 1 }
            )
            .filter { seen.add(it.id) }
            .map {
                TrackOption(
                    qn = it.id,
                    label = info.qualityLabelFor(it.id),
                    detail = BiliApi.trackDetail(it),
                    codecid = it.codecid,
                )
            }
        if (options.isNotEmpty()) return options
        return info.videos.map {
            TrackOption(
                qn = it.id,
                label = info.qualityLabelFor(it.id),
                detail = BiliApi.trackDetail(it),
                codecid = it.codecid,
            )
        }.distinctBy { it.qn }
    }

    /** 构造去重并按标识排序后的音轨选项。 */
    fun audios(info: BiliApi.PlayInfo): List<AudioOption> = info.audios
        .sortedByDescending { it.id }
        .map { AudioOption(it.id, BiliApi.audioLabelFor(it.id)) }
        .distinctBy { it.id }
}
