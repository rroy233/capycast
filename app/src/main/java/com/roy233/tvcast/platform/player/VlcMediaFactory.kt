package com.roy233.tvcast.platform.player

import androidx.core.net.toUri
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.core.playback.PlaybackRequest
import com.roy233.tvcast.core.playback.DEFAULT_PLAYBACK_USER_AGENT
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media

/**
 * 根据解析后的播放信息构建 libVLC 媒体，并记录媒体选项和候选轨道。
 *
 * 构建失败时会把已经创建的 [Media] 释放后重新抛出，避免打开失败路径泄漏
 * 原生媒体对象。
 *
 * @param libVlc 用于创建媒体的 libVLC 实例。
 */
internal class VlcMediaFactory(private val libVlc: LibVLC) {

    /**
     * 创建并配置媒体。
     *
     * @param info 已解析的播放信息。
     * @param seekToMs 开始播放前需要跳转的位置，单位为毫秒。
     * @return 已经配置好的媒体；调用方负责持有和释放。
     */
    fun create(info: PlaybackRequest, seekToMs: Long): Media {
        PlaybackDiagnostics.ensureSession("player-open")
        PlaybackDiagnostics.state("opening")
        PlaybackDiagnostics.attribute("player.seekToMs", seekToMs.coerceAtLeast(0L).toString())
        logOpenSummary(info, seekToMs)

        val url = info.progressiveUrl ?: info.video.url
        require(url.isNotBlank()) { "empty media url" }
        val media = Media(libVlc, url.toUri())
        try {
            val hardwareResult = runCatching { media.setHWDecoderEnabled(true, false) }
            if (hardwareResult.isFailure) {
                LogHub.w(
                    TAG,
                    "setHWDecoderEnabled failed: " +
                        LogRedaction.reason(hardwareResult.exceptionOrNull()),
                )
            }
            if (info.progressiveUrl == null) {
                val audioUrl = info.audio?.url.orEmpty()
                if (audioUrl.isNotBlank()) {
                    media.addOption(":input-slave=$audioUrl")
                    LogHub.i(TAG, "audio slave attached url=${LogRedaction.url(audioUrl)}")
                } else {
                    LogHub.w(TAG, "dash media has no audio track")
                }
            }
            if (seekToMs > 0L) {
                media.addOption(":start-time=${seekToMs / 1000.0}")
                LogHub.i(TAG, "media start-time=${seekToMs}ms")
            }
            media.addOption(":http-referrer=https://www.bilibili.com/")
            media.addOption(":http-user-agent=${DEFAULT_PLAYBACK_USER_AGENT}")
            media.addOption(":network-caching=2000")
            LogHub.i(
                TAG,
                "media options hwDecoder=mediacodec forcedFlush=false " +
                    "referrer=bilibili ua=${DEFAULT_PLAYBACK_USER_AGENT.take(32)}… networkCaching=2000",
            )
            return media
        } catch (t: Throwable) {
            runCatching { media.release() }
            throw t
        }
    }

    /**
     * 记录一次打开媒体的完整摘要。
     *
     * @param info 已解析的播放信息。
     * @param seekToMs 开始播放前需要跳转的位置，单位为毫秒。
     */
    private fun logOpenSummary(info: PlaybackRequest, seekToMs: Long) {
        val source = if (info.progressiveUrl == null) "dash" else "progressive"
        val video = info.video
        LogHub.i(
            TAG,
            "open source=$source title=${info.title.take(40)} " +
                "qn=${video.id}(${info.qualityLabel}) codec=${VlcTrackFormat.codecName(video.codecId)} " +
                "${video.width}x${video.height} bw=${video.bandwidth} " +
                "audio=${info.audio?.id}(${info.audioLabel}) seek=${seekToMs}ms " +
                "videos=${info.videos.size} audios=${info.audios.size} backups=${video.backupUrls.size}",
        )
        val videoUrl = info.progressiveUrl ?: video.url
        val safeVideoUrl = if (info.isLive) LogRedaction.host(videoUrl) else LogRedaction.url(videoUrl)
        LogHub.i(TAG, "open videoUrl=$safeVideoUrl")
        info.audio?.let { audio ->
            LogHub.i(TAG, "open audioUrl=${LogRedaction.url(audio.url)}")
        }
        if (video.backupUrls.isNotEmpty()) {
            LogHub.i(
                TAG,
                "open backupHosts=" +
                    video.backupUrls.take(4).joinToString(",") { LogRedaction.host(it) },
            )
        }
        PlaybackDiagnostics.attribute("stream.source", source)
        PlaybackDiagnostics.attribute("stream.title", info.title)
        PlaybackDiagnostics.attribute("stream.bvid", info.bvid)
        PlaybackDiagnostics.attribute("stream.aid", info.aid.toString())
        PlaybackDiagnostics.attribute("stream.cid", info.cid.toString())
        PlaybackDiagnostics.attribute("stream.durationSec", info.durationSec.toString())
        PlaybackDiagnostics.attribute("stream.qn", video.id.toString())
        PlaybackDiagnostics.attribute("stream.qnLabel", info.qualityLabel)
        PlaybackDiagnostics.attribute("stream.codec", VlcTrackFormat.codecName(video.codecId))
        PlaybackDiagnostics.attribute("stream.size", "${video.width}x${video.height}")
        PlaybackDiagnostics.attribute("stream.bandwidth", video.bandwidth.toString())
        PlaybackDiagnostics.attribute("stream.isPcdn", video.isPcdn.toString())
        PlaybackDiagnostics.attribute(
            "stream.videoUrl",
            if (info.isLive) LogRedaction.host(video.url) else LogRedaction.url(video.url),
        )
        PlaybackDiagnostics.attribute("stream.audioId", info.audio?.id?.toString().orEmpty())
        PlaybackDiagnostics.attribute(
            "stream.audioUrl",
            LogRedaction.url(info.audio?.url),
        )
        info.videos.take(VLC_MAX_TRACKS).forEach { track ->
            PlaybackDiagnostics.track(
                "video",
                mapOf(
                    "source" to "candidate",
                    "id" to track.id.toString(),
                    "codec" to VlcTrackFormat.codecName(track.codecId),
                    "size" to "${track.width}x${track.height}",
                    "bandwidth" to track.bandwidth.toString(),
                    "host" to LogRedaction.host(track.url),
                    "pcdn" to track.isPcdn.toString(),
                    "chosen" to (track.url == video.url).toString(),
                ),
            )
        }
        info.audios.take(VLC_MAX_TRACKS).forEach { track ->
            PlaybackDiagnostics.track(
                "audio",
                mapOf(
                    "source" to "candidate",
                    "id" to track.id.toString(),
                    "codec" to VlcTrackFormat.codecName(track.codecId),
                    "bandwidth" to track.bandwidth.toString(),
                    "host" to LogRedaction.host(track.url),
                    "pcdn" to track.isPcdn.toString(),
                    "chosen" to (track.url == info.audio?.url).toString(),
                ),
            )
        }
    }

    /** 日志标签。 */
    private companion object {
        const val TAG = VLC_PLAYER_TAG
    }
}
