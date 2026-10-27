package com.roy233.tvcast.platform.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.ui.PlayerView
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.data.bili.BiliApi
import com.roy233.tvcast.data.bili.PgcPlaybackException
import java.nio.charset.StandardCharsets
import java.io.IOException
import java.util.Collections
import android.util.Base64

/**
 * DASH、HDR、Dolby Vision 与 Widevine 内容使用的 Media3 播放器。
 *
 * Media3 将 DASH/fMP4 的编码、色彩与 DRM 信息交给 Android MediaCodec/CDM，
 * 并通过 SurfaceView 输出视频。
 */
@UnstableApi
class Media3PlayerEngine(
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

    @Volatile
    private var activeDrm = false

    private val drmHttpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(BiliApi.UA)
        .setDefaultRequestProperties(
            mapOf(
                "Origin" to WEB_ORIGIN,
                "Referer" to WEB_REFERER,
                "User-Agent" to BiliApi.UA,
            ),
        )

    private val mediaHttpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(BiliApi.UA)
        .setDefaultRequestProperties(
            mapOf("Referer" to WEB_REFERER, "User-Agent" to BiliApi.UA),
        )

    private val drmProvider = DefaultDrmSessionManagerProvider().apply {
        setDrmHttpDataSourceFactory(drmHttpFactory)
    }

    private val player = ExoPlayer.Builder(
        context.applicationContext,
        DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(false)
            .setMediaCodecSelector(hardwareVideoCodecSelector),
    ).setMediaSourceFactory(
        DefaultMediaSourceFactory(
            DefaultDataSource.Factory(context.applicationContext, mediaHttpFactory),
        )
            .setDrmSessionManagerProvider(drmProvider),
    ).build().also { instance ->
        // 布局通过 app:surface_type="surface_view" 固定直出；TextureView 会把 HDR 转为 SDR。
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

    /**
     * 打开当前轨道。
     *
     * DASH 点播的音视频表示层由 [MergingMediaSource] 合并为同一条播放时间线，避免
     * libVLC `input-slave` 在独立 fMP4 流定位后出现音视频时钟分离。
     */
    fun open(info: BiliApi.PlayInfo, seekToMs: Long) {
        require(
            info.progressiveUrl == null ||
                info.video.isHdr ||
                info.video.isDolbyVision ||
                info.isDrm,
        ) {
            "Media3 engine requires DASH, HDR, Dolby Vision, or DRM media"
        }
        activeDrm = info.isDrm
        var dashItem: MediaItem? = null
        val mediaSource = if (info.isDrm) {
            // Widevine 内容需要携带 PSSH 的 DASH 清单，让 Media3 在 CDM 回调中完成
            // challenge/license 交换。
            val pssh = listOf(info.video.psshBase64, info.audio?.psshBase64.orEmpty())
                .firstOrNull(String::isNotBlank)
                ?: throw PgcPlaybackException("PGC_DRM_METADATA_INVALID", "Widevine PSSH 缺失")
            val manifest = PgcDashManifest.buildDataUri(info, pssh)
            val drm = MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                .setLicenseUri(Uri.parse(WIDEVINE_LICENSE_URL))
                .setMultiSession(false)
                .build()
            dashItem = MediaItem.Builder()
                .setUri(Uri.parse(manifest))
                .setMimeType("application/dash+xml")
                .setDrmConfiguration(drm)
                .build()
            null
        } else {
            // DASH 表示层是独立的 fMP4 文件。Media3 统一维护两个 Progressive source
            // 的 seek 和缓冲时钟，避免直接将音频作为 libVLC input-slave。
            val video = progressiveSource(info.video)
            info.audio?.takeIf { it.url.isNotBlank() }
                ?.let { MergingMediaSource(video, progressiveSource(it)) }
                ?: video
        }
        PlaybackDiagnostics.ensureSession(
            when {
                info.isDrm -> "widevine-open"
                info.video.isDolbyVision -> "dolby-vision-open"
                info.video.isHdr -> "hdr-open"
                else -> "dash-open"
            },
        )
        PlaybackDiagnostics.state("opening")
        PlaybackDiagnostics.attribute("player.backend", "media3")
        PlaybackDiagnostics.attribute("stream.dynamicRange", if (info.video.isDolbyVision) "dolbyVision" else if (info.video.isHdr) "hdr" else "sdr")
        PlaybackDiagnostics.attribute("stream.drm", info.isDrm.toString())
        PlaybackDiagnostics.attribute("stream.codecString", info.video.codecs)
        PlaybackDiagnostics.attribute("stream.mimeType", info.video.mimeType)
        PlaybackDiagnostics.attribute("stream.audioCodecString", info.audio?.codecs.orEmpty())
        PlaybackDiagnostics.attribute("decoder.video.policy", "hardware-only")
        PlaybackDiagnostics.attribute("renderer.video.surface", "surfaceView")
        LogHub.i(
            TAG,
            "open media3 qn=${info.video.id} drm=${info.isDrm} hdr=${info.video.isHdr} dv=${info.video.isDolbyVision} mime=${info.video.mimeType} " +
                "codecs=${info.video.codecs} ${info.video.width}x${info.video.height} " +
                "video=${LogRedaction.url(info.video.url)} audio=${LogRedaction.url(info.audio?.url)} " +
                "audioMime=${info.audio?.mimeType.orEmpty()} audioCodecs=${info.audio?.codecs.orEmpty()} " +
                "seek=${seekToMs.coerceAtLeast(0L)}ms rendererPolicy=hardware-video-only",
        )
        try {
            if (dashItem != null) player.setMediaItem(dashItem!!) else player.setMediaSource(mediaSource!!)
            player.seekTo(seekToMs.coerceAtLeast(0L))
            player.prepare()
            player.playWhenReady = true
        } catch (error: Throwable) {
            val reason = safeErrorReason(error)
            LogHub.e(TAG, "open failed: $reason")
            PlaybackDiagnostics.fail(
                code = when {
                    info.isDrm -> "WIDEVINE_OPEN_FAILED"
                    info.video.isDolbyVision -> "DOLBY_VISION_OPEN_FAILED"
                    info.video.isHdr -> "HDR_OPEN_FAILED"
                    else -> "DASH_OPEN_FAILED"
                },
                message = when {
                    info.isDrm -> "受保护媒体播放器打开失败"
                    info.video.isDolbyVision -> "杜比视界播放器打开失败"
                    info.video.isHdr -> "HDR 播放器打开失败"
                    else -> "DASH 播放器打开失败"
                },
                detail = reason,
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

    /** 由服务器返回的 SegmentBase 范围构建仅含当前表示层的内存 DASH 清单。 */
    private object PgcDashManifest {
        fun buildDataUri(info: BiliApi.PlayInfo, psshBase64: String): String {
            val video = representation(info.video, "video", psshBase64)
            val audio = info.audio?.takeIf { it.url.isNotBlank() }?.let {
                representation(it, "audio", psshBase64.ifBlank { it.psshBase64 })
            }.orEmpty()
            val duration = info.durationSec.coerceAtLeast(1L)
            val mpd = """
                <?xml version="1.0" encoding="UTF-8"?>
                <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013"
                    type="static" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011"
                    minBufferTime="PT1.5S" mediaPresentationDuration="PT${duration}S">
                  <Period duration="PT${duration}S">
                    $video
                    $audio
                  </Period>
                </MPD>
            """.trimIndent()
            return "data:application/dash+xml;base64," +
                Base64.encodeToString(mpd.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        }

        private fun representation(track: BiliApi.Track, type: String, fallbackPssh: String): String {
            if (track.url.isBlank()) {
                throw PgcPlaybackException("PGC_DRM_METADATA_INVALID", "$type 表示层缺少媒体 URL")
            }
            val initRange = parseRange(track.initializationRange)
                ?: throw PgcPlaybackException("PGC_DRM_METADATA_INVALID", "$type 初始化范围无效")
            val indexRange = parseRange(track.indexRange)
                ?: throw PgcPlaybackException("PGC_DRM_METADATA_INVALID", "$type 索引范围无效")
            val pssh = track.psshBase64.ifBlank { fallbackPssh }
            val protection = if (pssh.isBlank()) "" else """
                <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>
                <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed">
                  <cenc:pssh>$pssh</cenc:pssh>
                </ContentProtection>
            """.trimIndent()
            val mime = track.mimeType.ifBlank { if (type == "video") "video/mp4" else "audio/mp4" }
            val attrs = buildString {
                append("id=\"").append(track.id).append("\" ")
                append("bandwidth=\"").append(track.bandwidth.coerceAtLeast(1L)).append("\"")
                if (type == "video" && track.width > 0 && track.height > 0) {
                    append(" width=\"").append(track.width).append("\" height=\"").append(track.height).append("\"")
                }
                if (track.codecs.isNotBlank()) append(" codecs=\"").append(xml(track.codecs)).append("\"")
            }
            return """
                <AdaptationSet contentType="$type" mimeType="$mime" segmentAlignment="true">
                  $protection
                  <Representation $attrs>
                    <BaseURL>${xml(track.url)}</BaseURL>
                    <SegmentBase indexRange="$indexRange">
                      <Initialization range="$initRange"/>
                    </SegmentBase>
                  </Representation>
                </AdaptationSet>
            """.trimIndent()
        }

        private fun parseRange(value: String): String? {
            val parts = value.split('-', limit = 2)
            if (parts.size != 2) return null
            val start = parts[0].toLongOrNull() ?: return null
            val end = parts[1].toLongOrNull() ?: return null
            return "$start-$end".takeIf { start >= 0L && end >= start }
        }

        private fun xml(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
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
                    code = "HDR_SOFTWARE_DECODER_SELECTED",
                    message = "高动态范围视频未使用硬件视频解码器",
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
            val transfer = format.colorInfo?.colorTransfer ?: C.COLOR_TRANSFER_SDR
            val actualRange = when (transfer) {
                C.COLOR_TRANSFER_ST2084 -> "hdr10-pq"
                C.COLOR_TRANSFER_HLG -> "hlg"
                else -> "sdr-or-unknown"
            }
            PlaybackDiagnostics.attribute("stream.dynamicRange.actual", actualRange)
            LogHub.i(TAG, "HDR output inputTransfer=$transfer actualRange=$actualRange surface=surfaceView")
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
                "load started track=${trackTypeName(mediaLoadData.trackType)} url=${mediaUriForLog(loadEventInfo.uri.toString())} " +
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
                    "duration=${loadEventInfo.loadDurationMs}ms url=${mediaUriForLog(loadEventInfo.uri.toString())}",
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
                "url=${mediaUriForLog(loadEventInfo.uri.toString())} " +
                "loaded=${loadEventInfo.bytesLoaded}B after=${loadEventInfo.loadDurationMs}ms " +
                "reason=${safeErrorReason(error)}"
            LogHub.e(TAG, message)
            PlaybackDiagnostics.fail(
                code = if (activeDrm) "WIDEVINE_MEDIA_LOAD_FAILED" else "DOLBY_VISION_LOAD_FAILED",
                message = if (activeDrm) "受保护媒体加载失败" else "杜比视界媒体加载失败",
                detail = message,
            )
        }

        override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
            LogHub.e(TAG, "video codec error: ${safeErrorReason(videoCodecError)}")
        }

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
            LogHub.e(TAG, "audio codec error: ${safeErrorReason(audioCodecError)}")
        }

        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            LogHub.e(TAG, "audio sink error: ${safeErrorReason(audioSinkError)}")
        }
    }

    /** 只为视频 MIME 保留硬件解码器，防止 Dolby Vision 回退到软件解码。 */
    private inner class HardwareVideoCodecSelector : MediaCodecSelector {
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
            val hardware = candidates.filter {
                it.hardwareAccelerated && !it.softwareOnly && (!requiresSecureDecoder || it.secure)
            }
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
                        code = if (activeDrm) "WIDEVINE_SECURE_DECODER_UNAVAILABLE" else "DOLBY_VISION_HARDWARE_DECODER_UNAVAILABLE",
                        message = if (activeDrm) "设备没有可用的 Widevine 安全硬件解码器" else "设备没有可用的杜比视界硬件视频解码器",
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
            "timestamp=${error.timestampMs} reason=${safeErrorReason(error)}"
        LogHub.e(TAG, message)
        val isLicenseFailure = error.errorCodeName.contains("DRM", ignoreCase = true) ||
            error.errorCodeName.contains("LICENSE", ignoreCase = true)
        val code = when {
            !activeDrm -> "DOLBY_VISION_${error.errorCodeName}"
            isLicenseFailure -> "WIDEVINE_LICENSE_FAILED"
            else -> "WIDEVINE_${error.errorCodeName}"
        }
        PlaybackDiagnostics.fail(
            code = code,
            message = if (activeDrm) "Widevine 受保护播放失败" else "杜比视界播放失败",
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

    /** 内存 DASH 清单编码了签名媒体地址，诊断只记录用途标签。 */
    private fun mediaUriForLog(raw: String): String =
        if (raw.startsWith("data:application/dash+xml", ignoreCase = true)) {
            "(in-memory DASH manifest)"
        } else {
            LogRedaction.url(raw)
        }

    /** Widevine 异常可能包含内存 MPD 的 data URI，先移除其编码内容再写诊断。 */
    private fun safeErrorReason(error: Throwable): String {
        val raw = error.message.orEmpty().replace(DASH_DATA_URI_PATTERN, "(in-memory DASH manifest)")
        return "${error.javaClass.simpleName}: ${LogRedaction.text(raw).take(240)}"
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
        const val TAG = "Media3Player"
        const val WEB_ORIGIN = "https://www.bilibili.com"
        const val WEB_REFERER = "https://www.bilibili.com/"
        const val WIDEVINE_LICENSE_URL = "https://bvc-drm.bilivideo.com/bili_widevine"
        val DASH_DATA_URI_PATTERN = Regex("data:application/dash\\+xml;base64,[^\\s\\\"']+", RegexOption.IGNORE_CASE)
        const val MIN_PLAYBACK_RATE = 0.25f
        const val MAX_PLAYBACK_RATE = 4f
        const val MAX_DIAGNOSTIC_VALUE_LENGTH = 1_500
    }
}
