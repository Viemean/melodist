package org.melodist.data

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.melodist.model.AudioFileFilter
import org.melodist.model.Song
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest

@Serializable
data class LocalSongCache(
    val id: String,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int = 0,
    val coverPath: String = "",
    val rawCoverPath: String = "",
    val hasLrc: Boolean = false,
    val lrcPath: String = "",
    val lastModified: Long = 0L,
) {
    fun toSong(): Song {
        val tier = AudioFileFilter.inferQualityTierByExtension(path)
        val resolvedCover = LocalMusicManager.resolveCoverUrl(path, coverPath)
        val resolvedRaw =
            LocalMusicManager.resolveRawCoverUrl(path, rawCoverPath).ifBlank {
                RawCoverHelper.findMatchingRawCoverUrl(resolvedCover) ?: ""
            }
        return Song(
            songId = id.hashCode().toLong(),
            songMid = "local_$id",
            name = title,
            singer = artist,
            album = album,
            durationSeconds = durationSeconds,
            currentTier = tier,
            coverUrl = resolvedCover,
            rawCoverUrl = resolvedRaw,
            localFilePath = path,
            dateAdded = lastModified,
        )
    }
}

@Serializable
data class LocalMusicConfig(
    val scannedSongs: List<LocalSongCache> = emptyList(),
    val lastDirectory: String = "",
    val lastScanTimeMs: Long = 0L,
    val sortOrder: String = "DEFAULT",
)

data class StorageDrive(
    val id: String,
    val name: String,
    val path: String,
    val isRemovable: Boolean = false,
    val freeSpaceBytes: Long = 0L,
    val totalSpaceBytes: Long = 0L,
)

data class LocalFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val modifiedMs: Long = 0L,
    val isAudio: Boolean = false,
)

object LocalMusicManager {
    private const val TAG = "LocalMusicManager"
    private const val PREF_NAME = "melodist_local_music"
    private const val KEY_CONFIG = "local_config_json"

