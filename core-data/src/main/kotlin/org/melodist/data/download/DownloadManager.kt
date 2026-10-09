package org.melodist.data.download

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.melodist.api.MusicApiService
import org.melodist.api.getLyrics
import org.melodist.api.getPlayUrl
import org.melodist.data.AppSettingsManager
import org.melodist.model.AudioQualityTier
import org.melodist.model.CoverUrlResolver
import org.melodist.model.Song
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class DownloadStatus {
    Pending,
    Downloading,
    Paused,
    Completed,
    Failed,
}

interface MediaCacheExporter {
    fun isTierFullyCached(
        songMid: String,
        tier: AudioQualityTier,
    ): Boolean

    fun exportCompleteCache(
        songMid: String,
        tier: AudioQualityTier,
        targetDir: File,
        baseFileName: String,
    ): File?
}

@Serializable
data class DownloadTask(
    val id: String,
    val song: Song,
    val tier: AudioQualityTier,
    val status: DownloadStatus = DownloadStatus.Pending,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val filePath: String = "",
    val errorMessage: String? = null,
    val createTimeMs: Long = System.currentTimeMillis(),
)

object DownloadManager {
    private const val TAG = "DownloadManager"
    private const val PREF_NAME = "melodist_downloads"
    private const val KEY_COMPLETED = "completed_downloads_json"
    private const val MAX_CONCURRENT_DOWNLOADS = 5

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val httpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    var cacheExporter: MediaCacheExporter? = null

    // 活跃任务列表与已完成历史
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val _activeTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val activeTasks: StateFlow<List<DownloadTask>> = _activeTasks.asStateFlow()

    private val _completedTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val completedTasks: StateFlow<List<DownloadTask>> = _completedTasks.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    /**
     * 根据歌曲唯一标识查询本地已完成的有效下载音频文件与对应音质。
     *
     * @param songMid 歌曲唯一标识符
     * @return 包含本地目标 File 与音质档位的二元组；未下载或文件被移除时返回 null
     */
    fun getCompletedDownload(songMid: String): Pair<java.io.File, AudioQualityTier>? {
        if (songMid.isBlank()) return null
        val task = _completedTasks.value.find { it.song.songMid == songMid } ?: return null
        val path = task.filePath
        if (path.isNotBlank()) {
            val file = java.io.File(path)
            if (file.exists() && file.isFile && file.length() > 0L) {
                return Pair(file, task.tier)
            }
        }
        return null
    }

