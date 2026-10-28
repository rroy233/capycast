package com.roy233.tvcast.platform.dlna

import android.content.Context
import android.net.wifi.WifiManager
import com.roy233.tvcast.core.diagnostics.LogHub
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * SSDP 组播发现服务：响应 M-SEARCH 并按需广播设备存活通知。
 *
 * @param context 用于获取 Wi-Fi 组播锁的应用上下文。
 * @param udn 设备唯一标识。
 * @param ip 当前设备局域网地址。
 * @param descriptionUrl 设备描述文档地址。
 */
internal class SsdpDiscoveryService(
    private val context: Context,
    private val udn: String,
    private val ip: String,
    private val descriptionUrl: String,
) {

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
    }

    /** 停止接收线程并释放组播资源。 */
    fun stop() {
        alive = false
        runCatching { socket.close() }
        runCatching { multicastLock?.release() }
        thread = null
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
                socket.send(DatagramPacket(bytes, bytes.size, SSDP_GROUP, SSDP_PORT))
            }
        }
    }

    /** 持续接收组播报文。 */
    private fun loop() {
        val buffer = ByteArray(MAX_PACKET)
        while (alive) {
            val packet = DatagramPacket(buffer, buffer.size)
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
                socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
            }
        }
    }

    /** 解析 SSDP 文本头。 */
    private fun parseHeaders(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (line in text.split("\r\n").drop(1)) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                map[line.substring(0, idx).trim().lowercase(Locale.US)] = line.substring(idx + 1).trim()
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

    private companion object {
        /** 日志标签。 */
        const val TAG = "DLNA"
        /** 单次接收的最大报文长度。 */
        const val MAX_PACKET = 4096
        /** SSDP 组播地址。 */
        const val SSDP_ADDR = "239.255.255.250"
        /** SSDP 端口。 */
        const val SSDP_PORT = 1900
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
