package com.roy233.tvcast.feature.playback.application

import com.roy233.tvcast.ui.model.PlaybackUiState
import com.roy233.tvcast.ui.model.TvMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证遥控器播放、跳转与倍速命令对播放器和界面状态的影响。 */
class PlaybackCoordinatorTest {

    @Test
    fun `toggle pauses active playback and makes the HUD visible`() {
        val fixture = Fixture(state = PlaybackUiState(mode = TvMode.PLAYING, isPlaying = true))

        fixture.coordinator.togglePlayPause()

        assertEquals(listOf(true to "remote-toggle"), fixture.pauseCommands)
        assertFalse(fixture.playerPlaying)
        assertFalse(fixture.state.isPlaying)
        assertTrue(fixture.state.hudVisible)
    }

    @Test
    fun `seek clamps to media duration and keeps danmaku clock aligned`() {
        val fixture = Fixture(
            state = PlaybackUiState(mode = TvMode.PLAYING),
            positionMs = 9_500L,
            durationMs = 10_000L,
        )

        fixture.coordinator.seekBy(1_000L)

        assertEquals(listOf(10_000L to "remote-seek"), fixture.seekCommands)
        assertEquals(listOf(10_000L), fixture.danmakuSeeks)
        assertEquals(10_000L, fixture.state.positionMs)
        assertTrue(fixture.state.hudVisible)
    }

    @Test
    fun `live playback rejects seek and speed changes without touching the engine`() {
        val fixture = Fixture(
            state = PlaybackUiState(mode = TvMode.PLAYING),
            live = true,
        )

        fixture.coordinator.seekBy(5_000L)
        fixture.coordinator.setPlaybackSpeed(2f)

        assertTrue(fixture.seekCommands.isEmpty())
        assertTrue(fixture.rateCommands.isEmpty())
        assertEquals("直播不支持倍速", fixture.state.toast)
    }

    private class Fixture(
        var state: PlaybackUiState,
        private val live: Boolean = false,
        var positionMs: Long = 2_000L,
        var durationMs: Long = 10_000L,
        var playerPlaying: Boolean = true,
    ) {
        val pauseCommands = mutableListOf<Pair<Boolean, String>>()
        val seekCommands = mutableListOf<Pair<Long, String>>()
        val rateCommands = mutableListOf<Pair<Float, String>>()
        val danmakuSeeks = mutableListOf<Long>()

        val coordinator = PlaybackCoordinator(
            canControl = { state.mode == TvMode.PLAYING || state.mode == TvMode.LOADING },
            canPreviewSeek = { state.mode == TvMode.PLAYING },
            displayedDurationMs = { state.durationMs },
            previewPositionMs = { state.seekPreviewPositionMs },
            updatePlaying = { state = state.copy(isPlaying = it, hudVisible = true) },
            updatePosition = { position, duration ->
                state = state.copy(positionMs = position, durationMs = duration ?: state.durationMs, hudVisible = true)
            },
            updatePreview = { position, duration ->
                state = state.copy(
                    positionMs = position ?: state.positionMs,
                    durationMs = duration ?: state.durationMs,
                    seekPreviewPositionMs = position,
                )
            },
            updateSpeed = { speed, message ->
                state = state.copy(playbackSpeed = speed, hudVisible = true, toast = message)
            },
            updateVolume = { state = state.copy(volume = it, hudVisible = true) },
            showToast = { state = state.copy(toast = it) },
            isEngineInitialized = { true },
            isLive = { live },
            positionMs = { positionMs },
            durationMs = { durationMs },
            isPlaying = { playerPlaying },
            seekPlayer = { target, reason ->
                seekCommands += target to reason
                positionMs = target
                target
            },
            setPlayerPaused = { paused, reason ->
                pauseCommands += paused to reason
                playerPlaying = !paused
            },
            setPlayerPlaybackRate = { rate, reason ->
                rateCommands += rate to reason
                rate
            },
            setPlayerVolume = { _, _ -> },
            onDanmakuSeek = { danmakuSeeks += it },
            onDanmakuPlaybackRateChanged = {},
        )
    }
}
