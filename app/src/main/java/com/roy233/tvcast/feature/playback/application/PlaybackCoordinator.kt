package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.ui.model.PlaybackSpeed
import com.roy233.tvcast.ui.model.TvMode
import com.roy233.tvcast.ui.model.PlaybackUiState

/**
 * 协调通用播放控制：暂停/恢复、进度调整、倍速与音量。
 *
 * 只承载与具体内容类型无关的传输操作；直播/点播会话、线路恢复和投屏解析由
 * 各自的协调器负责。
 */
class PlaybackCoordinator(
    private val readState: () -> PlaybackUiState,
    private val writeState: (PlaybackUiState) -> Unit,
    private val isEngineInitialized: () -> Boolean,
    private val isLive: () -> Boolean,
    private val positionMs: () -> Long,
    private val durationMs: () -> Long,
    private val isPlaying: () -> Boolean,
    private val seekPlayer: (Long, String) -> Long,
    private val setPlayerPaused: (Boolean, String) -> Unit,
    private val setPlayerPlaybackRate: (Float, String) -> Float,
    private val setPlayerVolume: (Int, String) -> Unit,
    private val onDanmakuSeek: (Long) -> Unit,
    private val onDanmakuPlaybackRateChanged: (Float) -> Unit,
) {

    /** 切换播放与暂停。 */
    fun togglePlayPause() {
        if (!isEngineInitialized()) {
            LogHub.w(TAG, "togglePlayPause ignored: player engine unavailable")
            return
        }
        val state = readState()
        if (state.mode != TvMode.PLAYING && state.mode != TvMode.LOADING) {
            LogHub.d(TAG, "togglePlayPause ignored: mode=${state.mode}")
            return
        }
        val playing = isPlaying()
        setPlayerPaused(playing, "remote-toggle")
        writeState(
            readState().copy(
                isPlaying = isPlaying(),
                hudVisible = true,
            ),
        )
    }

    /** 按指定毫秒数调整当前播放位置。 */
    fun seekBy(ms: Long) {
        if (isLive()) {
            LogHub.w(TAG, "seek ignored for live delta=${ms}ms")
            return
        }
        if (!isEngineInitialized()) {
            LogHub.w(TAG, "seekBy ignored: player engine unavailable")
            return
        }
        val length = durationMs()
        val max = if (length > 0) length else Long.MAX_VALUE
        val currentPosition = positionMs()
        val target = (currentPosition + ms).coerceIn(0L, max)
        if (length <= 0) {
            LogHub.w(TAG, "seekBy with unknown length; target=$target ms")
        }
        seekPlayer(target, "remote-seek")
        onDanmakuSeek(target)
        writeState(readState().copy(positionMs = target, hudVisible = true))
    }

    /**
     * 预览性地跳转到新位置。
     *
     * 每次方向键输入都会立即驱动播放器跳转，以便画面加载目标位置；待确认状态只用于
     * 控制进度条显示与自动提交，不会延后播放器的实际定位。
     */
    fun previewSeekBy(ms: Long) {
        if (isLive()) return
        val state = readState()
        if (!isEngineInitialized() || state.mode != TvMode.PLAYING) return

        val duration = durationMs().takeIf { it > 0L } ?: state.durationMs
        if (duration <= 0L) {
            LogHub.w(TAG, "previewSeekBy ignored: unknown duration")
            return
        }
        val basePosition = state.seekPreviewPositionMs ?: positionMs()
        val target = (basePosition + ms).coerceIn(0L, duration)
        seekPlayer(target, "remote-seek-preview")
        onDanmakuSeek(target)
        writeState(
            readState().copy(
                positionMs = target,
                durationMs = duration,
                seekPreviewPositionMs = target,
            ),
        )
    }

    /** 结束当前预览会话；播放器已在预览期间定位到目标位置。 */
    fun confirmPreviewSeek() {
        val state = readState()
        if (!isLive() && state.seekPreviewPositionMs != null) {
            writeState(state.copy(seekPreviewPositionMs = null))
        }
    }

    /** 将播放器与弹幕时钟切换到相同的标准播放速度。 */
    fun setPlaybackSpeed(rate: Float) {
        if (isLive()) {
            LogHub.w(TAG, "playback speed change ignored for live rate=$rate")
            writeState(readState().copy(toast = "直播不支持倍速"))
            return
        }
        val state = readState()
        if (!isEngineInitialized() ||
            (state.mode != TvMode.PLAYING && state.mode != TvMode.LOADING)
        ) {
            LogHub.w(TAG, "setPlaybackSpeed ignored: player unavailable or mode=${state.mode}")
            return
        }
        val target = PlaybackSpeed.normalize(rate)
        val applied = setPlayerPlaybackRate(target, "remote-speed")
        onDanmakuPlaybackRateChanged(applied)
        writeState(
            readState().copy(
                playbackSpeed = applied,
                hudVisible = true,
                toast = "播放速度 ${PlaybackSpeed.label(applied)}",
            ),
        )
    }

    /** 设置音量，沿用界面的 0..100 标度。 */
    fun setVolume(volume: Int) {
        if (!isEngineInitialized()) {
            LogHub.w(TAG, "setVolume ignored: player engine unavailable")
            return
        }
        val value = volume.coerceIn(0, 100)
        setPlayerVolume(value, "remote-volume")
        writeState(readState().copy(volume = value, hudVisible = true))
    }

    private companion object {
        /** 日志标签。 */
        const val TAG = "SimpleBili"
    }
}