    /**
     * 初始化离线下载管理器，加载持久化已完成任务记录。
     *
     * @param context 应用程序上下文
     */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (prefs == null) {
            prefs = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            loadCompletedTasks()
        }
    }

    /**
     * 检查当前应用是否具备写入外部公共存储目录的系统权限。
     *
     * @param context 应用程序上下文
     * @return 具备权限返回 true，否则返回 false
     */
    fun hasStoragePermission(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    fun requestStoragePermission(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                val intent =
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                context.startActivity(intent)
            } catch (_: Exception) {
                val intent =
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                context.startActivity(intent)
            }
        } else {
            if (context is android.app.Activity) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    context,
                    arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    1001,
                )
            }
        }
    }

    private fun loadCompletedTasks() {
        val raw = prefs?.getString(KEY_COMPLETED, null)
        if (!raw.isNullOrBlank()) {
            try {
                val list = json.decodeFromString<List<DownloadTask>>(raw)
                _completedTasks.value = list.filter { File(it.filePath).exists() }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load completed downloads", e)
            }
        }
    }

    private fun saveCompletedTasks() {
        try {
            val raw = json.encodeToString(_completedTasks.value)
            prefs?.edit()?.putString(KEY_COMPLETED, raw)?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save completed downloads", e)
        }
    }

    /**
     * 获取音质档位的英文标准化标签（如 Hi-Res, Master, SQ）。
     *
     * @param tier 音质档位枚举
     * @return 英文标准缩写名称
     */
    fun getCleanTierLabel(tier: AudioQualityTier): String =
        when (tier) {
            AudioQualityTier.HiRes -> "Hi-Res"
            AudioQualityTier.Master -> "Master"
            AudioQualityTier.SQ -> "SQ"
            AudioQualityTier.HQ -> "HQ"
            AudioQualityTier.Standard -> "Standard"
            AudioQualityTier.Atmos -> "Atmos"
            AudioQualityTier.Dolby -> "Dolby"
            AudioQualityTier.Premium -> "Premium"
        }

    /**
     * 根据歌曲元数据与音质档位生成下载文件的标准基础文件名（格式为 `歌手 - 歌名 - 音质`，不含扩展名）。
     *
     * @param song 歌曲实体
     * @param tier 音质档位枚举
     * @return 过滤非法字符后的安全文件名基础字符串
     */
    fun getStandardBaseName(
        song: Song,
        tier: AudioQualityTier,
    ): String {
        val cleanTitle = song.name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        val cleanSinger = song.singer.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        val tierLabel = getCleanTierLabel(tier)
        return "$cleanSinger - $cleanTitle - $tierLabel"
    }

    /**
     * 检查目标下载目录中是否已存在该歌曲指定音质的完整物理音频文件。
     *
     * @param song 歌曲实体
     * @param tier 音质档位枚举
     * @return 已存在的完整音频 File 对象；不存在返回 null
     */
    fun findExistingFile(
        song: Song,
        tier: AudioQualityTier,
    ): File? {
        val dir = AppSettingsManager.getEffectiveDownloadDirectory()
        val baseName = getStandardBaseName(song, tier)
        val possibleExts = listOf("flac", "mp3", "m4a", "wav")
        for (ext in possibleExts) {
            val candidate = File(dir, "$baseName.$ext")
            if (candidate.exists() && candidate.isFile && candidate.length() > 2048L) {
                return candidate
            }
        }
        return null
    }

    /**
     * 提交歌曲离线下载请求，加入等待队列并自动触发并发调度。
     *
     * @param song 目标下载歌曲实体
     * @param preferredTier 指定期望下载音质；若为 null 则采用全局首选音质配置
     */
    fun downloadSong(
        song: Song,
        preferredTier: AudioQualityTier? = null,
    ) {
        if (song.songMid.isBlank()) {
            _toastEvent.tryEmit("该歌曲信息不完整，无法下载")
            return
        }

        val targetTier = preferredTier ?: AppSettingsManager.settings.value.preferredQualityTier
        val taskId = "${song.songMid}_${targetTier.name}"

        // 1. 防重复下载检测：检查磁盘中是否已有完整同名文件
        val existing = findExistingFile(song, targetTier)
        if (existing != null) {
            _toastEvent.tryEmit("文件已存在：${existing.name}")
            // 若历史列表中没有，自动补全到已完成列表
            if (_completedTasks.value.none { it.id == taskId }) {
                val completedTask =
                    DownloadTask(
                        id = taskId,
                        song = song,
                        tier = targetTier,
                        status = DownloadStatus.Completed,
                        progress = 1f,
                        downloadedBytes = existing.length(),
                        totalBytes = existing.length(),
                        filePath = existing.absolutePath,
                    )
                _completedTasks.value = listOf(completedTask) + _completedTasks.value
                saveCompletedTasks()
            }
            return
        }

        if (_activeTasks.value.any { it.id == taskId && (it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Pending) }) {
            _toastEvent.tryEmit("已在下载队列中")
            return
        }

        val initialTask =
            DownloadTask(
                id = taskId,
                song = song,
                tier = targetTier,
                status = DownloadStatus.Pending,
            )

        _activeTasks.value = _activeTasks.value.filter { it.id != taskId } + initialTask
        _toastEvent.tryEmit("已添加至下载队列: ${song.name}")

        scheduleNextDownloads()
    }

    @Synchronized
    private fun scheduleNextDownloads() {
        val currentDownloading = _activeTasks.value.count { it.status == DownloadStatus.Downloading }
        val availableSlots = MAX_CONCURRENT_DOWNLOADS - currentDownloading
        if (availableSlots <= 0) return

        val tasksToStart =
            _activeTasks.value
                .filter { it.status == DownloadStatus.Pending }
                .take(availableSlots)

        for (task in tasksToStart) {
            updateActiveTask(task.id) { it.copy(status = DownloadStatus.Downloading) }
            val job =
                scope.launch {
                    executeDownload(task)
                }
            activeJobs[task.id] = job
        }
    }

    private suspend fun executeDownload(task: DownloadTask) {
        updateActiveTask(task.id) { it.copy(status = DownloadStatus.Downloading) }

        val apiService = MusicApiService()
        val song = task.song
        val preferredTier = task.tier

        try {
            val targetDir = AppSettingsManager.getEffectiveDownloadDirectory()
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val exporter = cacheExporter
            if (exporter != null && exporter.isTierFullyCached(song.songMid, preferredTier)) {
                val baseName = getStandardBaseName(song, preferredTier)
                val exportedFile = exporter.exportCompleteCache(song.songMid, preferredTier, targetDir, baseName)
                if (exportedFile != null && exportedFile.exists() && exportedFile.length() > 2048L) {
                    Log.i(TAG, "Reusing complete cached audio for download: ${exportedFile.name} (${exportedFile.length()} bytes)")
                    finalizeDownload(task, song, preferredTier, exportedFile, apiService)
                    return
                }
            }

            // 2. 若无缓存或缓存导出未通过完整性校验，执行常规网络直链获取
            val urlInfo = apiService.getPlayUrl(song.songMid, mediaMid = song.mediaMid, preferredTier = preferredTier)
            val downloadUrl = urlInfo.url
            if (downloadUrl.isNullOrBlank()) {
                throw IllegalStateException("获取下载直链失败 (可能需要 VIP 或版权受限)")
            }

            val actualTier = urlInfo.tier
            val finalBaseName = getStandardBaseName(song, actualTier)

            // 3. 若实际音质不同于请求音质（如降级），再次检查实际音质是否在缓存中完全就绪
            if (actualTier != preferredTier && exporter != null && exporter.isTierFullyCached(song.songMid, actualTier)) {
                val exportedFile = exporter.exportCompleteCache(song.songMid, actualTier, targetDir, finalBaseName)
                if (exportedFile != null && exportedFile.exists() && exportedFile.length() > 2048L) {
                    Log.i(TAG, "Reusing complete cached audio for actualTier: ${exportedFile.name} (${exportedFile.length()} bytes)")
                    finalizeDownload(task, song, actualTier, exportedFile, apiService)
                    return
                }
            }

            val ext =
                when {
                    downloadUrl.contains(".flac", ignoreCase = true) || actualTier == AudioQualityTier.SQ || actualTier == AudioQualityTier.HiRes -> "flac"
                    downloadUrl.contains(".m4a", ignoreCase = true) -> "m4a"
                    else -> "mp3"
                }

            val finalFile = File(targetDir, "$finalBaseName.$ext")

            // 再次检查目标文件
            if (finalFile.exists() && finalFile.length() > 2048L) {
                onDownloadSuccess(task.id, song, actualTier, finalFile)
                return
            }

            // 4. 流式下载音频到临时文件 .downloading
            val tempFile = File(targetDir, "$finalBaseName.$ext.downloading")

            val request = Request.Builder().url(downloadUrl).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP 错误: ${response.code}")
            }

            val body = response.body
            val totalBytes = body.contentLength()

            updateActiveTask(task.id) { it.copy(totalBytes = totalBytes) }

            var downloaded = 0L
            var lastUpdateMs = System.currentTimeMillis()
            var bytesSinceLastUpdate = 0L
            var currentSpeed = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        bytesSinceLastUpdate += read

                        val now = System.currentTimeMillis()
                        val diff = now - lastUpdateMs
                        if (diff >= 500) {
                            currentSpeed = (bytesSinceLastUpdate * 1000L) / diff
                            lastUpdateMs = now
                            bytesSinceLastUpdate = 0L

                            val progress = if (totalBytes > 0) downloaded.toFloat() / totalBytes else 0f
                            updateActiveTask(task.id) {
                                it.copy(
                                    downloadedBytes = downloaded,
                                    speedBytesPerSec = currentSpeed,
                                    progress = progress,
                                )
                            }
                        }
                    }
                }
            }

            // 5. 原子重命名为目标文件
            if (tempFile.exists()) {
                if (finalFile.exists()) finalFile.delete()
                val renamed = tempFile.renameTo(finalFile)
                if (!renamed) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }
            }

            finalizeDownload(task, song, actualTier, finalFile, apiService)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${task.song.name}: ${e.message}", e)
            val errorDesc =
                when {
                    e is java.io.FileNotFoundException &&
                        (
                            e.message?.contains("Operation not permitted") == true ||
                                e.message?.contains("Permission denied") == true ||
                                e.message?.contains("EACCES") == true ||
                                e.message?.contains("EPERM") == true
                        ) ->
                        "未授予存储权限(EPERM)，请开启“所有文件访问权限”"
                    e.message.isNullOrBlank() -> "未知错误"
                    else -> e.message ?: "下载失败"
                }
            updateActiveTask(task.id) {
                it.copy(
                    status = DownloadStatus.Failed,
                    errorMessage = errorDesc,
                    speedBytesPerSec = 0L,
                )
            }
            _toastEvent.tryEmit("下载失败: $errorDesc")
        } finally {
            activeJobs.remove(task.id)
            scheduleNextDownloads()
        }
    }

    private suspend fun finalizeDownload(
        task: DownloadTask,
        song: Song,
        tier: AudioQualityTier,
        file: File,
        apiService: MusicApiService,
    ) {
        updateActiveTask(task.id) {
            it.copy(
                downloadedBytes = file.length(),
                totalBytes = file.length(),
                progress = 1f,
                speedBytesPerSec = 0L,
            )
        }

        // 将元数据（歌曲名、歌手名、专辑名、最高清原图、双语歌词）直接内嵌写入音频文件
        embedMetadata(song, tier, file, apiService)

        // 通知系统 MediaStore 刷新
        appContext?.let { ctx ->
            MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null, null)
        }

        onDownloadSuccess(task.id, song, tier, file)
    }

    private const val MAX_RAW_COVER_BYTES = (2.5 * 1024 * 1024).toLong() // 2.5 MB
    private const val COVER_COMPRESSION_QUALITY = 88

    fun sanitizeCoverArt(rawBytes: ByteArray): ByteArray {
        if (rawBytes.size <= MAX_RAW_COVER_BYTES) {
            return rawBytes
        }
        return try {
            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
            try {
                val bos = ByteArrayOutputStream(rawBytes.size / 4)
                val success = bitmap.compress(Bitmap.CompressFormat.JPEG, COVER_COMPRESSION_QUALITY, bos)
                if (success) {
                    val compressed = bos.toByteArray()
                    if (compressed.isNotEmpty() && compressed.size < rawBytes.size) {
                        Log.i(TAG, "Compressed oversized cover from ${rawBytes.size} bytes to ${compressed.size} bytes (${bitmap.width}x${bitmap.height})")
                        return compressed
                    }
                }
                rawBytes
            } finally {
                bitmap.recycle()
            }
        } catch (t: Throwable) {
            try {
                Log.w(TAG, "Failed to compress oversized cover art, keeping raw bytes", t)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.w("DownloadStatus", "Operation failed", e)
            }
            rawBytes
        }
    }

    private suspend fun embedMetadata(
        song: Song,
        tier: AudioQualityTier,
        file: File,
        apiService: MusicApiService,
    ) {
        var coverBytes: ByteArray? = null
        var lyricsMerged: String? = null

        val isStandardOrHq = tier == AudioQualityTier.Standard || tier == AudioQualityTier.HQ
        val candidateUrls =
            if (isStandardOrHq) {
                listOf(
                    CoverUrlResolver.replaceDimension(song.coverUrl, 1200),
                    CoverUrlResolver.replaceDimension(song.coverUrl, 800),
                    song.coverUrl,
                )
            } else {
                song.rawCoverCandidates.ifEmpty {
                    listOf(
                        CoverUrlResolver.getRawUrlOnly(song.coverUrl) ?: "",
                        CoverUrlResolver.replaceDimension(song.coverUrl, 1200),
                        song.coverUrl,
                    )
                }
            }.filter { it.isNotBlank() && !it.startsWith("file://") }.distinct()

        for (candidateUrl in candidateUrls) {
            try {
                val coverReq = Request.Builder().url(candidateUrl).build()
                val coverResp = httpClient.newCall(coverReq).execute()
                if (coverResp.isSuccessful) {
                    val bodyBytes = coverResp.body.bytes()
                    if (bodyBytes.isNotEmpty()) {
                        coverBytes = sanitizeCoverArt(bodyBytes)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch candidate cover from $candidateUrl", e)
            }
        }

        try {
            val cachedLyrics =
                org.melodist.data.LyricCacheManager
                    .getLyrics(song.songMid)
            val lyricLines = cachedLyrics ?: apiService.getLyrics(song.songMid, song.songId)
            if (lyricLines.isNotEmpty()) {
                val sb = StringBuilder()
                for (line in lyricLines) {
                    val timeStr = formatLrcTime(line.timestampMs)
                    sb.append("[$timeStr]${line.text}\n")
                    if (line.hasTranslation) {
                        sb.append("[$timeStr]${line.transText}\n")
                    }
                }
                lyricsMerged = sb.toString().trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching lyrics for download", e)
        }

        try {
            val metadataPayload =
                AudioMetadataWriter.MetadataPayload(
                    title = song.name,
                    artist = song.singer,
                    album = song.album.ifBlank { song.name },
                    lyrics = lyricsMerged,
                    coverBytes = coverBytes,
                    coverMime = "image/jpeg",
                )
            AudioMetadataWriter.writeMetadata(file, metadataPayload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to embed metadata, preserving raw audio: ${e.message}")
        }
    }

    private fun onDownloadSuccess(
        taskId: String,
        song: Song,
        tier: AudioQualityTier,
        file: File,
    ) {
        val completedTask =
            DownloadTask(
                id = taskId,
                song = song,
                tier = tier,
                status = DownloadStatus.Completed,
                progress = 1f,
                downloadedBytes = file.length(),
                totalBytes = file.length(),
                filePath = file.absolutePath,
            )

        _activeTasks.value = _activeTasks.value.filter { it.id != taskId }
        _completedTasks.value = listOf(completedTask) + _completedTasks.value.filter { it.id != taskId }
        saveCompletedTasks()

        _toastEvent.tryEmit("下载完成: ${file.name}")
    }

    private fun updateActiveTask(
        taskId: String,
        transform: (DownloadTask) -> DownloadTask,
    ) {
        _activeTasks.value =
            _activeTasks.value.map {
                if (it.id == taskId) transform(it) else it
            }
    }

    /**
     * 暂停正在执行或排队中的下载任务。
     *
     * @param taskId 下载任务唯一 ID
     */
    fun pauseDownload(taskId: String) {
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        updateActiveTask(taskId) {
            it.copy(status = DownloadStatus.Paused, speedBytesPerSec = 0L)
        }
        scheduleNextDownloads()
    }

    /**
     * 恢复暂停或失败的下载任务，重置错误状态并重新排队。
     *
     * @param taskId 下载任务唯一 ID
     */
    fun resumeDownload(taskId: String) {
        val task = _activeTasks.value.find { it.id == taskId } ?: return
        updateActiveTask(taskId) {
            it.copy(status = DownloadStatus.Pending, errorMessage = null)
        }
        scheduleNextDownloads()
    }

    /**
     * 取消下载任务并从活跃任务列表中移除。
     *
     * @param taskId 下载任务唯一 ID
     */
    fun cancelDownload(taskId: String) {
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        _activeTasks.value = _activeTasks.value.filter { it.id != taskId }
        scheduleNextDownloads()
    }

    /**
     * 删除已完成的下载历史记录，可选择是否物理删除磁盘文件。
     *
     * @param taskId 下载任务唯一 ID
     * @param deleteFile 是否同步物理删除目标音频文件，默认 true
     */
    fun deleteDownloaded(
        taskId: String,
        deleteFile: Boolean = true,
    ) {
        val task = _completedTasks.value.find { it.id == taskId }
        if (task != null && deleteFile && task.filePath.isNotBlank()) {
            val f = File(task.filePath)
            if (f.exists()) f.delete()
        }
        _completedTasks.value = _completedTasks.value.filter { it.id != taskId }
        saveCompletedTasks()
    }

    /**
     * 根据音频文件路径清理已完成下载记录。
     *
     * @param filePath 音频文件绝对路径
     * @param deleteFile 是否同步物理删除目标音频文件，默认 true
     */
    fun deleteDownloadedByPath(
        filePath: String,
        deleteFile: Boolean = true,
    ) {
        val matching = _completedTasks.value.filter { it.filePath == filePath }
        if (matching.isNotEmpty()) {
            if (deleteFile && filePath.isNotBlank()) {
                val f = File(filePath)
                if (f.exists()) f.delete()
            }
            _completedTasks.value = _completedTasks.value.filter { it.filePath != filePath }
            saveCompletedTasks()
        }
    }

    /**
     * 根据歌曲唯一标识清理已完成下载记录。
     *
     * @param songMid 歌曲唯一标识符
     * @param deleteFile 是否同步物理删除目标音频文件，默认 true
     */
    fun deleteDownloadedBySongMid(
        songMid: String,
        deleteFile: Boolean = true,
    ) {
        val matching = _completedTasks.value.filter { it.song.songMid == songMid }
        if (matching.isNotEmpty()) {
            if (deleteFile) {
                matching.forEach { task ->
                    if (task.filePath.isNotBlank()) {
                        val f = File(task.filePath)
                        if (f.exists()) f.delete()
                    }
                }
            }
            _completedTasks.value = _completedTasks.value.filter { it.song.songMid != songMid }
            saveCompletedTasks()
        }
    }

    private fun formatLrcTime(timeMs: Long): String {
        val totalSec = timeMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val ms = (timeMs % 1000) / 10
        return "%02d:%02d.%02d".format(min, sec, ms)
    }
}
