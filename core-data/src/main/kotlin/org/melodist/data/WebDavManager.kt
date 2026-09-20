package org.melodist.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.melodist.api.AudioMetadataParser
import org.melodist.api.WebDavService
import org.melodist.model.Song
import org.melodist.model.WebDavConfig
import org.melodist.model.WebDavServer
import org.melodist.model.WebDavSongCache
import java.io.File
import java.util.UUID

object WebDavManager {
    private const val TAG = "WebDavManager"
    private const val PREF_NAME = "melodist_webdav_config"
    private const val KEY_CONFIG = "config_json"

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private var cacheDir: File? = null
    private var coversDir: File? = null
    private var lyricsDir: File? = null
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }
    private val webDavService = WebDavService()

    private var inMemoryConfig = WebDavConfig()

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (prefs == null) {
            prefs = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            cacheDir = File(appCtx.cacheDir, "webdav").apply { mkdirs() }
            coversDir = File(appCtx.cacheDir, "covers").apply { mkdirs() }
            lyricsDir = File(appCtx.cacheDir, "lyrics").apply { mkdirs() }
            loadConfig()
        }
    }

    fun getSafeCoversDir(): File {
        val folder = coversDir ?: File(appContext?.cacheDir ?: File("/tmp"), "covers")
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    fun getSafeCacheDir(): File {
        val folder = cacheDir ?: File(appContext?.cacheDir ?: File("/tmp"), "webdav")
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    fun getSafeLyricsDir(): File {
        val folder = lyricsDir ?: File(appContext?.cacheDir ?: File("/tmp"), "lyrics")
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    private fun safeWriteBytes(
        file: File,
        bytes: ByteArray,
    ) {
        file.parentFile?.mkdirs()
        file.outputStream().use { it.write(bytes) }
    }

    private fun loadConfig() {
        val raw = prefs?.getString(KEY_CONFIG, null)
        if (!raw.isNullOrBlank()) {
            try {
                inMemoryConfig = json.decodeFromString<WebDavConfig>(raw)
            } catch (_: Exception) {
                inMemoryConfig = WebDavConfig()
            }
        }
    }

    private fun saveConfig() {
        try {
            val raw = json.encodeToString(inMemoryConfig)
            prefs?.edit()?.putString(KEY_CONFIG, raw)?.apply()
        } catch (_: Exception) {
        }
    }

    fun getConfig(): WebDavConfig = inMemoryConfig

    fun getServers(): List<WebDavServer> = inMemoryConfig.servers

    fun getActiveServer(): WebDavServer? {
        val activeId = inMemoryConfig.activeServerId
        return inMemoryConfig.servers.find { it.id == activeId } ?: inMemoryConfig.servers.firstOrNull()
    }

    fun setActiveServer(serverId: String) {
        inMemoryConfig = inMemoryConfig.copy(activeServerId = serverId)
        saveConfig()
    }

    fun saveServer(server: WebDavServer) {
        val updatedServer =
            if (server.id.isBlank()) {
                server.copy(
                    id =
                        UUID
                            .randomUUID()
                            .toString()
                            .replace("-", "")
                            .take(16),
                )
            } else {
                server
            }
        val currentServers = inMemoryConfig.servers.toMutableList()
        val existingIndex = currentServers.indexOfFirst { it.id == updatedServer.id }
        if (existingIndex >= 0) {
            currentServers[existingIndex] = updatedServer
        } else {
            currentServers.add(updatedServer)
        }

        val activeId =
            if (inMemoryConfig.activeServerId.isNullOrBlank()) {
                updatedServer.id
            } else {
                inMemoryConfig.activeServerId
            }

        WebDavService.invalidateClient(updatedServer.id)
        inMemoryConfig = inMemoryConfig.copy(servers = currentServers, activeServerId = activeId)
        saveConfig()
    }

    fun removeServer(serverId: String) {
        val currentServers = inMemoryConfig.servers.filterNot { it.id == serverId }
        val newActiveId =
            if (inMemoryConfig.activeServerId == serverId) {
                currentServers.firstOrNull()?.id
            } else {
                inMemoryConfig.activeServerId
            }
        WebDavService.invalidateClient(serverId)
        inMemoryConfig = inMemoryConfig.copy(servers = currentServers, activeServerId = newActiveId)
        saveConfig()
    }

    fun getAuthorizationHeader(server: WebDavServer): String? =
        if (server.username.isNotBlank() || server.password.isNotBlank()) {
            okhttp3.Credentials.basic(server.username, server.password)
        } else {
            null
        }

    fun getStreamUri(
        server: WebDavServer,
        relativeHref: String,
    ): String = WebDavService.buildFullUri(server, relativeHref).toString()

    suspend fun resolvePlaybackUrl(
        server: WebDavServer,
        relativeHref: String,
    ): Pair<String, String?> = webDavService.resolvePlaybackUrl(server, relativeHref)

    /**
     * 获取指定 WebDAV 歌曲的本地缓存文件对象
     */
    fun getLocalCacheFile(
        serverId: String,
        relativeHref: String,
    ): File {
        val dir = getSafeCacheDir()
        val ext = relativeHref.substringAfterLast('.', "flac")
        val cleanName = (serverId + relativeHref).hashCode().toString().replace("-", "n")
        return File(dir, "$cleanName.$ext")
    }

    /**
     * 获取 WebDAV 音频文件的局部切片样本（优先使用完整本地缓存，缺失时通过 HTTP Range 拉取前 1.5MB 样本文件）
     */
    suspend fun fetchAudioSliceSample(
        server: WebDavServer,
        relativeHref: String,
    ): File? =
        withContext(Dispatchers.IO) {
            val local = getLocalCacheFile(server.id, relativeHref)
            if (local.exists() && local.length() > 64 * 1024L) return@withContext local

            val ext = relativeHref.substringAfterLast('.', "mp3")
            val cleanName = (server.id + relativeHref).hashCode().toString().replace("-", "n")
            val sliceFile = File(getSafeCacheDir(), "slice_$cleanName.$ext")
            if (sliceFile.exists() && sliceFile.length() > 64 * 1024L) return@withContext sliceFile

            try {
                val bytes = webDavService.fetchRangeBytes(server, relativeHref, 0L, 1_572_863L)
                if (bytes != null && bytes.size > 32 * 1024) {
                    sliceFile.writeBytes(bytes)
                    sliceFile
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch audio slice sample for $relativeHref", e)
                null
            }
        }

    /**
     * 获取 WebDAV 音频可供播放的 Uri（若有完整缓存返回本地文件，否则返回流式秒播 URI）
     */
    suspend fun resolvePlayableUri(song: Song): Uri? =
        withContext(Dispatchers.IO) {
            val server = getActiveServer() ?: return@withContext null
            val relativeHref = song.mediaMid.ifBlank { song.localFilePath ?: "" }
            if (relativeHref.isBlank()) return@withContext null

            val localFile = getLocalCacheFile(server.id, relativeHref)
            if (localFile.exists() && localFile.length() > 0L) {
                return@withContext Uri.fromFile(localFile)
            }

            // 流式秒播直链
            val fullUri = WebDavService.buildFullUri(server, relativeHref)
            Uri.parse(fullUri.toString())
        }

    /**
     * 读取文件头并抽取歌曲内嵌元数据、内嵌封面与歌词
     */
    suspend fun enrichSongMetadata(
        server: WebDavServer,
        rawCache: WebDavSongCache,
    ): WebDavSongCache =
        withContext(Dispatchers.IO) {
            var updated = rawCache
            val ext = rawCache.href.substringAfterLast('.', "flac")
            val hash = (server.id + rawCache.href).hashCode().toString().replace("-", "n")
            val tmpHdrFile = File(getSafeCacheDir(), "hdr_$hash.$ext")

            try {
                // 1. 请求头部 256KB
                val headerBytes = webDavService.fetchRangeBytes(server, rawCache.href, 0L, 262143L)
                if (headerBytes != null && headerBytes.isNotEmpty()) {
                    // 1.1 优先使用轻量纯字节解析器提取 FLAC (Vorbis Comment / Picture) 与 MP3 (ID3v2)
                    val parsed = AudioMetadataParser.parse(headerBytes)

                    var finalTitle = parsed.title?.ifBlank { null } ?: rawCache.title
                    var finalArtist = parsed.artist?.ifBlank { null } ?: rawCache.artist
                    var finalAlbum = parsed.album?.ifBlank { null } ?: rawCache.album
                    var finalDur = rawCache.duration
                    var coverPath = rawCache.coverPath
                    var rawCoverPath = rawCache.rawCoverPath

                    // 封面处理：扫描阶段仅压缩 500px WebP 供列表流畅滚动（原画大图由播放/查看大图时按需加载），覆盖旧低分辨率图
                    val coversFolder = getSafeCoversDir()

                    fun saveWebDavThumbnail(bytes: ByteArray) {
                        val path = org.melodist.data.pipeline.AudioMetadataPipeline.saveThumbnailWebp(
                            bytes = bytes,
                            folder = coversFolder,
                            baseHash = hash,
                            prefix = "webdav_",
                        )
                        if (path != null) {
                            coverPath = path
                        }
                    }

                    val parsedPicBytes = parsed.pictureBytes
                    val parsedPicOffset = parsed.pictureOffsetInFile
                    val parsedPicLen = parsed.pictureLength

                    if (parsedPicBytes != null && parsedPicBytes.size > 512) {
                        saveWebDavThumbnail(parsedPicBytes)
                    } else if (parsedPicOffset != null &&
                        parsedPicLen != null &&
                        parsedPicLen > 512 &&
                        parsedPicLen <= 8 * 1024 * 1024
                    ) {
                        // 若内嵌封面声明长度超过 256KB，按偏移量精准拉取完整图片
                        val picStart = parsedPicOffset
                        val picEnd = picStart + parsedPicLen - 1
                        val picBytes = webDavService.fetchRangeBytes(server, rawCache.href, picStart, picEnd)
                        if (picBytes != null && picBytes.size > 512) {
                            saveWebDavThumbnail(picBytes)
                        }
                    }

                    // 1.2 若纯字节未提取全（如非 FLAC/MP3 或无标签），降级使用 MediaMetadataRetriever 兜底提取
                    if (finalArtist.isBlank() || finalArtist == "WebDAV 音频" || coverPath.isNullOrBlank() || finalDur == 0) {
                        safeWriteBytes(tmpHdrFile, headerBytes)
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(tmpHdrFile.absolutePath)
                            val meta = org.melodist.data.pipeline.AudioMetadataPipeline.parseFromRetriever(
                                retriever = retriever,
                                fallbackTitle = finalTitle,
                                fallbackArtist = finalArtist,
                                fallbackAlbum = finalAlbum,
                            )
                            if (meta.title.isNotBlank() && (finalTitle == rawCache.title || finalTitle.isBlank())) {
                                finalTitle = meta.title
                            }
                            if (meta.artist.isNotBlank() && (finalArtist == "WebDAV 音频" || finalArtist.isBlank())) {
                                finalArtist = meta.artist
                            }
                            if (meta.album.isNotBlank() && (finalAlbum == "WebDAV 专辑" || finalAlbum.isBlank())) {
                                finalAlbum = meta.album
                            }
                            if (meta.durationSeconds > 0 && finalDur == 0) {
                                finalDur = meta.durationSeconds
                            }
                            if (coverPath.isNullOrBlank() && meta.pictureBytes != null) {
                                saveWebDavThumbnail(meta.pictureBytes)
                            }
                        } catch (_: Exception) {
                        } finally {
                            try {
                                retriever.release()
                            } catch (_: Exception) {
                            }
                        }
                    }

                    // 1.3 若依然无封面，嗅探同目录封面图片
                    if (coverPath.isNullOrBlank()) {
                        val parentFolder = rawCache.href.substringBeforeLast('/', "")
                        val folderCoverBytes = webDavService.fetchRemoteCover(server, parentFolder)
                        if (folderCoverBytes != null && folderCoverBytes.size > 512) {
                            saveWebDavThumbnail(folderCoverBytes)
                        }
                    }


                    // 1.4 歌词嗅探：优先 parsed.lyrics，次选同目录同名 .lrc，再选 extractEmbeddedLyricsFromBytes
                    var lyrics = updated.embeddedLyrics
                    if (lyrics.isNullOrBlank()) {
                        if (!parsed.lyrics.isNullOrBlank()) {
                            lyrics = parsed.lyrics
                        } else {
                            val remoteLrc = webDavService.fetchRemoteLrc(server, rawCache.href)
                            if (!remoteLrc.isNullOrBlank()) {
                                lyrics = remoteLrc
                            } else {
                                val embedded = WebDavService.extractEmbeddedLyricsFromBytes(headerBytes)
                                if (!embedded.isNullOrBlank()) {
                                    lyrics = embedded
                                }
                            }
                        }

                        if (!lyrics.isNullOrBlank()) {
                            val lrcDir = lyricsDir ?: File("/tmp/lyrics").apply { mkdirs() }
                            val lrcFile = File(lrcDir, "webdav_$hash.lrc")
                            try {
                                lrcFile.writeText(lyrics)
                            } catch (_: Exception) {
                            }
                        }
                    }

                    updated =
                        updated.copy(
                            title = finalTitle,
                            artist = finalArtist,
                            album = finalAlbum,
                            duration = finalDur,
                            coverPath = coverPath,
                            rawCoverPath = rawCoverPath,
                            embeddedLyrics = lyrics ?: updated.embeddedLyrics,
                        )
                }
            } catch (_: Exception) {
            } finally {
                try {
                    if (tmpHdrFile.exists()) tmpHdrFile.delete()
                } catch (_: Exception) {
                }
            }

            updated
        }

    /**
     * 获取 WebDAV 歌曲歌词内容（本地缓存 -> 内嵌歌词 -> 远程拉取）
     */
    suspend fun getSongLyrics(song: Song): String? =
        withContext(Dispatchers.IO) {
            val server = getActiveServer() ?: return@withContext null
            val relativeHref = song.mediaMid.ifBlank { song.localFilePath ?: "" }
            if (relativeHref.isBlank()) return@withContext null

            val hash = (server.id + relativeHref).hashCode().toString().replace("-", "n")
            val lrcDir = lyricsDir ?: File("/tmp/lyrics").apply { mkdirs() }
            val lrcFile = File(lrcDir, "webdav_$hash.lrc")
            if (lrcFile.exists() && lrcFile.length() > 0) {
                return@withContext try {
                    lrcFile.readText()
                } catch (_: Exception) {
                    null
                }
            }

            val cachedSong = server.cachedSongs.find { it.href.equals(relativeHref, ignoreCase = true) }
            if (!cachedSong?.embeddedLyrics.isNullOrBlank()) {
                return@withContext cachedSong.embeddedLyrics
            }

            // 尝试从 WebDAV 嗅探同名 .lrc
            val remoteLrc = webDavService.fetchRemoteLrc(server, relativeHref)
            if (!remoteLrc.isNullOrBlank()) {
                try {
                    lrcFile.writeText(remoteLrc)
                } catch (_: Exception) {
                }
                return@withContext remoteLrc
            }

            null
        }

    /**
     * 递归扫描指定目录并解析加入 WebDAV 音乐库
     */
    suspend fun scanAndEnrichFolder(
        server: WebDavServer,
        folderHref: String,
        onProgress: ((title: String, current: Int, total: Int) -> Unit)? = null,
    ): List<WebDavSongCache> =
        withContext(Dispatchers.IO) {
            // 1. 递归扫描获取所有音频
            val rawSongs =
                webDavService.scanFolderRecursive(server, folderHref) { status ->
                    onProgress?.invoke(status, 0, 0)
                }

            if (rawSongs.isEmpty()) {
                return@withContext emptyList()
            }

            // 2. 合并去重现存缓存
            val existingMap = server.cachedSongs.associateBy { it.href }.toMutableMap()
            val songsToProcess = mutableListOf<WebDavSongCache>()

            for (song in rawSongs) {
                val existing = existingMap[song.href]
                val isCoverValid =
                    existing?.coverPath?.let { cp ->
                        val f = File(cp)
                        cp.isNotBlank() && f.exists() && f.length() > 0L && !CoverCompressor.isLowResolution(f)
                    } ?: false
                if (existing != null && existing.duration > 0 && isCoverValid) {
                    // 已具有完整元数据，保留
                    existingMap[song.href] = existing
                } else {
                    val base = existing ?: song
                    songsToProcess.add(base)
                }
            }

            val total = songsToProcess.size
            var completed = 0

            // 3. 并发批量嗅探文件头并提取封面/歌词
            val enrichedList: List<WebDavSongCache> =
                coroutineScope {
                    val dispatcher = Dispatchers.IO.limitedParallelism(4)
                    songsToProcess
                        .map { raw ->
                            async(dispatcher) {
                                val enriched = enrichSongMetadata(server, raw)
                                synchronized(songsToProcess) {
                                    completed++
                                    onProgress?.invoke(enriched.title, completed, total)
                                }
                                enriched
                            }
                        }.awaitAll()
                }

            for (enriched in enrichedList) {
                existingMap[enriched.href] = enriched
            }

            val finalSongList = existingMap.values.toList()
            val updatedServer = server.copy(cachedSongs = finalSongList)
            saveServer(updatedServer)

            finalSongList
        }

    /**
     * 重新扫描补充尚未获取到元数据（封面、歌手、专辑、时长）的歌曲
     */
    suspend fun reEnrichMissingMetadata(
        server: WebDavServer,
        onProgress: ((title: String, current: Int, total: Int) -> Unit)? = null,
    ): List<WebDavSongCache> =
        withContext(Dispatchers.IO) {
            val allSongs = server.cachedSongs
            val missing =
                allSongs.filter { s ->
                    val cp = s.coverPath
                    val isCoverMissing = cp.isNullOrBlank() || !File(cp).exists()
                    s.duration == 0 ||
                        s.artist.isBlank() ||
                        s.artist == "未知歌手" ||
                        s.artist == "WebDAV 音频" ||
                        isCoverMissing
                }

            if (missing.isEmpty()) {
                return@withContext allSongs
            }

            val total = missing.size
            var completed = 0
            val existingMap = allSongs.associateBy { it.href }.toMutableMap()

            val enrichedList: List<WebDavSongCache> =
                coroutineScope {
                    val dispatcher = Dispatchers.IO.limitedParallelism(4)
                    missing
                        .map { raw ->
                            async(dispatcher) {
                                val enriched = enrichSongMetadata(server, raw)
                                synchronized(missing) {
                                    completed++
                                    onProgress?.invoke(enriched.title, completed, total)
                                }
                                enriched
                            }
                        }.awaitAll()
                }

            for (enriched in enrichedList) {
                existingMap[enriched.href] = enriched
            }

            val finalSongList = existingMap.values.toList()
            val updatedServer = server.copy(cachedSongs = finalSongList)
            saveServer(updatedServer)

            finalSongList
        }

    fun getAllCachedSongs(serverId: String? = null): List<Song> {
        val targetServer =
            if (serverId != null) {
                inMemoryConfig.servers.find { it.id == serverId }
            } else {
                getActiveServer()
            } ?: return emptyList()

        return targetServer.cachedSongs.map { cache ->
            val s = cache.toSong()
            val effectiveCover =
                if (s.coverUrl.isBlank()) {
                    getSongCoverPath(targetServer.id, cache.href) ?: ""
                } else {
                    s.coverUrl
                }
            val effectiveRaw =
                if (s.rawCoverUrl.isBlank()) {
                    getSongRawCoverPath(targetServer.id, cache.href) ?: ""
                } else {
                    s.rawCoverUrl
                }
            s.copy(coverUrl = effectiveCover, rawCoverUrl = effectiveRaw)
        }
    }

    fun clearCachedSongs(serverId: String? = null) {
        val targetServer =
            if (serverId != null) {
                inMemoryConfig.servers.find { it.id == serverId }
            } else {
                getActiveServer()
            } ?: return

        val serverFolder = getSafeCoversDir()
        if (serverFolder.exists()) {
            val prefix = "webdav_${targetServer.id}_"
            serverFolder.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        }

        val updated = targetServer.copy(cachedSongs = emptyList())
        saveServer(updated)
    }

    fun removeSongsFromCache(songs: List<Song>, serverId: String? = null) {
        if (songs.isEmpty()) return
        val targetServer =
            if (serverId != null) {
                inMemoryConfig.servers.find { it.id == serverId }
            } else {
                getActiveServer()
            } ?: return

        val removedHrefs = songs.mapNotNull { it.mediaMid.ifBlank { it.localFilePath }.takeIf { p -> !p.isNullOrBlank() } }.toSet()
        val removedMids = songs.map { it.songMid }.toSet()

        val filtered = targetServer.cachedSongs.filterNot { cache ->
            removedHrefs.contains(cache.href) || removedMids.contains(cache.toSong().songMid)
        }
        val updated = targetServer.copy(cachedSongs = filtered)
        saveServer(updated)
    }

    fun onCacheCleared() {
        val updatedServers =
            inMemoryConfig.servers.map { server ->
                val cleanedSongs =
                    server.cachedSongs.map { song ->
                        val cp = song.coverPath
                        if (!cp.isNullOrBlank() && !File(cp).exists()) {
                            song.copy(coverPath = null)
                        } else {
                            song
                        }
                    }
                server.copy(cachedSongs = cleanedSongs)
            }
        inMemoryConfig = inMemoryConfig.copy(servers = updatedServers)
        saveConfig()
    }

    /**
     * 获取指定歌曲的本地封面文件路径（若存在则返回 file:// 协议 URI，否则返回 null）
     */
    fun getSongCoverPath(
        serverId: String,
        href: String,
    ): String? {
        val folder = getSafeCoversDir()
        val hash = (serverId + href).hashCode().toString().replace("-", "n")
        val webp = File(folder, "webdav_$hash.webp")
        if (webp.exists() && webp.length() > 0L) return "file://${webp.absolutePath}"
        val png = File(folder, "webdav_$hash.png")
        if (png.exists() && png.length() > 0L) return "file://${png.absolutePath}"
        val jpg = File(folder, "webdav_$hash.jpg")
        if (jpg.exists() && jpg.length() > 0L) return "file://${jpg.absolutePath}"
        return null
    }

    /**
     * 获取指定歌曲的本地原始高清封面路径（若存在则返回 file:// 协议 URI，否则返回 null）
     */
    fun getSongRawCoverPath(
        serverId: String,
        href: String,
    ): String? {
        val folder = getSafeCoversDir()
        val hash = (serverId + href).hashCode().toString().replace("-", "n")
        val candidateExtensions = listOf("jpg", "png", "webp", "jpeg")
        for (ext in candidateExtensions) {
            val file = File(folder, "webdav_raw_$hash.$ext")
            if (file.exists() && file.length() > 512L) {
                return "file://${file.absolutePath}"
            }
        }
        return null
    }

    data class WebDavPlaybackMetadata(
        val coverUrl: String? = null,
        val rawCoverUrl: String? = null,
        val inferredTier: org.melodist.model.AudioQualityTier? = null,
    )

    /**
     * 为当前正在播放的 WebDAV 歌曲提取内嵌专辑封面（原画与缩略图双轨）与音质参数
     */
    suspend fun extractPlaybackMetadata(
        server: WebDavServer,
        song: Song,
    ): WebDavPlaybackMetadata =
        withContext(Dispatchers.IO) {
            val relativeHref = song.mediaMid.ifBlank { song.localFilePath ?: "" }
            if (relativeHref.isBlank()) return@withContext WebDavPlaybackMetadata()

            val existingCover = getSongCoverPath(server.id, relativeHref)
            var existingRaw = getSongRawCoverPath(server.id, relativeHref)
            val folder = getSafeCoversDir()
            val hash = (server.id + relativeHref).hashCode().toString().replace("-", "n")
            val targetWebp = File(folder, "webdav_$hash.webp")
            var finalCoverUrl = existingCover
            var finalRawCoverUrl = existingRaw
            var finalTier: org.melodist.model.AudioQualityTier? = null

            fun saveCoverPair(bytes: ByteArray) {
                if (bytes.size < 512) return
                if (finalRawCoverUrl.isNullOrBlank()) {
                    val rawFile = RawCoverHelper.saveRawCover(bytes, folder, "webdav_raw_$hash")
                    if (rawFile != null && rawFile.exists() && rawFile.length() > 0L) {
                        finalRawCoverUrl = "file://${rawFile.absolutePath}"
                    }
                }
                if (finalCoverUrl.isNullOrBlank() || !File(finalCoverUrl!!.removePrefix("file://")).exists()) {
                    val path = org.melodist.data.pipeline.AudioMetadataPipeline.saveThumbnailWebp(
                        bytes = bytes,
                        folder = folder,
                        baseHash = hash,
                        prefix = "webdav_",
                    )
                    if (path != null) {
                        finalCoverUrl = "file://$path"
                    } else if (finalRawCoverUrl != null) {
                        finalCoverUrl = finalRawCoverUrl
                    }
                }
            }

            // 1. 检查是否有完整的本地已缓存音频文件
            val localFile = getLocalCacheFile(server.id, relativeHref)
            if (localFile.exists() && localFile.length() > 0L) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(localFile.absolutePath)
                    if (finalCoverUrl.isNullOrBlank() || finalRawCoverUrl.isNullOrBlank()) {
                        val picBytes = retriever.embeddedPicture
                        if (picBytes != null && picBytes.size > 512) {
                            saveCoverPair(picBytes)
                        }
                    }
                    val sRate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
                    val bRate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
                    val ext = relativeHref.substringAfterLast('.', "flac")
                    if (sRate != null && sRate > 0) {
                        finalTier =
                            org.melodist.model.AudioQualityTier
                                .inferFromAudioFormat(sRate, mimeType = "audio/$ext", bitrate = bRate ?: 0)
                    }
                } catch (_: Exception) {
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                    }
                }
            }

            // 2. 线上流式播放场景：若缺少封面或音质，拉取头部 512KB
            if (finalCoverUrl.isNullOrBlank() || finalRawCoverUrl.isNullOrBlank() || finalTier == null) {
                val ext = relativeHref.substringAfterLast('.', "flac")
                val tmpHdrFile = File(getSafeCacheDir(), "hdr_play_$hash.$ext")
                try {
                    val headerBytes = webDavService.fetchRangeBytes(server, relativeHref, 0L, 524287L)
                    if (headerBytes != null && headerBytes.isNotEmpty()) {
                        val parsed = AudioMetadataParser.parse(headerBytes)
                        finalTier = parsed.inferTier("audio/$ext")

                        if (finalCoverUrl.isNullOrBlank() || finalRawCoverUrl.isNullOrBlank()) {
                            val parsedPicBytes = parsed.pictureBytes
                            val parsedPicOffset = parsed.pictureOffsetInFile
                            val parsedPicLen = parsed.pictureLength

                            if (parsedPicBytes != null && parsedPicBytes.size > 512) {
                                saveCoverPair(parsedPicBytes)
                            } else if (parsedPicOffset != null &&
                                parsedPicLen != null &&
                                parsedPicLen > 512 &&
                                parsedPicLen <= 8 * 1024 * 1024
                            ) {
                                val picBytes = webDavService.fetchRangeBytes(server, relativeHref, parsedPicOffset, parsedPicOffset + parsedPicLen - 1)
                                if (picBytes != null && picBytes.size > 512) {
                                    saveCoverPair(picBytes)
                                }
                            }
                        }

                        // 兜底使用 MediaMetadataRetriever
                        if (finalCoverUrl.isNullOrBlank() || finalRawCoverUrl.isNullOrBlank() || finalTier == null) {
                            safeWriteBytes(tmpHdrFile, headerBytes)
                            val retriever = android.media.MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(tmpHdrFile.absolutePath)
                                if (finalCoverUrl.isNullOrBlank() || finalRawCoverUrl.isNullOrBlank()) {
                                    val picBytes = retriever.embeddedPicture
                                    if (picBytes != null && picBytes.size > 512) {
                                        saveCoverPair(picBytes)
                                    }
                                }
                                if (finalTier == null) {
                                    val sRate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
                                    val bRate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
                                    if (sRate != null && sRate > 0) {
                                        finalTier =
                                            org.melodist.model.AudioQualityTier
                                                .inferFromAudioFormat(sRate, mimeType = "audio/$ext", bitrate = bRate ?: 0)
                                    }
                                }
                            } catch (_: Exception) {
                            } finally {
                                try {
                                    retriever.release()
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("WebDavManager", "Failed to extract metadata for WebDAV song: ${song.name}", e)
                } finally {
                    if (tmpHdrFile.exists()) tmpHdrFile.delete()
                }
            }

            // 3. 嗅探同目录封面图片
            if (finalCoverUrl.isNullOrBlank() || finalRawCoverUrl.isNullOrBlank()) {
                try {
                    val parentFolder = relativeHref.substringBeforeLast('/', "")
                    val folderCoverBytes = webDavService.fetchRemoteCover(server, parentFolder)
                    if (folderCoverBytes != null && folderCoverBytes.size > 512) {
                        saveCoverPair(folderCoverBytes)
                    }
                } catch (e: Exception) {
                    Log.w("WebDavManager", "Failed to fetch remote cover for folder: $relativeHref", e)
                }
            }

            WebDavPlaybackMetadata(
                coverUrl = finalCoverUrl,
                rawCoverUrl = finalRawCoverUrl,
                inferredTier = finalTier,
            )
        }

    /**
     * 实时按需确保 WebDAV 歌曲具有原画大图（本地存在直接返回，缺失则实时从缓存或远端按需提取）
     */
    suspend fun ensureRawCover(song: Song): String? =
        withContext(Dispatchers.IO) {
            val server = getActiveServer() ?: return@withContext null
            val relativeHref = song.mediaMid.ifBlank { song.localFilePath ?: "" }
            if (relativeHref.isBlank()) return@withContext null

            val existingRaw = getSongRawCoverPath(server.id, relativeHref)
            if (!existingRaw.isNullOrBlank()) return@withContext existingRaw

            val meta = extractPlaybackMetadata(server, song)
            meta.rawCoverUrl ?: meta.coverUrl?.let { RawCoverHelper.findMatchingRawCoverUrl(it) }
        }

    /**
     * 兼容接口：提取内嵌封面
     */
    suspend fun extractAndCacheSongCover(
        server: WebDavServer,
        song: Song,
    ): String? = extractPlaybackMetadata(server, song).coverUrl
}

