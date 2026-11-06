package com.roy233.tvcast.ui.tv

import com.roy233.tvcast.feature.display.CalibrationPhase
import com.roy233.tvcast.feature.display.CalibrationState
import com.roy233.tvcast.ui.model.TvUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationKeyRoutingTest {
    @Test
    fun `calibration owns back and key routing above the underlying page`() {
        for (phase in listOf(CalibrationPhase.INTRO, CalibrationPhase.ADJUST, CalibrationPhase.CONFIRM)) {
            val state = TvUiState(calibration = CalibrationState(phase = phase))
            assertEquals(KeySurface.CALIBRATION, TvKeyHandler.resolveKeySurface(state))
            assertEquals(TvBackAction.CANCEL_CALIBRATION, TvKeyHandler.resolveBackAction(state))
        }
    }
}
