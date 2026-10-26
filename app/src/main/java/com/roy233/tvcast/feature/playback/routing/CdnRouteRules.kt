package com.roy233.tvcast.feature.playback.routing

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 播放线路的端点分类、去重和优先级规则。 */
object CdnRouteRules {
    private val pcdnSuffixes = listOf(
        ".szbdyd.com",
        ".mountaintoys.cn",
        ".nexusedgeio.com",
        ".ahdohpiechei.com",
    )
    private val ipv4Host = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")

    fun order(urls: List<String>): List<String> = urls
        .asSequence()
        .filter { it.isNotBlank() }
        .distinct()
        .withIndex()
        .sortedWith(
            compareBy<IndexedValue<String>> { isPcdn(it.value) }
                .thenBy { !isOfficialCdn(it.value) }
                .thenBy { it.index },
        )
        .map { it.value }
        .toList()

    fun isPcdn(raw: String): Boolean {
        val url = httpUrlOf(raw) ?: return false
        val host = url.host.lowercase()
        if (host.isBlank()) return false
        val firstLabel = host.substringBefore('.')
        return ipv4Host.matches(host) ||
            host.startsWith("mcdn.bilivideo.") ||
            host.contains(".mcdn.bilivideo.") ||
            pcdnSuffixes.any { host.endsWith(it) } ||
            (firstLabel.startsWith("upos-") && firstLabel.contains("302")) ||
            host == "upos-sz-mirror14b.bilivideo.com" ||
            (url.port != 80 && url.port != 443) ||
            url.queryParameter("os")?.equals("mcdn", ignoreCase = true) == true
    }

    fun isOfficialCdn(raw: String): Boolean {
        val host = hostOf(raw)
        return host.endsWith(".bilivideo.com") || host.endsWith(".akamaized.net")
    }

    fun routeIdentity(raw: String): String = hostOf(raw).ifBlank { raw }

    fun hostOf(raw: String): String = httpUrlOf(raw)?.host?.lowercase().orEmpty()

    private fun httpUrlOf(raw: String) =
        (if (raw.startsWith("//")) "https:$raw" else raw).toHttpUrlOrNull()
}
