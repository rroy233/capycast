package com.roy233.tvcast.core.diagnostics

import android.content.Context
import android.os.Build
import com.roy233.tvcast.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 记录一次播放会话的完整诊断信息。
 *
 * 与只保存在内存中的 [LogHub] 不同，这里会把会话开始、失败和崩溃写入应用私有 目录，便于在电视被系统回收或应用崩溃后继续排查。`/playback` 端点会输出会话
 * 时间线、媒体轨道、播放统计与失败快照。
 *
 * 会话内存状态与 JSON 序列化由 [DiagnosticsSessionRecorder] 负责；文件读写由 [DiagnosticsFileStore]
 * 负责；本对象只承担生命周期入口与两者之间的编排。
 */
object PlaybackDiagnostics {

    /** 日志标签。 */
    private const val TAG = "PlaybackDiag"
    /** 诊断文件所在目录名。 */
    private const val DIRECTORY_NAME = "diagnostics"
    /** 持久化播放历史文件名。 */
    private const val HISTORY_FILE = "playback-history.log"
    /** 会话开始标记。 */
    private const val OPEN_MARKER = "[OPEN]"
    /** 会话结束标记。 */
    private const val CLOSE_MARKER = "[CLOSE]"
    /** 单条持久化记录允许的最大字符数。 */
    private const val MAX_HISTORY_LINE = 8000
    /** 播放历史文件允许的最大字节数。 */
    private const val MAX_HISTORY_BYTES = 256L * 1024L
    /** 轮转后保留的播放历史字节数。 */
    private const val MAX_HISTORY_TAIL_BYTES = 96L * 1024L

    /** 会话内存状态与 JSON 序列化。 */
    private val session = DiagnosticsSessionRecorder()

    /** 保护诊断文件读写的锁。 */
    private val fileLock = Any()

    /** 是否已经完成初始化。 */
    @Volatile private var installed = false

    /** 应用上下文。 */
    @Volatile private var appContext: Context? = null
    /** 诊断文件目录。 */
    @Volatile private var diagnosticsDir: File? = null
    /** 持久化播放历史文件。 */
    @Volatile private var historyFile: File? = null

