package com.roy233.tvcast.core.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.roy233.tvcast.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记录一次播放会话的完整诊断信息。
 *
 * 与只保存在内存中的 [LogHub] 不同，这里会把会话开始、失败和崩溃写入应用私有
 * 目录，便于在电视被系统回收或应用崩溃后继续排查。`/playback` 端点会输出会话
 * 时间线、媒体轨道、播放统计、失败快照和 libVLC 原生日志尾部。
 */
object PlaybackDiagnostics {

    /** 日志标签。 */
    private const val TAG = "PlaybackDiag"
    /** 诊断文件所在目录名。 */
    private const val DIRECTORY_NAME = "diagnostics"
    /** 本次运行使用的 libVLC 原生日志文件名。 */
    private const val VLC_LOG_FILE = "vlc.log"
    /** 上一次运行保留的 libVLC 原生日志文件名。 */
    private const val VLC_PREVIOUS_LOG_FILE = "vlc-previous.log"
    /** 持久化播放历史文件名。 */
    private const val HISTORY_FILE = "playback-history.log"
    /** 会话开始标记。 */
    private const val OPEN_MARKER = "[OPEN]"
    /** 会话结束标记。 */
    private const val CLOSE_MARKER = "[CLOSE]"
    /** 内存中保留的最大事件条数。 */
    private const val MAX_EVENTS = 320
    /** 内存中保留的最大统计采样数。 */
    private const val MAX_STATS_SAMPLES = 90
    /** 单条事件详情允许的最大字符数。 */
    private const val MAX_EVENT_DETAIL = 500
    /** 单个会话属性允许的最大字符数。 */
    private const val MAX_ATTRIBUTE_LENGTH = 400
    /** 单个会话最多保留的轨道条目数。 */
    private const val MAX_TRACKS = 160
    /** 单条持久化记录允许的最大字符数。 */
    private const val MAX_HISTORY_LINE = 8000
    /** 播放历史文件允许的最大字节数。 */
    private const val MAX_HISTORY_BYTES = 256L * 1024L
    /** 轮转后保留的播放历史字节数。 */
    private const val MAX_HISTORY_TAIL_BYTES = 96L * 1024L
    /** 单次读取的 libVLC 原生日志最大字节数。 */
    private const val MAX_VLC_TAIL_BYTES = 96L * 1024L
    /** 上一次运行保留的 libVLC 原生日志最大字节数。 */
    private const val MAX_VLC_PREVIOUS_BYTES = 256L * 1024L

    /** 保护会话内存状态的锁。 */
    private val lock = Any()
    /** 保护诊断文件读写的锁。 */
    private val fileLock = Any()

    /** 按时间顺序保留的会话事件。 */
    private val events = ArrayDeque<String>()
    /** 当前会话的键值属性。 */
    private val attributes = LinkedHashMap<String, String>()
    /** 与设备运行时相关、跨会话保留的属性。 */
    private val runtimeInfo = LinkedHashMap<String, String>()
    /** 当前会话的媒体轨道列表。 */
    private var tracks = JSONArray()
    /** 当前会话的播放统计采样。 */
    private var statsSamples = JSONArray()
    /** 第一次播放统计采样。 */
    private var statsFirst: JSONObject? = null
    /** 最近一次播放统计采样。 */
    private var statsLast: JSONObject? = null
    /** 最近一次失败快照。 */
    private var failure: JSONObject? = null

    /** 当前会话标识。 */
    private var sessionId = ""
    /** 当前会话来源。 */
    private var sessionSource = ""
    /** 当前会话开始时间文本。 */
    private var sessionStartedWall = ""
    /** 当前会话开始的单调时钟。 */
    private var sessionStartedElapsed = 0L
    /** 当前会话状态文本。 */
    private var sessionState = "none"
    /** 当前是否存在打开的会话。 */
    private var sessionOpen = false
    /** 读取 libVLC 原生日志时的起始偏移；当前保留本次运行的全部日志。 */
    private var vlcLogStartOffset = 0L
    /** 会话递增序号，避免同一秒内标识重复。 */
    private var sessionCounter = 0

    /** 是否已经完成初始化。 */
    @Volatile
    private var installed = false

    /** 应用上下文。 */
    @Volatile
    private var appContext: Context? = null
    /** 诊断文件目录。 */
    @Volatile
    private var diagnosticsDir: File? = null
    /** 当前运行使用的 libVLC 原生日志文件。 */
    @Volatile
    private var vlcLog: File? = null
    /** 上一次运行保留的 libVLC 原生日志文件。 */
    @Volatile
    private var previousVlcLog: File? = null
    /** 持久化播放历史文件。 */
    @Volatile
    private var historyFile: File? = null

