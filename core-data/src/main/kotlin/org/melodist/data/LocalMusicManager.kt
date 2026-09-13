package org.melodist.data

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.melodist.model.AudioQualityTier
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
    val hasLrc: Boolean = false,
    val lrcPath: String = "",
    val lastModified: Long = 0L,
) {
    fun toSong(): Song {
        val lower = path.lowercase()
        val tier =
            when {
                lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".ape") -> AudioQualityTier.SQ
                lower.endsWith(".dsf") || lower.endsWith(".dff") -> AudioQualityTier.HiRes
                else -> AudioQualityTier.HQ
            }
        return Song(
            songId = id.hashCode().toLong(),
            songMid = "local_$id",
            name = title,
            singer = artist,
            album = album,
            durationSeconds = durationSeconds,
            currentTier = tier,
            coverUrl = if (coverPath.isNotBlank()) "file://$coverPath" else "",
            localFilePath = path,
        )
    }
}

@Serializable
data class LocalMusicConfig(
    val scannedSongs: List<LocalSongCache> = emptyList(),
    val lastDirectory: String = "",
    val lastScanTimeMs: Long = 0L,
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

    private val SUPPORTED_AUDIO_EXT =
        setOf(
            "mp3",
            "flac",
            "wav",
            "m4a",
            "aac",
            "ogg",
            "ape",
            "dsf",
            "dff",
            "opus",
            "wma",
        )

    private var prefs: SharedPreferences? = null
    private var coversDir: File? = null
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }
    private var inMemoryConfig = LocalMusicConfig()

    fun init(context: Context) {
        if (prefs == null) {
            val appCtx = context.applicationContext
            prefs = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            coversDir = File(appCtx.cacheDir, "local_covers").apply { mkdirs() }
            loadConfig()
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
    }

    private fun saveConfig() {
        try {
            val raw = json.encodeToString(inMemoryConfig)
            prefs?.edit()?.putString(KEY_CONFIG, raw)?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save local music config", e)
        }
    }

    fun getScannedSongs(): List<Song> = inMemoryConfig.scannedSongs.map { it.toSong() }

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

    fun clearLibrary() {
        inMemoryConfig = inMemoryConfig.copy(scannedSongs = emptyList(), lastScanTimeMs = 0L)
        saveConfig()
    }

    /**
     * 检测所有可用存储源：内部存储、标准音乐/下载目录、外置 U 盘/移动硬盘挂载点
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
     * 浏览指定目录下的文件夹与音频文件
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
     * 从文件名推断 (歌曲名, 歌手名)
     */
    fun inferTitleArtist(fileName: String): Pair<String, String> {
        val clean = fileName.substringBeforeLast('.')
        return if (clean.contains(" - ")) {
            val parts = clean.split(" - ", limit = 2)
            val artist = parts[0].trim()
            val title = parts[1].trim()
            Pair(title, artist)
        } else {
            Pair(clean.trim(), "未知歌手")
        }
    }

    /**
     * 递归扫描指定目录，解析 ID3 元数据并持久化
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

                if (existing != null && existing.lastModified == mod) {
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
            } catch (_: Exception) {
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
        var title = inferredTitle
        var artist = inferredArtist
        var album = file.parentFile?.name ?: "本地音乐"
        var durationSec = 0
        var coverPath = ""

        try {
            retriever.setDataSource(file.absolutePath)
            val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val metaDur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)

            if (!metaTitle.isNullOrBlank()) title = metaTitle.trim()
            if (!metaArtist.isNullOrBlank()) artist = metaArtist.trim()
            if (!metaAlbum.isNullOrBlank()) album = metaAlbum.trim()
            if (!metaDur.isNullOrBlank()) {
                durationSec = (metaDur.toLongOrNull() ?: 0L).toInt() / 1000
            }

            // 提取内置封面并持久化至 cache/local_covers
            val picBytes = retriever.embeddedPicture
            if (picBytes != null && picBytes.isNotEmpty() && coversDir != null) {
                val hash = md5(file.absolutePath)
                val coverFile = File(coversDir, "cover_$hash.jpg")
                if (!coverFile.exists() || coverFile.length() == 0L) {
                    coverFile.writeBytes(picBytes)
                }
                coverPath = coverFile.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting metadata for ${file.name}", e)
        }

        // 检查同目录下同名 .lrc 歌词
        val lrcFile = File(file.parentFile, "${file.nameWithoutExtension}.lrc")
        val hasLrc = lrcFile.exists() && lrcFile.isFile && lrcFile.length() > 0L

        val id = md5(file.absolutePath)
        return LocalSongCache(
            id = id,
            path = file.absolutePath,
            title = title,
            artist = artist,
            album = album,
            durationSeconds = durationSec,
            coverPath = coverPath,
            hasLrc = hasLrc,
            lrcPath = if (hasLrc) lrcFile.absolutePath else "",
            lastModified = file.lastModified(),
        )
    }

    /**
     * 读取歌曲歌词：优先读取同名 .lrc，次选内嵌歌词
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
                    } catch (_: Exception) {
                    }
                }
            }

            // 2. 内嵌歌词提取
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(audioFile.absolutePath)
                // 部分格式支持 METADATA_KEY_LYRICS
                val embedded =
                    if (Build.VERSION.SDK_INT >= 29) {
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPILATION)
                    } else {
                        null
                    }
                retriever.release()
                if (!embedded.isNullOrBlank()) return@withContext embedded
            } catch (_: Exception) {
            }

            null
        }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
