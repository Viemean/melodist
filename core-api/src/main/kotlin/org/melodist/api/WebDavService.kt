package org.melodist.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.melodist.model.WebDavItem
import org.melodist.model.WebDavServer
import org.melodist.model.WebDavSongCache
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory

class WebDavService {
    companion object {
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

        val SUPPORTED_AUDIO_EXTENSIONS =
            setOf(
                ".flac",
                ".mp3",
                ".m4a",
                ".wav",
                ".ogg",
                ".aac",
                ".opus",
                ".ape",
            )

        private val clientCache = ConcurrentHashMap<String, OkHttpClient>()

        private fun getClient(server: WebDavServer): OkHttpClient {
            return clientCache.computeIfAbsent(server.id) {
                val builder =
                    OkHttpClient
                        .Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)

                if (server.trustSelfSigned) {
                    val trustAllCerts =
                        arrayOf<TrustManager>(
                            object : X509TrustManager {
                                override fun checkClientTrusted(
                                    chain: Array<out X509Certificate>?,
                                    authType: String?,
                                ) {}

                                override fun checkServerTrusted(
                                    chain: Array<out X509Certificate>?,
                                    authType: String?,
                                ) {}

                                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                            },
                        )

                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(null, trustAllCerts, SecureRandom())
                    builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                    builder.hostnameVerifier { _, _ -> true }
                }

                if (server.username.isNotBlank() || server.password.isNotBlank()) {
                    builder.authenticator { _, response ->
                        if (response.request.header("Authorization") != null) {
                            return@authenticator null // 已经尝试认证失败，避免死循环
                        }
                        val credential = Credentials.basic(server.username, server.password)
                        response.request
                            .newBuilder()
                            .header("Authorization", credential)
                            .build()
                    }
                }

                builder.build()
            }
        }

        fun invalidateClient(serverId: String) {
            clientCache.remove(serverId)
        }

        /**
         * 健壮构建 WebDAV 完整 URI，精确消除前后冗余斜线与子路径重叠
         */
        fun buildFullUri(
            server: WebDavServer,
            relativeHref: String,
        ): URI {
            var serverRaw = server.url.trim()
            if (!serverRaw.startsWith("http://", ignoreCase = true) &&
                !serverRaw.startsWith("https://", ignoreCase = true)
            ) {
                serverRaw = "http://$serverRaw"
            }
            val serverUri = URI(serverRaw.trimEnd('/') + "/")

            if (relativeHref.isBlank() || relativeHref == "/") {
                return serverUri
            }

            // 完整 URL 直接使用
            try {
                val abs = URI(relativeHref)
                if (abs.scheme.equals("http", ignoreCase = true) || abs.scheme.equals("https", ignoreCase = true)) {
                    return abs
                }
            } catch (_: Exception) {
            }

            // 路径拼接
            val decoded = URLDecoder.decode(relativeHref, "UTF-8")
            val path = if (decoded.startsWith('/')) decoded else "/$decoded"

            val serverBasePath = URLDecoder.decode(serverUri.path ?: "", "UTF-8").trimEnd('/')

            if (serverBasePath.isNotBlank() && serverBasePath != "/") {
                if (path.equals(serverBasePath, ignoreCase = true) ||
                    path.startsWith("$serverBasePath/", ignoreCase = true)
                ) {
                    val hostPort = if (serverUri.port != -1) "${serverUri.host}:${serverUri.port}" else serverUri.host
                    val hostBaseStr = "${serverUri.scheme}://$hostPort"
                    val segments = path.split('/').filter { it.isNotBlank() }
                    val escapedPath = "/" + segments.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                    val finalPath = if (path.endsWith('/')) "$escapedPath/" else escapedPath
                    return URI(hostBaseStr + finalPath)
                }
            }

            val relSegments = path.split('/').filter { it.isNotBlank() }
            val relEscaped = relSegments.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val finalRelPath = if (path.endsWith('/')) "$relEscaped/" else relEscaped
            return serverUri.resolve(finalRelPath)
        }

        fun isIgnoredEntry(name: String): Boolean {
            val clean = name.trim()
            if (clean.isBlank()) return true
            return clean.startsWith('.') ||
                clean.startsWith('@') ||
                clean.startsWith('#') ||
                clean.equals("lost+found", ignoreCase = true) ||
                clean.equals("\$RECYCLE.BIN", ignoreCase = true) ||
                clean.equals("System Volume Information", ignoreCase = true)
        }

