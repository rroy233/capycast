package com.roy233.tvcast.feature.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 验证更新流程开始前的默认状态。 */
class UpdateUiStateTest {

    @Test
    fun `default state is stable automatic and idle`() {
        val state = UpdateUiState()

        assertEquals(UpdateChannel.STABLE, state.channel)
        assertEquals(UpdateStatus.IDLE, state.status)
        assertEquals(true, state.automaticCheck)
        assertNull(state.availableUpdate)
        assertNull(state.downloadedApkPath)
        assertEquals(0L, state.downloadedBytes)
        assertEquals(0L, state.totalBytes)
    }
}