    private val SUPPORTED_AUDIO_EXT = org.melodist.model.AudioFileFilter.SUPPORTED_AUDIO_EXTENSIONS

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private var coversDir: File? = null
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }
    private var inMemoryConfig = LocalMusicConfig()
    private val _scannedSongsFlow = MutableStateFlow<List<Song>>(emptyList())
    val scannedSongsFlow: StateFlow<List<Song>> = _scannedSongsFlow.asStateFlow()

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /**
     * 初始化本地音乐管理器，装配全局上下文并加载持久化配置。
     *
     * @param context 应用程序上下文
     */
    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (prefs == null) {
            prefs = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            coversDir = File(appCtx.cacheDir, "local_covers").apply { mkdirs() }
            loadConfig()
            healMissingCovers()
        }
    }

    /**
     * 获取安全的本地封面缓存目录。
     *
     * @return 封面缓存目录 File 对象，若不存在则已自动创建
     */
    fun getSafeCoversDir(): File {
        val folder = coversDir ?: File(appContext?.cacheDir ?: File("/tmp"), "local_covers")
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    /**
     * 解析本地歌曲封面的有效 URI 路径。
     *
     * @param path 本地音频文件完整绝对路径
     * @param coverPath 已缓存的封面图片路径
     * @return 以 `file://` 起始的封面协议链接，无封面时返回空字符串
     */
    fun resolveCoverUrl(
        path: String,
        coverPath: String,
    ): String {
        if (coverPath.isNotBlank()) {
            val f = File(coverPath)
            if (f.exists() && f.length() > 0L) return "file://$coverPath"
        }
        val folder = getSafeCoversDir()
        val hash = md5(path)
        val webp = File(folder, "cover_$hash.webp")
        if (webp.exists() && webp.length() > 0L) return "file://${webp.absolutePath}"
        val jpg = File(folder, "cover_$hash.jpg")
        if (jpg.exists() && jpg.length() > 0L) return "file://${jpg.absolutePath}"
        return ""
    }

    /**
     * 解析本地歌曲原画高分辨率封面的有效 URI 路径。
     *
     * @param path 本地音频文件完整绝对路径
     * @param rawCoverPath 已缓存的原画封面图片路径
     * @return 以 `file://` 起始的原画封面协议链接，无原画时返回空字符串
     */
    fun resolveRawCoverUrl(
        path: String,
        rawCoverPath: String,
    ): String {
        if (rawCoverPath.isNotBlank()) {
            val f = File(rawCoverPath)
            if (f.exists() && f.length() > 512L) return "file://$rawCoverPath"
        }
        val folder = getSafeCoversDir()
        val hash = md5(path)
        val candidateExtensions = listOf("jpg", "png", "webp", "jpeg")
        for (ext in candidateExtensions) {
            val f = File(folder, "cover_raw_$hash.$ext")
            if (f.exists() && f.length() > 512L) return "file://${f.absolutePath}"
        }
        return ""
    }

    /**
     * 实时按需确保本地歌曲具备原画高分辨率封面。
     *
     * @param song 目标歌曲实体
     * @return 原画封面的 `file://` URI 字符串；提取失败或文件不存在返回 null
     */
    suspend fun ensureRawCover(song: Song): String? =
        withContext(Dispatchers.IO) {
            val path = song.localFilePath ?: return@withContext null
            val existing =
                resolveRawCoverUrl(path, "").ifBlank {
                    RawCoverHelper.findMatchingRawCoverUrl(song.coverUrl) ?: ""
                }
            if (existing.isNotBlank()) return@withContext existing

            val folder = getSafeCoversDir()
            val hash = md5(path)
            val rawFile = RawCoverHelper.extractRawCoverFromAudio(path, folder, hash)
            if (rawFile != null && rawFile.exists() && rawFile.length() > 512L) {
                val rawUrl = "file://${rawFile.absolutePath}"
                val updatedSongs =
                    inMemoryConfig.scannedSongs.map { s ->
                        if (s.path == path) s.copy(rawCoverPath = rawFile.absolutePath) else s
                    }
                if (updatedSongs != inMemoryConfig.scannedSongs) {
                    inMemoryConfig = inMemoryConfig.copy(scannedSongs = updatedSongs)
                    saveConfig()
                    _scannedSongsFlow.value = getScannedSongs()
                }
                rawUrl
            } else {
                null
            }
        }

    fun onCacheCleared() {
        val updatedSongs =
            inMemoryConfig.scannedSongs.map { song ->
                if (song.coverPath.isNotBlank() && !File(song.coverPath).exists()) {
                    song.copy(coverPath = "")
                } else {
                    song
                }
            }
        inMemoryConfig = inMemoryConfig.copy(scannedSongs = updatedSongs)
        saveConfig()
        _scannedSongsFlow.value = getScannedSongs()
    }

    fun healMissingCovers() {
        scope.launch {
            val songsToHeal =
                inMemoryConfig.scannedSongs.filter { s ->
                    val f = if (s.coverPath.isNotBlank()) File(s.coverPath) else null
                    (f == null || !f.exists() || f.length() == 0L || CoverCompressor.isLowResolution(f)) && File(s.path).exists()
                }
            if (songsToHeal.isEmpty()) return@launch

            val retriever = MediaMetadataRetriever()
            val folder = getSafeCoversDir()
            val healedMap = mutableMapOf<String, String>()

            for (s in songsToHeal) {
                try {
                    retriever.setDataSource(s.path)
                    val picBytes = retriever.embeddedPicture
                    if (picBytes != null && picBytes.isNotEmpty()) {
                        val hash = md5(s.path)
                        val webpPath =
                            org.melodist.data.pipeline.AudioMetadataPipeline
                                .saveThumbnailWebp(picBytes, folder, hash)
                        if (webpPath != null) {
                            healedMap[s.path] = webpPath
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("LocalSongCache", "Operation failed", e)
                }
            }

            try {
                retriever.release()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("LocalSongCache", "Operation failed", e)
            }

            if (healedMap.isNotEmpty()) {
                val updated =
                    inMemoryConfig.scannedSongs.map { s ->
                        val newCover = healedMap[s.path]
                        if (newCover != null) s.copy(coverPath = newCover) else s
                    }
                inMemoryConfig = inMemoryConfig.copy(scannedSongs = updated)
                saveConfig()
                _scannedSongsFlow.value = getScannedSongs()
            }
        }
    }

    private fun loadConfig() {
        val raw = prefs?.getString(KEY_CONFIG, null)
        if (!raw.isNullOrBlank()) {
            try {
                inMemoryConfig = json.decodeFromString(raw)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode local music config", e)
                inMemoryConfig = LocalMusicConfig()
            }
        }
        _scannedSongsFlow.value = getScannedSongs()
    }

    private fun saveConfig() {
        try {
            val raw = json.encodeToString(inMemoryConfig)
            prefs?.edit()?.putString(KEY_CONFIG, raw)?.apply()
            _scannedSongsFlow.value = getScannedSongs()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save local music config", e)
        }
    }

    fun getScannedSongs(): List<Song> = inMemoryConfig.scannedSongs.map { it.toSong() }

    fun removeSongByPath(path: String): Boolean {
        val before = inMemoryConfig.scannedSongs.size
        inMemoryConfig =
            inMemoryConfig.copy(
                scannedSongs = inMemoryConfig.scannedSongs.filterNot { it.path == path },
            )
        val removed = inMemoryConfig.scannedSongs.size < before
        if (removed) {
            saveConfig()
        }
        return removed
    }

    /**
     * 从本地文件系统与扫描曲库中物理删除指定的一组歌曲，同时联动清理关联歌词与下载记录。
     *
     * @param songs 待删除的歌曲实体列表
     * @param context 应用程序上下文，用于触发 MediaScannerConnection 通知系统媒体库同步
     */
    fun deleteSongs(
        songs: List<Song>,
        context: Context,
    ) {
        if (songs.isEmpty()) return
        val paths = mutableListOf<String>()
        val removedPaths = mutableSetOf<String>()
        for (song in songs) {
            val path =
                song.localFilePath?.takeIf { it.isNotBlank() }
                    ?: inMemoryConfig.scannedSongs
                        .find { "local_${it.id}" == song.songMid }
                        ?.path
                        .orEmpty()
            if (path.isNotBlank()) {
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                    val lrcFile = File(path.substringBeforeLast(".") + ".lrc")
                    if (lrcFile.exists()) {
                        lrcFile.delete()
                    }
                }
                paths.add(path)
                removedPaths.add(path)
                org.melodist.data.download.DownloadManager
                    .deleteDownloadedByPath(path)
                org.melodist.data.download.DownloadManager
                    .deleteDownloadedBySongMid(song.songMid, deleteFile = false)
            }
        }
        if (removedPaths.isNotEmpty()) {
            inMemoryConfig =
                inMemoryConfig.copy(
                    scannedSongs = inMemoryConfig.scannedSongs.filterNot { removedPaths.contains(it.path) },
                )
            saveConfig()
        }
        if (paths.isNotEmpty()) {
            try {
                android.media.MediaScannerConnection.scanFile(
                    context,
                    paths.toTypedArray(),
                    null,
                    null,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("LocalSongCache", "Operation failed", e)
            }
        }
    }

    fun notifyScannedSongsChanged() {
        _scannedSongsFlow.value = getScannedSongs()
    }

    fun getLastDirectory(): String {
        if (inMemoryConfig.lastDirectory.isNotBlank() && File(inMemoryConfig.lastDirectory).exists()) {
            return inMemoryConfig.lastDirectory
        }
        return Environment.getExternalStorageDirectory().absolutePath
    }

    fun setLastDirectory(path: String) {
        inMemoryConfig = inMemoryConfig.copy(lastDirectory = path)
        saveConfig()
    }

    fun getSortOrder(): org.melodist.model.SongSortOrder =
        try {
            org.melodist.model.SongSortOrder
                .valueOf(inMemoryConfig.sortOrder)
        } catch (_: IllegalArgumentException) {
            org.melodist.model.SongSortOrder.DEFAULT
        }

    fun setSortOrder(order: org.melodist.model.SongSortOrder) {
        if (inMemoryConfig.sortOrder != order.name) {
            inMemoryConfig = inMemoryConfig.copy(sortOrder = order.name)
            saveConfig()
        }
    }

    fun clearLibrary() {
        inMemoryConfig = inMemoryConfig.copy(scannedSongs = emptyList(), lastScanTimeMs = 0L)
        saveConfig()
    }

    /**
     * 检测设备上所有可用的存储卷驱动器（包括内部主存储、系统音乐/下载目录、外置 U 盘与 OTG 挂载点）。
     *
     * @param context 应用程序上下文
     * @return 识别到的存储设备驱动器列表
     */
    fun detectStorageDrives(context: Context): List<StorageDrive> {
        val drives = mutableListOf<StorageDrive>()
        val seenPaths = mutableSetOf<String>()

        // 1. 内部主存储
        val internalDir = Environment.getExternalStorageDirectory()
        if (internalDir.exists() && internalDir.canRead()) {
            seenPaths.add(internalDir.absolutePath)
            drives.add(
                StorageDrive(
                    id = "internal",
                    name = "内部存储",
                    path = internalDir.absolutePath,
                    isRemovable = false,
                    freeSpaceBytes = internalDir.freeSpace,
                    totalSpaceBytes = internalDir.totalSpace,
                ),
            )
        }

        // 2. 常用音乐文件夹
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        if (musicDir.exists() && musicDir.canRead() && !seenPaths.contains(musicDir.absolutePath)) {
            seenPaths.add(musicDir.absolutePath)
            drives.add(
                StorageDrive(
                    id = "music_dir",
                    name = "音乐文件夹 (Music)",
                    path = musicDir.absolutePath,
                    isRemovable = false,
                    freeSpaceBytes = musicDir.freeSpace,
                    totalSpaceBytes = musicDir.totalSpace,
                ),
            )
        }

        // 3. 下载文件夹
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadDir.exists() && downloadDir.canRead() && !seenPaths.contains(downloadDir.absolutePath)) {
            seenPaths.add(downloadDir.absolutePath)
            drives.add(
                StorageDrive(
                    id = "download_dir",
                    name = "下载文件夹 (Download)",
                    path = downloadDir.absolutePath,
                    isRemovable = false,
                    freeSpaceBytes = downloadDir.freeSpace,
                    totalSpaceBytes = downloadDir.totalSpace,
                ),
            )
        }

        // 4. 检测 /storage/ 挂载点（U 盘、OTG 外置卡、移动硬盘）
        try {
            val storageRoot = File("/storage")
            if (storageRoot.exists() && storageRoot.isDirectory) {
                storageRoot.listFiles()?.forEach { file ->
                    val path = file.absolutePath
                    val name = file.name
                    // 排除系统内部模拟卷与 self 软链
                    if (!name.equals("emulated", ignoreCase = true) &&
                        !name.equals("self", ignoreCase = true) &&
                        !name.equals("knox", ignoreCase = true) &&
                        file.isDirectory &&
                        file.canRead() &&
                        !seenPaths.contains(path)
                    ) {
                        seenPaths.add(path)
                        drives.add(
                            StorageDrive(
                                id = "usb_$name",
                                name = "U 盘 / 外置存储 ($name)",
                                path = path,
                                isRemovable = true,
                                freeSpaceBytes = file.freeSpace,
                                totalSpaceBytes = file.totalSpace,
                            ),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error listing /storage", e)
        }

        // 5. 通过 Android StorageManager 辅助检测外置卷
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (sm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                sm.storageVolumes.forEach { volume ->
                    val dir =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            volume.directory
                        } else {
                            // 反射获取 getPath
                            try {
                                val getPathMethod = volume.javaClass.getMethod("getPath")
                                (getPathMethod.invoke(volume) as? String)?.let { File(it) }
                            } catch (_: Exception) {
                                null
                            }
                        }
                    if (dir != null && dir.exists() && dir.canRead() && !seenPaths.contains(dir.absolutePath)) {
                        seenPaths.add(dir.absolutePath)
                        val desc = volume.getDescription(context)
                        drives.add(
                            StorageDrive(
                                id = "vol_${dir.name}",
                                name = if (volume.isRemovable) "外置存储 ($desc)" else desc,
                                path = dir.absolutePath,
                                isRemovable = volume.isRemovable,
                                freeSpaceBytes = dir.freeSpace,
                                totalSpaceBytes = dir.totalSpace,
                            ),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting via StorageManager", e)
        }

        return drives
    }

    /**
     * 浏览指定目录下的子文件夹与支持格式的音频文件，结果排序为文件夹置顶、文件名升序。
     *
     * @param dirPath 待浏览的本地目录绝对路径
     * @return 过滤与排序后的文件与子目录列表；路径无效或不可读时返回空列表
     */
    fun listDirectory(dirPath: String): List<LocalFileItem> {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory || !dir.canRead()) {
            return emptyList()
        }

        val items = mutableListOf<LocalFileItem>()
        val files = dir.listFiles() ?: return emptyList()

        for (f in files) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                items.add(
                    LocalFileItem(
                        name = f.name,
                        path = f.absolutePath,
                        isDirectory = true,
                        modifiedMs = f.lastModified(),
                    ),
                )
            } else if (f.isFile) {
                val ext = f.extension.lowercase()
                if (SUPPORTED_AUDIO_EXT.contains(ext)) {
                    items.add(
                        LocalFileItem(
                            name = f.name,
                            path = f.absolutePath,
                            isDirectory = false,
                            size = f.length(),
                            modifiedMs = f.lastModified(),
                            isAudio = true,
                        ),
                    )
                }
            }
        }

        // 文件夹在前、音频文件在后，按字母顺序排序
        items.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        return items
    }

    /**
     * 从音频文件名推断歌曲名称与歌手名称。
     *
     * @param fileName 原始音频文件名
     * @return 包含 (歌曲名, 歌手名) 的二元组
     */
    fun inferTitleArtist(fileName: String): Pair<String, String> =
        org.melodist.data.pipeline.AudioMetadataPipeline
            .inferTitleArtist(fileName)

    /**
     * 递归扫描指定目录下的所有音频文件，解析元数据、提取封面并持久化落库。
     *
     * @param targetDir 扫描目标根目录路径
     * @param onProgress 进度回调函数，入参分别为（当前处理曲目标题，已处理数，总文件数）
     * @return 扫描入库的所有歌曲实体列表；目录不存在时返回空列表
     */
    suspend fun scanDirectory(
        targetDir: String,
        onProgress: (title: String, current: Int, total: Int) -> Unit,
    ): List<Song> =
        withContext(Dispatchers.IO) {
            val dir = File(targetDir)
            if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()

            // 1. 递归收集所有音频文件
            val audioFiles = mutableListOf<File>()

            fun collect(current: File) {
                val list = current.listFiles() ?: return
                for (f in list) {
                    if (f.name.startsWith(".")) continue
                    if (f.isDirectory) {
                        collect(f)
                    } else if (f.isFile && SUPPORTED_AUDIO_EXT.contains(f.extension.lowercase())) {
                        audioFiles.add(f)
                    }
                }
            }
            collect(dir)

            val total = audioFiles.size
            val existingMap = inMemoryConfig.scannedSongs.associateBy { it.path }
            val newCaches = mutableListOf<LocalSongCache>()

            val retriever = MediaMetadataRetriever()

            audioFiles.forEachIndexed { index, file ->
                val path = file.absolutePath
                val mod = file.lastModified()
                val existing = existingMap[path]
                val isCoverValid =
                    existing?.coverPath?.let { cp ->
                        val f = File(cp)
                        cp.isNotBlank() && f.exists() && f.length() > 0L && !CoverCompressor.isLowResolution(f)
                    } ?: false

                if (existing != null && existing.lastModified == mod && (existing.coverPath.isBlank() || isCoverValid)) {
                    newCaches.add(existing)
                    onProgress(existing.title, index + 1, total)
                } else {
                    val cache = parseAudioFile(retriever, file)
                    newCaches.add(cache)
                    onProgress(cache.title, index + 1, total)
                }
            }

            try {
                retriever.release()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("LocalSongCache", "Operation failed", e)
            }

            inMemoryConfig =
                inMemoryConfig.copy(
                    scannedSongs = newCaches,
                    lastDirectory = targetDir,
                    lastScanTimeMs = System.currentTimeMillis(),
                )
            saveConfig()

            newCaches.map { it.toSong() }
        }

    private fun parseAudioFile(
        retriever: MediaMetadataRetriever,
        file: File,
    ): LocalSongCache {
        val (inferredTitle, inferredArtist) = inferTitleArtist(file.name)
        val defaultAlbum = file.parentFile?.name ?: "本地音乐"
        val parsed =
            try {
                retriever.setDataSource(file.absolutePath)
                org.melodist.data.pipeline.AudioMetadataPipeline.parseFromRetriever(
                    retriever = retriever,
                    fallbackTitle = inferredTitle,
                    fallbackArtist = inferredArtist,
                    fallbackAlbum = defaultAlbum,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error extracting metadata for ${file.name}", e)
                org.melodist.data.pipeline.ParsedAudioMetadata(
                    title = inferredTitle,
                    artist = inferredArtist,
                    album = defaultAlbum,
                    durationSeconds = 0,
                )
            }

        val folder = getSafeCoversDir()
        val hash = md5(file.absolutePath)
        val coverPath =
            parsed.pictureBytes?.let { bytes ->
                org.melodist.data.pipeline.AudioMetadataPipeline
                    .saveThumbnailWebp(bytes, folder, hash)
            } ?: ""

        val lrcFile =
            org.melodist.data.pipeline.AudioMetadataPipeline
                .detectCompanionLrc(file)
        val hasLrc = lrcFile != null

        return LocalSongCache(
            id = hash,
            path = file.absolutePath,
            title = parsed.title,
            artist = parsed.artist,
            album = parsed.album,
            durationSeconds = parsed.durationSeconds,
            coverPath = coverPath,
            rawCoverPath = "",
            hasLrc = hasLrc,
            lrcPath = lrcFile?.absolutePath ?: "",
            lastModified = file.lastModified(),
        )
    }

    /**
     * 根据本地文件绝对路径查询已扫描曲库中的歌曲对象。
     *
     * @param path 音频文件绝对路径
     * @return 匹配的歌曲实体；若路径尚未扫描入库则返回 null
     */
    fun findSongByPath(path: String): Song? {
        val cache = inMemoryConfig.scannedSongs.firstOrNull { it.path == path } ?: return null
        return cache.toSong()
    }

    /**
     * 根据路径与文件名快速构造未入库的临时歌曲对象（用于文件夹浏览即点即播）。
     *
     * @param path 本地音频文件绝对路径
     * @param fileName 音频文件名
     * @return 包含推断元数据与一致性 hash MID 的临时歌曲实体
     */
    fun buildTempSong(
        path: String,
        fileName: String,
    ): Song {
        val (inferredTitle, inferredArtist) = inferTitleArtist(fileName)
        return buildTempSong(path, inferredTitle, inferredArtist)
    }

    /**
     * 根据路径与显式标题歌手构造临时歌曲对象。
     *
     * @param path 本地音频文件绝对路径
     * @param inferredTitle 推断或指定的歌曲标题
     * @param inferredArtist 推断或指定的歌手名称
     * @return 构造完成的歌曲实体
     */
    fun buildTempSong(
        path: String,
        inferredTitle: String,
        inferredArtist: String,
    ): Song {
        val hash = md5(path)
        val defaultAlbum = File(path).parentFile?.name ?: "本地音频"
        val tier = AudioFileFilter.inferQualityTierByExtension(path)
        val coverUrl = resolveCoverUrl(path, "")
        return Song(
            songId = hash.hashCode().toLong(),
            songMid = "local_$hash",
            name = inferredTitle,
            singer = inferredArtist,
            album = defaultAlbum,
            currentTier = tier,
            coverUrl = coverUrl,
            localFilePath = path,
        )
    }

    /**
     * 读取指定本地歌曲的歌词文本（优先尝试同名外挂 .lrc，不存在时读取文件头解析内嵌歌词）。
     *
     * @param song 目标歌曲实体
     * @return 提取并解码的歌词文本字符串；无外挂且无内嵌歌词时返回 null
     */
    suspend fun getSongLyrics(song: Song): String? =
        withContext(Dispatchers.IO) {
            val filePath = song.localFilePath ?: return@withContext null
            val audioFile = File(filePath)
            if (!audioFile.exists()) return@withContext null

            // 1. 同名 .lrc 文件
            val lrcFile = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc")
            if (lrcFile.exists() && lrcFile.isFile) {
                try {
                    return@withContext lrcFile.readText(Charsets.UTF_8)
                } catch (_: Exception) {
                    try {
                        return@withContext lrcFile.readText(Charset.forName("GBK"))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w("LocalSongCache", "Operation failed", e)
                    }
                }
            }

            // 2. 内嵌歌词提取（优先通过纯字节轻量解析 VorbisComment / ID3v2 USLT）
            try {
                if (audioFile.length() > 64) {
                    val readLen = (audioFile.length()).coerceAtMost(512 * 1024L).toInt()
                    val headerBytes = ByteArray(readLen)
                    java.io.FileInputStream(audioFile).use { fis ->
                        var totalRead = 0
                        while (totalRead < readLen) {
                            val r = fis.read(headerBytes, totalRead, readLen - totalRead)
                            if (r <= 0) break
                            totalRead += r
                        }
                    }
                    val parsed =
                        org.melodist.api.AudioMetadataParser
                            .parse(headerBytes)
                    val lyrics =
                        parsed.lyrics ?: org.melodist.api.WebDavService
                            .extractEmbeddedLyricsFromBytes(headerBytes)
                    if (!lyrics.isNullOrBlank()) {
                        return@withContext lyrics
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("LocalSongCache", "Operation failed", e)
            }

            null
        }

    /**
     * 从系统媒体库 (MediaStore) 检索并导入时长大于 30 秒的本地音频文件，与当前曲库合并持久化。
     *
     * @param context 应用程序上下文
     * @param onProgress 可选的进度回调函数（当前曲目标题，已处理数，总数）
     * @return 合并去重后的完整本地歌曲列表
     */
    suspend fun scanSystemMediaStore(
        context: Context,
        onProgress: ((title: String, current: Int, total: Int) -> Unit)? = null,
    ): List<Song> =
        withContext(Dispatchers.IO) {
            val audioFiles = mutableListOf<File>()
            try {
                val projection =
                    arrayOf(
                        android.provider.MediaStore.Audio.Media._ID,
                        android.provider.MediaStore.Audio.Media.DATA,
                        android.provider.MediaStore.Audio.Media.TITLE,
                        android.provider.MediaStore.Audio.Media.ARTIST,
                        android.provider.MediaStore.Audio.Media.ALBUM,
                        android.provider.MediaStore.Audio.Media.DURATION,
                    )
                // 筛选音频时长大于 30 秒的正常音乐
                val selection = "${android.provider.MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${android.provider.MediaStore.Audio.Media.DURATION} >= 30000"
                val cursor =
                    context.contentResolver.query(
                        android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        selection,
                        null,
                        "${android.provider.MediaStore.Audio.Media.TITLE} ASC",
                    )

                cursor?.use { c ->
                    val dataIdx = c.getColumnIndex(android.provider.MediaStore.Audio.Media.DATA)
                    while (c.moveToNext()) {
                        if (dataIdx >= 0) {
                            val path = c.getString(dataIdx)
                            if (!path.isNullOrBlank()) {
                                val file = File(path)
                                if (file.exists() && file.isFile && SUPPORTED_AUDIO_EXT.contains(file.extension.lowercase())) {
                                    audioFiles.add(file)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying system MediaStore", e)
            }

            val total = audioFiles.size
            val existingMap = inMemoryConfig.scannedSongs.associateBy { it.path }
            val newCaches = mutableListOf<LocalSongCache>()
            val retriever = MediaMetadataRetriever()

            audioFiles.forEachIndexed { index, file ->
                val path = file.absolutePath
                val mod = file.lastModified()
                val existing = existingMap[path]
                val isCoverValid =
                    existing?.coverPath?.let { cp ->
                        val f = File(cp)
                        cp.isNotBlank() && f.exists() && f.length() > 0L && !CoverCompressor.isLowResolution(f)
                    } ?: false

                if (existing != null && existing.lastModified == mod && (existing.coverPath.isBlank() || isCoverValid)) {
                    newCaches.add(existing)
                    onProgress?.invoke(existing.title, index + 1, total)
                } else {
                    val cache = parseAudioFile(retriever, file)
                    newCaches.add(cache)
                    onProgress?.invoke(cache.title, index + 1, total)
                }
            }

            try {
                retriever.release()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("LocalSongCache", "Operation failed", e)
            }

            // 与现有库合并去重
            val mergedMap = LinkedHashMap<String, LocalSongCache>()
            for (item in inMemoryConfig.scannedSongs) {
                if (File(item.path).exists()) {
                    mergedMap[item.path] = item
                }
            }
            for (item in newCaches) {
                mergedMap[item.path] = item
            }

            val finalList = mergedMap.values.toList()
            inMemoryConfig =
                inMemoryConfig.copy(
                    scannedSongs = finalList,
                    lastScanTimeMs = System.currentTimeMillis(),
                )
            saveConfig()

            finalList.map { it.toSong() }
        }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
