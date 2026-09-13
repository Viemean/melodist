package org.melodist.tv.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.melodist.tv.BuildConfig
import java.util.concurrent.TimeUnit

sealed interface UpdateResult {
    data class NewVersion(
        val tagName: String,
        val downloadUrl: String?,
        val releaseNotes: String,
    ) : UpdateResult

    data class Latest(
        val currentVersion: String,
    ) : UpdateResult

    data class Error(
        val message: String,
    ) : UpdateResult
}

object UpdateChecker {
    const val REPO_WEB_URL = "https://github.com/Viemean/melodist"
    private const val GITHUB_API_LATEST_RELEASE = "https://api.github.com/repos/Viemean/melodist/releases/latest"

    private val httpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkUpdate(): UpdateResult =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    Request
                        .Builder()
                        .url(GITHUB_API_LATEST_RELEASE)
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "MelodistTV/${BuildConfig.VERSION_NAME}")
                        .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 404) {
                        return@withContext UpdateResult.Latest(BuildConfig.VERSION_NAME)
                    }
                    if (!response.isSuccessful) {
                        return@withContext UpdateResult.Error("HTTP ${response.code}")
                    }
                    val body = response.body?.string() ?: return@withContext UpdateResult.Error("响应为空")
                    val root = json.parseToJsonElement(body).jsonObject
                    val tagName = root["tag_name"]?.jsonPrimitive?.content.orEmpty()
                    val releaseNotes = root["body"]?.jsonPrimitive?.content.orEmpty()

                    var apkDownloadUrl: String? = null
                    root["assets"]?.jsonArray?.forEach { element ->
                        val assetObj = element.jsonObject
                        val name = assetObj["name"]?.jsonPrimitive?.content.orEmpty()
                        if (name.endsWith(".apk")) {
                            apkDownloadUrl = assetObj["browser_download_url"]?.jsonPrimitive?.content
                        }
                    }

                    if (isNewerVersion(tagName, BuildConfig.VERSION_NAME)) {
                        UpdateResult.NewVersion(
                            tagName = tagName,
                            downloadUrl = apkDownloadUrl,
                            releaseNotes = releaseNotes,
                        )
                    } else {
                        UpdateResult.Latest(BuildConfig.VERSION_NAME)
                    }
                }
            } catch (e: Exception) {
                UpdateResult.Error(e.message ?: "网络连接异常")
            }
        }

    fun isNewerVersion(
        remoteTag: String,
        localVersion: String,
    ): Boolean {
        val remoteClean = remoteTag.removePrefix("v").trim()
        val localClean = localVersion.removePrefix("v").trim()
        if (remoteClean.isBlank() || remoteClean == localClean) return false

        val remoteParts = remoteClean.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = localClean.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}
