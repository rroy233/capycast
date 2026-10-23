package com.roy233.tvcast.ui.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSeekAcceleratorTest {

    @Test
    fun holdsIncreaseTheSeekStepEverySecond() {
        val accelerator = PlaybackSeekAccelerator()

        assertEquals(
            10_000L,
            accelerator.nextIncrement(PlaybackSeekDirection.FORWARD, 1_000L, repeatCount = 0),
        )
        assertEquals(
            15_000L,
            accelerator.nextIncrement(PlaybackSeekDirection.FORWARD, 2_000L, repeatCount = 1),
        )
        assertEquals(
            22_500L,
            accelerator.nextIncrement(PlaybackSeekDirection.FORWARD, 3_000L, repeatCount = 2),
        )
    }

    @Test
    fun aNewPressOrDirectionRestoresTheBaseStep() {
        val accelerator = PlaybackSeekAccelerator()

        accelerator.nextIncrement(PlaybackSeekDirection.FORWARD, 1_000L, repeatCount = 0)
        accelerator.nextIncrement(PlaybackSeekDirection.FORWARD, 3_000L, repeatCount = 2)

        assertEquals(
            -10_000L,
            accelerator.nextIncrement(PlaybackSeekDirection.BACKWARD, 3_100L, repeatCount = 3),
        )
        assertEquals(
            10_000L,
            accelerator.nextIncrement(PlaybackSeekDirection.FORWARD, 4_000L, repeatCount = 0),
        )
    }
}