        /**
         * 从文件名推断歌曲名与歌手
         */
        fun inferTitleArtist(fileName: String): Pair<String, String> {
            val nameWithoutExt = fileName.substringBeforeLast('.')
            // 常见格式: "歌手 - 歌名"
            if (nameWithoutExt.contains(" - ")) {
                val parts = nameWithoutExt.split(" - ", limit = 2)
                val artist = parts[0].trim()
                val title = parts[1].trim()
                if (artist.isNotBlank() && title.isNotBlank()) {
                    return Pair(title, artist)
                }
            }
            // 格式: "序号. 歌名"
            val strippedIndex = nameWithoutExt.replaceFirst(Regex("""^\d{1,3}[\s._\-]+"""), "").trim()
            return Pair(strippedIndex.ifBlank { nameWithoutExt }, "WebDAV 音频")
        }

        fun extractEmbeddedLyricsFromBytes(bytes: ByteArray): String? {
            if (bytes.size < 64) return null
            val content =
                try {
                    String(bytes, Charsets.ISO_8859_1)
                } catch (_: Exception) {
                    return null
                }

            // 1. 查找 Vorbis comment (FLAC / OGG)
            val vorbisKeys = listOf("LYRICS=", "UNSYNCEDLYRICS=", "SYNCEDLYRICS=")
            for (key in vorbisKeys) {
                val idx = content.indexOf(key, ignoreCase = true)
                if (idx != -1) {
                    val start = idx + key.length
                    val end =
                        content.indexOfAny(charArrayOf('\u0000', '\u0001', '\u0002', '\u0003'), start).let {
                            if (it == -1) (start + 8192).coerceAtMost(content.length) else it
                        }
                    val rawVal = bytes.copyOfRange(idx + key.length, (idx + key.length + (end - start)).coerceAtMost(bytes.size))
                    val lyric =
                        try {
                            String(rawVal, Charsets.UTF_8).trim()
                        } catch (_: Exception) {
                            ""
                        }
                    if (lyric.isNotBlank() && (lyric.contains('[') || lyric.contains('\n'))) {
                        return lyric
                    }
                }
            }

            // 2. 查找 ID3v2 USLT (非同步歌词帧)
            val usltIdx = content.indexOf("USLT")
            if (usltIdx != -1 && usltIdx + 10 < bytes.size) {
                val frameSize =
                    (bytes[usltIdx + 4].toInt() and 0xFF shl 24) or
                        (bytes[usltIdx + 5].toInt() and 0xFF shl 16) or
                        (bytes[usltIdx + 6].toInt() and 0xFF shl 8) or
                        (bytes[usltIdx + 7].toInt() and 0xFF)
                val realSize = frameSize.coerceIn(10, 65536).coerceAtMost(bytes.size - usltIdx - 10)
                if (realSize > 10) {
                    val payload = bytes.copyOfRange(usltIdx + 10, usltIdx + 10 + realSize)
                    val encoding = payload[0].toInt()
                    val charset =
                        when (encoding) {
                            1 -> Charsets.UTF_16
                            2 -> Charsets.UTF_16BE
                            3 -> Charsets.UTF_8
                            else -> Charsets.ISO_8859_1
                        }
                    val rawText =
                        try {
                            String(payload, 4, payload.size - 4, charset).trim()
                        } catch (_: Exception) {
                            ""
                        }
                    if (rawText.isNotBlank()) {
                        return rawText
                    }
                }
            }

            return null
        }
    }

    /**
     * 测试 WebDAV 连通性
     */
    suspend fun testConnection(server: WebDavServer): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
                val client = getClient(server)
                val uri = buildFullUri(server, server.rootPath.ifBlank { "/" })

                val requestBuilder =
                    Request
                        .Builder()
                        .url(uri.toURL())
                        .method("PROPFIND", null)
                        .header("Depth", "0")
                        .header("User-Agent", "MelodistTV/1.0 WebDAV Client")

                if (server.username.isNotBlank() || server.password.isNotBlank()) {
                    requestBuilder.header("Authorization", Credentials.basic(server.username, server.password))
                }

                client.newCall(requestBuilder.build()).execute().use { response ->
                    when (response.code) {
                        200, 207 -> Pair(true, "连接成功，WebDAV 访问正常")
                        401 -> Pair(false, "认证失败：用户名或密码错误 (401)")
                        403 -> Pair(false, "访问受限：无权限访问该路径 (403)")
                        404 -> Pair(false, "路径不存在：请检查根路径配置 (404)")
                        else -> Pair(false, "HTTP 状态码: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Pair(false, "连接异常: ${e.message ?: "网络超时"}")
            }
        }

    /**
     * 遍历指定目录并解析子目录与音频文件列表
     */
    suspend fun listDirectory(
        server: WebDavServer,
        relativeHref: String,
    ): List<WebDavItem> =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<WebDavItem>()
            try {
                val client = getClient(server)
                val uri = buildFullUri(server, relativeHref)

                val propfindXml =
                    """
                    <?xml version="1.0" encoding="utf-8" ?>
                    <D:propfind xmlns:D="DAV:">
                      <D:prop>
                        <D:displayname/>
                        <D:resourcetype/>
                        <D:getcontentlength/>
                        <D:getlastmodified/>
                      </D:prop>
                    </D:propfind>
                    """.trimIndent()

                val requestBuilder =
                    Request
                        .Builder()
                        .url(uri.toURL())
                        .method("PROPFIND", propfindXml.toRequestBody(XML_MEDIA_TYPE))
                        .header("Depth", "1")
                        .header("User-Agent", "MelodistTV/1.0 WebDAV Client")

                if (server.username.isNotBlank() || server.password.isNotBlank()) {
                    requestBuilder.header("Authorization", Credentials.basic(server.username, server.password))
                }

                val responseBody =
                    client.newCall(requestBuilder.build()).execute().use { resp ->
                        if (resp.code != 200 && resp.code != 207) {
                            return@withContext emptyList()
                        }
                        resp.body.string()
                    }

                if (responseBody.isBlank()) return@withContext emptyList()

                val factory = DocumentBuilderFactory.newInstance()
                factory.isNamespaceAware = true
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(InputSource(StringReader(responseBody)))

                val responseNodes = doc.getElementsByTagNameNS("*", "response")
                val reqPath = URLDecoder.decode(uri.path ?: "", "UTF-8").trimEnd('/')

                val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)

                for (i in 0 until responseNodes.length) {
                    val respElem = responseNodes.item(i) as? Element ?: continue
                    val hrefNode = respElem.getElementsByTagNameNS("*", "href").item(0) ?: continue
                    val rawHref = hrefNode.textContent?.trim().orEmpty()
                    if (rawHref.isBlank()) continue

                    val itemUri =
                        try {
                            val abs = URI(rawHref)
                            if (abs.isAbsolute) abs else uri.resolve(rawHref)
                        } catch (_: Exception) {
                            uri.resolve(rawHref)
                        }

                    val itemPath = URLDecoder.decode(itemUri.path ?: "", "UTF-8").trimEnd('/')

                    // 排除请求目录本身
                    if (itemPath.equals(reqPath, ignoreCase = true)) {
                        continue
                    }

                    var isDir = false
                    val resTypeNode = respElem.getElementsByTagNameNS("*", "resourcetype").item(0) as? Element
                    if (resTypeNode != null && resTypeNode.getElementsByTagNameNS("*", "collection").length > 0) {
                        isDir = true
                    }

                    var displayName =
                        respElem
                            .getElementsByTagNameNS("*", "displayname")
                            .item(0)
                            ?.textContent
                            ?.trim()
                    if (displayName.isNullOrBlank()) {
                        displayName = itemPath.substringAfterLast('/')
                    }
                    if (displayName.isBlank()) {
                        displayName = itemPath
                    }

                    // 过滤隐藏文件与文件夹（如 .DS_Store, @eaDir, #recycle 等）
                    if (isIgnoredEntry(displayName)) {
                        continue
                    }

                    val lenStr =
                        respElem
                            .getElementsByTagNameNS("*", "getcontentlength")
                            .item(0)
                            ?.textContent
                            ?.trim()
                    val len = lenStr?.toLongOrNull() ?: 0L

                    val modStr =
                        respElem
                            .getElementsByTagNameNS("*", "getlastmodified")
                            .item(0)
                            ?.textContent
                            ?.trim()
                    val modDate =
                        try {
                            if (!modStr.isNullOrBlank()) dateFormat.parse(modStr)?.time else null
                        } catch (_: Exception) {
                            null
                        }

                    val storeHref = URLDecoder.decode(itemUri.path ?: "", "UTF-8")
                    val finalHref = if (isDir && !storeHref.endsWith('/')) "$storeHref/" else storeHref

                    if (isDir) {
                        items.add(
                            WebDavItem(
                                name = displayName,
                                href = finalHref,
                                isDirectory = true,
                                contentLength = len,
                                lastModified = modDate,
                            ),
                        )
                    } else {
                        val dotIdx = displayName.lastIndexOf('.')
                        val ext = if (dotIdx != -1) displayName.substring(dotIdx).lowercase() else ""
                        if (SUPPORTED_AUDIO_EXTENSIONS.contains(ext)) {
                            items.add(
                                WebDavItem(
                                    name = displayName,
                                    href = finalHref,
                                    isDirectory = false,
                                    contentLength = len,
                                    lastModified = modDate,
                                ),
                            )
                        }
                    }
                }

                // 文件夹优先，同级文件名升序排序
                items.sortWith(compareBy<WebDavItem> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            } catch (_: Exception) {
            }
            items
        }

    /**
     * 递归扫描指定目录树提取所有音频条目（排除所有隐藏文件与隐藏文件夹）
     */
    suspend fun scanFolderRecursive(
        server: WebDavServer,
        folderHref: String,
        onProgress: ((String) -> Unit)? = null,
    ): List<WebDavSongCache> =
        withContext(Dispatchers.IO) {
            val discoveredSongs = mutableListOf<WebDavSongCache>()
            val queue = ArrayDeque<String>()
            queue.add(folderHref)

            onProgress?.invoke("正在深度检索 WebDAV 目录树...")

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val list = listDirectory(server, current)

                for (item in list) {
                    if (isIgnoredEntry(item.name)) continue

                    if (item.isDirectory) {
                        queue.add(item.href)
                    } else {
                        val (title, artist) = inferTitleArtist(item.name)
                        discoveredSongs.add(
                            WebDavSongCache(
                                serverId = server.id,
                                href = item.href,
                                title = title,
                                artist = artist,
                                album = "WebDAV 专辑",
                                fileSize = item.contentLength,
                                lastModified = item.lastModified,
                            ),
                        )
                    }
                }
                onProgress?.invoke("已发现 ${discoveredSongs.size} 首音频...")
            }

            discoveredSongs
        }

    private suspend inline fun <T> Call.executeWithCancellation(block: (Response) -> T): T {
        val job = currentCoroutineContext()[Job]
        val handle = job?.invokeOnCompletion { cancel() }
        return try {
            execute().use(block)
        } finally {
            handle?.dispose()
        }
    }

    /**
     * 读取远程音频文件头部 Range 字节（默认前 256KB）用于提取内嵌元数据
     */
    suspend fun fetchRangeBytes(
        server: WebDavServer,
        relativeHref: String,
        start: Long = 0L,
        end: Long = 262143L,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val (resolvedUrl, authHeader) = resolvePlaybackUrl(server, relativeHref)
                val client = getClient(server)

                val requestBuilder =
                    Request
                        .Builder()
                        .url(resolvedUrl)
                        .header("Range", "bytes=$start-$end")
                        .header("User-Agent", "MelodistTV/1.0 WebDAV Client")

                if (!authHeader.isNullOrBlank()) {
                    requestBuilder.header("Authorization", authHeader)
                }

                client.newCall(requestBuilder.build()).executeWithCancellation { resp ->
                    if (resp.isSuccessful || resp.code == 206) {
                        resp.body.bytes()
                    } else {
                        null
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

    /**
     * 探测并获取同目录下同名 .lrc 歌词文件
     */
    suspend fun fetchRemoteLrc(
        server: WebDavServer,
        audioHref: String,
    ): String? =
        withContext(Dispatchers.IO) {
            try {
                val lrcHref = audioHref.substringBeforeLast('.') + ".lrc"
                val client = getClient(server)
                val uri = buildFullUri(server, lrcHref)

                val requestBuilder =
                    Request
                        .Builder()
                        .url(uri.toURL())
                        .header("User-Agent", "MelodistTV/1.0 WebDAV Client")

                if (server.username.isNotBlank() || server.password.isNotBlank()) {
                    requestBuilder.header("Authorization", Credentials.basic(server.username, server.password))
                }

                client.newCall(requestBuilder.build()).executeWithCancellation { resp ->
                    if (resp.isSuccessful && resp.code == 200) {
                        resp.body.string().takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

    /**
     * 嗅探目录中常见的封面图片文件（cover.jpg, folder.jpg 等）
     */
    suspend fun fetchRemoteCover(
        server: WebDavServer,
        folderHref: String,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            val candidates = listOf("cover.jpg", "cover.png", "folder.jpg", "front.jpg", "Cover.jpg", "Folder.jpg")
            val cleanFolder = if (folderHref.endsWith('/')) folderHref else "$folderHref/"
            val client = getClient(server)

            for (candidate in candidates) {
                try {
                    val coverHref = cleanFolder + candidate
                    val uri = buildFullUri(server, coverHref)
                    val requestBuilder =
                        Request
                            .Builder()
                            .url(uri.toURL())
                            .header("User-Agent", "MelodistTV/1.0 WebDAV Client")

                    if (server.username.isNotBlank() || server.password.isNotBlank()) {
                        requestBuilder.header("Authorization", Credentials.basic(server.username, server.password))
                    }

                    val candidateBytes =
                        client.newCall(requestBuilder.build()).executeWithCancellation { resp ->
                            if (resp.isSuccessful && resp.code == 200) {
                                val bytes = resp.body.bytes()
                                if (bytes.size > 1024) {
                                    bytes
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        }
                    if (candidateBytes != null) {
                        return@withContext candidateBytes
                    }
                } catch (_: Exception) {
                }
            }
            null
        }

    /**
     * 下载音频文件到本地私有缓存
     */
    suspend fun downloadAudioFile(
        server: WebDavServer,
        relativeHref: String,
        targetFile: File,
        onProgress: ((Float) -> Unit)? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val client = getClient(server)
                val uri = buildFullUri(server, relativeHref)

                val requestBuilder =
                    Request
                        .Builder()
                        .url(uri.toURL())
                        .header("User-Agent", "MelodistTV/1.0 WebDAV Downloader")

                if (server.username.isNotBlank() || server.password.isNotBlank()) {
                    requestBuilder.header("Authorization", Credentials.basic(server.username, server.password))
                }

                val tempFile = File(targetFile.parentFile, targetFile.name + ".tmp")
                val downloadSuccess =
                    client.newCall(requestBuilder.build()).executeWithCancellation { resp ->
                        if (!resp.isSuccessful) return@executeWithCancellation false
                        val body = resp.body
                        val totalLength = body.contentLength()

                        body.byteStream().use { input ->
                            FileOutputStream(tempFile).use { output ->
                                val buffer = ByteArray(32 * 1024)
                                var bytesRead: Int
                                var downloadedBytes = 0L

                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead
                                    if (totalLength > 0 && onProgress != null) {
                                        onProgress(downloadedBytes.toFloat() / totalLength)
                                    }
                                }
                                output.flush()
                            }
                        }
                        true
                    }
                if (!downloadSuccess) return@withContext false

                if (tempFile.exists() && tempFile.length() > 0) {
                    if (targetFile.exists()) targetFile.delete()
                    tempFile.renameTo(targetFile)
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        }

    /**
     * 解析真正的音频播放直链
     * 若 WebDAV 服务重定向至第三方对象存储（如 Alist 挂载云盘返回 302 S3 直链），提取重定向目标 URL，并移除 Authorization 头（避免 S3 校验失败报 400）
     */
    suspend fun resolvePlaybackUrl(
        server: WebDavServer,
        relativeHref: String,
    ): Pair<String, String?> =
        withContext(Dispatchers.IO) {
            val originalUri = buildFullUri(server, relativeHref).toString()
            val authHeader =
                if (server.username.isNotBlank() || server.password.isNotBlank()) {
                    Credentials.basic(server.username, server.password)
                } else {
                    null
                }

            try {
                val baseClient = getClient(server)
                val noRedirectClient =
                    baseClient
                        .newBuilder()
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build()

                val reqBuilder =
                    Request
                        .Builder()
                        .url(originalUri)
                        .head()
                        .header("User-Agent", "MelodistTV/1.0 WebDAV Client")

                if (!authHeader.isNullOrBlank()) {
                    reqBuilder.header("Authorization", authHeader)
                }

                noRedirectClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.code in 300..399) {
                        val location = resp.header("Location")
                        if (!location.isNullOrBlank()) {
                            val absoluteLocation =
                                try {
                                    val locUri = URI(location)
                                    if (locUri.isAbsolute) location else URI(originalUri).resolve(location).toString()
                                } catch (_: Exception) {
                                    location
                                }
                            return@withContext Pair(absoluteLocation, null)
                        }
                    }
                }
            } catch (_: Exception) {
            }

            Pair(originalUri, authHeader)
        }
}
