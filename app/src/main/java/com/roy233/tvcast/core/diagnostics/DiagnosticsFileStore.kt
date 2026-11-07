package com.roy233.tvcast.core.diagnostics

import java.io.File
import java.io.RandomAccessFile

/** 读取诊断文件尾部内容，并对输出做脱敏。 */
internal object DiagnosticsFileStore {

    /** 追加已脱敏并截断的历史行。调用方负责用会话文件锁串行化写入。 */
    fun appendHistory(file: File, safeLine: String) {
        file.parentFile?.mkdirs()
        file.appendText(safeLine + "\n")
    }

    /** 超过上限时仅保留文件尾部，并写入原有轮转标记。 */
    fun rotateHistoryIfNeeded(file: File, maxBytes: Long, tailBytes: Long) {
        if (!file.isFile || file.length() <= maxBytes) return
        val tail = readTailText(file, tailBytes)
        file.writeText("[ROTATED] trimmed playback history\n$tail")
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
    }
        .getOrDefault("")
}
