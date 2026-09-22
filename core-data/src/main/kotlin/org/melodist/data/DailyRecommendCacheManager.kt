package org.melodist.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.getDailyRecommendDetail
import org.melodist.api.getGuessRecommendSongs
import org.melodist.model.Song
import java.io.File

@Serializable
data class DailyRecommendData(
    val description: String = "",
    val songs: List<Song> = emptyList(),
    val fetchTimestamp: Long = 0L,
    val accountUin: String = "",
)

object DailyRecommendCacheManager {
    val ZONE_UTC8: java.time.ZoneId = java.time.ZoneId.of("GMT+8")

    fun getUtc8DateString(timestampMs: Long = System.currentTimeMillis()): String {
        val instant = java.time.Instant.ofEpochMilli(timestampMs)
        val formatter =
            java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd")
                .withZone(ZONE_UTC8)
        return formatter.format(instant)
    }

    fun getFormattedLocalDailyRecommendUpdateTime(): String {
        val localZone = java.time.ZoneId.systemDefault()
        val utc8Midnight =
            java.time.LocalDate
                .now(ZONE_UTC8)
                .atStartOfDay(ZONE_UTC8)
        val localTime = utc8Midnight.withZoneSameInstant(localZone)
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", localTime.hour, localTime.minute)
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val _recommendFlow = MutableStateFlow(DailyRecommendData())
    val recommendFlow: StateFlow<DailyRecommendData> = _recommendFlow.asStateFlow()
    private val _isLoadingFlow = MutableStateFlow(false)
    val isLoadingFlow: StateFlow<Boolean> = _isLoadingFlow.asStateFlow()

    private var cacheDirectory: File? = null
    private var preloader: ((List<String>) -> Unit)? = null
    private var isObservingUser = false

    fun init(
        context: Context,
        preloader: ((List<String>) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        val cDir = appContext.cacheDir
        cacheDirectory = cDir
        this.preloader = preloader
        cleanupOldCacheFiles(getUtc8DateString())
        loadFromDisk()

        if (!isObservingUser) {
            isObservingUser = true
            scope.launch {
                var lastUin: String? = null
                UserSession.profileFlow.collect { profile ->
                    val currentUin = if (UserSession.isLoggedIn) profile.uin else ""
                    if (lastUin == null) {
                        lastUin = currentUin
                        return@collect
                    }
                    if (currentUin != lastUin) {
                        lastUin = currentUin
                        _recommendFlow.value = DailyRecommendData(accountUin = currentUin)
                        deleteCurrentCacheFile()
                        loadRecommendSongs(MusicApiService(), forceRefresh = true)
                    }
                }
            }
        }
    }

    private fun getTodayCacheFile(): File? {
        val dir = cacheDirectory ?: return null
        return File(dir, "daily_recommend_${getUtc8DateString()}.json")
    }

    private fun deleteCurrentCacheFile() {
        try {
            getTodayCacheFile()?.delete()
        } catch (_: Exception) {
        }
    }

    private fun cleanupOldCacheFiles(currentDateStr: String) {
        val dir = cacheDirectory ?: return
        try {
            val legacyFile = File(dir, "daily_recommend_cache.json")
            if (legacyFile.exists()) {
                legacyFile.delete()
            }
            val files =
                dir.listFiles { _, name ->
                    name.startsWith("daily_recommend_") && name.endsWith(".json")
                } ?: return
            for (file in files) {
                if (file.name != "daily_recommend_$currentDateStr.json") {
                    file.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun triggerPreload(songs: List<Song>) {
        if (songs.isEmpty()) return
        val urls = songs.map { it.thumbnailCoverUrl }.filter { it.isNotBlank() }.distinct()
        preloader?.invoke(urls)
    }

    private fun loadFromDisk() {
        scope.launch {
            try {
                val file = getTodayCacheFile() ?: return@launch
                if (file.exists() && file.length() > 0) {
                    val content = file.readText()
                    val data = json.decodeFromString<DailyRecommendData>(content)
                    val currentUin = if (UserSession.isLoggedIn) UserSession.profile.uin else ""
                    if (isCacheValidInCycle(data, currentUin)) {
                        _recommendFlow.value = data
                        triggerPreload(data.songs)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("DailyRecommendCache", "Failed to load cache from disk", e)
            }
        }
    }

    private fun saveToDisk(data: DailyRecommendData) {
        scope.launch {
            try {
                val todayStr = getUtc8DateString()
                cleanupOldCacheFiles(todayStr)
                val file = getTodayCacheFile() ?: return@launch
                val content = json.encodeToString(DailyRecommendData.serializer(), data)
                file.writeText(content)
            } catch (e: Exception) {
                android.util.Log.w("DailyRecommendCache", "Failed to save cache to disk", e)
            }
        }
    }

    /**
     * 判断给定缓存是否属于当前 UTC+8 自然日（UTC+8 00:00 更新周期）。
     */
    fun isCacheValidInCycle(
        data: DailyRecommendData,
        currentUin: String,
        currentTimeMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (data.songs.isEmpty() || data.fetchTimestamp <= 0L) {
            return false
        }
        if (data.accountUin != currentUin) {
            return false
        }
        val currentDay = getUtc8DateString(currentTimeMs)
        val cacheDay = getUtc8DateString(data.fetchTimestamp)
        return currentDay == cacheDay
    }

    suspend fun loadRecommendSongs(
        apiService: MusicApiService,
        forceRefresh: Boolean = false,
    ): DailyRecommendData {
        val currentUin = if (UserSession.isLoggedIn) UserSession.profile.uin else ""
        val currentData = _recommendFlow.value

        if (!forceRefresh && isCacheValidInCycle(currentData, currentUin)) {
            triggerPreload(currentData.songs)
            return currentData
        }

        return withContext(Dispatchers.IO) {
            _isLoadingFlow.value = true
            try {
                val (desc, fetchedSongs) =
                    if (UserSession.isLoggedIn) {
                        val detail = apiService.getDailyRecommendDetail()
                        if (detail.songs.isNotEmpty()) {
                            detail.description to detail.songs
                        } else {
                            "" to apiService.getGuessRecommendSongs(30)
                        }
                    } else {
                        "" to apiService.getGuessRecommendSongs(30)
                    }

                val newData =
                    DailyRecommendData(
                        description = desc,
                        songs = fetchedSongs,
                        fetchTimestamp = System.currentTimeMillis(),
                        accountUin = currentUin,
                    )
                _recommendFlow.value = newData
                saveToDisk(newData)
                triggerPreload(newData.songs)
                newData
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DailyRecommendCache", "Failed to refresh daily recommend songs", e)
                _recommendFlow.value
            } finally {
                _isLoadingFlow.value = false
            }
        }
    }
}
