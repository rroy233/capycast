package com.roy233.tvcast.feature.playback.presentation

import com.roy233.tvcast.core.playback.MediaTrack
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.ui.model.AudioOption
import com.roy233.tvcast.ui.model.TrackOption

/** 将已解析的播放轨道转换为电视端选择项。 */
object TrackOptionMapper {

    /**
     * 构造去重并排序后的视频清晰度选项。
     *
     * @param request 播放器无关的播放请求。
     * @return 可供电视端选择的清晰度列表。
     */
    fun tracks(request: PlaybackRequest): List<TrackOption> {
        val seen = mutableSetOf<Int>()
        val options = request.videos
            .filter { it.codecId == 7 || it.codecId == 12 }
            .sortedWith(
                compareByDescending<MediaTrack> { it.id }
                    .thenByDescending { it.width.toLong() * it.height }
                    .thenBy { if (it.codecId == 7) 0 else 1 }
            )
            .filter { seen.add(it.id) }
            .map {
                TrackOption(
                    qn = it.id,
                    label = request.qualityLabels[it.id].orEmpty().ifBlank { qualityLabel(it.id) },
                    detail = trackDetail(it),
                    codecid = it.codecId,
                )
            }
        if (options.isNotEmpty()) return options
        return request.videos.map {
            TrackOption(
                qn = it.id,
                label = request.qualityLabels[it.id].orEmpty().ifBlank { qualityLabel(it.id) },
                detail = trackDetail(it),
                codecid = it.codecId,
            )
        }.distinctBy { it.qn }
    }

    /** 构造去重并按标识排序后的音轨选项。 */
    fun audios(request: PlaybackRequest): List<AudioOption> = request.audios
        .sortedByDescending { it.id }
        .map { AudioOption(it.id, audioLabel(it.id)) }
        .distinctBy { it.id }

    private fun qualityLabel(qn: Int): String = when (qn) {
        127 -> "8K"
        126 -> "Dolby"
        125 -> "HDR"
        120 -> "4K"
        116 -> "1080P60"
        112 -> "1080P+"
        80 -> "1080P"
        74 -> "720P60"
        64 -> "720P"
        32 -> "480P"
        16 -> "360P"
        10000 -> "原画"
        400 -> "蓝光"
        250 -> "超清"
        150 -> "高清"
        else -> "qn=$qn"
    }

    private fun audioLabel(id: Int): String = when (id) {
        30250 -> "Dolby Atmos"
        30280 -> "192K"
        30232 -> "132K"
        30216 -> "64K"
        else -> "audio=$id"
    }

    private fun trackDetail(track: MediaTrack): String {
        if (track.width <= 0 || track.height <= 0) return "MP4"
        val codec = when (track.codecId) {
            12 -> "HEVC"
            13 -> "AV1"
            7 -> "H.264"
            else -> "codec ${track.codecId}"
        }
        val dynamicRange = when {
            track.isDolbyVision -> " · Dolby Vision"
            track.isHdr -> " · HDR"
            else -> ""
        }
        return "${track.width}x${track.height} · $codec$dynamicRange"
    }
}
