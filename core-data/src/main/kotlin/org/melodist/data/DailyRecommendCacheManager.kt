package org.melodist.data

import android.content.Context
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
import java.util.Calendar

@Serializable
data class DailyRecommendData(
    val description: String = "",
    val songs: List<Song> = emptyList(),
    val fetchTimestamp: Long = 0L,
    val accountUin: String = "",
)

object DailyRecommendCacheManager {
    private const val CACHE_FILE_NAME = "daily_recommend_cache.json"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _recommendFlow = MutableStateFlow(DailyRecommendData())
    val recommendFlow: StateFlow<DailyRecommendData> = _recommendFlow.asStateFlow()

    private val _isLoadingFlow = MutableStateFlow(false)
    val isLoadingFlow: StateFlow<Boolean> = _isLoadingFlow.asStateFlow()

    private var cacheFile: File? = null
    private var preloader: ((List<String>) -> Unit)? = null
    private var isObservingUser = false

    fun init(
        context: Context,
        preloader: ((List<String>) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        cacheFile = File(appContext.cacheDir, CACHE_FILE_NAME)
        this.preloader = preloader
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
                        cacheFile?.delete()
                        loadRecommendSongs(MusicApiService(), forceRefresh = true)
                    }
                }
            }
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
                val file = cacheFile ?: return@launch
                if (file.exists() && file.length() > 0) {
                    val content = file.readText()
                    val data = json.decodeFromString<DailyRecommendData>(content)
                    _recommendFlow.value = data
                    triggerPreload(data.songs)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun saveToDisk(data: DailyRecommendData) {
        scope.launch {
            try {
                val file = cacheFile ?: return@launch
                val content = json.encodeToString(DailyRecommendData.serializer(), data)
                file.writeText(content)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 判断给定缓存是否属于当前自然日（每日 00:00 更新周期）。
     * 只要今日更新过一次，直接使用本地持久化缓存。
     */
    fun isCacheValidInCycle(
        data: DailyRecommendData,
        currentUin: String,
    ): Boolean {
        if (data.songs.isEmpty() || data.fetchTimestamp <= 0L) {
            return false
        }
        if (data.accountUin != currentUin) {
            return false
        }

        val now = System.currentTimeMillis()
        val todayMidnight =
            Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

        return data.fetchTimestamp >= todayMidnight.timeInMillis
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
                _recommendFlow.value
            } finally {
                _isLoadingFlow.value = false
            }
        }
    }
}
