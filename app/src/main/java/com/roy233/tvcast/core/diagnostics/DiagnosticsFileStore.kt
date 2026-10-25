package com.roy233.tvcast.core.diagnostics

import java.io.File
import java.io.RandomAccessFile

/** 读取诊断文件尾部内容，并对输出做脱敏。 */
internal object DiagnosticsFileStore {

    /**
     * 读取日志文件自起始偏移之后的行尾部。
     *
     * @param file 目标文件。
     * @param startOffset 本次会话的起始字节偏移。
     * @param maxLines 最多保留的行数。
     * @param maxBytes 单次读取的字节上限。
     * @return 脱敏后的文本；文件不可读时返回空串。
     */
    fun readVlcTail(file: File, startOffset: Long, maxLines: Int, maxBytes: Long): String {
        if (!file.isFile) return ""
        return runCatching {
            val length = file.length()
            if (length <= 0L) return@runCatching ""
            val from = maxOf(
                startOffset.coerceIn(0L, length),
                length - maxBytes,
            ).coerceAtLeast(0L)
            RandomAccessFile(file, "r").use { handle ->
                handle.seek(from)
                val size = (length - from).toInt().coerceAtMost(maxBytes.toInt())
                if (size <= 0) return@runCatching ""
                val bytes = ByteArray(size)
                handle.readFully(bytes)
                var text = String(bytes, Charsets.UTF_8)
                if (from > 0L) text = text.substringAfter('\n', "")
                text.lineSequence()
                    .filter { it.isNotBlank() }
                    .toList()
                    .takeLast(maxLines)
                    .joinToString("\n") { LogRedaction.text(it) }
            }
        }.getOrDefault("")
    }

    /**
     * 读取文件末尾指定字节数以内的文本。
     *
     * @param file 目标文件。
     * @param maxBytes 读取的字节上限。
     * @return 文件文本；不可读时返回空串。
     */
    fun readTailText(file: File, maxBytes: Long): String = runCatching {
        val length = file.length()
        if (length <= 0L) return@runCatching ""
        val from = (length - maxBytes).coerceAtLeast(0L)
        RandomAccessFile(file, "r").use { handle ->
            handle.seek(from)
            val size = (length - from).toInt()
            val bytes = ByteArray(size)
            handle.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrDefault("")
}
