package org.melodist.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
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
    private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L // 30 分钟保鲜冷却
    private const val TARGET_BATCH_SIZE = 30

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
     * 自适应分级获取 30 首猜你喜欢歌曲。
     * 红心与历史充足时按配比采样种子，不足时动态降级补齐或触发官方兜底。
     */
    private suspend fun fetchRecommendSongs(apiService: MusicApiService): List<Song> {
        val favSongs = UserLibraryCacheManager.loadFavoriteSongs(apiService, forceRefresh = false)
        val recentSongs = RecentPlaybackManager.recentSongsFlow.value

        val seeds =
            selectDiverseSeeds(
                favSongs = favSongs,
                recentSongs = recentSongs,
                maxSeeds = 6,
                favQuota = 4,
            )
        val seedIds = seeds.mapNotNull { it.songId.takeIf { id -> id > 0L } }

        return apiService.getGuessRecommendSongs(
            count = TARGET_BATCH_SIZE,
            seedSongIds = seedIds,
            excludeSongIds = seedIds.toSet(),
        )
    }

    /**
     * 从红心收藏（前 50 位不同歌手）与最近播放中，按 4:2 比例跨歌手抽取种子歌曲。
     */
    internal fun selectDiverseSeeds(
        favSongs: List<Song>,
        recentSongs: List<Song> = emptyList(),
        maxSeeds: Int = 6,
        favQuota: Int = 4,
    ): List<Song> {
        val validFavs = favSongs.filter { it.songId > 0L }
        val validRecents = recentSongs.filter { it.songId > 0L }

        if (validFavs.isEmpty() && validRecents.isEmpty()) return emptyList()

        fun extractPrimarySinger(song: Song): String =
            song.singer
                .split('/', ',', '&', '、')
                .firstOrNull()
                ?.trim()
                .orEmpty()

        val favBySinger = LinkedHashMap<String, MutableList<Song>>()
        for (song in validFavs) {
            val singer = extractPrimarySinger(song)
            favBySinger.getOrPut(singer) { mutableListOf() }.add(song)
        }
        val topFavSingers = favBySinger.keys.take(50).shuffled()

        val diverse = mutableListOf<Song>()
        val selectedSingers = mutableSetOf<String>()

        for (singer in topFavSingers) {
            val songs = favBySinger[singer].orEmpty()
            if (songs.isNotEmpty()) {
                diverse.add(songs.random())
                selectedSingers.add(singer)
            }
            if (diverse.size >= favQuota) break
        }

        val recentQuota = (maxSeeds - favQuota).coerceAtLeast(0)
        if (recentQuota > 0 && diverse.size < maxSeeds) {
            val recentBySinger = LinkedHashMap<String, MutableList<Song>>()
            for (song in validRecents) {
                val singer = extractPrimarySinger(song)
                if (!selectedSingers.contains(singer)) {
                    recentBySinger.getOrPut(singer) { mutableListOf() }.add(song)
                }
            }
            val shuffledRecentSingers = recentBySinger.keys.shuffled()
            for (singer in shuffledRecentSingers) {
                val songs = recentBySinger[singer].orEmpty()
                if (songs.isNotEmpty()) {
                    diverse.add(songs.random())
                    selectedSingers.add(singer)
                }
                if (diverse.size >= favQuota + recentQuota || diverse.size >= maxSeeds) break
            }
        }

        if (diverse.size < maxSeeds) {
            for (singer in topFavSingers) {
                if (selectedSingers.contains(singer)) continue
                val songs = favBySinger[singer].orEmpty()
                if (songs.isNotEmpty()) {
                    diverse.add(songs.random())
                    selectedSingers.add(singer)
                }
                if (diverse.size >= maxSeeds) break
            }
        }

        if (diverse.size < maxSeeds) {
            val remainingRecents =
                validRecents
                    .filter { s -> diverse.none { it.songId == s.songId } }
                    .shuffled()
            for (song in remainingRecents) {
                val singer = extractPrimarySinger(song)
                if (!selectedSingers.contains(singer)) {
                    diverse.add(song)
                    selectedSingers.add(singer)
                }
                if (diverse.size >= maxSeeds) break
            }
        }

        if (diverse.size < maxSeeds) {
            val allCandidates = (validFavs + validRecents).filter { s -> diverse.none { it.songId == s.songId } }.shuffled()
            for (song in allCandidates) {
                diverse.add(song)
                if (diverse.size >= maxSeeds) break
            }
        }

        return diverse
    }

    /**
     * 提前在后台静默抓取下一批 30 首，避免轮播耗尽时等待
     */
    fun prefetchNextBatch(apiService: MusicApiService = MusicApiService()) {
        if (isPrefetching || (nextBatch?.isNotEmpty() == true)) return
        scope.launch {
            try {
                isPrefetching = true
                val fetched = fetchRecommendSongs(apiService)
                if (fetched.isNotEmpty()) {
                    nextBatch = fetched
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("GuessRecommendManager", "Operation failed", e)
            } finally {
                isPrefetching = false
            }
        }
    }

    /**
     * 当前批次 30 首轮播完毕后切换到下一批
     */
    suspend fun rotateToNextBatch(apiService: MusicApiService = MusicApiService()): List<Song> =
        refreshMutex.withLock {
            val candidate = nextBatch
            nextBatch = null
            if (!candidate.isNullOrEmpty()) {
                _songsFlow.value = candidate
                lastRefreshTimestamp = System.currentTimeMillis()
                candidate
            } else {
                val fresh = fetchRecommendSongs(apiService)
                if (fresh.isNotEmpty()) {
                    _songsFlow.value = fresh
                    lastRefreshTimestamp = System.currentTimeMillis()
                }
                fresh.ifEmpty { _songsFlow.value }
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
                val songs = fetchRecommendSongs(apiService)
                if (songs.isNotEmpty()) {
                    _songsFlow.value = songs
                    nextBatch = null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("GuessRecommendManager", "Operation failed", e)
            } finally {
                lastRefreshTimestamp = System.currentTimeMillis()
                _isLoadingFlow.value = false
            }
        }
    }
}
