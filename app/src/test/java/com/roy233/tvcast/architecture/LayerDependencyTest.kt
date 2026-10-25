package com.roy233.tvcast.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 约束分层依赖方向，避免下层反向依赖上层。 */
class LayerDependencyTest {

    private val root = File("src/main/java/com/roy233/tvcast")

    @Test
    fun `data layer does not depend on ui or feature packages`() {
        assertNoImports(
            packageDir = "data",
            forbidden = listOf("com.roy233.tvcast.ui", "com.roy233.tvcast.feature"),
        )
    }

    @Test
    fun `core layer does not depend on ui or feature packages`() {
        assertNoImports(
            packageDir = "core",
            forbidden = listOf("com.roy233.tvcast.ui", "com.roy233.tvcast.feature"),
        )
    }

    @Test
    fun `playback coordinators do not reference the root ui state holder`() {
        val dir = File(root, "feature/playback")
        val offenders = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("TvStateHolder") }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue("playback feature must not touch TvStateHolder: $offenders", offenders.isEmpty())
    }

    private fun assertNoImports(packageDir: String, forbidden: List<String>) {
        val dir = File(root, packageDir)
        val offenders = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .filter { line -> line.startsWith("import ") }
                    .filter { line -> forbidden.any { line.contains(it) } }
                    .map { "${file.relativeTo(root).path}: $it" }
            }
            .toList()
        assertTrue("unexpected cross-layer imports: $offenders", offenders.isEmpty())
    }
}
