package org.melodist.data.download

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.melodist.api.MusicApiService
import org.melodist.api.getPlayUrl
import org.melodist.data.AppSettingsManager
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

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

    var cacheExporter: MediaCacheExporter? = null

    // 活跃任务列表与已完成历史
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val _activeTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val activeTasks: StateFlow<List<DownloadTask>> = _activeTasks.asStateFlow()

    private val _completedTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val completedTasks: StateFlow<List<DownloadTask>> = _completedTasks.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    /**
     * 检查当前应用是否具备写入外部公共存储目录的系统权限。
     */
    fun hasStoragePermission(context: Context): Boolean = DownloadStorageStore.hasStoragePermission(context)

    fun requestStoragePermission(context: Context) {
        DownloadStorageStore.requestStoragePermission(context)
    }

    /**
     * 获取音质档位的英文标准化标签。
     */
    fun getCleanTierLabel(tier: AudioQualityTier): String = DownloadStorageStore.getCleanTierLabel(tier)

    /**
     * 根据歌曲元数据与音质档位生成下载文件的标准基础文件名。
     */
    fun getStandardBaseName(
        song: Song,
        tier: AudioQualityTier,
    ): String = DownloadStorageStore.getStandardBaseName(song, tier)

    /**
     * 检查目标下载目录中是否已存在该歌曲指定音质的完整物理音频文件。
     */
    fun findExistingFile(
        song: Song,
        tier: AudioQualityTier,
    ): File? = DownloadStorageStore.findExistingFile(song, tier)

    /**
     * 供测试或内部使用的封面清洗与压缩方法。
     */
    fun sanitizeCoverArt(rawBytes: ByteArray): ByteArray = DownloadMetadataEmbedder.sanitizeCoverArt(rawBytes)

    /**
     * 根据歌曲唯一标识查询本地已完成的有效下载音频文件与对应音质。
     */
    fun getCompletedDownload(songMid: String): Pair<File, AudioQualityTier>? {
        if (songMid.isBlank()) return null
        val task = _completedTasks.value.find { it.song.songMid == songMid } ?: return null
        val path = task.filePath
        if (path.isNotBlank()) {
            val file = File(path)
            if (file.exists() && file.isFile && file.length() > 0L) {
                return Pair(file, task.tier)
            }
        }
        return null
    }

    /**
     * 初始化离线下载管理器，加载持久化任务记录。
     */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (prefs == null) {
            prefs = app.getSharedPreferences(DownloadStorageStore.PREF_NAME, Context.MODE_PRIVATE)
            _completedTasks.value = DownloadStorageStore.loadCompletedTasks(prefs, app)
            _activeTasks.value = DownloadStorageStore.loadActiveTasks(prefs)
        }
    }

    private fun saveActiveTasks() {
        DownloadStorageStore.saveActiveTasks(prefs, _activeTasks.value)
    }

    private fun saveCompletedTasks() {
        DownloadStorageStore.saveCompletedTasks(prefs, _completedTasks.value)
    }

    /**
     * 提交歌曲离线下载请求，加入等待队列并自动触发并发调度。
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
                val remaining =
                    DownloadStorageStore.prunePreviousCopies(
                        song.songMid,
                        existing.absolutePath,
                        _completedTasks.value,
                        appContext,
                    )
                _completedTasks.value = listOf(completedTask) + remaining
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
        saveActiveTasks()
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
            updateActiveTask(task.id, persist = true) { it.copy(status = DownloadStatus.Downloading) }
            val job =
                scope.launch {
                    executeDownload(task)
                }
            activeJobs[task.id] = job
        }
    }

    private suspend fun executeDownload(task: DownloadTask) {
        val initialTask = _activeTasks.value.find { it.id == task.id }
        if (initialTask == null || initialTask.status != DownloadStatus.Downloading) {
            return
        }

        val apiService = MusicApiService()
        val song = task.song
        val preferredTier = task.tier
        var activeTempFile: File? = null

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

            coroutineContext.ensureActive()
            val preUrlCheck = _activeTasks.value.find { it.id == task.id }
            if (preUrlCheck == null || preUrlCheck.status != DownloadStatus.Downloading) {
                throw CancellationException("Download cancelled or paused before url fetch")
            }

            // 2. 常规网络直链获取
            val urlInfo = apiService.getPlayUrl(song.songMid, mediaMid = song.mediaMid, preferredTier = preferredTier)
            val downloadUrl = urlInfo.url
            if (downloadUrl.isNullOrBlank()) {
                throw IllegalStateException("获取下载直链失败 (可能需要 VIP 或版权受限)")
            }

            val actualTier = urlInfo.tier
            val finalBaseName = getStandardBaseName(song, actualTier)

            coroutineContext.ensureActive()
            val postUrlCheck = _activeTasks.value.find { it.id == task.id }
            if (postUrlCheck == null || postUrlCheck.status != DownloadStatus.Downloading) {
                throw CancellationException("Download cancelled or paused after url fetch")
            }

            // 3. 降级音质缓存复用二次检测
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

            if (finalFile.exists() && finalFile.length() > 2048L) {
                onDownloadSuccess(task.id, song, actualTier, finalFile)
                return
            }

            // 4. 流式下载音频到临时文件 .downloading (支持 HTTP Range 断点续传)
            val tempFile = File(targetDir, "$finalBaseName.$ext.downloading")
            activeTempFile = tempFile
            val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

            val reqBuilder = Request.Builder().url(downloadUrl)
            if (existingBytes > 0L) {
                reqBuilder.header("Range", "bytes=$existingBytes-")
            }
            val firstCall = httpClient.newCall(reqBuilder.build())
            activeCalls[task.id] = firstCall
            var response = firstCall.execute()

            if (response.code == 416) {
                response.close()
                if (tempFile.exists()) tempFile.delete()
                val fullRequest = Request.Builder().url(downloadUrl).build()
                val retryCall = httpClient.newCall(fullRequest)
                activeCalls[task.id] = retryCall
                response = retryCall.execute()
            }

            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP 错误: ${response.code}")
            }

            val isPartial = response.code == 206
            val body = response.body
            val contentLength = body.contentLength()
            val totalBytes =
                if (isPartial) {
                    existingBytes + contentLength
                } else if (contentLength > 0L) {
                    contentLength
                } else {
                    -1L
                }

            val appendMode = isPartial && existingBytes > 0L
            var downloaded = if (appendMode) existingBytes else 0L

            if (!appendMode && tempFile.exists()) {
                tempFile.delete()
            }

            updateActiveTask(task.id) {
                it.copy(
                    totalBytes = if (totalBytes > 0L) totalBytes else it.totalBytes,
                    downloadedBytes = downloaded,
                    progress = if (totalBytes > 0L) (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f,
                )
            }

            var lastUpdateMs = System.currentTimeMillis()
            var bytesSinceLastUpdate = 0L
            var currentSpeed = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile, appendMode).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (!coroutineContext.isActive) {
                            throw CancellationException("Download cancelled or paused during stream read")
                        }
                        val currentTask = _activeTasks.value.find { it.id == task.id }
                        if (currentTask == null || currentTask.status != DownloadStatus.Downloading) {
                            throw CancellationException("Download task was paused or removed during stream read")
                        }

                        output.write(buffer, 0, read)
                        downloaded += read
                        bytesSinceLastUpdate += read

                        val now = System.currentTimeMillis()
                        val diff = now - lastUpdateMs
                        if (diff >= 500) {
                            currentSpeed = (bytesSinceLastUpdate * 1000L) / diff
                            lastUpdateMs = now
                            bytesSinceLastUpdate = 0L

                            val progress = if (totalBytes > 0L) (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                            updateActiveTask(task.id) {
                                it.copy(
                                    downloadedBytes = downloaded,
                                    totalBytes = if (totalBytes > 0L) totalBytes else it.totalBytes,
                                    speedBytesPerSec = currentSpeed,
                                    progress = progress,
                                )
                            }
                        }
                    }
                }
            }

            if (!coroutineContext.isActive) {
                throw CancellationException("Download cancelled before finalize")
            }
            val finalCheck = _activeTasks.value.find { it.id == task.id }
            if (finalCheck == null || finalCheck.status != DownloadStatus.Downloading) {
                throw CancellationException("Task status is no longer downloading (${finalCheck?.status}), aborting finalize")
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
            val isCancelled =
                e is CancellationException ||
                    e is java.io.InterruptedIOException ||
                    (
                        e is java.io.IOException && (
                            e.message?.contains("Canceled", ignoreCase = true) == true ||
                                e.message?.contains("Socket closed", ignoreCase = true) == true
                        )
                    ) ||
                    !coroutineContext.isActive

            if (isCancelled) {
                val cur = _activeTasks.value.find { it.id == task.id }
                if (cur != null && cur.status == DownloadStatus.Downloading) {
                    updateActiveTask(task.id, persist = true) {
                        it.copy(status = DownloadStatus.Paused, speedBytesPerSec = 0L)
                    }
                }
                if (activeTempFile != null && activeTempFile.exists() && _activeTasks.value.none { it.id == task.id }) {
                    activeTempFile.delete()
                }
                return
            }

            Log.e(TAG, "Download failed for ${task.song.name}: ${e.message}", e)
            val errorDesc =
                when {
                    e is java.io.FileNotFoundException &&
                        (
                            e.message?.contains("Operation not permitted") == true ||
                                e.message?.contains("Permission denied") == true ||
                                e.message?.contains("EACCES") == true ||
                                e.message?.contains("EPERM") == true
                        ) -> {
                        "未授予存储权限(EPERM)，请开启“所有文件访问权限”"
                    }

                    e.message.isNullOrBlank() -> {
                        "未知错误"
                    }

                    else -> {
                        e.message ?: "下载失败"
                    }
                }
            updateActiveTask(task.id, persist = true) {
                it.copy(
                    status = DownloadStatus.Failed,
                    errorMessage = errorDesc,
                    speedBytesPerSec = 0L,
                )
            }
            _toastEvent.tryEmit("下载失败: $errorDesc")
        } finally {
            activeCalls.remove(task.id)
            activeJobs.remove(task.id)
            val current = _activeTasks.value.find { it.id == task.id }
            if (current != null && current.status == DownloadStatus.Downloading) {
                updateActiveTask(task.id, persist = true) {
                    it.copy(status = DownloadStatus.Paused, speedBytesPerSec = 0L)
                }
            }
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
        if (!coroutineContext.isActive) return
        val preFinalizeTask = _activeTasks.value.find { it.id == task.id }
        if (preFinalizeTask == null || preFinalizeTask.status != DownloadStatus.Downloading) return

        updateActiveTask(task.id) {
            it.copy(
                downloadedBytes = file.length(),
                totalBytes = file.length(),
                progress = 1f,
                speedBytesPerSec = 0L,
            )
        }

        // 将元数据写入音频文件
        DownloadMetadataEmbedder.embedMetadata(
            taskId = task.id,
            song = song,
            tier = tier,
            file = file,
            apiService = apiService,
            httpClient = httpClient,
            activeCalls = activeCalls,
        )

        if (file.extension.equals("flac", ignoreCase = true)) {
            FlacTailSanitizer.sanitize(file)
        }

        if (!coroutineContext.isActive) return
        val postMetadataTask = _activeTasks.value.find { it.id == task.id }
        if (postMetadataTask == null || postMetadataTask.status != DownloadStatus.Downloading) return

        // 通知系统 MediaStore 刷新
        DownloadStorageStore.scanFiles(appContext, arrayOf(file.absolutePath))

        onDownloadSuccess(task.id, song, tier, file)
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
        saveActiveTasks()
        val remaining =
            DownloadStorageStore.prunePreviousCopies(
                song.songMid,
                file.absolutePath,
                _completedTasks.value,
                appContext,
            )
        _completedTasks.value = listOf(completedTask) + remaining
        saveCompletedTasks()

        _toastEvent.tryEmit("下载完成: ${file.name}")
    }

    private fun updateActiveTask(
        taskId: String,
        persist: Boolean = false,
        transform: (DownloadTask) -> DownloadTask,
    ) {
        _activeTasks.value =
            _activeTasks.value.map {
                if (it.id == taskId) transform(it) else it
            }
        if (persist) {
            saveActiveTasks()
        }
    }

    /**
     * 暂停正在执行或排队中的下载任务。
     */
    fun pauseDownload(taskId: String) {
        try {
            activeCalls[taskId]?.cancel()
        } catch (_: Exception) {
        }
        activeCalls.remove(taskId)
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        updateActiveTask(taskId, persist = true) {
            it.copy(status = DownloadStatus.Paused, speedBytesPerSec = 0L)
        }
        scheduleNextDownloads()
    }

    /**
     * 恢复暂停或失败的下载任务，重置错误状态并重新排队。
     */
    fun resumeDownload(taskId: String) {
        if (_activeTasks.value.none { it.id == taskId }) return
        updateActiveTask(taskId, persist = true) {
            it.copy(status = DownloadStatus.Pending, errorMessage = null, speedBytesPerSec = 0L)
        }
        scheduleNextDownloads()
    }

    /**
     * 取消下载任务并从活跃任务列表中移除，同步清理临时未完成文件。
     */
    fun cancelDownload(taskId: String) {
        try {
            activeCalls[taskId]?.cancel()
        } catch (_: Exception) {
        }
        activeCalls.remove(taskId)
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        val task = _activeTasks.value.find { it.id == taskId }
        _activeTasks.value = _activeTasks.value.filter { it.id != taskId }
        saveActiveTasks()
        if (task != null) {
            val targetDir = AppSettingsManager.getEffectiveDownloadDirectory()
            DownloadStorageStore.cleanTaskTempFiles(task.song, targetDir)
        }
        scheduleNextDownloads()
    }

    /**
     * 暂停所有正在下载或排队中的任务。
     */
    fun pauseAll() {
        for ((_, call) in activeCalls) {
            try {
                call.cancel()
            } catch (_: Exception) {
            }
        }
        activeCalls.clear()
        for ((_, job) in activeJobs) {
            job.cancel()
        }
        activeJobs.clear()
        _activeTasks.value =
            _activeTasks.value.map { task ->
                if (task.status == DownloadStatus.Downloading || task.status == DownloadStatus.Pending) {
                    task.copy(status = DownloadStatus.Paused, speedBytesPerSec = 0L)
                } else {
                    task
                }
            }
        saveActiveTasks()
    }

    /**
     * 恢复所有已暂停或失败的下载任务重新进入队列。
     */
    fun resumeAll() {
        _activeTasks.value =
            _activeTasks.value.map { task ->
                if (task.status == DownloadStatus.Paused || task.status == DownloadStatus.Failed) {
                    task.copy(status = DownloadStatus.Pending, errorMessage = null, speedBytesPerSec = 0L)
                } else {
                    task
                }
            }
        saveActiveTasks()
        scheduleNextDownloads()
    }

    /**
     * 取消并移除所有正在下载与排队中的任务，清理所有未完成的临时文件。
     */
    fun cancelAll() {
        for ((_, call) in activeCalls) {
            try {
                call.cancel()
            } catch (_: Exception) {
            }
        }
        activeCalls.clear()
        for ((_, job) in activeJobs) {
            job.cancel()
        }
        activeJobs.clear()

        val tasksToClean = _activeTasks.value
        _activeTasks.value = emptyList()
        saveActiveTasks()

        val targetDir = AppSettingsManager.getEffectiveDownloadDirectory()
        DownloadStorageStore.cleanAllTempFiles(tasksToClean, targetDir)

        _toastEvent.tryEmit("已清空所有下载任务")
    }

    /**
     * 删除已完成的下载历史记录，可选择是否物理删除磁盘文件。
     */
    fun deleteDownloaded(
        taskId: String,
        deleteFile: Boolean = true,
    ) {
        val task = _completedTasks.value.find { it.id == taskId }
        if (task != null && deleteFile && task.filePath.isNotBlank()) {
            val f = File(task.filePath)
            if (f.exists()) f.delete()
            val lrc = File(task.filePath.substringBeforeLast(".") + ".lrc")
            if (lrc.exists()) lrc.delete()
        }
        _completedTasks.value = _completedTasks.value.filter { it.id != taskId }
        saveCompletedTasks()
    }

    /**
     * 根据音频文件路径清理已完成下载记录。
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
                val lrc = File(filePath.substringBeforeLast(".") + ".lrc")
                if (lrc.exists()) lrc.delete()
            }
            _completedTasks.value = _completedTasks.value.filter { it.filePath != filePath }
            saveCompletedTasks()
        }
    }

    /**
     * 根据歌曲唯一标识清理已完成下载记录。
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
                        val lrc = File(task.filePath.substringBeforeLast(".") + ".lrc")
                        if (lrc.exists()) lrc.delete()
                    }
                }
            }
            _completedTasks.value = _completedTasks.value.filter { it.song.songMid != songMid }
            saveCompletedTasks()
        }
    }
}
