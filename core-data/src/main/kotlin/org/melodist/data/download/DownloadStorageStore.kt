package org.melodist.data.download

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.melodist.data.AppSettingsManager
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import java.io.File

internal object DownloadStorageStore {
    private const val TAG = "DownloadStorageStore"
    const val PREF_NAME = "melodist_downloads"
    const val KEY_COMPLETED = "completed_downloads_json"
    const val KEY_ACTIVE_TASKS = "active_downloads_json"

    private val json = Json { ignoreUnknownKeys = true }

    fun hasStoragePermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    fun requestStoragePermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent =
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                context.startActivity(intent)
            } catch (_: Exception) {
                val intent =
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                context.startActivity(intent)
            }
        } else {
            if (context is android.app.Activity) {
                ActivityCompat.requestPermissions(
                    context,
                    arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    1001,
                )
            }
        }
    }

    fun getCleanTierLabel(tier: AudioQualityTier): String =
        when (tier) {
            AudioQualityTier.HiRes -> "Hi-Res"
            AudioQualityTier.Master -> "Master"
            AudioQualityTier.SQ -> "SQ"
            AudioQualityTier.HQ -> "HQ"
            AudioQualityTier.Standard -> "Standard"
            AudioQualityTier.Dolby -> "Dolby"
            AudioQualityTier.DtsX -> "DTS_X"
            AudioQualityTier.Atmos -> "Atmos"
            AudioQualityTier.Premium -> "Premium"
        }

    fun getStandardBaseName(
        song: Song,
        tier: AudioQualityTier,
    ): String {
        val cleanTitle = song.name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        val cleanSinger = song.singer.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        val tierLabel = getCleanTierLabel(tier)
        return "$cleanSinger - $cleanTitle - $tierLabel"
    }

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

    fun scanFiles(
        context: Context?,
        paths: Array<String>,
    ) {
        if (context != null && paths.isNotEmpty()) {
            MediaScannerConnection.scanFile(context, paths, null, null)
        }
    }

    fun loadCompletedTasks(
        prefs: SharedPreferences?,
        context: Context?,
    ): List<DownloadTask> {
        val raw = prefs?.getString(KEY_COMPLETED, null)
        if (!raw.isNullOrBlank()) {
            try {
                val list = json.decodeFromString<List<DownloadTask>>(raw)
                val validList = list.filter { File(it.filePath).exists() }
                return deduplicateCompletedTasks(validList, prefs, context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load completed downloads", e)
            }
        }
        return emptyList()
    }

    fun saveCompletedTasks(
        prefs: SharedPreferences?,
        tasks: List<DownloadTask>,
    ) {
        try {
            val raw = json.encodeToString(tasks)
            prefs?.edit()?.putString(KEY_COMPLETED, raw)?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save completed downloads", e)
        }
    }

    fun loadActiveTasks(prefs: SharedPreferences?): List<DownloadTask> {
        val raw = prefs?.getString(KEY_ACTIVE_TASKS, null)
        if (!raw.isNullOrBlank()) {
            try {
                val list = json.decodeFromString<List<DownloadTask>>(raw)
                return list.map { task ->
                    if (task.status == DownloadStatus.Downloading) {
                        task.copy(status = DownloadStatus.Paused, speedBytesPerSec = 0L)
                    } else {
                        task.copy(speedBytesPerSec = 0L)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load active downloads", e)
            }
        }
        return emptyList()
    }

    fun saveActiveTasks(
        prefs: SharedPreferences?,
        tasks: List<DownloadTask>,
    ) {
        try {
            val list =
                tasks.map {
                    if (it.speedBytesPerSec != 0L) it.copy(speedBytesPerSec = 0L) else it
                }
            val raw = json.encodeToString(list)
            prefs?.edit()?.putString(KEY_ACTIVE_TASKS, raw)?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save active downloads", e)
        }
    }

    fun deduplicateCompletedTasks(
        tasks: List<DownloadTask>,
        prefs: SharedPreferences?,
        context: Context?,
    ): List<DownloadTask> {
        val seenMids = mutableSetOf<String>()
        val kept = mutableListOf<DownloadTask>()
        val obsoletePaths = mutableListOf<String>()

        for (task in tasks) {
            val mid = task.song.songMid
            if (mid.isNotBlank() && seenMids.add(mid)) {
                kept.add(task)
            } else {
                if (task.filePath.isNotBlank()) {
                    val f = File(task.filePath)
                    if (f.exists()) {
                        f.delete()
                        val lrc = File(task.filePath.substringBeforeLast(".") + ".lrc")
                        if (lrc.exists()) lrc.delete()
                        obsoletePaths.add(task.filePath)
                    }
                }
            }
        }

        if (obsoletePaths.isNotEmpty() && context != null) {
            MediaScannerConnection.scanFile(context, obsoletePaths.toTypedArray(), null, null)
        }

        if (kept.size != tasks.size) {
            saveCompletedTasks(prefs, kept)
        }
        return kept
    }

    fun prunePreviousCopies(
        songMid: String,
        currentFilePath: String,
        currentList: List<DownloadTask>,
        context: Context?,
    ): List<DownloadTask> {
        val obsoleteTasks = currentList.filter { it.song.songMid == songMid }
        if (obsoleteTasks.isEmpty()) return currentList

        val obsoletePaths = mutableListOf<String>()
        for (task in obsoleteTasks) {
            if (task.filePath.isNotBlank() && task.filePath != currentFilePath) {
                val file = File(task.filePath)
                if (file.exists()) {
                    file.delete()
                    val lrc = File(task.filePath.substringBeforeLast(".") + ".lrc")
                    if (lrc.exists()) lrc.delete()
                    obsoletePaths.add(task.filePath)
                }
            }
        }

        if (obsoletePaths.isNotEmpty() && context != null) {
            MediaScannerConnection.scanFile(context, obsoletePaths.toTypedArray(), null, null)
        }

        return currentList.filter { it.song.songMid != songMid }
    }

    fun cleanTaskTempFiles(
        song: Song,
        targetDir: File,
    ) {
        try {
            val allTiers = AudioQualityTier.entries
            val possibleExts = listOf("flac", "mp3", "m4a", "wav")
            for (t in allTiers) {
                val baseName = getStandardBaseName(song, t)
                for (ext in possibleExts) {
                    val tempFile = File(targetDir, "$baseName.$ext.downloading")
                    if (tempFile.exists()) tempFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean temp file for song ${song.songMid}", e)
        }
    }

    fun cleanAllTempFiles(
        tasks: List<DownloadTask>,
        targetDir: File,
    ) {
        for (task in tasks) {
            cleanTaskTempFiles(task.song, targetDir)
        }
    }
}