    /**
     * 初始化诊断目录、日志文件和历史记录。
     *
     * @param context 用于定位应用私有目录的上下文。
     */
    fun install(context: Context) {
        if (installed) return
        var unclean = false
        var previousVlcBytes = 0L
        synchronized(lock) {
            if (installed) return
            val application = context.applicationContext
            appContext = application
            val directory = File(application.filesDir, DIRECTORY_NAME)
            directory.mkdirs()
            diagnosticsDir = directory
            historyFile = File(directory, HISTORY_FILE)
            val currentLog = File(directory, VLC_LOG_FILE)
            vlcLog = currentLog
            previousVlcLog = File(directory, VLC_PREVIOUS_LOG_FILE)
            unclean = detectUncleanHistory()
            rotateHistoryIfNeeded()
            previousVlcBytes = rotateVlcLog(currentLog)
            installed = true
        }
        appendHistory(
            "[BOOT] app=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "device=${Build.MANUFACTURER}/${Build.MODEL} " +
                "android=${Build.VERSION.RELEASE}/${Build.VERSION.SDK_INT} " +
                "abis=${Build.SUPPORTED_ABIS.joinToString(",")}",
        )
        if (unclean) {
            appendHistory("[WARN] previous session did not close; app may have been killed or crashed")
        }
        LogHub.i(
            TAG,
            "diagnostics installed dir=${diagnosticsDir?.absolutePath} " +
                "previousSessionOpen=$unclean previousVlcBytes=$previousVlcBytes",
        )
        if (unclean) {
            LogHub.w(TAG, "previous playback session was not closed; history retained")
        }
    }

    /**
     * 开始一次新的播放会话，已有的会话会被标记为被替换。
     *
     * @param source 会话来源，例如投屏、最近播放或播放器打开。
     */
    fun beginSession(source: String) {
        val safeSource = source.take(80)
        val wall = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val shortWall = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val now = SystemClock.elapsedRealtime()
        val line: String
        val id: String
        var supersededId: String? = null
        synchronized(lock) {
            if (sessionOpen) {
                supersededId = sessionId
                closeSessionLocked("superseded")
            }
            sessionCounter += 1
            id = "$shortWall#${sessionCounter}"
            sessionId = id
            sessionSource = safeSource
            sessionStartedWall = wall
            sessionStartedElapsed = now
            sessionState = "starting"
            sessionOpen = true
            events.clear()
            attributes.clear()
            tracks = JSONArray()
            statsSamples = JSONArray()
            statsFirst = null
            statsLast = null
            failure = null
            // 保留本次运行从 libVLC 初始化开始的全部原生日志；读取时仍只取尾部。
            vlcLogStartOffset = 0L
            attributes["session.source"] = safeSource
            line = formatEventLocked("Session", "begin id=$id source=$safeSource")
        }
        supersededId?.let { appendHistory("$CLOSE_MARKER id=$it reason=superseded") }
        appendHistory("$OPEN_MARKER id=$id source=$safeSource startedAt=$wall")
        LogHub.i(TAG, "session begin id=$id source=$safeSource vlcLogFrom=$vlcLogStartOffset")
        LogHub.d(TAG, line)
    }

    /**
     * 确保存在一个打开的会话，不存在时才创建。
     *
     * @param source 需要创建会话时使用的来源。
     */
    fun ensureSession(source: String) {
        val open = synchronized(lock) { sessionOpen }
        if (!open) beginSession(source)
    }

    /**
     * 关闭当前会话并写入结束标记。
     *
     * @param reason 结束原因。
     */
    fun closeSession(reason: String) {
        val safeReason = reason.take(80)
        val line: String
        val id: String
        synchronized(lock) {
            if (!sessionOpen) return
            id = sessionId
            line = closeSessionLocked(safeReason)
        }
        appendHistory("$CLOSE_MARKER id=$id reason=$safeReason")
        LogHub.i(TAG, "session close id=$id reason=$safeReason")
        LogHub.d(TAG, line)
    }

    /**
     * 更新当前会话状态。
     *
     * @param state 新的状态文本。
     */
    fun state(state: String) {
        synchronized(lock) {
            if (sessionOpen) sessionState = state.take(80)
        }
    }

    /**
     * 写入一个会话属性。
     *
     * @param key 属性名。
     * @param value 属性值。
     */
    fun attribute(key: String, value: String) {
        val safeValue = LogRedaction.text(value).take(MAX_ATTRIBUTE_LENGTH)
        synchronized(lock) {
            attributes[key.take(80)] = safeValue
        }
    }

