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
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.getGuessRecommendSongs
import org.melodist.model.Song

object GuessRecommendManager {
    private const val REFRESH_INTERVAL_MS = 3 * 60 * 1000L // 3 分钟

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()

    private val _songsFlow = MutableStateFlow<List<Song>>(emptyList())
    val songsFlow: StateFlow<List<Song>> = _songsFlow.asStateFlow()

    private val _isLoadingFlow = MutableStateFlow(false)
    val isLoadingFlow: StateFlow<Boolean> = _isLoadingFlow.asStateFlow()

    private var loopJob: Job? = null
    private var isUserObserverStarted = false
    private var lastRefreshTimestamp = 0L

    private var nextBatch: List<Song>? = null
    private var isPrefetching = false

    var isPlayingPredicate: (() -> Boolean)? = null

    fun init(context: Context) {
        startObserverAndLoop()
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
                        return@collect
                    }
                    if (currentUin != lastUin) {
                        lastUin = currentUin
                        _songsFlow.value = emptyList()
                        nextBatch = null
                        refresh(MusicApiService(), forceRefresh = true)
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
                        val elapsed = System.currentTimeMillis() - lastRefreshTimestamp
                        if (elapsed >= REFRESH_INTERVAL_MS) {
                            if (isPlayingPredicate?.invoke() != true) {
                                refresh(apiService, forceRefresh = false)
                            }
                            delay(REFRESH_INTERVAL_MS)
                        } else {
                            val remaining = REFRESH_INTERVAL_MS - elapsed
                            delay(if (remaining > 0) remaining else REFRESH_INTERVAL_MS)
                        }
                    }
                }
        }
    }

    /**
     * 连续两次拉取并去重合并，构成 10 首猜你喜欢候选池
     */
    private suspend fun fetchTenSongs(apiService: MusicApiService): List<Song> {
        val first = apiService.getGuessRecommendSongs(count = 5)
        val second = apiService.getGuessRecommendSongs(count = 5)
        val combined = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        for (song in first + second) {
            if (song.songMid.isNotBlank() && seen.add(song.songMid)) {
                combined.add(song)
            }
        }
        return combined.take(10)
    }

    /**
     * 提前在后台静默抓取下一批 10 首，避免轮播耗尽时等待
     */
    fun prefetchNextBatch(apiService: MusicApiService = MusicApiService()) {
        if (isPrefetching || (nextBatch?.isNotEmpty() == true)) return
        scope.launch {
            try {
                isPrefetching = true
                val fetched = fetchTenSongs(apiService)
                if (fetched.isNotEmpty()) {
                    nextBatch = fetched
                }
            } catch (_: Exception) {
            } finally {
                isPrefetching = false
            }
        }
    }

    /**
     * 当前批次 10 首轮播完毕后切换到下一批
     */
    suspend fun rotateToNextBatch(apiService: MusicApiService = MusicApiService()): List<Song> {
        return refreshMutex.withLock {
            val candidate = nextBatch
            nextBatch = null
            if (!candidate.isNullOrEmpty()) {
                _songsFlow.value = candidate
                lastRefreshTimestamp = System.currentTimeMillis()
                candidate
            } else {
                val fresh = fetchTenSongs(apiService)
                if (fresh.isNotEmpty()) {
                    _songsFlow.value = fresh
                    lastRefreshTimestamp = System.currentTimeMillis()
                }
                fresh.ifEmpty { _songsFlow.value }
            }
        }
    }

    suspend fun refresh(
        apiService: MusicApiService = MusicApiService(),
        forceRefresh: Boolean = false,
    ) {
        refreshMutex.withLock {
            if (isPlayingPredicate?.invoke() == true && !forceRefresh) return
            if (_isLoadingFlow.value && !forceRefresh) return
            _isLoadingFlow.value = _songsFlow.value.isEmpty()
            try {
                val songs = fetchTenSongs(apiService)
                if (songs.isNotEmpty()) {
                    _songsFlow.value = songs
                    nextBatch = null
                }
            } catch (_: Exception) {
            } finally {
                lastRefreshTimestamp = System.currentTimeMillis()
                _isLoadingFlow.value = false
            }
        }
    }
}
