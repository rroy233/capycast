import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    id("com.android.application") version "8.10.1" apply false
    id("com.diffplug.spotless") version "8.11.0"
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}

// 统一格式化应用源码与所有 Gradle Kotlin DSL；format 与 format-check 使用完全相同的范围。
configure<SpotlessExtension> {
    kotlin {
        target("app/src/**/*.kt")
        ktfmt("0.65").kotlinlangStyle()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        ktfmt("0.65").kotlinlangStyle()
    }
}
