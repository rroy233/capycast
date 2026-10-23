package com.roy233.tvcast.platform.player

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.NativeLogcat
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * 生成播放状态、轨道和失败快照，并把 libVLC 原生日志带入诊断报告。
 *
 * 报告只读取当前状态，不修改播放器；失败报告是播放停止前的最后一份完整现场。
 *
 * @param player 播放器实例。
 * @param state 当前播放会话状态。
 * @param videoLayout 视频布局，用于记录视图尺寸。
 */
internal class VlcPlaybackReporter(
    private val player: MediaPlayer,
    private val state: VlcPlaybackState,
    private val videoLayout: VLCVideoLayout,
) {

    /** 是否已有原生日志采集线程在运行。 */
    @Volatile
    private var nativeCaptureRunning = false

    /**
     * 记录所有媒体轨道和播放器轨道选择。
     *
     * @param stage 快照阶段，例如 first-vout 或 failure。
     */
    fun logTrackSnapshot(stage: String) {
        val playerVideo = runCatching { player.videoTracksCount }.getOrDefault(-1)
        val playerAudio = runCatching { player.audioTracksCount }.getOrDefault(-1)
        val playerSpu = runCatching { player.spuTracksCount }.getOrDefault(-1)
        val videoTracks = runCatching {
            player.getVideoTracks()?.joinToString(",") { "${it.id}:${it.name}" }.orEmpty()
        }.getOrDefault("")
        val audioTracks = runCatching {
            player.getAudioTracks()?.joinToString(",") { "${it.id}:${it.name}" }.orEmpty()
        }.getOrDefault("")
        val media = state.currentMedia
        val parsed = runCatching { media?.isParsed }.getOrNull()
        val trackCount = runCatching { media?.trackCount }.getOrNull() ?: -1
        LogHub.i(
            TAG,
            "tracks stage=$stage playerVideo=$playerVideo playerAudio=$playerAudio " +
                "playerSpu=$playerSpu mediaParsed=$parsed mediaTracks=$trackCount",
        )
        if (videoTracks.isNotBlank()) LogHub.i(TAG, "  playerVideoTracks=$videoTracks")
        if (audioTracks.isNotBlank()) LogHub.i(TAG, "  playerAudioTracks=$audioTracks")
        if (media != null && trackCount > 0) {
            val count = trackCount.coerceAtMost(VLC_MAX_TRACKS)
            for (index in 0 until count) {
                val track = runCatching { media.getTrack(index) }.getOrNull() ?: continue
                val fields = trackFields(track)
                LogHub.i(
                    TAG,
                    "  mediaTrack[$index] " +
                        fields.entries.joinToString(" ") { "${it.key}=${it.value}" },
                )
                val trackKey = listOf(
                    track.type,
                    track.id,
                    track.codec.orEmpty(),
                    track.widthOrZero(),
                    track.heightOrZero(),
                    track.channelsOrZero(),
                    track.rateOrZero(),
                ).joinToString("|")
                if (state.loggedMediaTracks.add(trackKey)) {
                    PlaybackDiagnostics.track(VlcTrackFormat.trackTypeName(track.type), fields)
                }
            }
            if (trackCount > count) {
                LogHub.i(TAG, "  mediaTracks truncated at $count of $trackCount")
            }
        }
    }

    /**
     * 记录失败现场并写入失败诊断。
     *
     * 调用方应先执行一次 `sampleStats(force = true)`，确保快照包含最新的
     * 解码、丢帧和读取统计。
     *
     * @param stage 失败阶段。
     */
    fun reportFailure(stage: String) {
        val snapshot = failureSnapshot()
        LogHub.e(TAG, "failure stage=$stage $snapshot")
        PlaybackDiagnostics.event("FailureContext", "stage=$stage")
        logTrackSnapshot("failure-$stage")
        logNativeLogTail("failure-$stage", 40)
        PlaybackDiagnostics.fail(
            code = "VLC_ENCOUNTERED_ERROR",
            message = "libVLC 播放过程中报错",
            detail = "stage=$stage $snapshot",
        )
    }

    /**
     * 生成当前播放器和媒体的单行状态快照。
     *
     * @return 单行状态快照。
     */
    fun failureSnapshot(): String = buildString {
        append("session=").append(if (state.active) "active" else "idle")
        append(" ").append(mediaSummary())
        append(" playerState=").append(playerStateName())
        append(" playing=").append(runCatching { player.isPlaying }.getOrDefault(false))
        append(" time=").append(runCatching { player.time }.getOrDefault(-1L))
        append(" length=").append(runCatching { player.length }.getOrDefault(-1L))
        append(" seekable=").append(runCatching { player.isSeekable }.getOrDefault(false))
        append(" volume=").append(runCatching { player.volume }.getOrDefault(-1))
        append(" videoTracks=").append(runCatching { player.videoTracksCount }.getOrDefault(-1))
        append(" audioTracks=").append(runCatching { player.audioTracksCount }.getOrDefault(-1))
        append(" spuTracks=").append(runCatching { player.spuTracksCount }.getOrDefault(-1))
        append(" viewsAttached=").append(state.viewsAttached)
        append(" view=").append(viewSize())
        append(" openingSeen=").append(state.openingSeen)
        append(" playingSeen=").append(state.playingSeen)
        append(" voutSeen=").append(state.voutSeen)
        append(" openingMs=")
            .append(if (state.openingAt > 0L) state.openingAt - state.mediaOpenedAt else -1L)
        append(" playingMs=")
            .append(if (state.playingAt > 0L) state.playingAt - state.mediaOpenedAt else -1L)
        append(" voutMs=")
            .append(if (state.voutAt > 0L) state.voutAt - state.mediaOpenedAt else -1L)
        append(" lastStats=").append(state.lastStats?.toString() ?: "(none)")
    }

    /**
     * 生成当前媒体的单行摘要。
     *
     * @return 媒体摘要。
     */
    fun mediaSummary(): String {
        val media = state.currentMedia ?: return "media=(none)"
        val uri = runCatching { media.uri?.toString() }.getOrNull()
        val safeUri = if (state.currentIsLive) LogRedaction.host(uri) else LogRedaction.url(uri)
        return "media=(uri=$safeUri " +
            "parsed=${runCatching { media.isParsed }.getOrElse { "?" }} " +
            "state=${runCatching { media.state }.getOrElse { -1 }} " +
            "tracks=${runCatching { media.trackCount }.getOrElse { -1 }} " +
            "duration=${runCatching { media.duration }.getOrElse { -1L }}ms)"
    }

    /**
     * 返回当前播放器状态名称。
     *
     * @return 播放器状态名称。
     */
    fun playerStateName(): String = runCatching {
        when (player.playerState) {
            0 -> "NothingSpecial"
            1 -> "Opening"
            2 -> "Buffering"
            3 -> "Playing"
            4 -> "Paused"
            5 -> "Stopped"
            6 -> "Ended"
            7 -> "Error"
            else -> "Unknown(${player.playerState})"
        }
    }.getOrElse { "Unavailable(${LogRedaction.reason(it)})" }

    /**
     * 返回视频布局尺寸文本。
     *
     * @return 形如 1920x1080 的尺寸文本。
     */
    fun viewSize(): String = "${videoLayout.width}x${videoLayout.height}"

    /**
     * 判断视频布局是否尚未获得有效尺寸。
     *
     * @return 宽或高小于等于 0 时返回 true。
     */
    fun hasZeroViewSize(): Boolean = videoLayout.width <= 0 || videoLayout.height <= 0

    /**
     * 输出 libVLC 原生日志尾部。
     *
     * @param stage 触发阶段。
     * @param maxLines 最多输出的行数。
     */
    fun logNativeLogTail(stage: String, maxLines: Int) {
        val tail = PlaybackDiagnostics.vlcLogTail(maxLines)
        if (tail.isNotBlank()) {
            LogHub.i(
                TAG,
                "native file log tail stage=$stage lines=${tail.lineSequence().count()}",
            )
            tail.lineSequence().forEach { line -> LogHub.i("VlcNative", line) }
            return
        }
        LogHub.w(
            TAG,
            "native file log unavailable stage=$stage " +
                "bytes=${PlaybackDiagnostics.vlcLogBytes()}; reading logcat",
        )
        captureNativeLogAsync(stage, maxLines)
    }

    /**
     * 在后台读取 logcat 中的 libVLC 原生日志，避免阻塞播放线程。
     *
     * @param stage 触发阶段。
     * @param maxLines 最多读取的行数。
     */
    private fun captureNativeLogAsync(stage: String, maxLines: Int) {
        if (nativeCaptureRunning) return
        nativeCaptureRunning = true
        Thread {
            try {
                val tail = NativeLogcat.tail(maxLines, force = true)
                if (tail.isBlank()) {
                    LogHub.w(TAG, "native logcat unavailable or empty stage=$stage")
                    return@Thread
                }
                LogHub.i(
                    TAG,
                    "native logcat tail stage=$stage lines=${tail.lineSequence().count()}",
                )
                PlaybackDiagnostics.recordNativeLog(stage, tail)
                tail.lineSequence().forEach { line -> LogHub.i("VlcNative", line) }
            } finally {
                nativeCaptureRunning = false
            }
        }.apply {
            name = "vlc-native-log"
            isDaemon = true
        }.start()
    }

    /**
     * 把一条媒体轨道转换为诊断字段。
     *
     * @param track libVLC 媒体轨道。
     * @return 诊断字段映射。
     */
    private fun trackFields(track: IMedia.Track): Map<String, String> {
        val fields = linkedMapOf(
            "source" to "media",
            "type" to VlcTrackFormat.trackTypeName(track.type),
            "codec" to track.codec.orEmpty(),
            "originalCodec" to track.originalCodec.orEmpty(),
            "id" to track.id.toString(),
            "profile" to track.profile.toString(),
            "level" to track.level.toString(),
            "bitrate" to track.bitrate.toString(),
            "language" to track.language.orEmpty(),
            "description" to track.description.orEmpty(),
        )
        when (track) {
            is IMedia.VideoTrack -> {
                fields["width"] = track.width.toString()
                fields["height"] = track.height.toString()
                fields["sar"] = "${track.sarNum}:${track.sarDen}"
                fields["fps"] = "${track.frameRateNum}/${track.frameRateDen}"
                fields["orientation"] = track.orientation.toString()
                fields["projection"] = track.projection.toString()
            }
            is IMedia.AudioTrack -> {
                fields["channels"] = track.channels.toString()
                fields["sampleRate"] = track.rate.toString()
            }
        }
        return fields
    }

    /** 读取视频轨道宽度，非视频轨道返回 0。 */
    private fun IMedia.Track.widthOrZero(): Int =
        (this as? IMedia.VideoTrack)?.width ?: 0

    /** 读取视频轨道高度，非视频轨道返回 0。 */
    private fun IMedia.Track.heightOrZero(): Int =
        (this as? IMedia.VideoTrack)?.height ?: 0

    /** 读取音频轨道声道数，非音频轨道返回 0。 */
    private fun IMedia.Track.channelsOrZero(): Int =
        (this as? IMedia.AudioTrack)?.channels ?: 0

    /** 读取音频轨道采样率，非音频轨道返回 0。 */
    private fun IMedia.Track.rateOrZero(): Int =
        (this as? IMedia.AudioTrack)?.rate ?: 0

    /** 日志标签。 */
    private companion object {
        const val TAG = VLC_PLAYER_TAG
    }
}
