package com.roy233.tvcast.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 约束分层依赖方向，避免下层反向依赖上层。 */
class LayerDependencyTest {

    private val root = File("src/main/java/com/roy233/tvcast")

    @Test
    fun `data layer depends only on feature owned ports and models`() {
        val offenders = File(root, "data").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .filter { it.startsWith("import ") && it.contains("com.roy233.tvcast.feature") }
                    .filterNot { line ->
                        line.contains("com.roy233.tvcast.feature.playback.model") ||
                            line.contains("com.roy233.tvcast.feature.playback.routing") ||
                            line.contains("com.roy233.tvcast.feature.cast.domain") ||
                            line.contains("com.roy233.tvcast.feature.account.model") ||
                            line.contains("com.roy233.tvcast.feature.update.model") ||
                            line.contains("com.roy233.tvcast.feature.danmaku.domain") ||
                            line.contains("com.roy233.tvcast.feature.history")
                    }
                    .map { "${file.relativeTo(root).path}: $it" }
            }
            .toList()
        assertTrue("unexpected data-to-feature imports: $offenders", offenders.isEmpty())
        assertNoImports(packageDir = "data", forbidden = listOf("com.roy233.tvcast.ui"))
    }

    @Test
    fun `core layer does not depend on data ui feature or platform packages`() {
        assertNoImports(
            packageDir = "core",
            forbidden = listOf(
                "com.roy233.tvcast.ui",
                "com.roy233.tvcast.feature",
                "com.roy233.tvcast.data",
                "com.roy233.tvcast.platform",
            ),
        )
    }

    @Test
    fun `playback coordinators do not reference the root ui state holder`() {
        val dir = File(root, "feature/playback")
        val offenders = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val source = file.readText()
                source.contains("TvStateHolder") || source.contains("TvUiState")
            }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue("playback feature must not depend on root UI state: $offenders", offenders.isEmpty())
    }

    @Test
    fun `feature layer does not depend on data adapters`() {
        assertNoImports(
            packageDir = "feature",
            forbidden = listOf("com.roy233.tvcast.data"),
        )
    }

    @Test
    fun `platform layer does not depend on feature data or ui packages`() {
        assertNoImports(
            packageDir = "platform",
            forbidden = listOf(
                "com.roy233.tvcast.feature",
                "com.roy233.tvcast.data",
                "com.roy233.tvcast.ui",
            ),
        )
    }

    @Test
    fun `platform player adapters do not depend on Bilibili data models`() {
        assertNoImports(
            packageDir = "platform/player",
            forbidden = listOf("com.roy233.tvcast.data.bili"),
        )
    }

    @Test
    fun `playback domain does not depend on Android UI or player SDKs`() {
        assertNoImports(
            packageDir = "feature/playback/domain",
            forbidden = listOf(
                "android.",
                "androidx.compose",
                "com.google.android.exoplayer",
                "org.videolan.libvlc",
                "com.roy233.tvcast.platform.player",
                "com.roy233.tvcast.data.bili",
            ),
        )
    }

    @Test
    fun `account update and playback coordinators do not keep full root state`() {
        val dirs = listOf("feature/account", "feature/update", "feature/playback/application")
        val offenders = dirs.flatMap { packageDir ->
            File(root, packageDir).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { file ->
                    val source = file.readText()
                    source.contains("TvUiState") || source.contains("TvStateHolder")
                }
                .map { it.relativeTo(root).path }
                .toList()
        }
        assertTrue("feature coordinators must use owned state slices: $offenders", offenders.isEmpty())
    }

    @Test
    fun `project remains a single app module`() {
        val settings = File("../settings.gradle.kts").readText()
        val modules = Regex("""include\("([^\"]+)"\)""")
            .findAll(settings)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("expected only the :app module, found $modules", modules == listOf(":app"))
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
