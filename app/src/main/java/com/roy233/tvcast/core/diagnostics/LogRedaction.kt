package com.roy233.tvcast.core.diagnostics

import java.net.URI
import java.util.Locale

/**
 * 提供面向诊断日志的脱敏工具。
 *
 * 播放地址中包含带签名的查询参数，日志会被用户复制到 Issue 中，因此这里统一
 * 只保留协议、主机、端口、路径和查询参数名，绝不记录参数值、Cookie 或 nva 投屏载荷。
 */
object LogRedaction {

    /** 单条地址摘要允许的最大字符数。 */
    private const val MAX_SUMMARY_LENGTH = 200
    /** 地址路径允许保留的最大字符数。 */
    private const val MAX_PATH_LENGTH = 120
    /** 摘要中最多列出的查询参数名数量。 */
    private const val MAX_QUERY_NAMES = 6
    /** 单条异常原因允许的最大字符数。 */
    private const val MAX_REASON_LENGTH = 240
    /** 脱敏后替换敏感值使用的占位文本。 */
    private const val REDACTED = "***"

    /** 匹配文本中的 HTTP 或 HTTPS 地址。 */
    private val urlPattern = Regex("""(?i)https?://[^\s"'<>\\]+""")
    /** 匹配文本中的协议相对地址。 */
    private val protocolRelativePattern = Regex("""(?i)(?<![\w:])//[^\s"'<>\\]+""")
    /** 匹配投屏载荷参数。 */
    private val nvaPattern = Regex("""(?i)_nva_ext_=[^&\s"']+""")
    /** 匹配可能出现在文本日志中的凭据请求头。 */
    private val credentialPattern = Regex("""(?i)\b(cookie|set-cookie|authorization)\s*[:=]\s*[^\r\n]+""")
    /** 地址末尾常见的标点，匹配后需要原样保留。 */
    private val trailingPunctuation = charArrayOf(')', ']', '}', ',', '.', ';', '\'')

    /**
     * 将播放地址转换为可安全写入日志的摘要。
     *
     * @param raw 原始地址，可为空。
     * @return 仅包含协议、主机、端口、路径和查询参数名的摘要。
     */
    fun url(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "(empty)"
        val normalized = if (text.startsWith("//")) "https:$text" else text
        val parsed = runCatching { URI(normalized) }.getOrNull()
            ?: return text.substringBefore('?').take(MAX_SUMMARY_LENGTH)
        val scheme = parsed.scheme?.lowercase(Locale.US).orEmpty()
        val host = parsed.host.orEmpty()
        if (scheme.isEmpty() || host.isEmpty()) {
            return text.substringBefore('?').take(MAX_SUMMARY_LENGTH)
        }
        val port = if (parsed.port >= 0) ":${parsed.port}" else ""
        val path = parsed.rawPath.orEmpty().let {
            if (it.length > MAX_PATH_LENGTH) it.take(MAX_PATH_LENGTH) + "…" else it
        }
        val names = queryParameterNames(parsed.rawQuery)
        val query = if (names.isEmpty()) {
            ""
        } else {
            val listed = names.sorted().take(MAX_QUERY_NAMES).joinToString(",")
            val rest = names.size - MAX_QUERY_NAMES
            "?params=$listed" + if (rest > 0) ",+$rest" else ""
        }
        return "$scheme://$host$port$path$query".take(MAX_SUMMARY_LENGTH)
    }

    /** 从原始查询串中提取参数名集合。 */
    private fun queryParameterNames(rawQuery: String?): Set<String> {
        if (rawQuery.isNullOrBlank()) return emptySet()
        return rawQuery.split('&')
            .asSequence()
            .map { it.substringBefore('=') }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /**
     * 提取地址的主机名，无法解析时返回占位文本。
     *
     * @param raw 原始地址，可为空。
     * @return 小写主机名或 `(invalid)`。
     */
    fun host(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "(empty)"
        val normalized = if (text.startsWith("//")) "https:$text" else text
        val host = runCatching { URI(normalized).host }.getOrNull()
        return host?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: "(invalid)"
    }

    /**
     * 对任意日志文本中的地址、投屏载荷和凭据信息进行脱敏。
     *
     * @param raw 原始文本，可为空。
     * @return 可安全写入日志或诊断报告的文本。
     */
    fun text(raw: String?): String {
        var value = raw.orEmpty()
        if (value.isEmpty()) return ""
        value = nvaPattern.replace(value, "_nva_ext_=$REDACTED")
        value = credentialPattern.replace(value) { match ->
            "${match.groupValues[1]}: $REDACTED"
        }
        value = urlPattern.replace(value) { match ->
            urlWithTrailingPunctuation(match.value)
        }
        value = protocolRelativePattern.replace(value) { match ->
            urlWithTrailingPunctuation(match.value)
        }
        return value
    }

    /** 脱敏地址并保留其尾部标点。 */
    private fun urlWithTrailingPunctuation(raw: String): String {
        var candidate = raw
        var suffix = ""
        while (candidate.isNotEmpty() && candidate.last() in trailingPunctuation) {
            suffix = candidate.last() + suffix
            candidate = candidate.dropLast(1)
        }
        return url(candidate) + suffix
    }

    /**
     * 将异常转换为简短、脱敏的诊断文本。
     *
     * @param throwable 需要描述的异常，可为空。
     * @return 包含异常类型与原因的文本。
     */
    fun reason(throwable: Throwable?): String {
        if (throwable == null) return "(no exception)"
        val message = text(throwable.message).take(MAX_REASON_LENGTH)
        val suffix = if (message.isBlank()) "" else ": $message"
        return throwable.javaClass.simpleName + suffix
    }
}
