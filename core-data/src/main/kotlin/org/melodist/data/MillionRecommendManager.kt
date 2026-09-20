package org.melodist.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.melodist.api.MillionRecommendResult
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.getMillionRecommendDetail
import org.melodist.model.Song
import java.io.File

@Serializable
data class MillionRecommendData(
    val result: MillionRecommendResult = MillionRecommendResult(),
    val fetchTimestamp: Long = 0L,
    val accountUin: String = "",
)

object MillionRecommendManager {
    private const val ROTATION_INTERVAL_MS = 3 * 60 * 1000L // 3 分钟轮播

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _resultFlow = MutableStateFlow(MillionRecommendResult())
    val resultFlow: StateFlow<MillionRecommendResult> = _resultFlow.asStateFlow()

    private val _displaySongFlow = MutableStateFlow<Song?>(null)
    val displaySongFlow: StateFlow<Song?> = _displaySongFlow.asStateFlow()

    private val _isLoadingFlow = MutableStateFlow(false)
    val isLoadingFlow: StateFlow<Boolean> = _isLoadingFlow.asStateFlow()

    private var cacheDirectory: File? = null
    private var loopJob: Job? = null
    private var isUserObserverStarted = false
    private var currentData: MillionRecommendData = MillionRecommendData()

    fun init(context: Context) {
        val appContext = context.applicationContext
        cacheDirectory = appContext.cacheDir
        cleanupOldCacheFiles(DailyRecommendCacheManager.getUtc8DateString())
        loadFromDisk()
        startObserverAndLoop()
    }

    fun pickRandomDisplaySong() {
        val list = _resultFlow.value.songs
        if (list.isNotEmpty()) {
            val current = _displaySongFlow.value
            val candidates = if (list.size > 1 && current != null) list.filter { it.songMid != current.songMid } else list
            _displaySongFlow.value = candidates.randomOrNull() ?: list.first()
        }
    }

    private fun getTodayCacheFile(): File? {
        val dir = cacheDirectory ?: return null
        return File(dir, "million_recommend_${DailyRecommendCacheManager.getUtc8DateString()}.json")
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
            val files =
                dir.listFiles { _, name ->
                    name.startsWith("million_recommend_") && name.endsWith(".json")
                } ?: return
            for (file in files) {
                if (file.name != "million_recommend_$currentDateStr.json") {
                    file.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun loadFromDisk() {
        scope.launch {
            try {
                val file = getTodayCacheFile() ?: return@launch
                if (file.exists() && file.length() > 0) {
                    val content = file.readText()
                    val data = json.decodeFromString<MillionRecommendData>(content)
                    val currentUin = if (UserSession.isLoggedIn) UserSession.profile.uin else ""
                    if (isCacheValidInCycle(data, currentUin)) {
                        currentData = data
                        _resultFlow.value = data.result
                        pickRandomDisplaySong()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun saveToDisk(data: MillionRecommendData) {
        scope.launch {
            try {
                val todayStr = DailyRecommendCacheManager.getUtc8DateString()
                cleanupOldCacheFiles(todayStr)
                val file = getTodayCacheFile() ?: return@launch
                val content = json.encodeToString(MillionRecommendData.serializer(), data)
                file.writeText(content)
            } catch (_: Exception) {
            }
        }
    }

    fun isCacheValidInCycle(
        data: MillionRecommendData,
        currentUin: String,
        currentTimeMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (data.result.songs.isEmpty() || data.fetchTimestamp <= 0L) {
            return false
        }
        if (data.accountUin != currentUin) {
            return false
        }
        val currentDay = DailyRecommendCacheManager.getUtc8DateString(currentTimeMs)
        val cacheDay = DailyRecommendCacheManager.getUtc8DateString(data.fetchTimestamp)
        return currentDay == cacheDay
    }

    private fun startObserverAndLoop() {
        if (!isUserObserverStarted) {
            isUserObserverStarted = true
            scope.launch {
                var lastUin: String? = null
                UserSession.profileFlow.collect { profile ->
                    val currentUin = if (UserSession.isLoggedIn) profile.uin else ""
                    if (lastUin == null) {
                        lastUin = currentUin
                        if (currentUin.isNotBlank()) {
                            refresh(MusicApiService(), forceRefresh = false)
                        }
                        return@collect
                    }
                    if (currentUin != lastUin) {
                        lastUin = currentUin
                        currentData = MillionRecommendData(accountUin = currentUin)
                        _resultFlow.value = MillionRecommendResult()
                        _displaySongFlow.value = null
                        deleteCurrentCacheFile()
                        if (currentUin.isNotBlank()) {
                            refresh(MusicApiService(), forceRefresh = true)
                        }
                    }
                }
            }
        }

        if (loopJob?.isActive != true) {
            loopJob =
                scope.launch {
                    while (isActive) {
                        AppLifecycleManager.awaitForeground()
                        delay(ROTATION_INTERVAL_MS)
                        pickRandomDisplaySong()
                    }
                }
        }
    }

    suspend fun refresh(
        apiService: MusicApiService = MusicApiService(),
        forceRefresh: Boolean = false,
    ) {
        if (!UserSession.isLoggedIn) {
            _resultFlow.value = MillionRecommendResult()
            _displaySongFlow.value = null
            return
        }

        val currentUin = UserSession.profile.uin
        if (!forceRefresh && isCacheValidInCycle(currentData, currentUin)) {
            pickRandomDisplaySong()
            return
        }

        refreshMutex.withLock {
            if (!forceRefresh && isCacheValidInCycle(currentData, currentUin)) {
                pickRandomDisplaySong()
                return
            }
            if (_isLoadingFlow.value && !forceRefresh) return
            _isLoadingFlow.value = _resultFlow.value.songs.isEmpty()
            try {
                val detail = apiService.getMillionRecommendDetail()
                if (detail.songs.isNotEmpty() || detail.description.isNotBlank()) {
                    val newData =
                        MillionRecommendData(
                            result = detail,
                            fetchTimestamp = System.currentTimeMillis(),
                            accountUin = currentUin,
                        )
                    currentData = newData
                    _resultFlow.value = detail
                    saveToDisk(newData)
                    pickRandomDisplaySong()
                }
            } catch (_: Exception) {
            } finally {
                _isLoadingFlow.value = false
            }
        }
    }
}
