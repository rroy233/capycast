package com.roy233.tvcast.platform.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationVideoSizeTest {
    @Test
    fun `stopping the decoder does not invalidate calibration`() {
        assertFalse(isUnexpectedCalibrationVideoSize(0, 0))
        assertFalse(isUnexpectedCalibrationVideoSize(3840, 2160))
        assertTrue(isUnexpectedCalibrationVideoSize(1920, 1080))
    }
}
