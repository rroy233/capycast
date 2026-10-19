package com.roy233.tvcast.data.bili

import com.google.gson.JsonObject
import com.roy233.tvcast.core.diagnostics.LogHub
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 提供 CDN 地址分类、排序和播放轨道选择规则。 */
object CdnCandidateSelector {

    /** 日志标签。 */
    private const val TAG = "CdnSelector"
    /** 已知 PCDN 主机后缀。 */
    private val pcdnSuffixes = listOf(
        ".szbdyd.com",
        ".mountaintoys.cn",
        ".nexusedgeio.com",
        ".ahdohpiechei.com",
    )
    /** IPv4 主机名匹配规则。 */
    private val ipv4Host = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")

    /** 按 PCDN、官方 CDN 和原始顺序对地址进行排序。 */
    fun order(urls: List<String>): List<String> = urls
        .asSequence()
        .filter { it.isNotBlank() }
        .distinct()
        .withIndex()
        .sortedWith(
            compareBy<IndexedValue<String>> { isPcdn(it.value) }
                .thenBy { !isOfficialCdn(it.value) }
                .thenBy { it.index }
        )
        .map { it.value }
        .toList()

    /** 从 JSON 轨道对象读取备用播放地址。 */
    fun backupUrls(o: JsonObject): List<String> =
        (o.get("backupUrl") ?: o.get("backup_url"))
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { runCatching { it.asString }.getOrNull() }
            ?: emptyList()

    /** 将 JSON 轨道对象转换为播放轨道模型。 */
    fun parseTrack(o: JsonObject): BiliApi.Track {
        val primary = o.get("baseUrl")?.asString ?: o.get("base_url")?.asString ?: ""
        val ordered = order(listOf(primary) + backupUrls(o))
        val selected = ordered.firstOrNull() ?: primary
        val id = o.get("id")?.asInt ?: 0
        val codecid = o.get("codecid")?.asInt ?: 0
        val width = o.get("width")?.asInt ?: 0
        val height = o.get("height")?.asInt ?: 0
        LogHub.i(
            TAG,
            "cdn track id=$id codec=${codecName(codecid)} ${width}x${height} " +
                "bw=${o.get("bandwidth")?.asLong ?: 0L} " +
                "selected=${cdnHostOf(selected)} pcdn=${isPcdn(selected)} " +
                "basePcdn=${isPcdn(primary)} candidates=${ordered.size} " +
                "hosts=${ordered.take(4).joinToString(",") { cdnHostOf(it) }}",
        )
        return BiliApi.Track(
            id = id,
            codecid = codecid,
            width = width,
            height = height,
            bandwidth = o.get("bandwidth")?.asLong ?: 0L,
            url = selected,
            backup = ordered.drop(1),
            pcdn = isPcdn(selected),
        )
    }

    /** 从视频轨道中选择优先级最高的轨道。 */
    fun pickVideo(list: List<BiliApi.Track>): BiliApi.Track {
        val supported = list.filter { it.codecid == 7 || it.codecid == 12 }
        val skipped = list.filterNot { it.codecid == 7 || it.codecid == 12 }
        if (skipped.isNotEmpty()) {
            LogHub.i(
                TAG,
                "pickVideo skipped unsupported codecs=" +
                    skipped.take(8).joinToString(",") { "qn${it.id}:${codecName(it.codecid)}" },
            )
        }
        val usable = supported.ifEmpty { list }
        val selected = usable.sortedWith(
            compareByDescending<BiliApi.Track> { it.id }
                .thenBy { it.pcdn }
                .thenByDescending { it.width.toLong() * it.height }
                .thenBy { if (it.codecid == 7) 0 else 1 }
        ).first()
        LogHub.i(
            TAG,
            "pickVideo selected qn=${selected.id} ${codecName(selected.codecid)} " +
                "${selected.width}x${selected.height} pcdn=${selected.pcdn} " +
                "candidates=${list.size} fallback=${supported.isEmpty()}",
        )
        return selected
    }

    /** 从音频轨道中选择优先级最高的轨道。 */
    fun pickAudio(list: List<BiliApi.Track>): BiliApi.Track? {
        if (list.isEmpty()) {
            LogHub.i(TAG, "pickAudio no audio tracks")
            return null
        }
        val selected = list.sortedWith(
            compareBy<BiliApi.Track> { it.pcdn }
                .thenByDescending { if (it.id == 30280) 1 else 0 }
                .thenByDescending { it.id }
        ).firstOrNull()
        LogHub.i(
            TAG,
            "pickAudio selected id=${selected?.id} codec=${codecName(selected?.codecid ?: 0)} " +
                "pcdn=${selected?.pcdn} candidates=${list.size}",
        )
        return selected
    }

    /** 将编码标识转换为可读名称。 */
    private fun codecName(codecid: Int): String = when (codecid) {
        7 -> "H.264"
        12 -> "HEVC"
        13 -> "AV1"
        else -> "codec$codecid"
    }

    /** 判断播放地址是否符合 PCDN 特征。 */
    fun isPcdn(raw: String): Boolean {
        val url = httpUrlOf(raw) ?: return false
        val host = url.host.lowercase()
        if (host.isBlank()) return false
        val firstLabel = host.substringBefore('.')
        if (ipv4Host.matches(host)) return true
        if (host.startsWith("mcdn.bilivideo.")) return true
        if (host.contains(".mcdn.bilivideo.")) return true
        if (pcdnSuffixes.any { host.endsWith(it) }) return true
        if (firstLabel.startsWith("upos-") && firstLabel.contains("302")) return true
        if (host == "upos-sz-mirror14b.bilivideo.com") return true
        if (url.port != 80 && url.port != 443) return true
        if (url.queryParameter("os")?.equals("mcdn", ignoreCase = true) == true) return true
        return false
    }

    /** 将原始地址解析为 HTTP URL。 */
    private fun httpUrlOf(raw: String): okhttp3.HttpUrl? {
        val normalized = if (raw.startsWith("//")) "https:$raw" else raw
        return normalized.toHttpUrlOrNull()
    }

    /** 提取播放地址的规范化主机名。 */
    private fun cdnHostOf(raw: String): String =
        httpUrlOf(raw)?.host?.lowercase().orEmpty()

    /** 判断地址是否属于官方 CDN 域名。 */
    private fun isOfficialCdn(raw: String): Boolean {
        val host = cdnHostOf(raw)
        return host.endsWith(".bilivideo.com") || host.endsWith(".akamaized.net")
    }
}
