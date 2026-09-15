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

    suspend fun refresh(
        apiService: MusicApiService = MusicApiService(),
        forceRefresh: Boolean = false,
    ) {
        refreshMutex.withLock {
            if (isPlayingPredicate?.invoke() == true && !forceRefresh) return
            if (_isLoadingFlow.value && !forceRefresh) return
            _isLoadingFlow.value = _songsFlow.value.isEmpty()
            try {
                val songs = apiService.getGuessRecommendSongs(count = 20)
                if (songs.isNotEmpty()) {
                    _songsFlow.value = songs
                }
            } catch (_: Exception) {
            } finally {
                lastRefreshTimestamp = System.currentTimeMillis()
                _isLoadingFlow.value = false
            }
        }
    }
}
