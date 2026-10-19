package com.roy233.tvcast.platform.player

import android.os.Handler
import android.os.SystemClock
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import org.json.JSONObject
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia

/**
 * 周期性检查播放启动、视频输出、进度停滞和统计异常。
 *
 * 监控任务只在主线程处理器上运行；打开、停止和失败路径通过 [start] / [stop]
 * 统一管理生命周期，避免多个监控任务并发写同一份状态。
 *
 * @param player 播放器实例。
 * @param state 当前播放会话状态。
 * @param reporter 用于输出轨道、原生日志和状态快照的报告器。
 * @param handler 主线程处理器。
 */
internal class VlcPlaybackMonitor(
    private val player: MediaPlayer,
    private val state: VlcPlaybackState,
    private val reporter: VlcPlaybackReporter,
    private val handler: Handler,
) {

    /** 周期性播放监控任务。 */
    private val monitor = object : Runnable {
        /** 执行一次监控检查并重新调度。 */
        override fun run() {
            if (!state.active) return
            runCatching { monitorTick() }
                .onFailure {
                    LogHub.e(TAG, "monitor tick failed", it, stack = true)
                }
            if (state.active) handler.postDelayed(this, VLC_MONITOR_INTERVAL_MS)
        }
    }

    /** 启动周期监控。 */
    fun start() {
        handler.removeCallbacks(monitor)
        state.active = true
        handler.postDelayed(monitor, VLC_MONITOR_INTERVAL_MS)
    }

    /** 停止周期监控。 */
    fun stop() {
        state.active = false
        handler.removeCallbacks(monitor)
    }

    /**
     * 采集一次播放统计并检测异常增量。
     *
     * @param force 是否忽略采样间隔立即采集。
     */
    @Synchronized
    fun sampleStats(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - state.lastStatsAt < VLC_STATS_INTERVAL_MS) return
        state.lastStatsAt = now
        val media = state.currentMedia ?: return
        val stats = runCatching { media.stats }.getOrNull()
        if (stats == null) {
            if (!state.statsUnavailableLogged) {
                state.statsUnavailableLogged = true
                LogHub.w(TAG, "media stats unavailable; per-stream counters cannot be sampled")
            }
            return
        }
        val sample = JSONObject().apply {
            put("atMs", now - state.mediaOpenedAt)
            put("positionMs", runCatching { player.time }.getOrDefault(-1L))
            put("readBytes", stats.readBytes)
            put("inputBitrate", stats.inputBitrate.toDouble())
            put("demuxReadBytes", stats.demuxReadBytes)
            put("demuxBitrate", stats.demuxBitrate.toDouble())
            put("demuxCorrupted", stats.demuxCorrupted)
            put("demuxDiscontinuity", stats.demuxDiscontinuity)
            put("decodedVideo", stats.decodedVideo)
            put("decodedAudio", stats.decodedAudio)
            put("displayedPictures", stats.displayedPictures)
            put("lostPictures", stats.lostPictures)
            put("playedAbuffers", stats.playedAbuffers)
            put("lostAbuffers", stats.lostAbuffers)
        }
        state.lastStats = sample
        PlaybackDiagnostics.stats(sample)
        if (!state.statsFirstLogged) {
            state.statsFirstLogged = true
            LogHub.i(
                TAG,
                "stats baseline position=${sample.optLong("positionMs")}ms " +
                    "readBytes=${stats.readBytes} inputBitrate=${stats.inputBitrate} " +
                    "decodedVideo=${stats.decodedVideo} decodedAudio=${stats.decodedAudio} " +
                    "displayed=${stats.displayedPictures} lost=${stats.lostPictures} " +
                    "demuxCorrupted=${stats.demuxCorrupted} " +
                    "demuxDiscontinuity=${stats.demuxDiscontinuity}",
            )
        }
        checkStatAnomalies(stats, now)
        state.previousStats = stats
    }

    /**
     * 提示一次同类异常。
     *
     * @param key 异常类型键。
     * @param message 异常描述。
     */
    @Synchronized
    fun warnOnce(key: String, message: String) {
        if (!state.warnedOnce.add(key)) return
        LogHub.w(TAG, message)
        PlaybackDiagnostics.event("Anomaly", "$key $message")
    }

    /**
     * 限频提示同类异常。
     *
     * @param key 异常类型键。
     * @param message 异常描述。
     */
    @Synchronized
    fun warnThrottled(key: String, message: String) {
        val now = SystemClock.elapsedRealtime()
        val last = state.warnedAt[key] ?: 0L
        if (now - last < VLC_ANOMALY_INTERVAL_MS) return
        state.warnedAt[key] = now
        LogHub.w(TAG, message)
        PlaybackDiagnostics.event("Anomaly", "$key $message")
    }

    /** 周期性检查启动、视频输出和进度停滞。 */
    private fun monitorTick() {
        if (!state.active) return
        val now = SystemClock.elapsedRealtime()
        val openElapsed = now - state.mediaOpenedAt
        if (!state.openingSeen && openElapsed > VLC_NO_OPENING_MS) {
            warnOnce(
                "noOpening",
                "no Opening event after ${openElapsed}ms state=${reporter.playerStateName()} " +
                    "nativeLogBytes=${PlaybackDiagnostics.vlcLogBytes()}",
            )
        }
        if (!state.playingSeen && openElapsed > VLC_NO_PLAYING_MS) {
            warnOnce(
                "noPlaying",
                "no Playing event after ${openElapsed}ms state=${reporter.playerStateName()} " +
                    "nativeLogBytes=${PlaybackDiagnostics.vlcLogBytes()}",
            )
            reporter.logTrackSnapshot("watchdog-no-playing")
            reporter.logNativeLogTail("watchdog-no-playing", 25)
        }
        if (state.playingSeen && !state.voutSeen && now - state.playingAt > VLC_NO_VOUT_MS) {
            warnOnce(
                "noVout",
                "no video output ${now - state.playingAt}ms after Playing; " +
                    "possible audio-only playback, decoder or surface failure",
            )
            reporter.logTrackSnapshot("watchdog-no-vout")
            sampleStats(force = true)
        }
        if (state.playingSeen &&
            runCatching { player.isPlaying }.getOrDefault(false) &&
            state.lastProgressAt > 0L &&
            now - state.lastProgressAt > VLC_STALL_MS
        ) {
            warnThrottled(
                "stalled",
                "playback position stalled for ${now - state.lastProgressAt}ms at " +
                    "${runCatching { player.time }.getOrDefault(-1L)}ms",
            )
            sampleStats(force = true)
            reporter.logNativeLogTail("watchdog-stalled", 25)
        }
        if (!state.vlcLogWarningLogged &&
            openElapsed > VLC_NO_NATIVE_LOG_MS &&
            PlaybackDiagnostics.vlcLogBytes() <= 0L
        ) {
            state.vlcLogWarningLogged = true
            LogHub.w(
                TAG,
                "libvlc native log file still empty after ${openElapsed}ms; " +
                    "playback report will read the VLC logcat instead",
            )
            PlaybackDiagnostics.event(
                "Anomaly",
                "nativeLogEmpty after=${openElapsed}ms",
            )
        }
        sampleStats(force = false)
    }

    /**
     * 对比相邻统计采样，记录丢帧、损坏和停读等异常。
     *
     * @param stats 本次统计。
     * @param now 当前单调时刻。
     */
    private fun checkStatAnomalies(stats: IMedia.Stats, now: Long) {
        val previous = state.previousStats ?: return
        val playing = runCatching { player.isPlaying }.getOrDefault(false)
        val position = runCatching { player.time }.getOrDefault(-1L)
        val positionMoved = position > 0L
        val lostDelta = stats.lostPictures - previous.lostPictures
        if (lostDelta > 0) {
            warnThrottled(
                "lostPictures",
                "dropped video frames +$lostDelta (total=${stats.lostPictures})",
            )
        }
        val corruptedDelta = stats.demuxCorrupted - previous.demuxCorrupted
        if (corruptedDelta > 0) {
            warnThrottled(
                "demuxCorrupted",
                "demux corrupted blocks +$corruptedDelta (total=${stats.demuxCorrupted})",
            )
        }
        val readDelta = stats.readBytes - previous.readBytes
        if (playing && positionMoved && readDelta <= 0) {
            warnThrottled(
                "noRead",
                "no network read progress while playing (readBytes=${stats.readBytes})",
            )
        }
        val videoDelta = stats.decodedVideo - previous.decodedVideo
        val displayDelta = stats.displayedPictures - previous.displayedPictures
        if (playing && positionMoved && videoDelta <= 0) {
            warnThrottled(
                "noDecodedVideo",
                "no decoded video frames while position advances " +
                    "(decoded=${stats.decodedVideo} displayed=${stats.displayedPictures})",
            )
        }
        if (videoDelta > 0 && displayDelta <= 0) {
            warnThrottled(
                "notDisplayed",
                "decoded $videoDelta video frame(s) but none displayed " +
                    "(displayed=${stats.displayedPictures} lost=${stats.lostPictures})",
            )
        }
        if (playing &&
            positionMoved &&
            stats.playedAbuffers == 0 &&
            runCatching { player.audioTracksCount }.getOrDefault(0) > 0
        ) {
            warnThrottled(
                "noAudio",
                "no audio buffers played yet (audioTracks=" +
                    "${runCatching { player.audioTracksCount }.getOrDefault(0)})",
            )
        }
        if (now - state.mediaOpenedAt > 30_000L &&
            stats.decodedVideo == 0 &&
            stats.displayedPictures == 0
        ) {
            warnThrottled(
                "noVideoStats",
                "no video frames decoded after ${now - state.mediaOpenedAt}ms " +
                    "(audioOnly=${stats.decodedAudio > 0})",
            )
        }
    }

    /** 日志标签。 */
    private companion object {
        const val TAG = VLC_PLAYER_TAG
    }
}
