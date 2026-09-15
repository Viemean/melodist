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
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.getRecommendFeed
import org.melodist.model.RecommendShelf
import java.io.File

@Serializable
data class RecommendFeedData(
    val shelves: List<RecommendShelf> = emptyList(),
    val fetchTimestamp: Long = 0L,
    val accountUin: String = "",
)

object RecommendFeedManager {
    private const val CACHE_FILE_NAME = "recommend_feed_cache.json"
    private const val AUTO_REFRESH_INTERVAL_MS = 10 * 60 * 1000L // 10 分钟

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _shelvesFlow = MutableStateFlow<List<RecommendShelf>>(emptyList())
    val shelvesFlow: StateFlow<List<RecommendShelf>> = _shelvesFlow.asStateFlow()

    private val _isLoadingFlow = MutableStateFlow(false)
    val isLoadingFlow: StateFlow<Boolean> = _isLoadingFlow.asStateFlow()

    private var cacheFile: File? = null
    private var loopJob: Job? = null
    private var isUserObserverStarted = false
    private var lastFetchTimestamp = 0L

    fun init(context: Context) {
        val appContext = context.applicationContext
        cacheFile = File(appContext.cacheDir, CACHE_FILE_NAME)
        loadFromDisk()
        startObserverAndLoop()
    }

    private fun loadFromDisk() {
        scope.launch {
            try {
                val file = cacheFile ?: return@launch
                if (file.exists() && file.length() > 0) {
                    val content = file.readText()
                    val data = json.decodeFromString<RecommendFeedData>(content)
                    val currentUin = if (UserSession.isLoggedIn) UserSession.profile.uin else ""
                    if (data.accountUin == currentUin && data.shelves.isNotEmpty()) {
                        _shelvesFlow.value = data.shelves
                        lastFetchTimestamp = data.fetchTimestamp
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun saveToDisk(data: RecommendFeedData) {
        scope.launch {
            try {
                val file = cacheFile ?: return@launch
                val content = json.encodeToString(RecommendFeedData.serializer(), data)
                file.writeText(content)
            } catch (_: Exception) {
            }
        }
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
                            refresh(MusicApiService(), forceRefresh = _shelvesFlow.value.isEmpty())
                        }
                        return@collect
                    }
                    if (currentUin != lastUin) {
                        lastUin = currentUin
                        _shelvesFlow.value = emptyList()
                        lastFetchTimestamp = 0L
                        cacheFile?.delete()
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
                    val apiService = MusicApiService()
                    while (isActive) {
                        AppLifecycleManager.awaitForeground()
                        val now = System.currentTimeMillis()
                        val elapsed = if (lastFetchTimestamp > 0L) now - lastFetchTimestamp else 0L
                        val remaining = AUTO_REFRESH_INTERVAL_MS - elapsed
                        if (remaining > 0L) {
                            delay(remaining)
                        } else {
                            if (UserSession.isLoggedIn) {
                                refresh(apiService, forceRefresh = true)
                            }
                            delay(AUTO_REFRESH_INTERVAL_MS)
                        }
                    }
                }
        }
    }

    suspend fun refresh(
        apiService: MusicApiService = MusicApiService(),
        forceRefresh: Boolean = false,
    ) {
        if (!UserSession.isLoggedIn) {
            _shelvesFlow.value = emptyList()
            return
        }

        val currentUin = UserSession.profile.uin
        val now = System.currentTimeMillis()
        if (!forceRefresh && _shelvesFlow.value.isNotEmpty() && (now - lastFetchTimestamp < AUTO_REFRESH_INTERVAL_MS)) {
            return
        }

        refreshMutex.withLock {
            if (_isLoadingFlow.value && !forceRefresh) return
            _isLoadingFlow.value = _shelvesFlow.value.isEmpty()
            try {
                val shelves = apiService.getRecommendFeed(direction = 0, page = 1, sNum = 6)
                if (shelves.isNotEmpty()) {
                    _shelvesFlow.value = shelves
                    lastFetchTimestamp = System.currentTimeMillis()
                    saveToDisk(
                        RecommendFeedData(
                            shelves = shelves,
                            fetchTimestamp = lastFetchTimestamp,
                            accountUin = currentUin,
                        ),
                    )
                }
            } catch (_: Exception) {
            } finally {
                _isLoadingFlow.value = false
            }
        }
    }
}
