package com.roy233.tvcast.data.bili.mapper

import com.roy233.tvcast.core.playback.MediaTrack
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.data.bili.BiliApi

/** 将 B 站播放 DTO 转换为播放器契约；字段映射保持一对一。 */
object BiliPlaybackMapper {
    fun map(info: BiliApi.PlayInfo): PlaybackRequest = PlaybackRequest(
        title = info.title,
        bvid = info.bvid,
        aid = info.aid,
        cid = info.cid,
        durationSec = info.durationSec,
        video = mapTrack(info.video),
        audio = info.audio?.let(::mapTrack),
        progressiveUrl = info.progressiveUrl,
        videos = info.videos.map(::mapTrack),
        audios = info.audios.map(::mapTrack),
        cover = info.cover,
        isLive = info.isLive,
        liveFormat = info.liveFormat,
        liveCdn = info.liveCdn,
        liveBackupCount = info.liveBackupCount,
        isPgc = info.isPgc,
        epId = info.epId,
        seasonId = info.seasonId,
        hasPaid = info.hasPaid,
        isDrm = info.isDrm,
        isPreview = info.isPreview,
        actualQuality = info.actualQuality,
        acceptedQualities = info.acceptQualities,
        qualityLabels = info.qualityLabels,
        userAgent = BiliApi.UA,
    )

    fun mapTrack(track: BiliApi.Track) = MediaTrack(
        id = track.id,
        codecId = track.codecid,
        width = track.width,
        height = track.height,
        bandwidth = track.bandwidth,
        url = track.url,
        backupUrls = track.backup,
        isPcdn = track.pcdn,
        mimeType = track.mimeType,
        codecs = track.codecs,
        isHdr = track.isHdr,
        isDolbyVision = track.isDolbyVision,
        isDolbyAudio = track.isDolbyAudio,
        initializationRange = track.initializationRange,
        indexRange = track.indexRange,
        psshBase64 = track.psshBase64,
    )
}
