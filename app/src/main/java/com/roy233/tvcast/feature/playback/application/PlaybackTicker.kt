package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.core.playback.LivePlaybackTelemetry
import com.roy233.tvcast.core.playback.PlaybackSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 播放轮询需要宿主提供的播放状态、诊断与网络信息。 */
interface PlaybackTickerHost {
    val coroutineScope: CoroutineScope
    fun isEngineInitialized(): Boolean
    fun isPlaybackVisible(): Boolean
    fun isBuffering(): Boolean
    fun displayedDurationMs(): Long
    fun updateProgress(positionMs: Long, durationMs: Long, playing: Boolean, volume: Int, liveBackupCount: Int)
    fun updateNetwork(wifiName: String, localIp: String)
    fun snapshot(): PlaybackSnapshot
    fun currentAvailable(): Boolean
    fun isLive(): Boolean
    fun liveBackupCount(): Int
    fun liveTelemetry(): LivePlaybackTelemetry?
    fun onRefreshVideoSize()
    fun onDanmakuPlayerTime(positionMs: Long)
    fun onVodProgress(positionMs: Long, activelyPlaying: Boolean)
    fun onHistoryPosition(positionMs: Long, durationMs: Long, playing: Boolean)
    fun onLiveProgress(progress: LivePlaybackTelemetry, playing: Boolean)
    fun wifiName(): String
    fun localIp(): String
}

/**
 * 定时轮询播放进度并同步界面、弹幕、历史与直播健康状态。
 *
 * 轮询周期、采样条件、日志与状态更新频率由调用方通过 [PlaybackTickerHost] 提供。
 */
class PlaybackTicker(
    private val host: PlaybackTickerHost,
) {

    /** 当前轮询任务。 */
    private var tickerJob: Job? = null

    /** 启动定时任务以同步播放进度和设备网络信息。 */
    fun start() {
        tickerJob?.cancel()
        tickerJob = host.coroutineScope.launch {
            var tick = 0
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                tick++
                if (host.isEngineInitialized() &&
                    host.isPlaybackVisible()
                ) {
                    val snapshot = host.snapshot()
                    val pos = snapshot.positionMs
                    val len = snapshot.durationMs
                    val playing = snapshot.isPlaying
                    val activelyPlaying = playing && !host.isBuffering()
                    val volume = snapshot.volume
                    val live = host.isLive()
                    if (!live) host.onVodProgress(pos, activelyPlaying)
                    if (host.currentAvailable()) {
                        host.onHistoryPosition(
                            positionMs = if (live) 0L else pos,
                            durationMs = if (live) 0L else len,
                            playing = activelyPlaying,
                        )
                    }
                    if (live) {
                        host.liveTelemetry()?.let { host.onLiveProgress(it, activelyPlaying) }
                    }
                    host.updateProgress(
                        positionMs = if (live) 0L else pos.coerceAtLeast(0L),
                        durationMs = if (live) 0L else if (len > 0) len else host.displayedDurationMs(),
                        playing = playing,
                        volume = volume,
                        liveBackupCount = if (live) host.liveBackupCount() else 0,
                    )
                    if (playing && !host.isBuffering()) {
                        host.onRefreshVideoSize()
                        if (!live) host.onDanmakuPlayerTime(pos.coerceAtLeast(0L))
                    }
                }
                if (tick % NETWORK_REFRESH_TICKS == 0) {
                    host.updateNetwork(host.wifiName(), host.localIp())
                }
            }
        }
    }

    /** 停止轮询任务。 */
    fun stop() {
        tickerJob?.cancel()
    }

    private companion object {
        /** 播放进度轮询周期。 */
        const val TICK_INTERVAL_MS = 500L
        /** 网络信息刷新间隔（以轮询次数计）。 */
        const val NETWORK_REFRESH_TICKS = 10
    }
}