    /**
     * 写入一个跨会话保留的运行时属性。
     *
     * @param key 属性名。
     * @param value 属性值。
     */
    fun runtime(key: String, value: String) {
        val safeValue = LogRedaction.text(value).take(MAX_ATTRIBUTE_LENGTH)
        synchronized(lock) {
            runtimeInfo[key.take(80)] = safeValue
        }
    }

    /**
     * 记录一条媒体轨道。
     *
     * @param kind 轨道类型，例如 video 或 audio。
     * @param fields 轨道字段。
     */
    fun track(kind: String, fields: Map<String, String>) {
        val entry = JSONObject().apply {
            put("kind", kind)
            fields.forEach { (key, value) ->
                put(key, LogRedaction.text(value).take(MAX_ATTRIBUTE_LENGTH))
            }
        }
        synchronized(lock) {
            if (tracks.length() < MAX_TRACKS) tracks.put(entry)
        }
    }

    /**
     * 记录一条播放时间线事件。
     *
     * @param name 事件名称。
     * @param detail 事件详情。
     */
    fun event(name: String, detail: String = "") {
        val line = synchronized(lock) {
            formatEventLocked(name.take(60), detail)
        }
        LogHub.d(TAG, line)
    }

    /**
     * 记录一次播放统计采样。
     *
     * @param sample 统计采样 JSON。
     */
    fun stats(sample: JSONObject) {
        synchronized(lock) {
            if (statsFirst == null) statsFirst = sample
            statsLast = sample
            statsSamples.put(sample)
            while (statsSamples.length() > MAX_STATS_SAMPLES) {
                statsSamples.remove(0)
            }
        }
    }

    /**
     * 记录播放失败，并立即把失败快照持久化。
     *
     * @param code 失败代码。
     * @param message 面向用户的失败描述。
     * @param detail 供排查使用的详细上下文。
     */
    fun fail(code: String, message: String, detail: String = "") {
        val safeMessage = LogRedaction.text(message).take(400)
        val safeDetail = LogRedaction.text(detail).take(1600)
        val id: String
        val line: String
        synchronized(lock) {
            id = sessionId.ifBlank { "no-session" }
            val snapshot = JSONObject().apply {
                put("elapsedMs", elapsedLocked())
                put("code", code.take(80))
                put("message", safeMessage)
                put("detail", safeDetail)
                put("state", "failed:${code.take(60)}")
                put("lastStats", statsLast ?: JSONObject.NULL)
                put("attributes", JSONObject().apply {
                    attributes.forEach { (key, value) -> put(key, value) }
                })
            }
            failure = snapshot
            line = formatEventLocked("Failure", "code=${code.take(80)} message=$safeMessage")
            if (sessionOpen) {
                sessionState = "failed:${code.take(60)}"
                sessionOpen = false
            }
        }
        appendHistory("[FAIL] id=$id code=${code.take(80)} message=$safeMessage")
        if (safeDetail.isNotBlank()) appendHistory("    detail=$safeDetail")
        val nativeTail = vlcLogTail(40)
        if (nativeTail.isNotBlank()) appendHistory("[VLC] id=$id\n$nativeTail")
        appendHistory("$CLOSE_MARKER id=$id reason=failed:${code.take(60)}")
        LogHub.e(TAG, "session failure id=$id code=$code message=$safeMessage detail=$safeDetail")
        LogHub.d(TAG, line)
    }

    /**
     * 记录未捕获异常，便于在进程崩溃后继续排查。
     *
     * @param threadName 发生异常的线程名。
     * @param throwable 未捕获异常。
     */
    fun recordCrash(threadName: String, throwable: Throwable) {
        val stack = runCatching {
            throwable.stackTraceToString()
                .lineSequence()
                .take(40)
                .joinToString("\n")
        }.getOrDefault("")
        val safeStack = LogRedaction.text(stack)
        val id: String
        synchronized(lock) {
            id = sessionId.ifBlank { "no-session" }
            formatEventLocked(
                "Crash",
                "thread=${threadName.take(60)} ${LogRedaction.reason(throwable)}",
            )
            sessionState = "crashed"
            sessionOpen = false
        }
        appendHistory(
            "[CRASH] id=$id thread=${threadName.take(60)} " +
                "${LogRedaction.reason(throwable)}\n$safeStack",
        )
        LogHub.e(
            TAG,
            "crash id=$id thread=$threadName ${LogRedaction.reason(throwable)}",
            throwable,
            stack = true,
        )
    }

