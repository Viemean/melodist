package org.melodist.core.connect.mcp

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.melodist.core.connect.util.NetworkUtils
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class McpHttpServer(
    private val dispatcher: McpJsonRpcDispatcher,
    private val authTokenProvider: () -> String,
    private val preferredPort: Int = 8090,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val activeSessions = ConcurrentHashMap<String, OutputStream>()
    private val clientSockets = ConcurrentHashMap.newKeySet<Socket>()

    var actualPort: Int = preferredPort
        private set

    val serverUrl: String
        get() {
            val ip = NetworkUtils.getLocalIpv4Address() ?: "127.0.0.1"
            return "http://$ip:$actualPort"
        }

    fun isRunning(): Boolean = serverSocket != null && serverSocket?.isClosed == false

    @Synchronized
    fun start(): Int {
        if (isRunning()) return actualPort

        val targetPort = findAvailablePort(preferredPort)
        try {
            val ss = ServerSocket(targetPort)
            serverSocket = ss
            actualPort = targetPort
            Log.i(TAG, "McpHttpServer started on $serverUrl")

            acceptJob =
                scope.launch {
                    while (isActive) {
                        val client =
                            try {
                                ss.accept()
                            } catch (e: Exception) {
                                if (e is CancellationException || ss.isClosed) break
                                Log.w(TAG, "Accept error", e)
                                break
                            }
                        scope.launch {
                            handleClient(client)
                        }
                    }
                }
            return actualPort
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start McpHttpServer on port $targetPort", e)
            stop()
            return -1
        }
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null

        activeSessions.values.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        activeSessions.clear()

        clientSockets.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        clientSockets.clear()

        Log.i(TAG, "McpHttpServer stopped")
    }

    private suspend fun handleClient(socket: Socket) {
        clientSockets.add(socket)
        try {
            socket.use { s ->
                try {
                    s.soTimeout = 15000
                    val rawInput = BufferedInputStream(s.getInputStream())
                    val output = s.getOutputStream()

                    val requestLine =
                        try {
                            readHttpLine(rawInput) ?: return
                        } catch (e: HttpLineTooLongException) {
                            writeResponse(output, 400, "Bad Request", "text/plain", "Bad Request: ${e.message}\n")
                            return
                        }
                    val parts = requestLine.split(" ")
                    if (parts.size < 2) return

                    val method = parts[0].uppercase()
                    val fullPath = parts[1]

                    val headers = mutableMapOf<String, String>()
                    var headerLine =
                        try {
                            readHttpLine(rawInput)
                        } catch (e: HttpLineTooLongException) {
                            writeResponse(output, 431, "Request Header Fields Too Large", "text/plain", "Header Too Large: ${e.message}\n")
                            return
                        }
                    var headerCount = 0
                    while (!headerLine.isNullOrBlank()) {
                        headerCount++
                        if (headerCount > 100) {
                            writeResponse(output, 431, "Request Header Fields Too Large", "text/plain", "Too Many Headers\n")
                            return
                        }
                        val colon = headerLine.indexOf(':')
                        if (colon > 0) {
                            val key = headerLine.substring(0, colon).trim().lowercase()
                            val value = headerLine.substring(colon + 1).trim()
                            headers[key] = value
                        }
                        headerLine =
                            try {
                                readHttpLine(rawInput)
                            } catch (e: HttpLineTooLongException) {
                                writeResponse(output, 431, "Request Header Fields Too Large", "text/plain", "Header Too Large: ${e.message}\n")
                                return
                            }
                    }

                    if (method == "OPTIONS") {
                        writeCorsPreflight(output)
                        return
                    }

                    val uriParts = fullPath.split("?", limit = 2)
                    val path = uriParts[0]
                    val queryParams = if (uriParts.size > 1) parseQueryString(uriParts[1]) else emptyMap()

                    val sessionId = queryParams["sessionId"]
                    val isSessionAuthenticated = sessionId != null && activeSessions.containsKey(sessionId)
                    if (!isSessionAuthenticated && !validateAuthToken(headers, queryParams)) {
                        writeResponse(output, 401, "Unauthorized", "text/plain", "Unauthorized: Invalid or missing token\n")
                        return
                    }

                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    if (contentLength > 2 * 1024 * 1024) {
                        writeResponse(output, 413, "Payload Too Large", "text/plain", "Payload exceeds 2MB limit\n")
                        return
                    }

                    val isKnownEndpoint = path == "/sse" || path == "/message" || path == "/messages" || path == "/ping"

                    when {
                        method == "GET" && path == "/sse" -> {
                            handleSseConnection(s, output)
                        }

                        method == "POST" && (path == "/message" || path == "/messages") -> {
                            val body =
                                if (contentLength > 0) {
                                    val buffer = ByteArray(contentLength)
                                    var totalRead = 0
                                    while (totalRead < contentLength) {
                                        val read = rawInput.read(buffer, totalRead, contentLength - totalRead)
                                        if (read < 0) break
                                        totalRead += read
                                    }
                                    String(buffer, 0, totalRead, Charsets.UTF_8)
                                } else {
                                    ""
                                }

                            handlePostMessage(output, sessionId, body)
                        }

                        method == "GET" && path == "/ping" -> {
                            writeResponse(output, 200, "OK", "application/json", "{\"status\":\"ok\"}")
                        }

                        isKnownEndpoint -> {
                            val allow =
                                when (path) {
                                    "/sse", "/ping" -> "GET, OPTIONS"
                                    else -> "POST, OPTIONS"
                                }
                            writeResponse(
                                output,
                                405,
                                "Method Not Allowed",
                                "text/plain",
                                "Method $method not allowed for $path\n",
                                extraHeaders = "Allow: $allow\r\n",
                            )
                        }

                        else -> {
                            writeResponse(output, 404, "Not Found", "text/plain", "Endpoint not found: $path\n")
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.w(TAG, "Error handling client connection", e)
                    }
                }
            }
        } finally {
            clientSockets.remove(socket)
        }
    }

    private suspend fun handleSseConnection(
        socket: Socket,
        output: OutputStream,
    ) {
        socket.soTimeout = 0
        val sessionId = UUID.randomUUID().toString()
        activeSessions[sessionId] = output

        val sseHeader =
            buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/event-stream; charset=utf-8\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Connection: keep-alive\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("\r\n")
            }
        synchronized(output) {
            output.write(sseHeader.toByteArray(Charsets.UTF_8))
            output.flush()

            val currentToken = authTokenProvider().trim()
            val tokenParam = if (currentToken.isNotBlank()) "&token=$currentToken" else ""
            val endpointEvent = "event: endpoint\r\ndata: /message?sessionId=$sessionId$tokenParam\r\n\r\n"
            output.write(endpointEvent.toByteArray(Charsets.UTF_8))
            output.flush()
        }

        try {
            while (socket.isConnected && !socket.isClosed) {
                delay(15000L)
                synchronized(output) {
                    output.write(": ping\r\n\r\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            }
        } catch (_: Exception) {
        } finally {
            activeSessions.remove(sessionId)
        }
    }

    private suspend fun handlePostMessage(
        output: OutputStream,
        sessionId: String?,
        body: String,
    ) {
        val response = dispatcher.dispatch(body)
        if (response != null) {
            sessionId?.let { sid ->
                activeSessions[sid]?.let { sseOut ->
                    try {
                        val sseData =
                            buildString {
                                append("event: message\r\n")
                                response.lines().forEach { line ->
                                    append("data: ").append(line.trimEnd('\r')).append("\r\n")
                                }
                                append("\r\n")
                            }
                        synchronized(sseOut) {
                            sseOut.write(sseData.toByteArray(Charsets.UTF_8))
                            sseOut.flush()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to push message to SSE stream for sessionId=$sid", e)
                    }
                }
            }
            writeResponse(output, 200, "OK", "application/json", response)
        } else {
            writeResponse(output, 202, "Accepted", "text/plain", "Accepted\n")
        }
    }

    private fun validateAuthToken(
        headers: Map<String, String>,
        queryParams: Map<String, String>,
    ): Boolean {
        val expected = authTokenProvider().trim()
        if (expected.isBlank()) return true // No token configured -> allow

        val queryToken = queryParams["token"]?.trim()
        if (queryToken == expected) return true

        val authHeader = headers["authorization"]?.trim()
        if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
            val bearerToken = authHeader.substring(7).trim()
            if (bearerToken == expected) return true
        }

        return false
    }

    private fun writeCorsPreflight(output: OutputStream) {
        val res =
            buildString {
                append("HTTP/1.1 204 No Content\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
        output.write(res.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun writeResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: String,
        extraHeaders: String = "",
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val res =
            buildString {
                append("HTTP/1.1 $statusCode $statusText\r\n")
                append("Content-Type: $contentType; charset=utf-8\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                if (extraHeaders.isNotBlank()) {
                    append(extraHeaders)
                }
                append("Connection: close\r\n")
                append("\r\n")
            }
        output.write(res.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun parseQueryString(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                result[key] = value
            }
        }
        return result
    }

    private fun readHttpLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (baos.size() == 0) return null
                break
            }
            if (b == '\n'.code) {
                val bytes = baos.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.UTF_8)
            }
            baos.write(b)
            if (baos.size() > 32768) {
                throw HttpLineTooLongException("HTTP line length exceeded maximum limit of 32KB")
            }
        }
        return String(baos.toByteArray(), Charsets.UTF_8)
    }

    private class HttpLineTooLongException(
        message: String,
    ) : IOException(message)

    private fun findAvailablePort(startPort: Int): Int {
        for (port in startPort until (startPort + 10)) {
            try {
                ServerSocket(port).use {
                    return port
                }
            } catch (_: Exception) {
            }
        }
        return startPort
    }

    companion object {
        private const val TAG = "McpHttpServer"
    }
}
