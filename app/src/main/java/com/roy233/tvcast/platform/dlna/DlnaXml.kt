package com.roy233.tvcast.platform.dlna

/** 提供 DLNA DIDL-Lite 元数据的读取与 XML 转义工具。 */
object DlnaXml {

    /** 从元数据中提取标题文本。 */
    fun extractTitle(metadata: String): String {
        val m = Regex("<dc:title>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL).find(metadata)
            ?: Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(metadata)
        return m?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
    }

    /** 从元数据中提取长描述文本。 */
    fun extractLongDescription(metadata: String): String {
        val m = Regex(
            "<upnp:longDescription>(.*?)</upnp:longDescription>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(metadata)
            ?: Regex(
                "<longDescription>(.*?)</longDescription>",
                RegexOption.DOT_MATCHES_ALL,
            ).find(metadata)
        return m?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
    }

    /** 将文本转义为可安全嵌入 XML 的内容。 */
    fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** 将常见 XML 实体还原为原始字符。 */
    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