    /**
     * 初始化诊断目录、日志文件和历史记录。
     *
     * @param context 用于定位应用私有目录的上下文。
     */
    fun install(context: Context) {
        if (installed) return
        var unclean = false
        synchronized(fileLock) {
            if (installed) return
            val application = context.applicationContext
            appContext = application
            val directory = File(application.filesDir, DIRECTORY_NAME)
            directory.mkdirs()
            diagnosticsDir = directory
            historyFile = File(directory, HISTORY_FILE)
            unclean = detectUncleanHistory()
            rotateHistoryIfNeeded()
            installed = true
        }
        appendHistory(
            "[BOOT] app=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "device=${Build.MANUFACTURER}/${Build.MODEL} " +
                "android=${Build.VERSION.RELEASE}/${Build.VERSION.SDK_INT} " +
                "abis=${Build.SUPPORTED_ABIS.joinToString(",")}",
        )
        if (unclean) {
            appendHistory(
                "[WARN] previous session did not close; app may have been killed or crashed",
            )
        }
        LogHub.i(
            TAG,
            "diagnostics installed dir=${diagnosticsDir?.absolutePath} " +
                "previousSessionOpen=$unclean",
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
        val begin = session.begin(source)
        begin.supersededId?.let { appendHistory("$CLOSE_MARKER id=$it reason=superseded") }
        appendHistory(
            "$OPEN_MARKER id=${begin.id} source=${begin.source} startedAt=${begin.startedWall}",
        )
        LogHub.i(
            TAG,
            "session begin id=${begin.id} source=${begin.source}",
        )
        LogHub.d(TAG, begin.line)
    }

    /**
     * 确保存在一个打开的会话，不存在时才创建。
     *
     * @param source 需要创建会话时使用的来源。
     */
    fun ensureSession(source: String) {
        session.ensure(source)
    }

    /**
     * 关闭当前会话并写入结束标记。
     *
     * @param reason 结束原因。
     */
    fun closeSession(reason: String) {
        val close = session.close(reason) ?: return
        appendHistory("$CLOSE_MARKER id=${close.id} reason=${reason.take(80)}")
        LogHub.i(TAG, "session close id=${close.id} reason=${reason.take(80)}")
        LogHub.d(TAG, close.line)
    }

    /**
     * 更新当前会话状态。
     *
     * @param state 新的状态文本。
     */
    fun state(state: String) {
        session.state(state)
    }

    /**
     * 写入一个会话属性。
     *
     * @param key 属性名。
     * @param value 属性值。
     */
    fun attribute(key: String, value: String) {
        session.attribute(key, value)
    }

    /**
     * 写入一个跨会话保留的运行时属性。
     *
     * @param key 属性名。
     * @param value 属性值。
     */
    fun runtime(key: String, value: String) {
        session.runtime(key, value)
    }

    /**
     * 记录一条媒体轨道。
     *
     * @param kind 轨道类型，例如 video 或 audio。
     * @param fields 轨道字段。
     */
    fun track(kind: String, fields: Map<String, String>) {
        session.track(kind, fields)
    }

    /**
     * 记录一条播放时间线事件。
     *
     * @param name 事件名称。
     * @param detail 事件详情。
     */
    fun event(name: String, detail: String = "") {
        val line = session.event(name, detail)
        LogHub.d(TAG, line)
    }

    /**
     * 记录一次播放统计采样。
     *
     * @param sample 统计采样 JSON。
     */
    fun stats(sample: JSONObject) {
        session.stats(sample)
    }

    /**
     * 记录播放失败，并立即把失败快照持久化。
     *
     * @param code 失败代码。
     * @param message 面向用户的失败描述。
     * @param detail 供排查使用的详细上下文。
     */
    fun fail(code: String, message: String, detail: String = "") {
        val failure = session.fail(code, message, detail)
        appendHistory(
            "[FAIL] id=${failure.id} code=${failure.code.take(80)} message=${failure.message}",
        )
        if (failure.detail.isNotBlank()) appendHistory("    detail=${failure.detail}")
        appendHistory("$CLOSE_MARKER id=${failure.id} reason=failed:${failure.code.take(60)}")
        LogHub.e(
            TAG,
            "session failure id=${failure.id} code=${failure.code} message=${failure.message} detail=${failure.detail}",
        )
        LogHub.d(TAG, failure.line)
    }

    /**
     * 记录未捕获异常，便于在进程崩溃后继续排查。
     *
     * @param threadName 发生异常的线程名。
     * @param throwable 未捕获异常。
     */
    fun recordCrash(threadName: String, throwable: Throwable) {
        val crash = session.recordCrash(threadName, throwable)
        appendHistory(
            "[CRASH] id=${crash.id} thread=${threadName.take(60)} " +
                "${crash.reason}\n${crash.stack}",
        )
        LogHub.e(
            TAG,
            "crash id=${crash.id} thread=$threadName ${crash.reason}",
            throwable,
            stack = true,
        )
    }

    /**
     * 返回当前会话的单行摘要，用于状态接口。
     *
     * @return 单行会话摘要。
     */
    fun summary(): String = session.summary()

    /**
     * 构造完整的播放诊断报告。
     *
     * @return 可直接返回给 `/playback` 的 JSON 文本。
     */
    fun reportJson(): String {
        val root = JSONObject()
        runCatching {
            root.put(
                "generatedAt",
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
            )
            root.put("installed", installed)
            appContext?.let { context -> root.put("device", DeviceDiagnostics.snapshot(context)) }
            root.put("session", session.sessionJson())
            root.put("runtime", session.runtimeJson())
            root.put("history", JSONObject().put("tail", historyTail(120)))
        }
            .onFailure { error ->
                runCatching {
                    root.put("reportError", LogRedaction.reason(error))
                }
            }
        return root.toString(2)
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
                }
                .getOrDefault("(unreadable)")
        }
    }

    /**
     * 读取文件尾部文本。
     *
     * @param file 目标文件。
     * @param maxBytes 读取的字节上限。
     * @return 文件文本；不可读时返回空串。
     */
    private fun readTailText(file: File, maxBytes: Long): String =
        DiagnosticsFileStore.readTailText(file, maxBytes)

    /** 追加一条脱敏后的持久化记录。 */
    private fun appendHistory(raw: String) {
        val file = historyFile ?: return
        val safe = LogRedaction.text(raw).take(MAX_HISTORY_LINE)
        synchronized(fileLock) {
            runCatching {
                DiagnosticsFileStore.rotateHistoryIfNeeded(
                    file,
                    maxBytes = MAX_HISTORY_BYTES,
                    tailBytes = MAX_HISTORY_TAIL_BYTES,
                )
                DiagnosticsFileStore.appendHistory(file, safe)
            }
        }
    }

    /** 在文件锁内按大小轮转播放历史。 */
    private fun rotateHistoryIfNeededLocked(file: File) {
        DiagnosticsFileStore.rotateHistoryIfNeeded(
            file,
            maxBytes = MAX_HISTORY_BYTES,
            tailBytes = MAX_HISTORY_TAIL_BYTES,
        )
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
        val tail =
            synchronized(fileLock) {
                runCatching { readTailText(file, MAX_HISTORY_TAIL_BYTES) }.getOrDefault("")
            }
        if (tail.isBlank()) return false
        return tail.lastIndexOf(OPEN_MARKER) > tail.lastIndexOf(CLOSE_MARKER)
    }
}
