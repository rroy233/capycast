package com.roy233.tvcast.core.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny on-device diagnostics channel.
 *
 * The real device has no reliable adb in normal use, so the app exposes its
 * recent runtime state on the LAN:
 *
 *   http://<device-ip>:8098/state   JSON (device, login, current play)
 *   http://<device-ip>:8098/log     recent log lines
 *   http://<device-ip>:8098/playback playback diagnostics (tracks, events, native log)
 *   http://<device-ip>:8098/device  device codec and display capabilities
 *   http://<device-ip>:8098/        HTML, includes the pending QR login URL
 *
 * It deliberately never stores the access_key or cookie values.
 */
object LogHub {

    /** 诊断 HTTP 服务端口。 */
    private const val PORT = 8098
    /** 内存中保留的最大日志条数。 */
    private const val MAX_LINES = 2400
    /** 错误日志在内存中保留的最大堆栈帧数。 */
    private const val STACK_FRAMES = 5

    /** 按时间顺序保存的日志行。 */
    private val lines = ArrayDeque<String>()
    /** 当前诊断 HTTP 服务。 */
    private var server: Server? = null
    /** 用于读取设备显示指标的应用上下文。 */
    private var appContext: Context? = null
    /** 当前登录账号标识。 */
    private var loginMid: Long = 0L
    /** 当前播放摘要。 */
    private var playInfo: String = ""
    /** 是否存在待处理的二维码登录。 */
    private var qrPending: Boolean = false
    /** 当前二维码登录地址。 */
    private var qrUrl: String = ""
    /** 当前 DLNA 描述地址。 */
    private var dlnaUrl: String = ""
    /** 当前投屏标题。 */
    private var castTitle: String = ""

    /** 启动局域网诊断 HTTP 服务。 */
    fun start(ctx: Context) {
        appContext = ctx.applicationContext
        if (server != null) return
        try {
            server = Server(PORT).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            i("LogHub", "http://${localIp()}:$PORT/log")
        } catch (t: Throwable) {
            e("LogHub", "start failed: ${t.message}", t)
        }
    }

    /** 停止局域网诊断 HTTP 服务。 */
    fun stop() {
        try { server?.stop() } catch (_: Throwable) {}
        server = null
    }

