package com.roy233.tvcast.core.diagnostics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsFileStoreTest {

    @Test
    fun `append history keeps the serialized line boundary`() {
        val directory = Files.createTempDirectory("diagnostics-store-").toFile()
        try {
            val file = File(directory, "playback-history.log")
            DiagnosticsFileStore.appendHistory(file, "[OPEN] id=session")
            DiagnosticsFileStore.appendHistory(file, "[CLOSE] id=session")

            assertEquals(
                "[OPEN] id=session\n[CLOSE] id=session\n",
                file.readText(),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `history rotation retains only the configured tail and marker`() {
        val directory = Files.createTempDirectory("diagnostics-rotation-").toFile()
        try {
            val file = File(directory, "playback-history.log")
            file.writeText("0123456789ABCDEFGHIJ")

            DiagnosticsFileStore.rotateHistoryIfNeeded(file, maxBytes = 10L, tailBytes = 8L)

            assertTrue(file.readText().startsWith("[ROTATED] trimmed playback history\n"))
            assertTrue(file.readText().endsWith("CDEFGHIJ"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
