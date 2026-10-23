package com.roy233.tvcast.platform.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.ui.PlayerView
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.data.bili.BiliApi
import java.io.IOException
import java.util.Collections

/**
 * 杜比视界专用的 Media3 播放器。
 *
 * Media3 将 fMP4 中的 Dolby Vision 配置、色彩信息和 profile 交给 Android MediaCodec，
 * 并通过 SurfaceView 直出，避免 libVLC 的 RGB 视频输出路径丢失 10-bit HDR 信息。
 */
class DolbyVisionPlayerEngine(
    context: Context,
    playerView: PlayerView,
    private val events: Events,
) {

    /**
     * Dolby Vision 的软件解码无法保证保留动态元数据，也无法在 4K 下稳定播放。
     * 因此视频轨只返回硬件 MediaCodec；音频轨保持系统默认选择，兼顾 E-AC-3 兼容性。
     */
    private val hardwareVideoCodecSelector = HardwareVideoCodecSelector()

    /** 向宿主报告播放器状态，保持 Activity 的 UI 与弹幕生命周期统一。 */
    interface Events {
        fun onPlaying()
        fun onPaused()
        fun onBuffering(buffering: Boolean)
        fun onEnded()
        fun onError(error: PlaybackException)
        fun onVideoSizeChanged(width: Int, height: Int)
    }

    private val player = ExoPlayer.Builder(
        context.applicationContext,
        DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(false)
            .setMediaCodecSelector(hardwareVideoCodecSelector),
    ).build().also { instance ->
        playerView.player = instance
        instance.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                LogHub.i(
                    TAG,
                    "state=${stateName(playbackState)} playWhenReady=${instance.playWhenReady} " +
                        "isPlaying=${instance.isPlaying} position=${instance.currentPosition}ms " +
                        "buffered=${instance.bufferedPosition}ms duration=${instance.duration}",
                )
                PlaybackDiagnostics.event(
                    "Media3State",
                    "state=${stateName(playbackState)} position=${instance.currentPosition}ms " +
                        "buffered=${instance.bufferedPosition}ms duration=${instance.duration}",
                )
                when (playbackState) {
                    Player.STATE_BUFFERING -> events.onBuffering(true)
                    Player.STATE_READY -> {
                        events.onBuffering(false)
                        if (instance.playWhenReady) events.onPlaying()
                    }
                    Player.STATE_ENDED -> events.onEnded()
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                LogHub.i(
                    TAG,
                    "isPlaying=$isPlaying state=${stateName(instance.playbackState)} " +
                        "position=${instance.currentPosition}ms",
                )
                if (isPlaying) events.onPlaying() else events.onPaused()
            }

            override fun onPlayerError(error: PlaybackException) {
                logPlaybackError("player", error)
                events.onError(error)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                LogHub.i(
                    TAG,
                    "video size=${videoSize.width}x${videoSize.height} " +
                        "pixelRatio=${videoSize.pixelWidthHeightRatio}",
                )
                events.onVideoSizeChanged(videoSize.width, videoSize.height)
            }
        })
        instance.addAnalyticsListener(Media3AnalyticsLogger())
    }

    /** 打开分离的视频、音频 DASH 表示层，并让 Media3 负责 Dolby Vision 解码。 */
    fun open(info: BiliApi.PlayInfo, seekToMs: Long) {
        require(info.video.isDolbyVision) { "Dolby Vision engine requires a Dolby Vision track" }
        val video = progressiveSource(info.video)
        val mediaSource = info.audio?.takeIf { it.url.isNotBlank() }
            ?.let { MergingMediaSource(video, progressiveSource(it)) }
            ?: video
        PlaybackDiagnostics.ensureSession("dolby-vision-open")
        PlaybackDiagnostics.state("opening")
        PlaybackDiagnostics.attribute("player.backend", "media3")
        PlaybackDiagnostics.attribute("stream.dynamicRange", "dolbyVision")
        PlaybackDiagnostics.attribute("stream.codecString", info.video.codecs)
        PlaybackDiagnostics.attribute("stream.mimeType", info.video.mimeType)
        PlaybackDiagnostics.attribute("stream.audioCodecString", info.audio?.codecs.orEmpty())
        PlaybackDiagnostics.attribute("decoder.video.policy", "hardware-only")
        LogHub.i(
            TAG,
            "open Dolby Vision qn=${info.video.id} mime=${info.video.mimeType} " +
                "codecs=${info.video.codecs} ${info.video.width}x${info.video.height} " +
                "video=${LogRedaction.url(info.video.url)} audio=${LogRedaction.url(info.audio?.url)} " +
                "audioMime=${info.audio?.mimeType.orEmpty()} audioCodecs=${info.audio?.codecs.orEmpty()} " +
                "seek=${seekToMs.coerceAtLeast(0L)}ms rendererPolicy=hardware-video-only",
        )
        try {
            player.setMediaSource(mediaSource)
            player.seekTo(seekToMs.coerceAtLeast(0L))
            player.prepare()
            player.playWhenReady = true
        } catch (error: Throwable) {
            LogHub.e(TAG, "open failed: ${LogRedaction.reason(error)}", error, stack = true)
            PlaybackDiagnostics.fail(
                code = "DOLBY_VISION_OPEN_FAILED",
                message = "杜比视界播放器打开失败",
                detail = LogRedaction.reason(error),
            )
            throw error
        }
    }

    /** 暂停或恢复。 */
    fun setPaused(paused: Boolean) {
        if (paused) player.pause() else player.play()
    }

    /** 定位，返回已经限幅后的目标位置。 */
    fun seekTo(positionMs: Long): Long {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val target = if (duration == null) positionMs.coerceAtLeast(0L) else positionMs.coerceIn(0L, duration)
        player.seekTo(target)
        return target
    }

    /** 设置播放速率。 */
    fun setPlaybackRate(rate: Float): Float {
        val target = rate.coerceIn(MIN_PLAYBACK_RATE, MAX_PLAYBACK_RATE)
        player.setPlaybackSpeed(target)
        return target
    }

    /** 设置音量，沿用界面的 0..100 标度。 */
    fun setVolume(volume: Int) {
        player.volume = volume.coerceIn(0, 100) / 100f
    }

    fun stop() = player.stop()
    fun release() = player.release()
    fun positionMs(): Long = player.currentPosition.coerceAtLeast(0L)
    fun durationMs(): Long = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
    fun isPlaying(): Boolean = player.isPlaying
    fun volume(): Int = (player.volume * 100).toInt().coerceIn(0, 100)

    private fun progressiveSource(track: BiliApi.Track): MediaSource {
        val headers = mapOf(
            "Referer" to "https://www.bilibili.com/",
            "User-Agent" to BiliApi.UA,
        )
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(BiliApi.UA)
            .setDefaultRequestProperties(headers)
        val item = MediaItem.Builder()
            .setUri(track.url)
            .setMimeType(track.mimeType.ifBlank { null })
            .build()
        return ProgressiveMediaSource.Factory(factory).createMediaSource(item)
    }

    /** Media3 的底层事件；完整记录实际解码器、轨道格式与网络加载失败。 */
    private inner class Media3AnalyticsLogger : AnalyticsListener {
        override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
            val summary = tracks.groups.joinToString(separator = " | ") { group ->
                (0 until group.length).joinToString(prefix = "type=${trackTypeName(group.type)}[", postfix = "]") { index ->
                    "selected=${group.isTrackSelected(index)} supported=${group.isTrackSupported(index)} " +
                        formatSummary(group.getTrackFormat(index))
                }
            }
            LogHub.i(TAG, "tracks changed $summary")
            PlaybackDiagnostics.attribute("media3.tracks", summary.take(MAX_DIAGNOSTIC_VALUE_LENGTH))
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            val verifiedHardware = hardwareVideoCodecSelector.isHardwareDecoder(decoderName)
            val message = "video decoder initialized name=$decoderName hardware=$verifiedHardware " +
                "initializedAt=${initializedTimestampMs}ms duration=${initializationDurationMs}ms"
            if (verifiedHardware) {
                LogHub.i(TAG, message)
            } else {
                LogHub.e(TAG, "HARDWARE_DECODER_POLICY_VIOLATION $message")
                PlaybackDiagnostics.fail(
                    code = "DOLBY_VISION_SOFTWARE_DECODER_SELECTED",
                    message = "杜比视界未使用硬件视频解码器",
                    detail = message,
                )
            }
            PlaybackDiagnostics.attribute("decoder.video.name", decoderName)
            PlaybackDiagnostics.attribute("decoder.video.hardware", verifiedHardware.toString())
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            val summary = formatSummary(format)
            LogHub.i(
                TAG,
                "video input format $summary reuse=${decoderReuseEvaluation?.result} " +
                    "decoder=${decoderReuseEvaluation?.decoderName.orEmpty()}",
            )
            PlaybackDiagnostics.attribute("decoder.video.inputFormat", summary)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            LogHub.i(
                TAG,
                "audio decoder initialized name=$decoderName initializedAt=${initializedTimestampMs}ms " +
                    "duration=${initializationDurationMs}ms",
            )
            PlaybackDiagnostics.attribute("decoder.audio.name", decoderName)
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            val summary = formatSummary(format)
            LogHub.i(TAG, "audio input format $summary reuse=${decoderReuseEvaluation?.result}")
            PlaybackDiagnostics.attribute("decoder.audio.inputFormat", summary)
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            LogHub.i(TAG, "first video frame rendered at=${renderTimeMs}ms output=${output.javaClass.simpleName}")
            PlaybackDiagnostics.event("Media3FirstFrame", "renderTime=${renderTimeMs}ms")
        }

        override fun onLoadStarted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            retryCount: Int,
        ) {
            LogHub.d(
                TAG,
                "load started track=${trackTypeName(mediaLoadData.trackType)} url=${LogRedaction.url(loadEventInfo.uri.toString())} " +
                    "retry=$retryCount format=${mediaLoadData.trackFormat?.let(::formatSummary).orEmpty()}",
            )
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            LogHub.d(
                TAG,
                "load completed track=${trackTypeName(mediaLoadData.trackType)} bytes=${loadEventInfo.bytesLoaded} " +
                    "duration=${loadEventInfo.loadDurationMs}ms url=${LogRedaction.url(loadEventInfo.uri.toString())}",
            )
        }

        @Deprecated("Media3 keeps this AnalyticsListener callback for compatibility")
        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            val message = "load failed track=${trackTypeName(mediaLoadData.trackType)} canceled=$wasCanceled " +
                "url=${LogRedaction.url(loadEventInfo.uri.toString())} " +
                "loaded=${loadEventInfo.bytesLoaded}B after=${loadEventInfo.loadDurationMs}ms " +
                "reason=${LogRedaction.reason(error)}"
            LogHub.e(TAG, message, error, stack = true)
            PlaybackDiagnostics.fail(
                code = "DOLBY_VISION_LOAD_FAILED",
                message = "杜比视界媒体加载失败",
                detail = message,
            )
        }

        override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
            LogHub.e(TAG, "video codec error: ${LogRedaction.reason(videoCodecError)}", videoCodecError, stack = true)
        }

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
            LogHub.e(TAG, "audio codec error: ${LogRedaction.reason(audioCodecError)}", audioCodecError, stack = true)
        }

        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            LogHub.e(TAG, "audio sink error: ${LogRedaction.reason(audioSinkError)}", audioSinkError, stack = true)
        }
    }

    /** 只为视频 MIME 保留硬件解码器，防止 Dolby Vision 回退到软件解码。 */
    private class HardwareVideoCodecSelector : MediaCodecSelector {
        private val selectedHardwareDecoderNames = Collections.synchronizedSet(mutableSetOf<String>())

        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean,
        ): List<MediaCodecInfo> {
            val candidates = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder,
            )
            if (!mimeType.startsWith("video/", ignoreCase = true)) return candidates
            val hardware = candidates.filter { it.hardwareAccelerated && !it.softwareOnly }
            val candidateSummary = candidates.joinToString { codec ->
                "${codec.name}(hardware=${codec.hardwareAccelerated},software=${codec.softwareOnly},secure=${codec.secure})"
            }
            val selectedSummary = hardware.joinToString { it.name }
            LogHub.i(
                TAG,
                "hardware decoder query mime=$mimeType secure=$requiresSecureDecoder tunneling=$requiresTunnelingDecoder " +
                    "candidates=[$candidateSummary] selected=[$selectedSummary]",
            )
            if (hardware.isEmpty()) {
                val message = "no hardware video decoder mime=$mimeType secure=$requiresSecureDecoder " +
                    "tunneling=$requiresTunnelingDecoder candidates=[$candidateSummary]"
                if (requiresTunnelingDecoder) {
                    // Media3 会将隧道播放能力作为可选路径探测；普通 Surface 播放不依赖它。
                    LogHub.d(TAG, "tunneled playback unavailable; regular Surface playback remains eligible: $message")
                } else {
                    LogHub.e(TAG, message)
                    PlaybackDiagnostics.fail(
                        code = "DOLBY_VISION_HARDWARE_DECODER_UNAVAILABLE",
                        message = "设备没有可用的杜比视界硬件视频解码器",
                        detail = message,
                    )
                }
            } else {
                selectedHardwareDecoderNames += hardware.map { it.name }
            }
            return hardware
        }

        fun isHardwareDecoder(decoderName: String): Boolean =
            synchronized(selectedHardwareDecoderNames) { decoderName in selectedHardwareDecoderNames }
    }

    private fun logPlaybackError(stage: String, error: PlaybackException) {
        val message = "Media3 $stage failed code=${error.errorCodeName}(${error.errorCode}) " +
            "timestamp=${error.timestampMs} reason=${LogRedaction.reason(error)}"
        LogHub.e(TAG, message, error, stack = true)
        PlaybackDiagnostics.fail(
            code = "DOLBY_VISION_${error.errorCodeName}",
            message = "杜比视界播放失败",
            detail = message,
        )
    }

    private fun formatSummary(format: Format): String {
        val color = format.colorInfo?.let { info ->
            "color(space=${info.colorSpace},range=${info.colorRange},transfer=${info.colorTransfer}," +
                "luma=${info.lumaBitdepth},chroma=${info.chromaBitdepth},hdrStatic=${info.hdrStaticInfo?.size ?: 0}B)"
        } ?: "color=(none)"
        return "mime=${format.sampleMimeType.orEmpty()} container=${format.containerMimeType.orEmpty()} " +
            "codecs=${format.codecs.orEmpty()} ${format.width}x${format.height} fps=${format.frameRate} " +
            "bitrate=${format.bitrate} channels=${format.channelCount} sampleRate=${format.sampleRate} $color"
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    private fun trackTypeName(trackType: Int): String = when (trackType) {
        C.TRACK_TYPE_VIDEO -> "video"
        C.TRACK_TYPE_AUDIO -> "audio"
        C.TRACK_TYPE_TEXT -> "text"
        C.TRACK_TYPE_METADATA -> "metadata"
        else -> "type=$trackType"
    }

    private companion object {
        const val TAG = "DolbyVisionPlayer"
        const val MIN_PLAYBACK_RATE = 0.25f
        const val MAX_PLAYBACK_RATE = 4f
        const val MAX_DIAGNOSTIC_VALUE_LENGTH = 1_500
    }
}
