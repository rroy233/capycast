package com.roy233.tvcast.platform.dlna

import com.roy233.tvcast.core.diagnostics.LogHub
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** 表示待写回 DLNA 客户端的 HTTP 响应。 */
internal data class DlnaHttpResponse(
    val code: Int,
    val reason: String,
    val contentType: String,
    val body: ByteArray,
    val extra: Map<String, String> = emptyMap(),
)

/** DLNA HTTP socket 生命周期、请求读取和响应写入。 */
internal class DlnaHttpServer(
    port: Int,
    private val onGet: (String) -> DlnaHttpResponse,
    private val onPost: (String, Map<String, String>, ByteArray) -> DlnaHttpResponse,
) {
    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }
    private val pool = Executors.newCachedThreadPool()

    @Volatile
    private var alive = true

    fun start() {
        thread(name = "dlna-http", isDaemon = true) {
            while (alive) {
                val socket = try {
                    serverSocket.accept()
                } catch (failure: Throwable) {
                    if (alive) LogHub.e(TAG, "accept failed: ${failure.message}", failure)
                    break
                }
                pool.execute {
                    runCatching { handle(socket) }
                        .onFailure { LogHub.e(TAG, "http handler failed: ${it.message}", it) }
                }
            }
        }
    }

    fun stop() {
        alive = false
        runCatching { serverSocket.close() }
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { connection ->
            connection.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(connection.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                write(connection, response(400, "Bad Request", "text/plain", "bad request"))
                return
            }
            val method = parts[0].uppercase(Locale.US)
            val path = parts[1].substringBefore('?')
            val headers = readHeaders(input)
            if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
                connection.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                connection.getOutputStream().flush()
            }
            val body = readBody(input, headers)
            LogHub.i(
                TAG,
                "HTTP $method $path from ${connection.inetAddress?.hostAddress} " +
                    "soap=${headers["soapaction"]?.take(80)} body=${body.size}B",
            )
            val result = when (method) {
                "GET", "HEAD" -> onGet(path)
                "POST" -> onPost(path, headers, body)
                "SUBSCRIBE" -> response(
                    200,
                    "OK",
                    "text/xml; charset=\"utf-8\"",
                    ByteArray(0),
                    mapOf("SID" to "uuid:${UUID.randomUUID()}", "TIMEOUT" to "Second-1800"),
                )
                "UNSUBSCRIBE" -> response(200, "OK", "text/plain", ByteArray(0))
                else -> response(405, "Method Not Allowed", "text/plain", "method not allowed")
            }
            write(connection, if (method == "HEAD") result.copy(body = ByteArray(0)) else result)
        }
    }

    private fun response(
        code: Int,
        reason: String,
        type: String,
        body: String,
        extra: Map<String, String> = emptyMap(),
    ) = response(code, reason, type, body.toByteArray(Charsets.UTF_8), extra)

    private fun response(
        code: Int,
        reason: String,
        type: String,
        body: ByteArray,
        extra: Map<String, String> = emptyMap(),
    ) = DlnaHttpResponse(code, reason, type, body, extra)

    private fun write(socket: Socket, response: DlnaHttpResponse) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ${response.code} ${response.reason}\r\n")
        head.append("Content-Type: ${response.contentType}\r\n")
        head.append("Content-Length: ${response.body.size}\r\n")
        head.append("Connection: close\r\n")
        for ((key, value) in response.extra) head.append("$key: $value\r\n")
        head.append("\r\n")
        val output = socket.getOutputStream()
        output.write(head.toString().toByteArray(Charsets.UTF_8))
        if (response.body.isNotEmpty()) output.write(response.body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (output.size() == 0) null else output.toString("UTF-8")
            if (byte == '\n'.code) {
                val bytes = output.toByteArray()
                val length = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes.size - 1
                } else {
                    bytes.size
                }
                return String(bytes, 0, length, Charsets.UTF_8)
            }
            output.write(byte)
            if (output.size() > MAX_HEADER_LINE_BYTES) return null
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                    line.substring(separator + 1).trim()
            }
        }
        return headers
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return readChunked(input)
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return ByteArray(0)
        if (length > MAX_BODY_BYTES) error("body too large: $length")
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(data, read, length - read)
            if (count < 0) break
            read += count
        }
        return if (read == length) data else data.copyOf(read)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input) ?: break
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                readLine(input)
                break
            }
            if (output.size() + size > MAX_BODY_BYTES) error("chunked body too large")
            val data = ByteArray(size)
            var read = 0
            while (read < size) {
                val count = input.read(data, read, size - read)
                if (count < 0) break
                read += count
            }
            output.write(data, 0, read)
            readLine(input)
        }
        return output.toByteArray()
    }

    private companion object {
        const val TAG = "DLNA"
        const val SOCKET_TIMEOUT_MS = 15_000
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 32_768
    }
}
