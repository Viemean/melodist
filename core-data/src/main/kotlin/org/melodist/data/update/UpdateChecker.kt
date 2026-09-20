package org.melodist.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
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

    suspend fun checkUpdate(
        currentVersion: String,
        targetKeyword: String? = null,
    ): UpdateResult =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    Request
                        .Builder()
                        .url(GITHUB_API_LATEST_RELEASE)
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "Melodist/$currentVersion")
                        .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 404) {
                        return@withContext UpdateResult.Latest(currentVersion)
                    }
                    if (!response.isSuccessful) {
                        return@withContext UpdateResult.Error("HTTP ${response.code}")
                    }
                    val body = response.body.string()
                    val root = json.parseToJsonElement(body).jsonObject
                    val tagName = root["tag_name"]?.jsonPrimitive?.content.orEmpty()
                    val releaseNotes = root["body"]?.jsonPrimitive?.content.orEmpty()
                    val htmlUrl = root["html_url"]?.jsonPrimitive?.content

                    val apkAssets = mutableListOf<Pair<String, String>>()
                    root["assets"]?.jsonArray?.forEach { element ->
                        val assetObj = element.jsonObject
                        val name = assetObj["name"]?.jsonPrimitive?.content.orEmpty()
                        val downloadUrl = assetObj["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
                        if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                            apkAssets.add(name to downloadUrl)
                        }
                    }

                    val downloadUrl = resolveDownloadUrl(apkAssets, targetKeyword, htmlUrl)
                    if (!targetKeyword.isNullOrBlank() && downloadUrl == null) {
                        // 该 Release 仅包含其他平台的专属包，当前平台判定为无适用更新
                        return@withContext UpdateResult.Latest(currentVersion)
                    }

                    if (isNewerVersion(tagName, currentVersion)) {
                        UpdateResult.NewVersion(
                            tagName = tagName,
                            downloadUrl = downloadUrl,
                            releaseNotes = releaseNotes,
                        )
                    } else {
                        UpdateResult.Latest(currentVersion)
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

        val localEffective =
            if (localClean.startsWith("20.") && localClean.count { it == '.' } >= 3) {
                localClean.removePrefix("20.")
            } else {
                localClean
            }
        if (remoteClean == localEffective) return false

        val remoteParts = remoteClean.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = localEffective.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    fun resolveDownloadUrl(
        apkAssets: List<Pair<String, String>>,
        targetKeyword: String?,
        fallbackHtmlUrl: String?,
    ): String? {
        if (targetKeyword.isNullOrBlank()) {
            return apkAssets.firstOrNull()?.second ?: fallbackHtmlUrl
        }
        val keywordLower = targetKeyword.lowercase()

        if (keywordLower.contains("originos")) {
            val originOsMatch = apkAssets.firstOrNull { it.first.lowercase().contains("originos") }
            if (originOsMatch != null) {
                return originOsMatch.second
            }
        }

        if (keywordLower == "mobile") {
            val standardMobileMatch =
                apkAssets.firstOrNull {
                    val lower = it.first.lowercase()
                    lower.contains("mobile") && !lower.contains("originos")
                }
            if (standardMobileMatch != null) {
                return standardMobileMatch.second
            }
        }

        val directMatch = apkAssets.firstOrNull { it.first.lowercase().contains(keywordLower) }
        if (directMatch != null) {
            return directMatch.second
        }

        val otherPlatformKeywords = listOf("tv", "mobile", "originos").filter { it != keywordLower }
        val universalApk =
            apkAssets.firstOrNull { (name, _) ->
                val lowerName = name.lowercase()
                otherPlatformKeywords.none { other -> lowerName.contains(other) }
            }
        return universalApk?.second
    }
}
