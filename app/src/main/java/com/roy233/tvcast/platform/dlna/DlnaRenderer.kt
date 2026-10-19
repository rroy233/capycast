package com.roy233.tvcast.platform.dlna

import android.content.Context
import android.net.wifi.WifiManager
import com.roy233.tvcast.BuildConfig
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.R
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Minimal UPnP/DLNA MediaRenderer that the Bilibili phone app discovers as a
 * normal "投屏" target.
 *
 * The phone sends `SetAVTransportURI` with a DIDL-Lite document.  The real
 * video identity is inside `<upnp:longDescription>` as an encrypted
 * `_nva_ext_` payload; [NvaExtDecoder] turns it into aid/cid, and the
 * [Listener] resolves the account's max quality with libVLC.
 *
 * The device description deliberately advertises a generic DLNA renderer
 * without Bilibili vendor fields: when it claims to be an official
 * "云视听小电视" TV, the Android app switches to the proprietary BiliLink/NVA
 * projection protocol (`SETUP /projection`) instead of DLNA, which this
 * renderer does not implement.
 */
class DlnaRenderer(
    /** 用于读取设备资源和网络服务的上下文。 */
    private val context: Context,
    /** 接收投屏与播放控制事件的回调接口。 */
    private val listener: Listener,
    /** 对外广播的 DLNA 设备名称。 */
    private val deviceName: String = "CapyCast",
) {

    /** 接收 DLNA 媒体与播放控制事件。 */
    interface Listener {
        /** 接收到新的媒体地址和 DIDL 元数据。 */
        fun onCast(uri: String, metadata: String, title: String, longDescription: String)
        /** 收到播放命令。 */
        fun onPlay()
        /** 收到暂停命令。 */
        fun onPause()
        /** 收到停止命令。 */
        fun onStop()
        /** 收到跳转命令。 */
        fun onSeek(positionMs: Long)
        /** 收到音量设置命令。 */
        fun onSetVolume(volume: Int)
        /** 返回当前播放位置，单位为毫秒。 */
        fun positionMs(): Long
        /** 返回当前媒体时长，单位为毫秒。 */
        fun durationMs(): Long
        /** 返回当前是否正在播放。 */
        fun isPlaying(): Boolean
        /** 返回当前音量值。 */
        fun volume(): Int
    }

    /** 保存 DLNA 传输状态和媒体元数据。 */
    private class TransportState {
        /** 当前传输状态。 */
        @Volatile var transport: String = "NO_MEDIA_PRESENT"
        /** 当前媒体地址。 */
        @Volatile var uri: String = ""
        /** 当前 DIDL-Lite 元数据。 */
        @Volatile var metadata: String = ""
        /** 当前媒体标题。 */
        @Volatile var title: String = ""
        /** 当前媒体时长，单位为毫秒。 */
        @Volatile var durationMs: Long = 0L
        /** 当前音量值。 */
        @Volatile var volume: Int = 50
        /** 当前是否静音。 */
        @Volatile var mute: Boolean = false
    }

    /** 线程安全的 DLNA 传输状态。 */
    private val state = TransportState()
    /** 设备对外使用的 IPv4 地址。 */
    private val ip: String = LogHub.localIp()
    /** HTTP 描述和控制服务端口。 */
    private val port: Int = PORT
    /** 持久化的 UPnP 设备标识。 */
    private val udn: String = loadUdn()
    /** AVTransport 服务描述资源。 */
    private val scpdAvt: ByteArray by lazy { readRaw(R.raw.scpd_avt) }
    /** RenderingControl 服务描述资源。 */
    private val scpdRcs: ByteArray by lazy { readRaw(R.raw.scpd_rcs) }
    /** ConnectionManager 服务描述资源。 */
    private val scpdCms: ByteArray by lazy { readRaw(R.raw.scpd_cms) }

    /** DLNA 服务是否正在运行。 */
    @Volatile
    private var running = false
    /** HTTP 服务实例。 */
    private var http: HttpServer? = null
    /** SSDP 服务实例。 */
    private var ssdp: SsdpServer? = null
    /** SSDP 保活线程。 */
    private var keepAlive: Thread? = null

    /** 当前设备描述文档的访问地址。 */
    val descriptionUrl: String
        get() = "http://$ip:$port/description.xml"

    /** 启动 HTTP、SSDP 和设备保活服务。 */
    fun start() {
        if (running) return
        running = true
        try {
            http = HttpServer().also { it.start() }
        } catch (t: Throwable) {
            LogHub.e(TAG, "http start failed: ${t.message}", t)
            running = false
            return
        }
        try {
            ssdp = SsdpServer().also { it.start() }
        } catch (t: Throwable) {
            LogHub.e(TAG, "ssdp start failed: ${t.message}", t)
        }
        keepAlive = thread(name = "dlna-keepalive", isDaemon = true) {
            while (running) {
                try {
                    Thread.sleep(30_000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break
                runCatching { ssdp?.notify("ssdp:alive") }
            }
        }
        LogHub.i(TAG, "renderer started name=$deviceName url=$descriptionUrl")
        LogHub.setDlna(descriptionUrl)
    }

    /** 停止 HTTP、SSDP 和设备保活服务。 */
    fun stop() {
        if (!running) return
        running = false
        runCatching { ssdp?.notify("ssdp:byebye") }
        runCatching { ssdp?.stop() }
        runCatching { http?.stop() }
        keepAlive?.interrupt()
        keepAlive = null
        ssdp = null
        http = null
        LogHub.i(TAG, "renderer stopped")
    }

    /** 更新当前媒体时长。 */
    fun setDurationMs(ms: Long) {
        state.durationMs = ms.coerceAtLeast(0L)
    }

    /**
     * 将传输状态标记为停止。
     *
     * @param reason 停止原因，用于诊断日志。
     */
    fun markCastFailed(reason: String = "") {
        state.transport = "STOPPED"
        LogHub.i(TAG, "markCastFailed reason=$reason transport=${state.transport}")
    }

    // ------------------------------------------------------------------ //
    // HTTP
    // ------------------------------------------------------------------ //
    /** 接收并处理 DLNA HTTP 请求的内部服务。 */
    private inner class HttpServer {
        /** 监听 HTTP 请求的服务端套接字。 */
        private val serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        /** 处理 HTTP 请求的线程池。 */
        private val pool = Executors.newCachedThreadPool()
        /** HTTP 服务是否继续接受请求。 */
        @Volatile
        private var alive = true

        /** 启动 HTTP 接受线程。 */
        fun start() {
            thread(name = "dlna-http", isDaemon = true) {
                while (alive) {
                    val socket = try {
                        serverSocket.accept()
                    } catch (t: Throwable) {
                        if (alive) LogHub.e(TAG, "accept failed: ${t.message}", t)
                        break
                    }
                    pool.execute {
                        runCatching { handle(socket) }
                            .onFailure { LogHub.e(TAG, "http handler failed: ${it.message}", it) }
                    }
                }
            }
        }

        /** 停止 HTTP 接受线程并关闭线程池。 */
        fun stop() {
            alive = false
            runCatching { serverSocket.close() }
            pool.shutdownNow()
        }

        /** 读取并处理单个 HTTP 连接。 */
        private fun handle(socket: Socket) {
            socket.use { s ->
                s.soTimeout = 15_000
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    write(s, response(400, "Bad Request", "text/plain", "bad request"))
                    return
                }
                val method = parts[0].uppercase(Locale.US)
                val path = parts[1].substringBefore('?')
                val headers = readHeaders(input)
                if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
                    s.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                    s.getOutputStream().flush()
                }
                val body = readBody(input, headers)
                LogHub.i(
                    TAG,
                    "HTTP $method $path from ${s.inetAddress?.hostAddress} " +
                        "soap=${headers["soapaction"]?.take(80)} body=${body.size}B"
                )
                val resp = when (method) {
                    "GET", "HEAD" -> handleGet(path)
                    "POST" -> handlePost(path, headers, body)
                    "SUBSCRIBE" -> response(
                        200, "OK", "text/xml; charset=\"utf-8\"", ByteArray(0),
                        mapOf(
                            "SID" to "uuid:${UUID.randomUUID()}",
                            "TIMEOUT" to "Second-1800",
                        )
                    )
                    "UNSUBSCRIBE" -> response(200, "OK", "text/plain", ByteArray(0))
                    else -> response(405, "Method Not Allowed", "text/plain", "method not allowed")
                }
                val out = if (method == "HEAD") resp.copy(body = ByteArray(0)) else resp
                write(s, out)
            }
        }
    }

    /** 表示待写回客户端的 HTTP 响应。 */
    private data class HttpResponse(
        /** HTTP 状态码。 */
        val code: Int,
        /** HTTP 状态原因短语。 */
        val reason: String,
        /** 响应内容类型。 */
        val contentType: String,
        /** 响应正文。 */
        val body: ByteArray,
        /** 额外响应头。 */
        val extra: Map<String, String> = emptyMap(),
    )

    /** 使用字符串正文构造 HTTP 响应。 */
    private fun response(
        code: Int,
        reason: String,
        type: String,
        body: String,
        extra: Map<String, String> = emptyMap(),
    ): HttpResponse = response(code, reason, type, body.toByteArray(Charsets.UTF_8), extra)

    /** 使用字节正文构造 HTTP 响应。 */
    private fun response(
        code: Int,
        reason: String,
        type: String,
        body: ByteArray,
        extra: Map<String, String> = emptyMap(),
    ): HttpResponse = HttpResponse(code, reason, type, body, extra)

    /** 将 HTTP 响应写入套接字。 */
    private fun write(socket: Socket, resp: HttpResponse) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ${resp.code} ${resp.reason}\r\n")
        head.append("Content-Type: ${resp.contentType}\r\n")
        head.append("Content-Length: ${resp.body.size}\r\n")
        head.append("Connection: close\r\n")
        for ((k, v) in resp.extra) head.append("$k: $v\r\n")
        head.append("\r\n")
        val out = socket.getOutputStream()
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        if (resp.body.isNotEmpty()) out.write(resp.body)
        out.flush()
    }

    /** 从输入流读取一行 UTF-8 文本。 */
    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) {
                val bytes = out.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes.size - 1
                } else {
                    bytes.size
                }
                return String(bytes, 0, len, Charsets.UTF_8)
            }
            out.write(b)
            if (out.size() > 32_768) return null
        }
    }

    /** 读取并规范化 HTTP 请求头。 */
    private fun readHeaders(input: InputStream): Map<String, String> {
        val map = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                map[line.substring(0, idx).trim().lowercase(Locale.US)] =
                    line.substring(idx + 1).trim()
            }
        }
        return map
    }

    /** 根据请求头读取 HTTP 请求正文。 */
    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return readChunked(input)
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return ByteArray(0)
        if (length > MAX_BODY) error("body too large: $length")
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n < 0) break
            read += n
        }
        return if (read == length) data else data.copyOf(read)
    }

    /** 读取 chunked 编码的 HTTP 请求正文。 */
    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input) ?: break
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                readLine(input)
                break
            }
            if (out.size() + size > MAX_BODY) error("chunked body too large")
            val data = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(data, read, size - read)
                if (n < 0) break
                read += n
            }
            out.write(data, 0, read)
            readLine(input)
        }
        return out.toByteArray()
    }

    /** 处理设备描述、服务描述和状态页面的 GET 请求。 */
    private fun handleGet(path: String): HttpResponse = when (path) {
        "/bilibili/description.xml", "/description.xml", "/dd.xml" ->
            response(200, "OK", "text/xml; charset=\"utf-8\"", descriptionXml())
        "/scpd/avt.xml", "/scpd.xml" ->
            response(200, "OK", "text/xml; charset=\"utf-8\"", scpdAvt)
        "/scpd/rcs.xml" ->
            response(200, "OK", "text/xml; charset=\"utf-8\"", scpdRcs)
        "/scpd/cms.xml" ->
            response(200, "OK", "text/xml; charset=\"utf-8\"", scpdCms)
        "/", "/index.html" -> {
            val body = buildString {
                append("<html><head><meta charset=\"utf-8\"></head><body><pre>")
                append("CapyCast DLNA renderer\n")
                append("name: ").append(deviceName).append('\n')
                append("udn: uuid:").append(udn).append('\n')
                append("description: ").append(descriptionUrl).append('\n')
                append("transport: ").append(transportState()).append('\n')
                append("title: ").append(state.title).append('\n')
                append("</pre></body></html>")
            }
            response(200, "OK", "text/html; charset=utf-8", body)
        }
        else -> response(404, "Not Found", "text/plain", "not found")
    }

    /** 处理 NVA 请求和 SOAP 控制请求。 */
    private fun handlePost(
        path: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): HttpResponse {
        if (path.startsWith("/nva") || path.contains("nva", ignoreCase = true)) {
            LogHub.i(TAG, "NVA-like POST $path body=${body.size}B")
            return response(200, "OK", "text/plain", ByteArray(0))
        }
        val req = try {
            SoapRequestParser.parse(body, headers["soapaction"], ::serviceFor)
        } catch (t: Throwable) {
            LogHub.e(TAG, "SOAP parse failed: ${t.message}", t)
            return response(400, "Bad Request", "text/plain", "bad soap")
        }
        val result = try {
            dispatch(req)
        } catch (t: Throwable) {
            LogHub.e(TAG, "SOAP dispatch ${req.action} failed: ${t.message}", t)
            soapFault(req.service, 501, "Action Failed")
        }
        return response(200, "OK", "text/xml; charset=\"utf-8\"", result)
    }

    // ------------------------------------------------------------------ //
    // SOAP
    // ------------------------------------------------------------------ //
    /** 分发 SOAP 动作并构造对应响应。 */
    private fun dispatch(req: SoapRequest): ByteArray {
        val s = state
        return when (req.action) {
            "SetAVTransportURI" -> {
                val uri = req.args["CurrentURI"].orEmpty()
                val meta = req.args["CurrentURIMetaData"].orEmpty()
                val title = DlnaXml.extractTitle(meta)
                val longDesc = DlnaXml.extractLongDescription(meta)
                s.uri = uri
                s.metadata = meta
                s.title = title
                s.durationMs = DlnaTime.parseDidlDuration(meta)
                s.transport = "TRANSITIONING"
                LogHub.i(
                    TAG,
                    "SetAVTransportURI title=${title.take(80)} " +
                        "uri=${LogRedaction.url(uri)} metadata=${meta.length}B " +
                        "longDescription=${longDesc.length}B duration=${s.durationMs}ms",
                )
                if (longDesc.isBlank()) {
                    LogHub.w(TAG, "SetAVTransportURI has no longDescription; cast identity missing")
                }
                runCatching { listener.onCast(uri, meta, title, longDesc) }
                    .onFailure { LogHub.e(TAG, "onCast failed", it, stack = true) }
                soapOk(req.service, req.action)
            }
            "Play" -> {
                s.transport = "PLAYING"
                LogHub.i(TAG, "Play command transport=${s.transport} title=${s.title.take(60)}")
                runCatching { listener.onPlay() }
                    .onFailure { LogHub.e(TAG, "onPlay failed", it, stack = true) }
                soapOk(req.service, req.action)
            }
            "Pause" -> {
                s.transport = "PAUSED_PLAYBACK"
                LogHub.i(TAG, "Pause command transport=${s.transport}")
                runCatching { listener.onPause() }
                    .onFailure { LogHub.e(TAG, "onPause failed", it, stack = true) }
                soapOk(req.service, req.action)
            }
            "Stop" -> {
                s.transport = "STOPPED"
                LogHub.i(TAG, "Stop command transport=${s.transport}")
                runCatching { listener.onStop() }
                    .onFailure { LogHub.e(TAG, "onStop failed", it, stack = true) }
                soapOk(req.service, req.action)
            }
            "Seek" -> {
                val target = req.args["Target"].orEmpty()
                val ms = DlnaTime.parse(target)
                LogHub.i(TAG, "Seek $target -> ${ms}ms")
                runCatching { listener.onSeek(ms) }
                    .onFailure { LogHub.e(TAG, "onSeek failed", it, stack = true) }
                soapOk(req.service, req.action)
            }
            "GetTransportInfo" -> soapOk(
                req.service, req.action,
                listOf(
                    "CurrentTransportState" to transportState(),
                    "CurrentTransportStatus" to "OK",
                    "CurrentSpeed" to "1",
                )
            )
            "GetPositionInfo" -> {
                val pos = positionMs()
                val dur = durationMs()
                soapOk(
                    req.service, req.action,
                    listOf(
                        "Track" to "1",
                        "TrackDuration" to DlnaTime.format(dur),
                        "TrackMetaData" to s.metadata,
                        "TrackURI" to s.uri,
                        "RelTime" to DlnaTime.format(pos),
                        "AbsTime" to DlnaTime.format(pos),
                        "RelCount" to "2147483647",
                        "AbsCount" to "2147483647",
                    )
                )
            }
            "GetMediaInfo" -> soapOk(
                req.service, req.action,
                listOf(
                    "NrTracks" to if (s.uri.isEmpty()) "0" else "1",
                    "MediaDuration" to DlnaTime.format(durationMs()),
                    "CurrentURI" to s.uri,
                    "CurrentURIMetaData" to s.metadata,
                    "NextURI" to "",
                    "NextURIMetaData" to "",
                    "PlayMedium" to "NETWORK",
                    "RecordMedium" to "NOT_IMPLEMENTED",
                    "WriteStatus" to "NOT_IMPLEMENTED",
                )
            )
            "GetTransportSettings" -> soapOk(
                req.service, req.action,
                listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            )
            "GetDeviceCapabilities" -> soapOk(
                req.service, req.action,
                listOf(
                    "PlayMedia" to "NETWORK",
                    "RecMedia" to "NOT_IMPLEMENTED",
                    "RecQualityModes" to "NOT_IMPLEMENTED",
                )
            )
            "GetCurrentTransportActions" -> soapOk(
                req.service, req.action, listOf("Actions" to "Play,Pause,Stop,Seek")
            )
            "SetPlayMode", "Next", "Previous" -> soapOk(req.service, req.action)
            "GetVolume" -> soapOk(
                req.service, req.action,
                listOf("CurrentVolume" to volume().toString())
            )
            "SetVolume" -> {
                val v = req.args["DesiredVolume"]?.toIntOrNull()?.coerceIn(0, 100) ?: 50
                s.volume = v
                LogHub.i(TAG, "SetVolume command value=$v")
                runCatching { listener.onSetVolume(v) }
                    .onFailure { LogHub.e(TAG, "onSetVolume failed", it, stack = true) }
                soapOk(req.service, req.action)
            }
            "GetMute" -> soapOk(
                req.service, req.action,
                listOf("CurrentMute" to if (s.mute) "1" else "0")
            )
            "SetMute" -> {
                s.mute = req.args["DesiredMute"] in listOf("1", "true", "True")
                soapOk(req.service, req.action)
            }
            "GetVolumeDB" -> soapOk(req.service, req.action, listOf("CurrentVolume" to "0"))
            "GetVolumeDBRange" -> soapOk(
                req.service, req.action,
                listOf("MinValue" to "-32767", "MaxValue" to "32767")
            )
            "ListPresets" -> soapOk(
                req.service, req.action,
                listOf("CurrentPresetNameList" to "FactoryDefaults")
            )
            "SelectPreset" -> soapOk(req.service, req.action)
            "GetProtocolInfo" -> soapOk(
                req.service, req.action,
                listOf(
                    "Source" to "",
                    "Sink" to "http-get:*:video/mp4:*,http-get:*:video/x-matroska:*," +
                        "http-get:*:application/vnd.apple.mpegurl:*," +
                        "http-get:*:application/dash+xml:*," +
                        "http-get:*:audio/mp4:*,http-get:*:audio/mpeg:*",
                )
            )
            "GetCurrentConnectionIDs" -> soapOk(
                req.service, req.action, listOf("ConnectionIDs" to "0")
            )
            "GetCurrentConnectionInfo" -> soapOk(
                req.service, req.action,
                listOf(
                    "RcsID" to "0",
                    "AVTransportID" to "0",
                    "ProtocolInfo" to "",
                    "PeerConnectionManager" to "",
                    "PeerConnectionID" to "-1",
                    "Direction" to "Input",
                    "Status" to "OK",
                )
            )
            else -> {
                LogHub.i(TAG, "unhandled SOAP action=${req.action}; replying empty")
                soapOk(req.service, req.action)
            }
        }
    }

    /** 根据 SOAP 动作选择对应的 UPnP 服务。 */
    private fun serviceFor(action: String): String = when (action) {
        "GetVolume", "SetVolume", "GetMute", "SetMute", "GetVolumeDB",
        "GetVolumeDBRange", "ListPresets", "SelectPreset" -> RCS
        "GetProtocolInfo", "GetCurrentConnectionIDs", "GetCurrentConnectionInfo" -> CMS
        else -> AVT
    }

    /** 构造成功的 SOAP 动作响应。 */
    private fun soapOk(
        service: String,
        action: String,
        args: List<Pair<String, String>> = emptyList(),
    ): ByteArray {
        val body = args.joinToString("") { (k, v) ->
            "<$k>${DlnaXml.escape(v)}</$k>"
        }
        val inner = "<u:${action}Response xmlns:u=\"${DlnaXml.escape(service)}\">$body</u:${action}Response>"
        return soapEnvelope(inner)
    }

    /** 构造 SOAP 错误响应。 */
    private fun soapFault(service: String, code: Int, description: String): ByteArray {
        val inner = "<s:Fault><faultcode>s:Client</faultcode>" +
            "<faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
            "<errorCode>$code</errorCode>" +
            "<errorDescription>${DlnaXml.escape(description)}</errorDescription>" +
            "</UPnPError></detail></s:Fault>"
        return soapEnvelope(inner)
    }

    /** 将 SOAP 内容包装为完整 SOAP 信封。 */
    private fun soapEnvelope(inner: String): ByteArray {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"$SOAP_ENV\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>$inner</s:Body></s:Envelope>"
        return xml.toByteArray(Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ //
    // description / helpers
    // ------------------------------------------------------------------ //
    /** 生成设备描述 XML 文档。 */
    private fun descriptionXml(): ByteArray {
        val svc = listOf(
            Triple(AVT, "urn:upnp-org:serviceId:AVTransport", "avt"),
            Triple(RCS, "urn:upnp-org:serviceId:RenderingControl", "rcs"),
            Triple(CMS, "urn:upnp-org:serviceId:ConnectionManager", "cms"),
        ).joinToString("\n") { (type, id, key) ->
            """      <service>
        <serviceType>$type</serviceType>
        <serviceId>$id</serviceId>
        <SCPDURL>/scpd/$key.xml</SCPDURL>
        <controlURL>/control/$key</controlURL>
        <eventSubURL>/event/$key</eventSubURL>
      </service>"""
        }
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>$MEDIA_RENDERER</deviceType>
    <friendlyName>${DlnaXml.escape(deviceName)}</friendlyName>
    <manufacturer>CapyCast</manufacturer>
    <manufacturerURL>https://github.com/rroy233/capycast</manufacturerURL>
    <modelDescription>${DlnaXml.escape(deviceName)}</modelDescription>
    <modelName>${DlnaXml.escape(deviceName)}</modelName>
    <modelNumber>${DlnaXml.escape(BuildConfig.VERSION_NAME)}</modelNumber>
    <modelURL>https://github.com/rroy233/capycast</modelURL>
    <serialNumber>${DlnaXml.escape(udn.substringBefore('-').uppercase(Locale.US))}</serialNumber>
    <UDN>uuid:${DlnaXml.escape(udn)}</UDN>
    <dlna:X_DLNADOC xmlns:dlna="urn:schemas-dlna-org:device-1-0">DMR-1.50</dlna:X_DLNADOC>
    <dlna:X_DLNACAP xmlns:dlna="urn:schemas-dlna-org:device-1-0">av-upload,image-upload,audio-upload</dlna:X_DLNACAP>
    <serviceList>
$svc
    </serviceList>
  </device>
</root>
"""
        return xml.toByteArray(Charsets.UTF_8)
    }

    /** 获取兼顾播放器状态的传输状态。 */
    private fun transportState(): String {
        if (state.transport == "TRANSITIONING" && listener.isPlaying()) return "PLAYING"
        return state.transport
    }

    /** 获取当前播放位置并限制为非负值。 */
    private fun positionMs(): Long {
        val pos = runCatching { listener.positionMs() }.getOrDefault(0L)
        return pos.coerceAtLeast(0L)
    }

    /** 获取播放器时长或 DLNA 状态中的时长。 */
    private fun durationMs(): Long {
        val fromPlayer = runCatching { listener.durationMs() }.getOrDefault(0L)
        return if (fromPlayer > 0) fromPlayer else state.durationMs
    }

    /** 获取并校验当前音量值。 */
    private fun volume(): Int {
        val v = runCatching { listener.volume() }.getOrDefault(state.volume)
        return if (v in 0..100) v else state.volume
    }

    /** 读取 raw 资源文件内容。 */
    private fun readRaw(id: Int): ByteArray =
        context.resources.openRawResource(id).use { it.readBytes() }

    /** 读取或生成并持久化 UPnP 设备标识。 */
    private fun loadUdn(): String {
        val sp = context.getSharedPreferences("dlna", Context.MODE_PRIVATE)
        sp.getString("udn", null)?.let { return it }
        val v = UUID.randomUUID().toString()
        sp.edit().putString("udn", v).apply()
        return v
    }

    // ------------------------------------------------------------------ //
    // SSDP
    // ------------------------------------------------------------------ //
    /** 负责 SSDP 组播发现和设备广播的内部服务。 */
    private inner class SsdpServer {
        /** SSDP 组播套接字。 */
        private val socket = MulticastSocket(null as SocketAddress?).apply {
            reuseAddress = true
            bind(InetSocketAddress(SSDP_PORT))
            timeToLive = 2
        }
        /** 用于加入组播的网络接口。 */
        private val iface: NetworkInterface? = findInterface()
        /** Android Wi-Fi 组播锁。 */
        private val multicastLock: WifiManager.MulticastLock? = acquireMulticastLock()
        /** SSDP 服务是否继续运行。 */
        @Volatile
        private var alive = true
        /** SSDP 接收线程。 */
        private var thread: Thread? = null

        /** 加入组播组并启动 SSDP 接收线程。 */
        fun start() {
            try {
                if (iface != null) {
                    socket.joinGroup(InetSocketAddress(SSDP_GROUP, SSDP_PORT), iface)
                    socket.networkInterface = iface
                } else {
                    socket.joinGroup(SSDP_GROUP)
                }
            } catch (t: Throwable) {
                LogHub.e(TAG, "ssdp join failed: ${t.message}", t)
            }
            socket.soTimeout = 1000
            thread = thread(name = "dlna-ssdp", isDaemon = true) { loop() }
            notify("ssdp:alive")
        }

        /** 停止 SSDP 服务并释放组播资源。 */
        fun stop() {
            alive = false
            runCatching { socket.close() }
            runCatching { multicastLock?.release() }
        }

        /** 循环接收并处理 SSDP 数据报。 */
        private fun loop() {
            val buf = ByteArray(8192)
            while (alive) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (t: Throwable) {
                    if (alive) LogHub.e(TAG, "ssdp receive failed: ${t.message}", t)
                    break
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (text.startsWith("M-SEARCH", ignoreCase = true)) {
                    runCatching { handleMSearch(text, packet) }
                        .onFailure { LogHub.e(TAG, "ssdp m-search failed: ${it.message}", it) }
                }
            }
        }

        /** 响应一个 SSDP M-SEARCH 请求。 */
        private fun handleMSearch(text: String, packet: DatagramPacket) {
            val headers = parseHeaders(text)
            val st = headers["st"] ?: "ssdp:all"
            LogHub.i(TAG, "M-SEARCH from ${packet.address?.hostAddress} ST=$st")
            val targets = when {
                st == "ssdp:all" -> listOf(
                    "upnp:rootdevice",
                    "uuid:$udn",
                    MEDIA_RENDERER,
                    AVT,
                    RCS,
                    CMS,
                    DLNA_DEVICE,
                )
                st == "upnp:rootdevice" -> listOf("upnp:rootdevice")
                st == "uuid:$udn" -> listOf("uuid:$udn")
                st.startsWith("uuid:", ignoreCase = true) -> emptyList()
                else -> listOf(st)
            }
            for (target in targets) {
                val usn = if (target == "uuid:$udn") target else "uuid:$udn::$target"
                val resp = "HTTP/1.1 200 OK\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "DATE: ${httpDate()}\r\n" +
                    "EXT:\r\n" +
                    "LOCATION: $descriptionUrl\r\n" +
                    "SERVER: $SERVER\r\n" +
                    "ST: $target\r\n" +
                    "USN: $usn\r\n" +
                    "BOOTID.UPNP.ORG: 1\r\n" +
                    "CONFIGID.UPNP.ORG: 1\r\n" +
                    "\r\n"
                val bytes = resp.toByteArray(Charsets.UTF_8)
                runCatching {
                    socket.send(
                        DatagramPacket(bytes, bytes.size, packet.address, packet.port)
                    )
                }
            }
        }

        /** 向 SSDP 组播地址发送设备状态通知。 */
        fun notify(nts: String) {
            val targets = listOf(
                "upnp:rootdevice" to "uuid:$udn::upnp:rootdevice",
                "uuid:$udn" to "uuid:$udn",
                MEDIA_RENDERER to "uuid:$udn::$MEDIA_RENDERER",
                AVT to "uuid:$udn::$AVT",
                RCS to "uuid:$udn::$RCS",
                CMS to "uuid:$udn::$CMS",
                DLNA_DEVICE to "uuid:$udn::$DLNA_DEVICE",
            )
            for ((nt, usn) in targets) {
                val msg = "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: $descriptionUrl\r\n" +
                    "NT: $nt\r\n" +
                    "NTS: $nts\r\n" +
                    "SERVER: $SERVER\r\n" +
                    "USN: $usn\r\n" +
                    "BOOTID.UPNP.ORG: 1\r\n" +
                    "CONFIGID.UPNP.ORG: 1\r\n" +
                    "\r\n"
                val bytes = msg.toByteArray(Charsets.UTF_8)
                runCatching {
                    socket.send(
                        DatagramPacket(bytes, bytes.size, SSDP_GROUP, SSDP_PORT)
                    )
                }
            }
        }

        /** 解析 SSDP 文本头。 */
        private fun parseHeaders(text: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            for (line in text.split("\r\n").drop(1)) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    map[line.substring(0, idx).trim().lowercase(Locale.US)] =
                        line.substring(idx + 1).trim()
                }
            }
            return map
        }

        /** 生成 GMT 格式的 HTTP 日期。 */
        private fun httpDate(): String {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT")
            return fmt.format(Date())
        }

        /** 查找用于 SSDP 通信的网络接口。 */
        private fun findInterface(): NetworkInterface? = try {
            val addr = InetAddress.getByName(ip)
            NetworkInterface.getByInetAddress(addr) ?: run {
                var found: NetworkInterface? = null
                for (n in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!n.isUp || n.isLoopback) continue
                    for (a in Collections.list(n.inetAddresses)) {
                        if (a is Inet4Address && !a.isLoopbackAddress) {
                            found = n
                            break
                        }
                    }
                    if (found != null) break
                }
                found
            }
        } catch (_: Throwable) {
            null
        }

        /** 获取 Wi-Fi 组播锁。 */
        private fun acquireMulticastLock(): WifiManager.MulticastLock? = try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.createMulticastLock("simplebilicast-dlna")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            LogHub.e(TAG, "multicast lock failed: ${t.message}", t)
            null
        }
    }

    private companion object {
        /** 日志标签。 */
        const val TAG = "DLNA"
        /** HTTP 服务端口。 */
        const val PORT = 18099
        /** 单次 HTTP 请求正文最大长度。 */
        const val MAX_BODY = 8 * 1024 * 1024
        /** SSDP 组播地址。 */
        const val SSDP_ADDR = "239.255.255.250"
        /** SSDP 端口。 */
        const val SSDP_PORT = 1900
        /** SOAP 1.1 命名空间。 */
        const val SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/"
        /** UPnP MediaRenderer 服务类型。 */
        const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
        /** DLNA 设备类型。 */
        const val DLNA_DEVICE = "urn:schemas-dlna-org:device-1-0"
        /** AVTransport 服务类型。 */
        const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
        /** RenderingControl 服务类型。 */
        const val RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
        /** ConnectionManager 服务类型。 */
        const val CMS = "urn:schemas-upnp-org:service:ConnectionManager:1"
        /** 设备服务端标识。 */
        const val SERVER = "Linux/3.0.0 UPnP/1.0 Platinum/1.0.5.13"
        /** SSDP 组播地址对象。 */
        val SSDP_GROUP: InetAddress = InetAddress.getByName(SSDP_ADDR)
    }
}
