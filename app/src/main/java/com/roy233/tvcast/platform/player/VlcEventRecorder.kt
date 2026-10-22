package com.roy233.tvcast.platform.player

import android.os.SystemClock
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import org.videolan.libvlc.MediaPlayer
import java.util.Locale
import kotlin.math.abs

/**
 * 把 libVLC 播放器事件转换为诊断时间线和界面状态更新。
 *
 * 事件回调来自播放器线程，本类只更新内存状态并写入诊断；阻塞性的文件读取和
 * logcat 采集由 [VlcPlaybackReporter] 在后台执行。
 *
 * @param player 播放器实例。
 * @param state 当前播放会话状态。
 * @param monitor 周期监控器，用于采样和启动/停止看门狗。
 * @param reporter 报告器，用于轨道、失败和原生日志输出。
 */
internal class VlcEventRecorder(
    private val player: MediaPlayer,
    private val state: VlcPlaybackState,
    private val monitor: VlcPlaybackMonitor,
    private val reporter: VlcPlaybackReporter,
) {

    /**
     * 记录单个播放器事件并更新监控状态。
     *
     * @param event libVLC 播放器事件。
     */
    fun record(event: MediaPlayer.Event) {
        val now = SystemClock.elapsedRealtime()
        when (event.type) {
            MediaPlayer.Event.TimeChanged -> handleTimeChanged(event, now)
            MediaPlayer.Event.PositionChanged -> Unit
            MediaPlayer.Event.Buffering -> handleBuffering(event)
            MediaPlayer.Event.Opening -> {
                state.openingSeen = true
                state.openingAt = now
                PlaybackDiagnostics.state("opening")
                PlaybackDiagnostics.event(
                    "Opening",
                    "afterOpen=${now - state.mediaOpenedAt}ms state=${reporter.playerStateName()}",
                )
                LogHub.i(TAG, "opening afterOpen=${now - state.mediaOpenedAt}ms")
            }
            MediaPlayer.Event.Playing -> {
                handlePlaying(now)
                PlaybackDiagnostics.event(
                    "Playing",
                    "afterOpen=${now - state.mediaOpenedAt}ms " +
                        "state=${reporter.playerStateName()} " +
                        "length=${runCatching { player.length }.getOrDefault(-1L)}ms",
                )
            }
            MediaPlayer.Event.Paused -> {
                state.lastProgressAt = now
                PlaybackDiagnostics.state("paused")
                PlaybackDiagnostics.event(
                    "Paused",
                    "position=${runCatching { player.time }.getOrDefault(-1L)}ms",
                )
                LogHub.i(TAG, "paused at=${runCatching { player.time }.getOrDefault(-1L)}ms")
            }
            MediaPlayer.Event.Stopped -> {
                if (isLateTerminalEvent("Stopped")) return
                if (!state.openingSeen &&
                    now - state.mediaOpenedAt < VLC_MEDIA_SWITCH_GRACE_MS
                ) {
                    PlaybackDiagnostics.event(
                        "Stopped",
                        "ignored during media switch elapsed=${now - state.mediaOpenedAt}ms",
                    )
                    LogHub.d(TAG, "stopped during media switch; ignored")
                    return
                }
                PlaybackDiagnostics.state("stopped")
                PlaybackDiagnostics.event("Stopped", "elapsed=${now - state.mediaOpenedAt}ms")
                LogHub.i(TAG, "stopped elapsed=${now - state.mediaOpenedAt}ms")
                monitor.sampleStats(force = true)
                monitor.stop()
                PlaybackDiagnostics.closeSession("stream-stopped")
            }
            MediaPlayer.Event.EndReached -> {
                if (isLateTerminalEvent("EndReached")) return
                PlaybackDiagnostics.state("ended")
                PlaybackDiagnostics.event(
                    "EndReached",
                    "elapsed=${now - state.mediaOpenedAt}ms " +
                        "length=${runCatching { player.length }.getOrDefault(-1L)}ms",
                )
                LogHub.i(
                    TAG,
                    "end reached elapsed=${now - state.mediaOpenedAt}ms " +
                        "length=${runCatching { player.length }.getOrDefault(-1L)}ms",
                )
                monitor.sampleStats(force = true)
                monitor.stop()
                PlaybackDiagnostics.closeSession("end-reached")
            }
            MediaPlayer.Event.EncounteredError -> {
                if (isLateTerminalEvent("EncounteredError")) return
                PlaybackDiagnostics.state("error")
                PlaybackDiagnostics.event(
                    "EncounteredError",
                    "afterOpen=${now - state.mediaOpenedAt}ms " +
                        "state=${reporter.playerStateName()}",
                )
                monitor.sampleStats(force = true)
                reporter.reportFailure("encountered-error")
                monitor.stop()
            }
            MediaPlayer.Event.Vout -> handleVout(event, now)
            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted,
            MediaPlayer.Event.ESSelected -> {
                val typeName = VlcTrackFormat.trackTypeName(event.esChangedType)
                PlaybackDiagnostics.event(
                    eventName(event.type),
                    "trackType=$typeName trackId=${event.esChangedID}",
                )
                LogHub.i(
                    TAG,
                    "${eventName(event.type)} trackType=$typeName trackId=${event.esChangedID}",
                )
            }
            MediaPlayer.Event.LengthChanged -> {
                PlaybackDiagnostics.event(
                    "LengthChanged",
                    "length=${event.lengthChanged}ms",
                )
                LogHub.i(TAG, "length changed to ${event.lengthChanged}ms")
            }
            MediaPlayer.Event.SeekableChanged -> {
                PlaybackDiagnostics.event("SeekableChanged", "seekable=${event.seekable}")
            }
            MediaPlayer.Event.PausableChanged -> {
                PlaybackDiagnostics.event("PausableChanged", "pausable=${event.pausable}")
            }
            MediaPlayer.Event.MediaChanged -> {
                PlaybackDiagnostics.event("MediaChanged", reporter.mediaSummary())
                LogHub.i(TAG, "media changed ${reporter.mediaSummary()}")
            }
            MediaPlayer.Event.RecordChanged -> {
                PlaybackDiagnostics.event(
                    "RecordChanged",
                    "recording=${event.recording} path=${event.recordPath.orEmpty()}",
                )
            }
            else -> {
                PlaybackDiagnostics.event(eventName(event.type), eventDetail(event))
            }
        }
    }

    /**
     * 判断终止事件是否来自已经结束的会话。
     *
     * @param name 终止事件名称。
     * @return 是否应当忽略该事件。
     */
    private fun isLateTerminalEvent(name: String): Boolean {
        if (state.active) return false
        PlaybackDiagnostics.event(name, "ignored after session end")
        LogHub.d(TAG, "$name after session end; ignored")
        return true
    }

    /**
     * 处理 Playing 事件，记录首帧准备阶段的时间和轨道信息。
     *
     * @param now 当前单调时刻。
     */
    private fun handlePlaying(now: Long) {
        state.playingSeen = true
        state.playingAt = now
        state.lastProgressAt = now
        PlaybackDiagnostics.state("playing")
        val first = !state.firstPlayingLogged
        state.firstPlayingLogged = true
        LogHub.i(
            TAG,
            "playing afterOpen=${now - state.mediaOpenedAt}ms first=$first " +
                "state=${reporter.playerStateName()} " +
                "length=${runCatching { player.length }.getOrDefault(-1L)} " +
                "seekable=${runCatching { player.isSeekable }.getOrDefault(false)} " +
                "audioTracks=${runCatching { player.audioTracksCount }.getOrDefault(-1)} " +
                "videoTracks=${runCatching { player.videoTracksCount }.getOrDefault(-1)}",
        )
        if (first) {
            PlaybackDiagnostics.attribute(
                "player.firstPlayingMs",
                (now - state.mediaOpenedAt).toString(),
            )
            reporter.logTrackSnapshot("first-playing")
            monitor.sampleStats(force = true)
        }
    }

    /**
     * 处理视频输出事件，记录首个画面的时间。
     *
     * @param event libVLC 视频输出事件。
     * @param now 当前单调时刻。
     */
    private fun handleVout(event: MediaPlayer.Event, now: Long) {
        val count = event.voutCount
        val first = count > 0 && !state.voutSeen
        if (first) {
            state.voutSeen = true
            state.voutAt = now
            val afterPlaying = if (state.playingAt > 0L) now - state.playingAt else -1L
            LogHub.i(
                TAG,
                "first vout afterOpen=${now - state.mediaOpenedAt}ms " +
                    "afterPlaying=${afterPlaying}ms " +
                    "count=${event.voutCount} view=${reporter.viewSize()}",
            )
            PlaybackDiagnostics.attribute(
                "player.firstVoutMs",
                (now - state.mediaOpenedAt).toString(),
            )
            if (reporter.hasZeroViewSize()) {
                monitor.warnOnce("zeroSurface", "video surface has zero size before vout")
            }
            reporter.logTrackSnapshot("first-vout")
        }
        if (count <= 0) {
            monitor.warnOnce(
                "zeroVout",
                "video output event reported count=$count; waiting for a usable vout",
            )
        }
        PlaybackDiagnostics.event(
            "Vout",
            "count=$count first=$first view=${reporter.viewSize()}",
        )
    }

    /**
     * 处理缓冲事件，仅在跨过 10% 分桶时记录。
     *
     * @param event libVLC 缓冲事件。
     */
    private fun handleBuffering(event: MediaPlayer.Event) {
        val value = event.buffering
        val bucket = (value / 10f).toInt().coerceIn(0, 10)
        if (value < 100f) {
            PlaybackDiagnostics.state(if (state.playingSeen) "playing" else "buffering")
        } else if (state.playingSeen) {
            PlaybackDiagnostics.state("playing")
        }
        if (bucket != state.lastBufferingBucket) {
            state.lastBufferingBucket = bucket
            PlaybackDiagnostics.event("Buffering", "value=$value bucket=${bucket * 10}")
            if (value < 100f) {
                LogHub.d(TAG, "buffering ${value}%")
            } else {
                LogHub.i(TAG, "buffering complete")
            }
        }
    }

    /**
     * 处理时间进度事件，限制日志频率并跟踪卡顿。
     *
     * @param event libVLC 时间事件。
     * @param now 当前单调时刻。
     */
    private fun handleTimeChanged(event: MediaPlayer.Event, now: Long) {
        val time = event.timeChanged
        val delta = time - state.lastPositionMs
        if (time > 0L && (state.lastPositionMs < 0L || delta >= 250L)) {
            state.lastProgressAt = now
        }
        state.lastPositionMs = time
        val jumped = abs(delta) > 3000L
        if (jumped || now - state.lastTimeLogAt >= VLC_TIME_LOG_INTERVAL_MS) {
            state.lastTimeLogAt = now
            PlaybackDiagnostics.event(
                "TimeChanged",
                "time=${time}ms delta=${delta}ms${if (jumped) " jump=true" else ""}",
            )
        }
    }

    /**
     * 返回事件名称。
     *
     * @param type libVLC 事件类型。
     * @return 事件名称。
     */
    private fun eventName(type: Int): String = when (type) {
        MediaPlayer.Event.MediaChanged -> "MediaChanged"
        MediaPlayer.Event.Opening -> "Opening"
        MediaPlayer.Event.Buffering -> "Buffering"
        MediaPlayer.Event.Playing -> "Playing"
        MediaPlayer.Event.Paused -> "Paused"
        MediaPlayer.Event.Stopped -> "Stopped"
        MediaPlayer.Event.EndReached -> "EndReached"
        MediaPlayer.Event.EncounteredError -> "EncounteredError"
        MediaPlayer.Event.TimeChanged -> "TimeChanged"
        MediaPlayer.Event.PositionChanged -> "PositionChanged"
        MediaPlayer.Event.SeekableChanged -> "SeekableChanged"
        MediaPlayer.Event.PausableChanged -> "PausableChanged"
        MediaPlayer.Event.LengthChanged -> "LengthChanged"
        MediaPlayer.Event.Vout -> "Vout"
        MediaPlayer.Event.ESAdded -> "ESAdded"
        MediaPlayer.Event.ESDeleted -> "ESDeleted"
        MediaPlayer.Event.ESSelected -> "ESSelected"
        MediaPlayer.Event.RecordChanged -> "RecordChanged"
        else -> "Event$type"
    }

    /**
     * 返回事件详情。
     *
     * @param event libVLC 事件。
     * @return 事件详情文本。
     */
    private fun eventDetail(event: MediaPlayer.Event): String = runCatching {
        when (event.type) {
            MediaPlayer.Event.TimeChanged -> "time=${event.timeChanged}ms"
            MediaPlayer.Event.PositionChanged ->
                "position=${String.format(Locale.US, "%.4f", event.positionChanged)}"
            MediaPlayer.Event.LengthChanged -> "length=${event.lengthChanged}ms"
            MediaPlayer.Event.SeekableChanged -> "seekable=${event.seekable}"
            MediaPlayer.Event.PausableChanged -> "pausable=${event.pausable}"
            MediaPlayer.Event.Vout -> "count=${event.voutCount}"
            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted,
            MediaPlayer.Event.ESSelected ->
                "trackType=${VlcTrackFormat.trackTypeName(event.esChangedType)} " +
                    "trackId=${event.esChangedID}"
            MediaPlayer.Event.RecordChanged ->
                "recording=${event.recording} path=${event.recordPath.orEmpty()}"
            else -> ""
        }
    }.getOrElse { "detail unavailable: ${LogRedaction.reason(it)}" }

    /** 日志标签。 */
    private companion object {
        const val TAG = VLC_PLAYER_TAG
    }
}
