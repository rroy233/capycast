package com.roy233.tvcast.core.diagnostics

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 保存一次播放会话的内存状态与时间线，并负责会话 JSON 的序列化。
 *
 * 不涉及文件读写：持久化由 [PlaybackDiagnostics] 通过 [DiagnosticsFileStore]
 * 完成。会话替换、失败与崩溃的关闭顺序在这里固定，确保 `/playback` 输出稳定。
 */
internal class DiagnosticsSessionRecorder {

    private val lock = Any()

    private val events = ArrayDeque<String>()
    private val attributes = LinkedHashMap<String, String>()
    private val runtimeInfo = LinkedHashMap<String, String>()
    private var tracks = JSONArray()
    private var statsSamples = JSONArray()
    private var statsFirst: JSONObject? = null
    private var statsLast: JSONObject? = null
    private var failure: JSONObject? = null

    private var sessionId = ""
    private var sessionSource = ""
    private var sessionStartedWall = ""
    private var sessionStartedElapsed = 0L
    private var sessionState = "none"
    private var sessionOpen = false
    private var vlcLogStartOffset = 0L
    private var sessionCounter = 0

    /** 一次 [begin] 的结果，供调用方写入持久化标记。 */
    data class Begin(
        val id: String,
        val source: String,
        val startedWall: String,
        val line: String,
        val supersededId: String?,
    )

    /** 一次 [close] 的结果。 */
    data class Close(val id: String, val line: String)

    /** 一次 [fail] 的结果。 */
    data class Failure(val id: String, val code: String, val message: String, val detail: String, val line: String)

    /** 一次 [recordCrash] 的结果。 */
    data class Crash(val id: String, val reason: String, val stack: String)

    fun begin(source: String): Begin {
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
            vlcLogStartOffset = 0L
            attributes["session.source"] = safeSource
            line = formatEventLocked("Session", "begin id=$id source=$safeSource")
        }
        return Begin(id, safeSource, wall, line, supersededId)
    }

    fun isOpen(): Boolean = synchronized(lock) { sessionOpen }

    fun ensure(source: String) {
        if (!isOpen()) begin(source)
    }

    fun close(reason: String): Close? {
        val safeReason = reason.take(80)
        return synchronized(lock) {
            if (!sessionOpen) return@synchronized null
            Close(sessionId, closeSessionLocked(safeReason))
        }
    }

    fun state(state: String) {
        synchronized(lock) {
            if (sessionOpen) sessionState = state.take(80)
        }
    }

    fun attribute(key: String, value: String) {
        val safeValue = LogRedaction.text(value).take(MAX_ATTRIBUTE_LENGTH)
        synchronized(lock) {
            attributes[key.take(80)] = safeValue
        }
    }

    fun runtime(key: String, value: String) {
        val safeValue = LogRedaction.text(value).take(MAX_ATTRIBUTE_LENGTH)
        synchronized(lock) {
            runtimeInfo[key.take(80)] = safeValue
        }
    }

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

    fun event(name: String, detail: String): String = synchronized(lock) {
        formatEventLocked(name.take(60), detail)
    }

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

    fun fail(code: String, message: String, detail: String): Failure {
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
        return Failure(id, code, safeMessage, safeDetail, line)
    }

    fun recordCrash(threadName: String, throwable: Throwable): Crash {
        val stack = runCatching {
            throwable.stackTraceToString()
                .lineSequence()
                .take(40)
                .joinToString("\n")
        }.getOrDefault("")
        val safeStack = LogRedaction.text(stack)
        val id: String
        val reason = LogRedaction.reason(throwable)
        synchronized(lock) {
            id = sessionId.ifBlank { "no-session" }
            formatEventLocked("Crash", "thread=${threadName.take(60)} $reason")
            sessionState = "crashed"
            sessionOpen = false
        }
        return Crash(id, reason, safeStack)
    }

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

    /** 构造当前会话的 JSON 内容。 */
    fun sessionJson(): JSONObject = synchronized(lock) {
        buildSessionJsonLocked()
    }

    /** 构造跨会话运行时属性的 JSON 内容。 */
    fun runtimeJson(): JSONObject = synchronized(lock) {
        JSONObject().apply {
            runtimeInfo.forEach { (key, value) -> put(key, value) }
        }
    }

    /** 读取本次运行保留的 libVLC 原生日志起始偏移。 */
    fun vlcLogStartOffset(): Long = synchronized(lock) { vlcLogStartOffset }

    /** 在会话内存中追加一条带相对时间的事件。 */
    private fun formatEventLocked(name: String, detail: String): String {
        val line = PlaybackReportSerializer.formatEvent(elapsedLocked(), name, detail, MAX_EVENT_DETAIL)
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

    private companion object {
        const val MAX_EVENTS = 320
        const val MAX_STATS_SAMPLES = 90
        const val MAX_EVENT_DETAIL = 500
        const val MAX_ATTRIBUTE_LENGTH = 400
        const val MAX_TRACKS = 160
    }
}
