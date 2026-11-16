package com.roy233.tvcast.feature.feedback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackCoordinatorTest {

    @Test
    fun `open defaults to GitHub and uses current metadata`() {
        val fixture = fixture()

        fixture.coordinator.open()

        assertEquals(listOf(FeedbackChannel.GITHUB.qrPayload), fixture.payloads)
        assertTrue(fixture.state.visible)
        assertEquals(FeedbackChannel.GITHUB, fixture.state.channel)
        assertEquals("0.2.1 (19)", fixture.state.versionLabel)
        assertFalse(fixture.state.isGenerating)
        assertEquals("二维码生成失败，请重试。", fixture.state.errorMessage)
    }

    @Test
    fun `switching channel replaces QR payload and close clears session state`() {
        val fixture = fixture()
        fixture.coordinator.open()

        fixture.coordinator.selectChannel(FeedbackChannel.EMAIL)
        fixture.coordinator.close()

        assertEquals(
            listOf(FeedbackChannel.GITHUB.qrPayload, FeedbackChannel.EMAIL.qrPayload),
            fixture.payloads,
        )
        assertEquals(FeedbackUiState(), fixture.state)
        assertFalse(fixture.state.visible)
        assertNull(fixture.state.qrCode)
    }

    @Test
    fun `switching back to cached channel does not re-encode payload`() {
        var state = FeedbackUiState()
        val payloads = mutableListOf<String>()
        val constructor =
            android.graphics.Bitmap::class.java.declaredConstructors.first().apply {
                isAccessible = true
            }
        val params =
            constructor.parameterTypes
                .map<Class<*>, Any?> {
                    when {
                        it == Boolean::class.javaPrimitiveType -> false
                        it == Int::class.javaPrimitiveType -> 0
                        it == Long::class.javaPrimitiveType -> 0L
                        it.isPrimitive -> 0
                        else -> null
                    }
                }
                .toTypedArray()
        val dummyBitmap = constructor.newInstance(*params) as android.graphics.Bitmap
        val coordinator =
            FeedbackCoordinator(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                qrCodeEncoder =
                    FeedbackQrCodeEncoder { payload ->
                        payloads += payload
                        dummyBitmap
                    },
                metadataProvider =
                    FeedbackMetadataProvider {
                        FeedbackMetadata(
                            versionLabel = "0.2.1 (19)",
                        )
                    },
                readState = { state },
                writeState = { state = it },
                generationDispatcher = Dispatchers.Unconfined,
            )

        coordinator.open()
        coordinator.selectChannel(FeedbackChannel.EMAIL)
        coordinator.selectChannel(FeedbackChannel.GITHUB)

        assertEquals(
            listOf(FeedbackChannel.GITHUB.qrPayload, FeedbackChannel.EMAIL.qrPayload),
            payloads,
        )
        assertEquals(FeedbackChannel.GITHUB, state.channel)
        assertEquals(dummyBitmap, state.qrCode)
        assertFalse(state.isGenerating)
    }

    private fun fixture(): Fixture {
        var state = FeedbackUiState()
        val payloads = mutableListOf<String>()
        val coordinator =
            FeedbackCoordinator(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                qrCodeEncoder =
                    FeedbackQrCodeEncoder { payload ->
                        payloads += payload
                        null
                    },
                metadataProvider =
                    FeedbackMetadataProvider {
                        FeedbackMetadata(
                            versionLabel = "0.2.1 (19)",
                        )
                    },
                readState = { state },
                writeState = { state = it },
                generationDispatcher = Dispatchers.Unconfined,
            )
        return Fixture(coordinator, { state }, payloads)
    }

    private class Fixture(
        val coordinator: FeedbackCoordinator,
        private val readState: () -> FeedbackUiState,
        val payloads: List<String>,
    ) {
        val state: FeedbackUiState
            get() = readState()
    }
}
