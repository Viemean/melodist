package org.melodist.mobile.connect

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.melodist.api.WebDavService
import org.melodist.core.connect.util.NetworkUtils
import org.melodist.data.WebDavManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

class LocalAudioStreamServer(
    private val context: Context,
    private val port: Int = 8766,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private val okHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    val serverUrl: String
        get() {
            val ip = NetworkUtils.getLocalIpv4Address() ?: "127.0.0.1"
            return "http://$ip:$port"
        }

    fun start() {
        if (serverSocket != null) return
        scope.launch {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                while (isActive) {
                    val client = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    scope.launch {
                        handleClient(client)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    fun buildLocalAudioStreamUrl(filePath: String): String {
        val encoded = Uri.encode(filePath)
        return "$serverUrl/stream/local?path=$encoded"
    }

    fun buildLocalCoverUrl(coverPath: String): String {
        val cleanPath = if (coverPath.startsWith("file://")) coverPath.removePrefix("file://") else coverPath
        val encoded = Uri.encode(cleanPath)
        return "$serverUrl/cover/local?path=$encoded"
    }

    fun buildWebDavStreamUrl(serverId: String, href: String): String {
        val encServer = Uri.encode(serverId)
        val encHref = Uri.encode(href)
        return "$serverUrl/stream/webdav?server=$encServer&href=$encHref"
    }

    fun buildProxyStreamUrl(targetUrl: String): String {
        val encoded = Uri.encode(targetUrl)
        return "$serverUrl/stream/proxy?url=$encoded"
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2 || parts[0] != "GET") {
                    sendNotFound(output)
                    return
                }

                val fullPath = parts[1]
                val headers = parseHeaders(input)
                val rangeHeader = headers["range"]

                when {
                    fullPath.startsWith("/stream/local") -> {
                        val pathParam = extractQueryParam(fullPath, "path")
                        if (pathParam.isNullOrBlank()) {
                            sendNotFound(output)
                            return
                        }
                        val decodedPath = Uri.decode(pathParam)
                        val file = File(decodedPath)
                        if (!file.exists() || !file.canRead()) {
                            sendNotFound(output)
                            return
                        }
                        serveFileWithRange(file, rangeHeader, output)
                    }
                    fullPath.startsWith("/cover/local") -> {
                        val pathParam = extractQueryParam(fullPath, "path")
                        if (pathParam.isNullOrBlank()) {
                            sendNotFound(output)
                            return
                        }
                        val decodedPath = Uri.decode(pathParam)
                        val file = File(decodedPath)
                        if (!file.exists() || !file.canRead()) {
                            sendNotFound(output)
                            return
                        }
                        serveCoverImage(file, output)
                    }
                    fullPath.startsWith("/stream/webdav") -> {
                        val serverParam = extractQueryParam(fullPath, "server")
                        val hrefParam = extractQueryParam(fullPath, "href")
                        if (serverParam.isNullOrBlank() || hrefParam.isNullOrBlank()) {
                            sendNotFound(output)
                            return
                        }
                        val decodedServer = Uri.decode(serverParam)
                        val decodedHref = Uri.decode(hrefParam)
                        serveWebDavStream(decodedServer, decodedHref, rangeHeader, output)
                    }
                    fullPath.startsWith("/stream/proxy") -> {
                        val urlParam = extractQueryParam(fullPath, "url")
                        if (urlParam.isNullOrBlank()) {
                            sendNotFound(output)
                            return
                        }
                        val decodedUrl = Uri.decode(urlParam)
                        serveHttpProxy(decodedUrl, rangeHeader, output)
                    }
                    else -> {
                        sendNotFound(output)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun serveFileWithRange(file: File, rangeHeader: String?, output: OutputStream) {
        val fileLength = file.length()
        var start = 0L
        var end = fileLength - 1

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val ranges = rangeHeader.removePrefix("bytes=").split("-")
            start = ranges.getOrNull(0)?.toLongOrNull() ?: 0L
            end = ranges.getOrNull(1)?.toLongOrNull() ?: (fileLength - 1)
        }

        if (start > end || start >= fileLength) {
            val res = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$fileLength\r\n\r\n"
            output.write(res.toByteArray())
            output.flush()
            return
        }

        val contentLength = end - start + 1
        val isPartial = !rangeHeader.isNullOrBlank()

        val ext = file.extension.lowercase()
        val mimeType = when (ext) {
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "m4a", "aac", "mp4" -> "audio/mp4"
            "opus" -> "audio/opus"
            "mp3" -> "audio/mpeg"
            else -> "audio/mpeg"
        }

        val headerBuilder = StringBuilder()
        if (isPartial) {
            headerBuilder.append("HTTP/1.1 206 Partial Content\r\n")
            headerBuilder.append("Content-Range: bytes $start-$end/$fileLength\r\n")
        } else {
            headerBuilder.append("HTTP/1.1 200 OK\r\n")
        }
        headerBuilder.append("Content-Type: $mimeType\r\n")
        headerBuilder.append("Content-Length: $contentLength\r\n")
        headerBuilder.append("Accept-Ranges: bytes\r\n")
        headerBuilder.append("Connection: close\r\n\r\n")

        output.write(headerBuilder.toString().toByteArray())

        FileInputStream(file).use { fis ->
            if (start > 0) {
                fis.skip(start)
            }
            val buffer = ByteArray(32 * 1024)
            var remaining = contentLength
            while (remaining > 0) {
                val toRead = remaining.coerceAtMost(buffer.size.toLong()).toInt()
                val read = fis.read(buffer, 0, toRead)
                if (read == -1) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        output.flush()
    }

    private fun serveCoverImage(file: File, output: OutputStream) {
        val ext = file.extension.lowercase()
        val mimeType = when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val fileLength = file.length()
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $mimeType\r\n" +
            "Content-Length: $fileLength\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray())
        FileInputStream(file).use { fis ->
            val buf = ByteArray(32 * 1024)
            var bytes: Int
            while (fis.read(buf).also { bytes = it } != -1) {
                output.write(buf, 0, bytes)
            }
        }
        output.flush()
    }

    private suspend fun serveWebDavStream(serverId: String, href: String, rangeHeader: String?, output: OutputStream) {
        val servers = WebDavManager.getServers()
        val server = servers.find { it.id == serverId }
            ?: WebDavManager.getActiveServer()
            ?: servers.firstOrNull()
            ?: run {
                sendNotFound(output)
                return
            }
        val (streamUrl, authHeader) = WebDavManager.resolvePlaybackUrl(server, href)
        val reqBuilder = Request.Builder().url(streamUrl)
        if (!authHeader.isNullOrBlank()) {
            reqBuilder.header("Authorization", authHeader)
        }
        if (!rangeHeader.isNullOrBlank()) {
            reqBuilder.header("Range", rangeHeader)
        }

        val call = okHttpClient.newCall(reqBuilder.build())
        val resp = call.execute()
        resp.use { r ->
            val status = r.code
            val body = r.body ?: run {
                sendNotFound(output)
                return
            }
            val headerBuilder = StringBuilder("HTTP/1.1 $status ${r.message}\r\n")
            headerBuilder.append("Content-Type: ${r.header("Content-Type", "audio/mpeg")}\r\n")
            r.header("Content-Length")?.let { headerBuilder.append("Content-Length: $it\r\n") }
            r.header("Content-Range")?.let { headerBuilder.append("Content-Range: $it\r\n") }
            headerBuilder.append("Accept-Ranges: bytes\r\n")
            headerBuilder.append("Connection: close\r\n\r\n")

            output.write(headerBuilder.toString().toByteArray())
            body.byteStream().use { ins ->
                pipeStream(ins, output)
            }
        }
    }

    private fun serveHttpProxy(targetUrl: String, rangeHeader: String?, output: OutputStream) {
        val reqBuilder = Request.Builder().url(targetUrl)
        if (!rangeHeader.isNullOrBlank()) {
            reqBuilder.header("Range", rangeHeader)
        }
        val call = okHttpClient.newCall(reqBuilder.build())
        val resp = call.execute()
        resp.use { r ->
            val headerBuilder = StringBuilder("HTTP/1.1 ${r.code} ${r.message}\r\n")
            headerBuilder.append("Content-Type: ${r.header("Content-Type", "audio/mpeg")}\r\n")
            r.header("Content-Length")?.let { headerBuilder.append("Content-Length: $it\r\n") }
            r.header("Content-Range")?.let { headerBuilder.append("Content-Range: $it\r\n") }
            headerBuilder.append("Accept-Ranges: bytes\r\n")
            headerBuilder.append("Connection: close\r\n\r\n")

            output.write(headerBuilder.toString().toByteArray())
            r.body?.byteStream()?.use { ins ->
                pipeStream(ins, output)
            }
        }
    }

    private fun pipeStream(input: InputStream, output: OutputStream) {
        val buf = ByteArray(32 * 1024)
        var bytes: Int
        while (input.read(buf).also { bytes = it } != -1) {
            output.write(buf, 0, bytes)
        }
        output.flush()
    }

    private fun sendNotFound(output: OutputStream) {
        val res = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(res.toByteArray())
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c: Int
        while (input.read().also { c = it } != -1) {
            if (c == '\r'.code) {
                val next = input.read()
                if (next == '\n'.code) break
                sb.append(c.toChar())
                if (next != -1) sb.append(next.toChar())
            } else if (c == '\n'.code) {
                break
            } else {
                sb.append(c.toChar())
            }
        }
        return if (sb.isEmpty() && c == -1) null else sb.toString()
    }

    private fun parseHeaders(input: InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon != -1) {
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                headers[key] = value
            }
        }
        return headers
    }

    private fun extractQueryParam(url: String, key: String): String? {
        val query = url.substringAfter('?', "")
        if (query.isBlank()) return null
        return query.split("&").mapNotNull {
            val keyPart = it.substringBefore('=')
            val valPart = it.substringAfter('=', "")
            if (keyPart == key && it.contains('=')) valPart else null
        }.firstOrNull()
    }
}
