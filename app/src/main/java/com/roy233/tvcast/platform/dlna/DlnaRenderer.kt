package com.roy233.tvcast.platform.dlna

import android.content.Context
import com.roy233.tvcast.BuildConfig
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.R
import java.net.InetAddress
import java.util.Locale
import java.util.UUID
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
        /** 当前媒体是否支持定位。直播等不可定位媒体返回 false。 */
        fun isSeekable(): Boolean
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
    /** 线程安全的 DLNA 传输状态。 */
    private val state = DlnaTransportState()

    /** SOAP 动作分发器。 */
    private val soapDispatcher by lazy { SoapActionDispatcher(state, listener) }
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
    private var http: DlnaHttpServer? = null
    /** SSDP 服务实例。 */
    private var ssdp: SsdpDiscoveryService? = null
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
            http = DlnaHttpServer(port, ::handleGet, ::handlePost).also { it.start() }
        } catch (t: Throwable) {
            LogHub.e(TAG, "http start failed: ${t.message}", t)
            running = false
            return
        }
        try {
            ssdp = SsdpDiscoveryService(context, udn, ip, descriptionUrl).also { it.start() }
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

    /** 使用字符串正文构造 HTTP 响应。 */
    private fun response(
        code: Int,
        reason: String,
        type: String,
        body: String,
        extra: Map<String, String> = emptyMap(),
    ): DlnaHttpResponse = DlnaHttpResponse(code, reason, type, body.toByteArray(Charsets.UTF_8), extra)

    /** 使用字节正文构造 HTTP 响应。 */
    private fun response(
        code: Int,
        reason: String,
        type: String,
        body: ByteArray,
        extra: Map<String, String> = emptyMap(),
    ): DlnaHttpResponse = DlnaHttpResponse(code, reason, type, body, extra)

    /** 处理设备描述、服务描述和状态页面的 GET 请求。 */
    private fun handleGet(path: String): DlnaHttpResponse = when (path) {
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
                append("transport: ").append(state.transport).append('\n')
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
    ): DlnaHttpResponse {
        if (path.startsWith("/nva") || path.contains("nva", ignoreCase = true)) {
            LogHub.i(TAG, "NVA-like POST $path body=${body.size}B")
            return response(200, "OK", "text/plain", ByteArray(0))
        }
        val req = try {
            SoapRequestParser.parse(body, headers["soapaction"], soapDispatcher::serviceFor)
        } catch (t: Throwable) {
            LogHub.e(TAG, "SOAP parse failed: ${t.message}", t)
            return response(400, "Bad Request", "text/plain", "bad soap")
        }
        val result = try {
            dispatch(req)
        } catch (t: Throwable) {
            LogHub.e(TAG, "SOAP dispatch ${req.action} failed: ${t.message}", t)
            soapDispatcher.fault(req.service, 501, "Action Failed")
        }
        return response(200, "OK", "text/xml; charset=\"utf-8\"", result)
    }

    /** 分发 SOAP 动作并构造响应。 */
    private fun dispatch(req: SoapRequest): ByteArray = soapDispatcher.dispatch(req)

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
