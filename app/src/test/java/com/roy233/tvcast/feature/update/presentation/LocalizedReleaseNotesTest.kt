package com.roy233.tvcast.feature.update.presentation

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalizedReleaseNotesTest {
    private val body =
        requireNotNull(javaClass.getResource("/release-notes/multilingual-body.md")).readText()

    @Test
    fun selectsGeneratedSectionsForEachAppLanguage() {
        assertEquals(
            "# 更新内容\n\n- 支持字幕",
            LocalizedReleaseNotes.select(body, Locale.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "# 更新內容\n\n- 支援字幕",
            LocalizedReleaseNotes.select(body, Locale.TRADITIONAL_CHINESE),
        )
        assertEquals(
            "# What’s New\n\n- Added subtitles",
            LocalizedReleaseNotes.select(body, Locale.UK),
        )
    }

    @Test
    fun matchesChineseScriptBeforeRegion() {
        val hantChina = Locale.Builder().setLanguage("zh").setScript("Hant").setRegion("CN").build()
        val hansTaiwan =
            Locale.Builder().setLanguage("zh").setScript("Hans").setRegion("TW").build()
        assertEquals("# 更新內容\n\n- 支援字幕", LocalizedReleaseNotes.select(body, hantChina))
        assertEquals("# 更新内容\n\n- 支持字幕", LocalizedReleaseNotes.select(body, hansTaiwan))
        assertEquals(
            "# 更新內容\n\n- 支援字幕",
            LocalizedReleaseNotes.select(body, Locale.forLanguageTag("zh-HK")),
        )
        assertEquals(
            "# 更新內容\n\n- 支援字幕",
            LocalizedReleaseNotes.select(body, Locale.forLanguageTag("zh-MO")),
        )
        assertEquals("# 更新内容\n\n- 支持字幕", LocalizedReleaseNotes.select(body, Locale.CHINESE))
    }

    @Test
    fun returnsTheEntireBodyForLegacyUnsupportedOrMissingLanguage() {
        val legacy = "# 旧版本\r\n- 更新内容\r\n"
        val missingEnglish = body.substringBefore("<details>\n<summary>English</summary>")
        assertEquals(legacy, LocalizedReleaseNotes.select(legacy, Locale.ENGLISH))
        assertEquals(body, LocalizedReleaseNotes.select(body, Locale.FRENCH))
        assertEquals(body, LocalizedReleaseNotes.select(body, null))
        assertEquals(missingEnglish, LocalizedReleaseNotes.select(missingEnglish, Locale.ENGLISH))
    }

    @Test
    fun returnsOriginalBodyForInvalidOrEmptySections() {
        val cases =
            listOf(
                body.replace("<!-- capycast:release-notes:en:end -->", ""),
                body +
                    "\n<!-- capycast:release-notes:en:start -->\nduplicate\n<!-- capycast:release-notes:en:end -->",
                body.replace(
                    "<!-- capycast:release-notes:en:start -->",
                    "<!-- capycast:release-notes:en:begin -->",
                ),
                body.replace("# What’s New\n\n- Added subtitles", "   "),
                body.replace(
                    "<!-- capycast:release-notes:zh-CN:start -->",
                    "<!-- capycast:release-notes:zh-TW:start -->",
                ),
            )
        cases.forEach { malformed ->
            assertEquals(malformed, LocalizedReleaseNotes.select(malformed, Locale.ENGLISH))
        }
    }

    @Test
    fun supportsAdditionalConfiguredLanguagesWithoutChangingTheParser() {
        val frenchBody = body.replace("release-notes:en:", "release-notes:fr:")
        assertEquals(
            "# What’s New\n\n- Added subtitles",
            LocalizedReleaseNotes.select(frenchBody, Locale.CANADA_FRENCH),
        )
    }

    @Test
    fun returnsFullBodyWhenMatchingChineseVariantIsMissing() {
        val missingTraditional = body.replace("release-notes:zh-TW:", "release-notes:ja:")
        assertEquals(
            missingTraditional,
            LocalizedReleaseNotes.select(missingTraditional, Locale.TRADITIONAL_CHINESE),
        )
    }

    @Test
    fun supportsWindowsLineEndingsAndPreservesOriginalOnFallback() {
        val windowsBody = body.replace("\n", "\r\n")
        assertEquals(
            "# What’s New\n\n- Added subtitles",
            LocalizedReleaseNotes.select(windowsBody, Locale.ENGLISH),
        )
        assertEquals(windowsBody, LocalizedReleaseNotes.select(windowsBody, Locale.FRENCH))
    }
}
