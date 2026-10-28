package com.roy233.tvcast.ui.model

import com.roy233.tvcast.feature.update.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证根状态与播放/投屏状态切片之间的字段映射。 */
class PlaybackUiStateMappingTest {

    @Test
    fun `playback state round trip preserves every root state field`() {
        val root = TvUiState(
            loggedIn = true,
            accountName = "测试账号",
            mid = 42L,
            vip = true,
            version = "0.1.5",
            update = TvUiState().update.copy(status = UpdateStatus.AVAILABLE),
            castTitle = "测试投屏",
            isPlaying = true,
            positionMs = 12_345L,
            durationMs = 67_890L,
            toast = "已连接",
        )

        assertEquals(root, root.withPlaybackUiState(root.playbackUiState()))
    }

    @Test
    fun `playback state updates preserve account update and version fields`() {
        val root = TvUiState(
            loggedIn = true,
            accountName = "测试账号",
            mid = 42L,
            vip = true,
            version = "0.1.5",
            update = TvUiState().update.copy(status = UpdateStatus.DOWNLOADING),
        )
        val playback = root.playbackUiState().copy(
            isPlaying = true,
            positionMs = 5_000L,
            toast = "正在播放",
        )

        val updated = root.withPlaybackUiState(playback)

        assertEquals(true, updated.loggedIn)
        assertEquals("测试账号", updated.accountName)
        assertEquals(42L, updated.mid)
        assertEquals(true, updated.vip)
        assertEquals("0.1.5", updated.version)
        assertEquals(UpdateStatus.DOWNLOADING, updated.update.status)
        assertEquals(true, updated.isPlaying)
        assertEquals(5_000L, updated.positionMs)
        assertEquals("正在播放", updated.toast)
    }
}
