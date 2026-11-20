package com.roy233.tvcast.feature.update.presentation

import java.util.Locale

/** 根据当前应用语言读取发布说明；旧格式、缺失或损坏时保留完整正文。 */
internal object LocalizedReleaseNotes {
    private const val FORMAT_MARKER = "<!-- capycast:release-notes:v1 -->"
    private const val MARKER_PREFIX = "<!-- capycast:release-notes:"
    private val sectionMarker =
        Regex("^<!-- capycast:release-notes:([A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*):(start|end) -->$")

    /** 返回语言段落；无法识别语言或解析正文时返回原始正文。 */
    fun select(body: String, locale: Locale?): String {
        if (locale == null) return body
        val lines = body.split('\n').map { it.removeSuffix("\r") }
        if (lines.firstOrNull() != FORMAT_MARKER) return body

        val sections = mutableMapOf<String, String>()
        var activeLanguage: String? = null
        val content = mutableListOf<String>()
        for (line in lines.drop(1)) {
            if (!line.trimStart().startsWith(MARKER_PREFIX)) {
                if (activeLanguage != null) content += line
                continue
            }
            val match = sectionMarker.matchEntire(line) ?: return body
            val (rawTag, action) = match.destructured
            val tag = rawTag.lowercase(Locale.ROOT)
            when (action) {
                "start" -> {
                    if (activeLanguage != null || tag in sections) return body
                    activeLanguage = tag
                    content.clear()
                }
                "end" -> {
                    if (activeLanguage != tag) return body
                    sections[tag] = content.joinToString("\n").trim('\n')
                    activeLanguage = null
                }
            }
        }
        if (activeLanguage != null || sections.isEmpty()) return body
        return locale.releaseNotesCandidates().firstNotNullOfOrNull {
            sections[it]?.takeIf(String::isNotBlank)
        } ?: body
    }

    /** 语言区域优先精确匹配；中文按文字系统映射到现有的简体或繁体标签。 */
    private fun Locale.releaseNotesCandidates(): List<String> = buildList {
        val languageCode = language.lowercase(Locale.ROOT)
        if (languageCode.isBlank()) return@buildList
        add(toLanguageTag().lowercase(Locale.ROOT))
        if (languageCode == "zh") {
            val scriptCode = script.lowercase(Locale.ROOT)
            if (scriptCode.isNotEmpty()) add("zh-$scriptCode")
            val traditional =
                when (scriptCode) {
                    "hant" -> true
                    "hans" -> false
                    else -> country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO")
                }
            add(if (traditional) "zh-tw" else "zh-cn")
        } else {
            add(languageCode)
        }
    }
        .distinct()
}
