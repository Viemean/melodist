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
import org.melodist.api.MillionRecommendResult
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.getMillionRecommendDetail

object MillionRecommendManager {
    private const val AUTO_REFRESH_INTERVAL_MS = 10 * 60 * 1000L // 10 分钟
    private const val ROTATION_INTERVAL_MS = 3 * 60 * 1000L // 3 分钟轮播

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()

    private val _resultFlow = MutableStateFlow(MillionRecommendResult())
    val resultFlow: StateFlow<MillionRecommendResult> = _resultFlow.asStateFlow()

    private val _displaySongFlow = MutableStateFlow<org.melodist.model.Song?>(null)
    val displaySongFlow: StateFlow<org.melodist.model.Song?> = _displaySongFlow.asStateFlow()

    private val _isLoadingFlow = MutableStateFlow(false)
    val isLoadingFlow: StateFlow<Boolean> = _isLoadingFlow.asStateFlow()

    private var loopJob: Job? = null
    private var isUserObserverStarted = false
    private var lastRefreshTimestamp = 0L

    fun init(context: Context) {
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
                        _resultFlow.value = MillionRecommendResult()
                        _displaySongFlow.value = null
                        lastRefreshTimestamp = 0L
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

        val now = System.currentTimeMillis()
        if (!forceRefresh && _resultFlow.value.songs.isNotEmpty() && (now - lastRefreshTimestamp < AUTO_REFRESH_INTERVAL_MS)) {
            pickRandomDisplaySong()
            return
        }

        refreshMutex.withLock {
            if (_isLoadingFlow.value && !forceRefresh) return
            _isLoadingFlow.value = _resultFlow.value.songs.isEmpty()
            try {
                val detail = apiService.getMillionRecommendDetail()
                if (detail.songs.isNotEmpty() || detail.description.isNotBlank()) {
                    _resultFlow.value = detail
                    lastRefreshTimestamp = System.currentTimeMillis()
                    pickRandomDisplaySong()
                }
            } catch (_: Exception) {
            } finally {
                _isLoadingFlow.value = false
            }
        }
    }
}