    /**
     * 持久化一次 libVLC 原生日志尾部。
     *
     * @param source 触发来源。
     * @param text 需要脱敏后写入的日志文本。
     */
    fun recordNativeLog(source: String, text: String) {
        val safe = LogRedaction.text(text).take(MAX_HISTORY_LINE)
        if (safe.isBlank()) return
        appendHistory("[NATIVE] source=${source.take(80)}\n$safe")
    }

    /**
     * 返回当前会话的单行摘要，用于状态接口。
     *
     * @return 单行会话摘要。
     */
    fun summary(): String = synchronized(lock) {
        val id = sessionId.ifBlank { "none" }
        val currentFailure = failure
        if (currentFailure == null) {
            "session=$id state=$sessionState events=${events.size}"
        } else {
            "session=$id state=$sessionState code=${currentFailure.optString("code")} " +
                "message=${currentFailure.optString("message").take(160)} events=${events.size}"
        }
    }

    /**
     * 构造完整的播放诊断报告。
     *
     * @return 可直接返回给 `/playback` 的 JSON 文本。
     */
    fun reportJson(): String {
        val root = JSONObject()
        runCatching {
            root.put("generatedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            root.put("installed", installed)
            appContext?.let { context -> root.put("device", DeviceDiagnostics.snapshot(context)) }
            synchronized(lock) {
                root.put("session", buildSessionJsonLocked())
                root.put(
                    "runtime",
                    JSONObject().apply {
                        runtimeInfo.forEach { (key, value) -> put(key, value) }
                    },
                )
            }
            val fileLog = buildVlcLogJson()
            root.put("vlcLog", fileLog)
            if (!fileLog.optBoolean("available")) {
                root.put("nativeLogcat", NativeLogcat.report(160))
            }
            root.put("history", JSONObject().put("tail", historyTail(120)))
        }.onFailure { error ->
            runCatching {
                root.put("reportError", LogRedaction.reason(error))
            }
        }
        return root.toString(2)
    }

    /**
     * 返回当前 libVLC 原生日志文件的字节数。
     *
     * @return 文件大小；不可用时为 0。
     */
    fun vlcLogBytes(): Long = vlcLog?.takeIf { it.isFile }?.length() ?: 0L

    /**
     * 读取当前会话开始后的 libVLC 原生日志尾部。
     *
     * @param maxLines 最多返回的行数。
     * @return 脱敏后的原生日志文本。
     */
    fun vlcLogTail(maxLines: Int): String {
        val file = vlcLog ?: return ""
        val start = synchronized(lock) { vlcLogStartOffset }
        return readVlcTail(file, start, maxLines)
    }

    /**
     * 读取持久化播放历史的尾部。
     *
     * @param maxLines 最多返回的行数。
     * @return 脱敏后的历史文本。
     */
    fun historyTail(maxLines: Int): String {
        val file = historyFile ?: return "(history unavailable)"
        if (!file.isFile) return "(empty)"
        return synchronized(fileLock) {
            runCatching {
                readTailText(file, MAX_HISTORY_TAIL_BYTES)
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .toList()
                    .takeLast(maxLines)
                    .joinToString("\n") { LogRedaction.text(it) }
            }.getOrDefault("(unreadable)")
        }
    }

    /**
     * 返回指定上下文对应的 libVLC 原生日志文件。
     *
     * @param context 用于定位应用私有目录的上下文。
     * @return 原生日志文件。
     */
    fun vlcLogFile(context: Context): File {
        val directory = File(context.applicationContext.filesDir, DIRECTORY_NAME)
        directory.mkdirs()
        return File(directory, VLC_LOG_FILE)
    }

    /** 在会话内存中追加一条带相对时间的事件。 */
    private fun formatEventLocked(name: String, detail: String): String {
        val safeDetail = LogRedaction.text(detail).take(MAX_EVENT_DETAIL)
        val line = "+${elapsedLocked()}ms $name" + if (safeDetail.isBlank()) "" else " $safeDetail"
        events.addLast(line)
        while (events.size > MAX_EVENTS) events.removeFirst()
        return line
    }

    /** 关闭会话并返回生成的事件行。 */
    private fun closeSessionLocked(reason: String): String {
        sessionOpen = false
        sessionState = if (sessionState.startsWith("failed")) sessionState else reason.take(80)
        return formatEventLocked("Session", "close reason=$reason")
    }

    /** 返回会话已运行的毫秒数。 */
    private fun elapsedLocked(): Long =
        if (sessionStartedElapsed > 0L) {
            SystemClock.elapsedRealtime() - sessionStartedElapsed
        } else {
            0L
        }

    /** 构造会话 JSON 内容。 */
    private fun buildSessionJsonLocked(): JSONObject = JSONObject().apply {
        put("id", sessionId)
        put("source", sessionSource)
        put("startedAt", sessionStartedWall)
        put("state", sessionState)
        put("open", sessionOpen)
        put("elapsedMs", elapsedLocked())
        put("attributes", JSONObject().apply {
            attributes.forEach { (key, value) -> put(key, value) }
        })
        put("tracks", JSONArray().apply {
            for (index in 0 until tracks.length()) put(tracks.get(index))
        })
        put("events", JSONArray().apply {
            events.forEach { put(it) }
        })
        put("stats", JSONObject().apply {
            put("samples", statsSamples.length())
            put("first", statsFirst ?: JSONObject.NULL)
            put("last", statsLast ?: JSONObject.NULL)
            put("series", JSONArray().apply {
                for (index in 0 until statsSamples.length()) put(statsSamples.get(index))
            })
        })
        put("failure", failure ?: JSONObject.NULL)
    }

    /** 构造 libVLC 原生日志 JSON。 */
    private fun buildVlcLogJson(): JSONObject = JSONObject().apply {
        val current = vlcLog
        put("file", current?.absolutePath.orEmpty())
        put("bytes", current?.takeIf { it.isFile }?.length() ?: 0L)
        put("available", (current?.takeIf { it.isFile }?.length() ?: 0L) > 0L)
        put("tail", vlcLogTail(180))
        val previous = previousVlcLog
        if (previous != null && previous.isFile) {
            put("previousBytes", previous.length())
            put("previousTail", readVlcTail(previous, 0L, 80))
        }
    }

    /** 读取文件尾部并生成脱敏的日志行。 */
    private fun readVlcTail(file: File, startOffset: Long, maxLines: Int): String {
        if (!file.isFile) return ""
        return runCatching {
            val length = file.length()
            if (length <= 0L) return@runCatching ""
            val from = maxOf(
                startOffset.coerceIn(0L, length),
                length - MAX_VLC_TAIL_BYTES,
            ).coerceAtLeast(0L)
            RandomAccessFile(file, "r").use { handle ->
                handle.seek(from)
                val size = (length - from).toInt().coerceAtMost(MAX_VLC_TAIL_BYTES.toInt())
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

    /** 读取文件尾部文本。 */
    private fun readTailText(file: File, maxBytes: Long): String = runCatching {
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

    /** 追加一条脱敏后的持久化记录。 */
    private fun appendHistory(raw: String) {
        val file = historyFile ?: return
        val safe = LogRedaction.text(raw).take(MAX_HISTORY_LINE)
        synchronized(fileLock) {
            runCatching {
                file.parentFile?.mkdirs()
                rotateHistoryIfNeededLocked(file)
                file.appendText(safe + "\n")
            }
        }
    }

    /** 在文件锁内按大小轮转播放历史。 */
    private fun rotateHistoryIfNeededLocked(file: File) {
        if (!file.isFile || file.length() <= MAX_HISTORY_BYTES) return
        val tail = readTailText(file, MAX_HISTORY_TAIL_BYTES)
        file.writeText("[ROTATED] trimmed playback history\n$tail")
    }

    /** 在安装阶段检查播放历史是否需要轮转。 */
    private fun rotateHistoryIfNeeded() {
        val file = historyFile ?: return
        synchronized(fileLock) {
            runCatching { rotateHistoryIfNeededLocked(file) }
        }
    }

    /** 判断上一次运行是否没有正常关闭会话。 */
    private fun detectUncleanHistory(): Boolean {
        val file = historyFile ?: return false
        val tail = synchronized(fileLock) {
            runCatching { readTailText(file, MAX_HISTORY_TAIL_BYTES) }.getOrDefault("")
        }
        if (tail.isBlank()) return false
        return tail.lastIndexOf(OPEN_MARKER) > tail.lastIndexOf(CLOSE_MARKER)
    }

    /** 将上一次运行的 libVLC 原生日志轮转为备份，返回备份字节数。 */
    private fun rotateVlcLog(current: File): Long = runCatching {
        val previous = previousVlcLog
        val size = if (current.isFile) current.length() else 0L
        when {
            !current.isFile -> 0L
            size <= 0L || previous == null -> {
                current.delete()
                0L
            }
            else -> {
                previous.delete()
                if (size <= MAX_VLC_PREVIOUS_BYTES && current.renameTo(previous)) {
                    size
                } else {
                    // 文件过大或跨文件系统时只保留尾部，避免备份无限增长。
                    val tail = readTailText(current, MAX_VLC_PREVIOUS_BYTES)
                    previous.writeText(tail)
                    current.delete()
                    tail.toByteArray(Charsets.UTF_8).size.toLong()
                }
            }
        }
    }.getOrDefault(0L)
}
