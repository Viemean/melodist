package org.melodist.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(
        val progress: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : UpdateDownloadState
    data class Completed(val apkFile: File) : UpdateDownloadState
    data class Error(val message: String) : UpdateDownloadState
}

object AppUpdateDownloader {
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun getUpdatesDir(context: Context): File {
        val dir = File(context.cacheDir, "updates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 清理所有历史残余 APK 及临时文件，确保空间及时释放
     */
    fun cleanOldApks(context: Context, excludeFile: File? = null) {
        val dir = getUpdatesDir(context)
        dir.listFiles()?.forEach { file ->
            if (excludeFile == null || file.absolutePath != excludeFile.absolutePath) {
                file.delete()
            }
        }
    }

    /**
     * 流式下载 APK 文件，输出进度 Flow
     */
    fun downloadApk(
        context: Context,
        downloadUrl: String,
        tagName: String,
    ): Flow<UpdateDownloadState> = flow {
        emit(UpdateDownloadState.Idle)
        val dir = getUpdatesDir(context)
        val cleanTag = tagName.removePrefix("v").replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val finalApk = File(dir, "melodist_${cleanTag}.apk")
        val tmpApk = File(dir, "melodist_${cleanTag}.apk.tmp")

        // 若当前已经存在完整同名 APK（且大小大于 1MB），直接复用
        if (finalApk.exists() && finalApk.length() > 1024 * 1024) {
            emit(UpdateDownloadState.Completed(finalApk))
            return@flow
        }

        // 下载前清理其他旧版本的残余安装包
        cleanOldApks(context, excludeFile = finalApk)

        val request = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "Melodist-UpdateDownloader")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                emit(UpdateDownloadState.Error("HTTP ${response.code}"))
                return@flow
            }
            val body = response.body
            val totalBytes = body.contentLength()
            var bytesDownloaded = 0L

            body.byteStream().use { input ->
                FileOutputStream(tmpApk).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    var lastEmitTime = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesDownloaded += read
                        val now = System.currentTimeMillis()
                        // 节流推送进度，避免过于高频触发 Compose 重组
                        if (now - lastEmitTime >= 100L || bytesDownloaded == totalBytes) {
                            lastEmitTime = now
                            val progress = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
                            emit(UpdateDownloadState.Downloading(progress, bytesDownloaded, totalBytes))
                        }
                    }
                    output.flush()
                }
            }

            if (tmpApk.exists()) {
                if (finalApk.exists()) {
                    finalApk.delete()
                }
                if (tmpApk.renameTo(finalApk)) {
                    emit(UpdateDownloadState.Completed(finalApk))
                } else {
                    emit(UpdateDownloadState.Error("重命名安装包文件失败"))
                }
            } else {
                emit(UpdateDownloadState.Error("下载文件未成功生成"))
            }
        } catch (e: CancellationException) {
            tmpApk.delete()
            throw e
        } catch (e: Exception) {
            tmpApk.delete()
            emit(UpdateDownloadState.Error(e.message ?: "下载过程中发生异常"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 调起系统安装器安装 APK
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() <= 0) return false
        val appContext = context.applicationContext
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!appContext.packageManager.canRequestPackageInstalls()) {
                    val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${appContext.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(manageIntent)
                    return false
                }
            }

            val authority = "${appContext.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(appContext, authority, apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(installIntent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
