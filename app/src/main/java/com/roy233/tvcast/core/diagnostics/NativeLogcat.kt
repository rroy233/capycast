package com.roy233.tvcast.core.diagnostics

import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.io.File

/**
 * 读取当前进程自己的 libVLC 原生日志。
 *
 * 部分 libVLC 构建不支持 `--file-logging`，原生日志只会写入 logcat。这里通过
 * `logcat --pid` 读取当前进程的 `VLC` 标签日志；不同系统可能因为 SELinux 或
 * logcat 参数差异而拒绝访问，此时会记录一次原因并安静降级。
 */
object NativeLogcat {

    /** 日志标签。 */
    private const val TAG = "NativeLog"
    /** 单次读取的最多日志行数。 */
    private const val MAX_LINES = 200
    /** 单次读取等待 logcat 退出的毫秒数。 */
    private const val TIMEOUT_MS = 3_000L
    /** 两次实际读取之间的最小间隔。 */
    private const val CACHE_MS = 5_000L
    /** logcat 可执行文件路径。 */
    private const val LOGCAT_BINARY = "/system/bin/logcat"
    /** 过滤的 libVLC 原生日志标签，不同构建可能使用不同标签。 */
    private val VLC_TAGS = listOf("VLC:V", "libvlc:V", "vlcjni:V")

    /** 不可用原因；为 null 表示尚未遇到命令级失败。 */
    @Volatile
    private var unavailableReason: String? = null
    /** 最近一次成功读取的日志，失败后保留供报告使用。 */
    @Volatile
    private var lastTail: String = ""
    /** 最近一次实际执行 logcat 的单调时刻。 */
    @Volatile
    private var lastReadAt: Long = -CACHE_MS

    /**
     * 读取当前进程的 libVLC 原生日志尾部。
     *
     * @param maxLines 最多返回的行数。
     * @param force 是否忽略缓存，立即重新读取。
     * @return 脱敏后的原生日志；不可用或暂时为空时返回空字符串。
     */
    @Synchronized
    fun tail(maxLines: Int = MAX_LINES, force: Boolean = false): String {
        if (unavailableReason != null) return lastTail
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReadAt < CACHE_MS) return lastTail
        lastReadAt = now
        val pid = Process.myPid()
        val lineLimit = (maxLines.coerceAtLeast(1) * 2).toString()
        val attempts = listOf(
            listOf("-d", "-v", "threadtime", "-t", lineLimit, "--pid=$pid", "-s") + VLC_TAGS,
            listOf("-d", "-v", "threadtime", "-t", lineLimit, "-s") + VLC_TAGS,
        )
        var sawSuccessfulEmpty = false
        for (args in attempts) {
            val result = runCatching { runLogcat(args) }.getOrNull()
            if (result == null) {
                markUnavailable("logcat timed out")
                return lastTail
            }
            val (exitCode, output) = result
            if (exitCode != 0) {
                if (output.isNotBlank()) {
                    markUnavailable(output.lineSequence().firstOrNull().orEmpty())
                    return ""
                }
                continue
            }
            val lines = output.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("--------- beginning") }
                .filter { line -> linePid(line)?.let { it == pid } ?: false }
                .toList()
            if (lines.isNotEmpty()) {
                lastTail = lines.takeLast(maxLines)
                    .joinToString("\n") { LogRedaction.text(it) }
                return lastTail
            }
            sawSuccessfulEmpty = true
        }
        if (!sawSuccessfulEmpty) markUnavailable("logcat returned no usable output")
        return lastTail
    }

    /**
     * 返回原生日志读取状态，用于诊断报告。
     *
     * @param maxLines 最多返回的行数。
     * @return 状态 JSON。
     */
    fun report(maxLines: Int = MAX_LINES): JSONObject = JSONObject().apply {
        val text = tail(maxLines)
        put("available", text.isNotBlank())
        put("tail", text)
        put("status", unavailableReason ?: if (text.isBlank()) "empty" else "available")
    }

    /** 执行一次 logcat 并返回退出码与输出。 */
    private fun runLogcat(args: List<String>): Pair<Int, String> {
        val binary = if (File(LOGCAT_BINARY).canExecute()) LOGCAT_BINARY else "logcat"
        val process = ProcessBuilder(listOf(binary) + args)
            .redirectErrorStream(true)
            .start()
        if (!waitFor(process, TIMEOUT_MS)) {
            runCatching { process.destroy() }
            return 1 to "logcat timeout"
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.exitValue() to output
    }

    /** 在低版本 Android 上轮询等待进程结束。 */
    private fun waitFor(process: java.lang.Process, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                process.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                runCatching { Thread.sleep(25L) }
            }
        }
        return false
    }

    /** 提取 threadtime 格式日志行中的进程号。 */
    private fun linePid(line: String): Int? {
        val parts = line.trimStart().split(WHITESPACE)
        return parts.getOrNull(2)?.toIntOrNull()
    }

    /** 记录一次命令级不可用原因，避免重复尝试。 */
    private fun markUnavailable(reason: String) {
        val safeReason = LogRedaction.text(reason).take(240)
        if (unavailableReason != null) return
        unavailableReason = safeReason
        LogHub.w(TAG, "native logcat unavailable: $safeReason")
    }

    /** 匹配日志字段之间的空白。 */
    private val WHITESPACE = Regex("\\s+")
}
