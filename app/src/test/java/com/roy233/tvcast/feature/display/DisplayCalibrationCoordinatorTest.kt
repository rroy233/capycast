package com.roy233.tvcast.feature.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayCalibrationCoordinatorTest {
    private class MemoryStore(initial: SavedCalibration = SavedCalibration(false, DisplayCorners())) : DisplayCalibrationStore {
        var value = initial
        var saves = 0
        override fun load() = value
        override fun savePrompted() { value = value.copy(prompted = true) }
        override fun save(corners: DisplayCorners) {
            value = SavedCalibration(true, corners, true)
            saves++
        }
    }

    @Test
    fun `first launch prompts once and skipping preserves the full frame`() {
        val store = MemoryStore()
        val applied = mutableListOf<Pair<DisplayCorners, Boolean>>()
        val coordinator = DisplayCalibrationCoordinator(store, { 1920 to 1080 }, {}, { corners, completed ->
            applied += corners to completed
        })
        coordinator.initialize()
        assertEquals(CalibrationPhase.INTRO, coordinator.state.phase)
        coordinator.skipOrCancel()
        assertTrue(store.value.prompted)
        assertEquals(DisplayCorners(), store.value.corners)
        assertEquals(0, store.saves)
        assertFalse(store.value.completed)

        val restarted = DisplayCalibrationCoordinator(store, { 1920 to 1080 }, {}, { corners, completed ->
            applied += corners to completed
        })
        restarted.initialize()
        assertEquals(CalibrationPhase.HIDDEN, restarted.state.phase)
        assertEquals(DisplayCorners() to false, applied.last())
    }

    @Test
    fun `each corner moves independently and saves only after confirmation`() {
        val store = MemoryStore()
        val applied = mutableListOf<Pair<DisplayCorners, Boolean>>()
        val coordinator = DisplayCalibrationCoordinator(store, { 100 to 100 }, {}, { corners, completed ->
            applied += corners to completed
        })
        coordinator.initialize()
        coordinator.start()
        coordinator.adjust(CalibrationDirection.RIGHT)
        coordinator.adjust(CalibrationDirection.DOWN)
        assertEquals(CalibrationPoint(0.02f, 0.02f), coordinator.state.draft.topLeft)
        assertEquals(CalibrationPoint(0f, 1f), coordinator.state.draft.bottomLeft)
        coordinator.nextCorner()
        coordinator.adjust(CalibrationDirection.LEFT)
        coordinator.nextCorner()
        coordinator.adjust(CalibrationDirection.UP)
        coordinator.nextCorner()
        coordinator.nextCorner()
        assertEquals(CalibrationPhase.CONFIRM, coordinator.state.phase)
        assertEquals(0, store.saves)
        coordinator.review()
        assertEquals(1, coordinator.state.corner)
        repeat(4) { coordinator.nextCorner() }
        coordinator.finish()

        assertEquals(CalibrationPhase.HIDDEN, coordinator.state.phase)
        assertEquals(1, store.saves)
        assertEquals(
            DisplayCorners(
                topLeft = CalibrationPoint(0.02f, 0.02f),
                topRight = CalibrationPoint(0.98f, 0f),
                bottomRight = CalibrationPoint(1f, 0.98f),
            ),
            store.value.corners,
        )
        assertEquals(store.value.corners to true, applied.last())
    }

    @Test
    fun `canceling a manual calibration keeps the previous saved geometry`() {
        val original = DisplayCorners(topLeft = CalibrationPoint(0.03f, 0.04f))
        val store = MemoryStore(SavedCalibration(true, original))
        val coordinator = DisplayCalibrationCoordinator(store, { 1920 to 1080 }, {}, { _, _ -> })
        coordinator.initialize()
        coordinator.start()
        coordinator.adjust(CalibrationDirection.RIGHT)
        coordinator.skipOrCancel()
        assertEquals(original, store.value.corners)
        assertEquals(0, store.saves)
        assertFalse(coordinator.state.phase == CalibrationPhase.ADJUST)
    }

    @Test
    fun `finishing with default corners still enables the calibrated output path`() {
        val store = MemoryStore()
        val coordinator = DisplayCalibrationCoordinator(store, { 1920 to 1080 }, {}, { _, _ -> })
        coordinator.initialize()
        coordinator.start()
        repeat(4) { coordinator.nextCorner() }
        coordinator.finish()
        assertEquals(DisplayCorners(), store.value.corners)
        assertTrue(store.value.completed)
    }

    @Test
    fun `corner positions clamp invalid values`() {
        assertEquals(DisplayCorners(), DisplayCorners(
            topLeft = CalibrationPoint(Float.NaN, Float.POSITIVE_INFINITY),
            topRight = CalibrationPoint(2f, -1f),
            bottomRight = CalibrationPoint(Float.NEGATIVE_INFINITY, 3f),
            bottomLeft = CalibrationPoint(-1f, 2f),
        ).sanitized())
    }
}
