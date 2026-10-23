package com.roy233.tvcast.data.bili.danmaku

import android.content.Context
import com.google.gson.JsonParser
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.data.bili.BiliApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * B 站直播弹幕长连接客户端。
 *
 * 负责 `getDanmuInfo` 准入、WebSocket 建连、进房认证、30 秒心跳、粘包拆包与
 * 断线重连。弹幕解析交给 [LiveDanmakuProtocol]，只向上层回调解码后的条目。
 *
 * 连接与回调都发生在 [scope] 或 okhttp 回调线程，宿主须自行保证 UI 状态在主线程。
 */
class LiveDanmakuClient(
    private val scope: CoroutineScope,
    private val context: Context,
    private val roomId: Long,
    private val cookie: String,
    private val uid: Long,
    private val onItems: (List<DanmakuItem>) -> Unit,
    private val onState: (State) -> Unit = {},
) {

    /** 连接生命周期状态，供宿主记录诊断信息。 */
    enum class State { CONNECTING, CONNECTED, AUTHENTICATED, DISCONNECTED, FAILED }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var token: String = ""
    private var buvid: String = ""
    private var hosts: List<LiveDanmakuApi.Host> = emptyList()
    private var hostIndex = 0
    private var closed = false
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var buffer = ByteArray(0)

    /** 开始连接；已连接时调用会先断开旧连接。 */
    fun start() {
        closed = false
        scope.launch { connect() }
    }

    /** 关闭连接并停止心跳与重连。 */
    fun close() {
        closed = true
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        buffer = ByteArray(0)
        webSocket?.close(1000, "close")
        webSocket = null
    }

    /** 立即发送一次心跳；宿主导出给诊断/在线人数查询使用。 */
    fun heartbeat() {
        webSocket?.let { ws ->
            ws.send(ByteString.of(*LiveDanmakuProtocol.encodeHeartbeat()))
        }
    }

    private suspend fun connect() {
        if (closed) return
        onState(State.CONNECTING)
        val info = try {
            withContext(Dispatchers.IO) { LiveDanmakuApi.fetchDanmuInfo(context, roomId, cookie) }
        } catch (failure: Throwable) {
            LogHub.e(TAG, "getDanmuInfo failed room=$roomId: ${failure.message}")
            onState(State.FAILED)
            scheduleReconnect()
            return
        }
        if (closed) return
        token = info.token
        buvid = info.buvid3
        hosts = info.hosts
        if (hosts.isEmpty()) {
            LogHub.e(TAG, "getDanmuInfo returned no hosts room=$roomId")
            onState(State.FAILED)
            scheduleReconnect()
            return
        }
        val host = hosts[hostIndex % hosts.size]
        val request = Request.Builder()
            .url(host.wssUrl)
            .header("User-Agent", BiliApi.UA)
            .header("Origin", "https://live.bilibili.com")
            .build()
        webSocket = httpClient.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            onState(State.CONNECTED)
            val join = LiveDanmakuProtocol.encodeJoin(roomId, token, uid, buvid)
            webSocket.send(ByteString.of(*join))
            LogHub.i(TAG, "live danmaku ws connected room=$roomId")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            buffer += bytes.toByteArray()
            splitBuffer()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onState(State.DISCONNECTED)
            LogHub.i(TAG, "live danmaku ws closed room=$roomId code=$code reason=$reason")
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onState(State.FAILED)
            LogHub.e(TAG, "live danmaku ws failed room=$roomId error=${t.javaClass.simpleName}")
            scheduleReconnect()
        }
    }

    /** 按 4 字节大端总长逐包切分，处理可能的粘包与拆包。 */
    private fun splitBuffer() {
        while (buffer.size >= LiveDanmakuProtocol.HEADER_SIZE) {
            val size = readInt32(buffer, 0)
            if (size < LiveDanmakuProtocol.HEADER_SIZE) {
                buffer = ByteArray(0)
                return
            }
            if (size > buffer.size) return
            val pack = buffer.copyOfRange(0, size)
            buffer = buffer.copyOfRange(size, buffer.size)
            handlePack(pack)
        }
    }

    private fun handlePack(pack: ByteArray) {
        val packets = LiveDanmakuProtocol.decode(pack)
        val items = ArrayList<DanmakuItem>()
        for (packet in packets) {
            when (packet.op) {
                LiveDanmakuProtocol.OP_JOIN_REPLY -> {
                    val code = runCatching {
                        JsonParser.parseString(
                            String(packet.body, Charsets.UTF_8),
                        ).asJsonObject.get("code")?.asInt ?: -1
                    }.getOrDefault(-1)
                    if (code == 0) {
                        onState(State.AUTHENTICATED)
                        heartbeat()
                        startHeartbeat()
                        LogHub.i(TAG, "live danmaku authenticated room=$roomId")
                    } else {
                        LogHub.e(TAG, "live danmaku auth rejected room=$roomId code=$code")
                        webSocket?.close(1000, "auth-rejected")
                    }
                }
                LiveDanmakuProtocol.OP_HEARTBEAT_REPLY -> Unit
                LiveDanmakuProtocol.OP_MESSAGE -> {
                    LiveDanmakuProtocol.parseDanmaku(packet.body)?.let { items += it }
                }
            }
        }
        if (items.isNotEmpty()) onItems(items)
    }

    /** 进房成功后启动 30 秒心跳循环。 */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                webSocket?.let { ws ->
                    ws.send(ByteString.of(*LiveDanmakuProtocol.encodeHeartbeat()))
                }
            }
        }
    }

    /** 指数退避重连，封顶 30 秒；每次重连前重新换取 token。 */
    private fun scheduleReconnect() {
        if (closed) return
        reconnectJob?.cancel()
        val delayMs = RECONNECT_BASE_MS shl reconnectAttempt.coerceAtMost(5)
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            if (closed) return@launch
            hostIndex++
            connect()
        }
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private companion object {
        const val TAG = "LiveDanmakuClient"
        const val HEARTBEAT_INTERVAL_MS = 30_000L
        const val RECONNECT_BASE_MS = 1_000L
    }
}
