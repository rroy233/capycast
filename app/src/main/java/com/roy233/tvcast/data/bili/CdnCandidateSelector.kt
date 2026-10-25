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
    /** 已有播放器支持的主流视频编码。 */
    private val supportedVideoCodecs = setOf(7, 12, 13)

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
        listOfNotNull(o.get("backupUrl"), o.get("backup_url"))
            .firstOrNull { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { runCatching { it.asString }.getOrNull() }
            ?: emptyList()

    /** 将 JSON 轨道对象转换为播放轨道模型。 */
    fun parseTrack(o: JsonObject, isDolbyAudio: Boolean = false): BiliApi.Track {
        val primary = o.string("baseUrl").ifBlank { o.string("base_url") }
        val ordered = order(listOf(primary) + backupUrls(o))
        val selected = ordered.firstOrNull() ?: primary
        val id = o.int("id")
        val codecid = o.int("codecid")
        val width = o.int("width")
        val height = o.int("height")
        val mimeType = o.string("mimeType").ifBlank { o.string("mime_type") }
        val codecs = o.string("codecs")
        val colorSpace = o.string("colorSpace").ifBlank { o.string("color_space") }
        val transfer = o.string("transferCharacteristics")
            .ifBlank { o.string("transfer_characteristics") }
        val metadata = listOf(mimeType, codecs, colorSpace, transfer, o.toString()).joinToString(" ")
            .lowercase()
        val isDolbyVision = id == 126 || metadata.contains("dolby-vision") ||
            metadata.contains("dolby_vision") || codecs.startsWith("dv", ignoreCase = true)
        val isHdr = isDolbyVision || id == 125 || id == 129 ||
            metadata.contains("bt2020") || metadata.contains("hdr") ||
            metadata.contains("hlg") || metadata.contains("arib-std-b67") ||
            metadata.contains("st2084") || metadata.contains("pq")
        val segmentBase = listOfNotNull(o.get("segmentBase"), o.get("segment_base"))
            .firstOrNull { it.isJsonObject }
            ?.asJsonObject
        val initializationRange = segmentBase?.string("initialization")
            ?.ifBlank { segmentBase.string("initialization_range") }.orEmpty()
        val indexRange = segmentBase?.string("indexRange")
            ?.ifBlank { segmentBase.string("index_range") }.orEmpty()
        LogHub.i(
            TAG,
            "cdn track id=$id codec=${codecName(codecid)} ${width}x${height} " +
                "bw=${o.long("bandwidth")} " +
                "selected=${cdnHostOf(selected)} pcdn=${isPcdn(selected)} " +
                "basePcdn=${isPcdn(primary)} candidates=${ordered.size} " +
                "hosts=${ordered.take(4).joinToString(",") { cdnHostOf(it) }}",
        )
        return BiliApi.Track(
            id = id,
            codecid = codecid,
            width = width,
            height = height,
            bandwidth = o.long("bandwidth"),
            url = selected,
            backup = ordered.drop(1),
            pcdn = isPcdn(selected),
            mimeType = mimeType,
            codecs = codecs,
            isHdr = isHdr,
            isDolbyVision = isDolbyVision,
            isDolbyAudio = isDolbyAudio,
            initializationRange = initializationRange,
            indexRange = indexRange,
        )
    }

    /** 从视频轨道中选择优先级最高的轨道。 */
    fun pickVideo(list: List<BiliApi.Track>): BiliApi.Track {
        val supported = list.filter { isSupportedVideoCodec(it.codecid) }
        val skipped = list.filterNot { isSupportedVideoCodec(it.codecid) }
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

    /** 判断编码是否属于现有播放后端支持的主流视频编码。 */
    fun isSupportedVideoCodec(codecid: Int): Boolean = codecid in supportedVideoCodecs

    /** 从音频轨道中选择优先级最高的轨道。 */
    fun pickAudio(list: List<BiliApi.Track>): BiliApi.Track? {
        if (list.isEmpty()) {
            LogHub.i(TAG, "pickAudio no audio tracks")
            return null
        }
        val selected = list.sortedWith(
            compareBy<BiliApi.Track> { it.pcdn }
                .thenByDescending { if (it.isDolbyAudio) 1 else 0 }
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

    private fun JsonObject.string(name: String): String =
        runCatching { get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty() }.getOrDefault("")

    private fun JsonObject.int(name: String): Int =
        runCatching { get(name)?.takeUnless { it.isJsonNull }?.asInt ?: 0 }.getOrDefault(0)

    private fun JsonObject.long(name: String): Long =
        runCatching { get(name)?.takeUnless { it.isJsonNull }?.asLong ?: 0L }.getOrDefault(0L)

    /** 判断地址是否属于官方 CDN 域名。 */
    fun isOfficialCdn(raw: String): Boolean {
        val host = cdnHostOf(raw)
        return host.endsWith(".bilivideo.com") || host.endsWith(".akamaized.net")
    }
}