    /** 记录一条信息级日志。 */
    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        add("I/$tag", msg)
    }

    /** 记录一条调试级日志，用于播放事件等细节。 */
    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        add("D/$tag", msg)
    }

    /** 记录一条警告级日志。 */
    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        add("W/$tag", msg)
    }

    /**
     * 记录一条错误级日志及可选异常。
     *
     * @param stack 是否把少量堆栈帧写入内存日志；完整堆栈始终输出到 logcat。
     */
    fun e(tag: String, msg: String, t: Throwable? = null, stack: Boolean = false) {
        Log.e(tag, msg, t)
        val detail = buildString {
            append(msg)
            if (t != null) {
                append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message)
                if (stack) {
                    t.stackTrace.take(STACK_FRAMES).forEach { frame ->
                        append("\n    at ").append(frame)
                    }
                }
            }
        }
        add("E/$tag", detail)
    }

    /** 更新当前登录账号标识。 */
    fun setLogin(mid: Long) { loginMid = mid }
    /** 更新当前播放摘要。 */
    fun setPlay(info: String) { playInfo = info }
    /** 更新二维码登录待处理状态。 */
    fun setQrPending(pending: Boolean) { qrPending = pending }
    /** 更新二维码登录地址。 */
    fun setQrUrl(url: String) { qrUrl = url }
    /** 更新 DLNA 描述地址。 */
    fun setDlna(url: String) { dlnaUrl = url }
    /** 更新当前投屏标题。 */
    fun setCast(title: String) { castTitle = title }

    /** 将一条日志追加到有上限的内存队列。 */
    private fun add(level: String, msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(lines) {
            lines.addLast("[$ts] $level $msg")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    /** 导出当前内存中的全部日志文本。 */
    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }

    /** 构造当前设备和投屏状态的 JSON 文本。 */
    fun stateJson(): String {
        val j = JSONObject()
        j.put("app", "CapyCast")
        j.put("ip", localIp())
        j.put("logUrl", "http://${localIp()}:$PORT/log")
        j.put("stateUrl", "http://${localIp()}:$PORT/state")
        j.put("model", Build.MODEL)
        j.put("brand", Build.BRAND)
        j.put("android", Build.VERSION.RELEASE)
        j.put("sdk", Build.VERSION.SDK_INT)
        j.put("abi", Build.SUPPORTED_ABIS.joinToString(","))
        j.put("mid", loginMid)
        j.put("qrPending", qrPending)
        j.put("qrUrl", qrUrl)
        j.put("dlnaUrl", dlnaUrl)
        j.put("castTitle", castTitle)
        j.put("play", playInfo)
        j.put("playback", PlaybackDiagnostics.summary())
        j.put("playbackUrl", "http://${localIp()}:$PORT/playback")
        j.put("deviceUrl", "http://${localIp()}:$PORT/device")
        j.put("vlcLogBytes", PlaybackDiagnostics.vlcLogBytes())
        appContext?.let { ctx ->
            val dm = ctx.resources.displayMetrics
            j.put("widthPx", dm.widthPixels)
            j.put("heightPx", dm.heightPixels)
            j.put("density", dm.density)
            j.put("widthDp", (dm.widthPixels / dm.density).toInt())
            j.put("heightDp", (dm.heightPixels / dm.density).toInt())
        }
        return j.toString(2)
    }

    /** 查找当前设备可用于局域网访问的 IPv4 地址。 */
    fun localIp(): String {
        return try {
            val candidates = mutableListOf<Pair<String, String>>()
            for (iface in java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in java.util.Collections.list(iface.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        candidates += iface.name to host
                    }
                }
            }
            (candidates.firstOrNull { it.first.startsWith("wlan") }
                ?: candidates.firstOrNull { it.first.startsWith("eth") }
                ?: candidates.firstOrNull())?.second ?: "127.0.0.1"
        } catch (_: Throwable) {
            "127.0.0.1"
        }
    }

    /** 为诊断页面提供日志、状态和登录链接。 */
    private class Server(port: Int) : NanoHTTPD(port) {
        /** 根据请求路径返回诊断内容。 */
        override fun serve(session: IHTTPSession): Response {
            return when (session.uri) {
                "/log", "/logs" ->
                    newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", LogHub.dump())
                "/state", "/json" ->
                    newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", LogHub.stateJson())
                "/playback", "/playback.json" ->
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json; charset=utf-8",
                        PlaybackDiagnostics.reportJson(),
                    )
                "/device", "/device.json" -> {
                    val ctx = LogHub.appContext
                    val body = if (ctx == null) {
                        "{\"error\":\"diagnostics not initialized\"}"
                    } else {
                        runCatching { DeviceDiagnostics.snapshot(ctx).toString(2) }
                            .getOrElse { "{\"error\":\"device info unavailable\"}" }
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)
                }
                else -> {
                    val url = LogHub.qrUrl
                    val link = if (url.isNotEmpty()) {
                        "<p>登录链接（手机浏览器直接打开）：<br><a href=\"$url\">$url</a></p>"
                    } else {
                        "<p>当前没有待处理的登录链接。</p>"
                    }
                    val html = "<html><head><meta charset=\"utf-8\"></head><body>" +
                        "<pre>${LogHub.stateJson()}</pre>$link" +
                        "<p><a href=\"/log\">/log</a> | <a href=\"/state\">/state</a> | " +
                        "<a href=\"/playback\">/playback</a> | <a href=\"/device\">/device</a></p>" +
                        "</body></html>"
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
                }
            }
        }
    }
}
