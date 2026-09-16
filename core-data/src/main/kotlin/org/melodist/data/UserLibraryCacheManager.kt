package org.melodist.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.addAlbumToFavorite
import org.melodist.api.getFavoriteAlbums
import org.melodist.api.getFavoriteSongsDetail
import org.melodist.api.getPlaylistSongs
import org.melodist.api.getPlaylists
import org.melodist.api.removeAlbumFromFavorite
import org.melodist.model.Album
import org.melodist.model.Playlist
import org.melodist.model.Song
import java.io.File

private const val FAVORITE_SONGS_CACHE_TTL_MS = 10 * 60 * 1000L

@Serializable
data class UserLibraryData(
    val playlists: List<Playlist> = emptyList(),
    val favoriteCount: Int = 0,
    val favoriteAlbums: List<Album> = emptyList(),
    val fetchTimestamp: Long = 0L,
    val accountUin: String = "",
)

@Serializable
data class FavoriteSongsCache(
    val songs: List<Song> = emptyList(),
    val fetchTimestamp: Long = 0L,
    val accountUin: String = "",
)

@Serializable
data class PlaylistSongsCache(
    val songs: List<Song> = emptyList(),
    val totalCount: Int = 0,
    val fetchTimestamp: Long = 0L,
    val accountUin: String = "",
)

object UserLibraryCacheManager {
    private const val CACHE_FILE_NAME = "user_library_cache.json"
    private const val FAV_SONGS_CACHE_FILE_NAME = "favorite_songs_cache.json"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _libraryFlow = MutableStateFlow(UserLibraryData())
    val libraryFlow: StateFlow<UserLibraryData> = _libraryFlow.asStateFlow()

    private val _isLoadingFlow = MutableStateFlow(false)
    val isLoadingFlow: StateFlow<Boolean> = _isLoadingFlow.asStateFlow()

    private val _favoriteSongsFlow = MutableStateFlow<List<Song>>(emptyList())
    val favoriteSongsFlow: StateFlow<List<Song>> = _favoriteSongsFlow.asStateFlow()

    private val _isFavSongsLoading = MutableStateFlow(false)
    val isFavSongsLoading: StateFlow<Boolean> = _isFavSongsLoading.asStateFlow()

    private var cacheFile: File? = null
    private var favSongsCacheFile: File? = null
    private var playlistCacheDir: File? = null
    private var isObservingUser = false
    private var periodicRefreshJob: Job? = null
    private var favSongsCache = FavoriteSongsCache()

    fun init(context: Context) {
        val appContext = context.applicationContext
        cacheFile = File(appContext.cacheDir, CACHE_FILE_NAME)
        favSongsCacheFile = File(appContext.cacheDir, FAV_SONGS_CACHE_FILE_NAME)
        playlistCacheDir = File(appContext.cacheDir, "playlist_cache").apply { mkdirs() }
        loadFromDisk()
        loadFavSongsFromDisk()

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
                        playlistCacheDir?.deleteRecursively()
                        playlistCacheDir?.mkdirs()
                        if (currentUin.isBlank()) {
                            _libraryFlow.value = UserLibraryData()
                            _favoriteSongsFlow.value = emptyList()
                            favSongsCache = FavoriteSongsCache()
                            cacheFile?.delete()
                            favSongsCacheFile?.delete()
                        } else {
                            _libraryFlow.value = UserLibraryData(accountUin = currentUin)
                            _favoriteSongsFlow.value = emptyList()
                            favSongsCache = FavoriteSongsCache()
                            cacheFile?.delete()
                            favSongsCacheFile?.delete()
                            loadLibrary(MusicApiService(), forceRefresh = true)
                        }
                    }
                }
            }
        }
    }

    private fun loadFromDisk() {
        scope.launch {
            try {
                val file = cacheFile ?: return@launch
                if (file.exists() && file.length() > 0) {
                    val data = json.decodeFromString<UserLibraryData>(file.readText())
                    _libraryFlow.value = data
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun loadFavSongsFromDisk() {
        scope.launch {
            try {
                val file = favSongsCacheFile ?: return@launch
                if (file.exists() && file.length() > 0) {
                    val cache = json.decodeFromString<FavoriteSongsCache>(file.readText())
                    val currentUin = if (UserSession.isLoggedIn) UserSession.profile.uin else ""
                    if (cache.accountUin == currentUin && cache.songs.isNotEmpty()) {
                        favSongsCache = cache
                        _favoriteSongsFlow.value = cache.songs
                    }
                }
            } catch (_: Exception) {
            }
            startPeriodicRefresh()
        }
    }

    private fun saveToDisk(data: UserLibraryData) {
        scope.launch {
            try {
                val file = cacheFile ?: return@launch
                file.writeText(json.encodeToString(UserLibraryData.serializer(), data))
            } catch (_: Exception) {
            }
        }
    }

    private fun saveFavSongsToDisk(cache: FavoriteSongsCache) {
        scope.launch {
            try {
                val file = favSongsCacheFile ?: return@launch
                file.writeText(json.encodeToString(FavoriteSongsCache.serializer(), cache))
            } catch (_: Exception) {
            }
        }
    }

    fun hasValidCache(currentUin: String): Boolean {
        val data = _libraryFlow.value
        return data.accountUin == currentUin && (data.playlists.isNotEmpty() || data.favoriteCount > 0)
    }

    fun isFavoriteSongsCacheValid(): Boolean {
        val currentUin = if (UserSession.isLoggedIn) UserSession.profile.uin else return false
        if (favSongsCache.accountUin != currentUin) return false
        if (favSongsCache.songs.isEmpty()) return false
        return System.currentTimeMillis() - favSongsCache.fetchTimestamp < FAVORITE_SONGS_CACHE_TTL_MS
    }

    suspend fun loadFavoriteSongs(
        apiService: MusicApiService,
        forceRefresh: Boolean = false,
    ): List<Song> {
        if (!UserSession.isLoggedIn) return emptyList()
        if (!forceRefresh && isFavoriteSongsCacheValid()) return favSongsCache.songs

        return withContext(Dispatchers.IO) {
            _isFavSongsLoading.value = true
            try {
                val allSongs = mutableListOf<Song>()
                var page = 1
                var hasMore = true
                while (hasMore) {
                    val result = apiService.getFavoriteSongsDetail(page = page, pageSize = 100)
                    allSongs.addAll(result.songs)
                    hasMore = result.hasMore
                    page++
                }
                val currentUin = UserSession.profile.uin
                val cache =
                    FavoriteSongsCache(
                        songs = allSongs,
                        fetchTimestamp = System.currentTimeMillis(),
                        accountUin = currentUin,
                    )
                favSongsCache = cache
                _favoriteSongsFlow.value = allSongs
                saveFavSongsToDisk(cache)
                allSongs
            } catch (_: Exception) {
                favSongsCache.songs
            } finally {
                _isFavSongsLoading.value = false
            }
        }
    }

    private fun updateFavoriteCount(count: Int, uin: String) {
        val updated =
            _libraryFlow.value.copy(
                favoriteCount = count,
                accountUin = uin,
            )
        _libraryFlow.value = updated
        saveToDisk(updated)
    }

    /**
     * 智能轻量第一页探测与同步 (Top-Page Smart Diff Probe)
     * - 头部新增：仅将新收藏的歌曲追加合并到本地头部，0 后续网络开销
     * - 中间或末尾变动（外部大清理/取消收藏/重排）：触发异步全量拉取
     * - 无变动：仅更新时间戳
     */
    suspend fun probeAndSyncFavoritesFirstPage(apiService: MusicApiService): List<Song> {
        if (!UserSession.isLoggedIn) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val firstPageResult = apiService.getFavoriteSongsDetail(page = 1, pageSize = 30)
                val remoteSongs = firstPageResult.songs
                val remoteTotal = firstPageResult.total
                val localSongs = favSongsCache.songs
                val currentUin = UserSession.profile.uin

                // Case 1: 本地为空但远端有数据 -> 触发全量拉取
                if (localSongs.isEmpty() && remoteSongs.isNotEmpty()) {
                    return@withContext loadFavoriteSongs(apiService, forceRefresh = true)
                }

                // Case 2: 远端为空但本地不为空 -> 说明用户在外部清空了全部喜欢
                if (remoteSongs.isEmpty() && localSongs.isNotEmpty()) {
                    val emptyCache = FavoriteSongsCache(emptyList(), System.currentTimeMillis(), currentUin)
                    favSongsCache = emptyCache
                    _favoriteSongsFlow.value = emptyList()
                    saveFavSongsToDisk(emptyCache)
                    updateFavoriteCount(0, currentUin)
                    return@withContext emptyList()
                }

                val localFirstMid = localSongs.firstOrNull()?.songMid
                val remoteFirstMid = remoteSongs.firstOrNull()?.songMid

                if (localFirstMid == remoteFirstMid) {
                    // 头部第一首相同，进一步比对前 30 首序列
                    val checkCount = minOf(remoteSongs.size, localSongs.size)
                    val isPrefixIdentical = (0 until checkCount).all { i ->
                        remoteSongs[i].songMid == localSongs[i].songMid
                    }
                    if (isPrefixIdentical && (remoteTotal <= 0 || remoteTotal == localSongs.size)) {
                        // Case 3: 完全一致，无任何变更，仅更新时间戳
                        val updatedCache = favSongsCache.copy(fetchTimestamp = System.currentTimeMillis(), accountUin = currentUin)
                        favSongsCache = updatedCache
                        saveFavSongsToDisk(updatedCache)
                        return@withContext localSongs
                    } else {
                        // 中间或尾部有变动（例如在外部删除了中间的歌，导致位移）-> 触发异步全量拉取
                        return@withContext loadFavoriteSongs(apiService, forceRefresh = true)
                    }
                }

                // 头部第一首不同，检查是否为“头部新增”
                val localHeadIndexInRemote = remoteSongs.indexOfFirst { it.songMid == localFirstMid }
                if (localHeadIndexInRemote > 0) {
                    // 检查从 localHeadIndexInRemote 开始的连续子序列是否与本地头部吻合
                    val matchLength = minOf(remoteSongs.size - localHeadIndexInRemote, localSongs.size)
                    val isSubsequenceMatch = (0 until matchLength).all { i ->
                        remoteSongs[localHeadIndexInRemote + i].songMid == localSongs[i].songMid
                    }
                    if (isSubsequenceMatch) {
                        // Case 4: 确认为头部新增了 N 首歌
                        val newSongs = remoteSongs.take(localHeadIndexInRemote)
                        val merged = newSongs + localSongs
                        val updatedCache = FavoriteSongsCache(
                            songs = merged,
                            fetchTimestamp = System.currentTimeMillis(),
                            accountUin = currentUin,
                        )
                        favSongsCache = updatedCache
                        _favoriteSongsFlow.value = merged
                        saveFavSongsToDisk(updatedCache)
                        updateFavoriteCount(merged.size, currentUin)
                        return@withContext merged
                    }
                }

                // Case 5: 既不是完全一致，也不是纯粹头部新增 -> 说明外部进行了复杂清理或重排，执行异步全量更新
                loadFavoriteSongs(apiService, forceRefresh = true)
            } catch (_: Exception) {
                favSongsCache.songs
            }
        }
    }

    fun onFavoriteToggled(
        song: Song,
        isFavorited: Boolean,
    ) {
        // Optimistic local update
        val current = _favoriteSongsFlow.value.toMutableList()
        if (isFavorited) {
            if (current.none { it.songMid == song.songMid }) current.add(0, song)
        } else {
            current.removeAll { it.songMid == song.songMid }
        }
        _favoriteSongsFlow.value = current
        val currentUin = if (UserSession.isLoggedIn) UserSession.profile.uin else ""
        val updatedCache = favSongsCache.copy(songs = current, fetchTimestamp = System.currentTimeMillis(), accountUin = currentUin)
        favSongsCache = updatedCache
        saveFavSongsToDisk(updatedCache)
        updateFavoriteCount(current.size, currentUin)

        // 延迟 1.5 秒后主动拉取第 1 页进行服务端确认校验
        scope.launch {
            delay(1500)
            if (!UserSession.isLoggedIn) return@launch
            try {
                probeAndSyncFavoritesFirstPage(MusicApiService())
            } catch (_: Exception) {
            }
        }
    }

    fun onSongAddedToPlaylist(
        dirId: Long,
        song: Song,
    ) {
        onSongsAddedToPlaylist(dirId, listOf(song))
    }

    fun onSongsAddedToPlaylist(
        dirId: Long,
        songs: List<Song>,
    ) {
        if (songs.isEmpty()) return
        val currentData = _libraryFlow.value
        val count = songs.size
        val firstCover = songs.firstOrNull()?.thumbnailCoverUrl.orEmpty()
        val updatedPlaylists =
            currentData.playlists.map { playlist ->
                if (playlist.dirId == dirId) {
                    playlist.copy(
                        songCount = playlist.songCount + count,
                        picUrl = playlist.picUrl.ifBlank { firstCover },
                    )
                } else {
                    playlist
                }
            }
        val isMyFav = dirId == 201L
        val updatedData =
            currentData.copy(
                playlists = updatedPlaylists,
                favoriteCount = if (isMyFav) currentData.favoriteCount + count else currentData.favoriteCount,
            )
        _libraryFlow.value = updatedData
        saveToDisk(updatedData)

        if (isMyFav) {
            val currentFavs = _favoriteSongsFlow.value.toMutableList()
            val existingMids = currentFavs.map { it.songMid }.toSet()
            val newSongs = songs.filter { !existingMids.contains(it.songMid) }
            if (newSongs.isNotEmpty()) {
                currentFavs.addAll(0, newSongs)
                _favoriteSongsFlow.value = currentFavs
            }
        }

        scope.launch {
            delay(1500)
            if (!UserSession.isLoggedIn) return@launch
            try {
                loadLibrary(MusicApiService(), forceRefresh = true)
                if (isMyFav) {
                    loadFavoriteSongs(MusicApiService(), forceRefresh = true)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun onSongsRemovedFromPlaylist(
        dirId: Long,
        songs: List<Song>,
    ) {
        if (songs.isEmpty()) return
        val currentData = _libraryFlow.value
        val count = songs.size
        val removedMids = songs.map { it.songMid }.toSet()
        val updatedPlaylists =
            currentData.playlists.map { playlist ->
                if (playlist.dirId == dirId) {
                    playlist.copy(
                        songCount = (playlist.songCount - count).coerceAtLeast(0),
                    )
                } else {
                    playlist
                }
            }
        val isMyFav = dirId == 201L
        val updatedData =
            currentData.copy(
                playlists = updatedPlaylists,
                favoriteCount = if (isMyFav) (currentData.favoriteCount - count).coerceAtLeast(0) else currentData.favoriteCount,
            )
        _libraryFlow.value = updatedData
        saveToDisk(updatedData)

        if (isMyFav) {
            val currentFavs = _favoriteSongsFlow.value.filter { !removedMids.contains(it.songMid) }
            _favoriteSongsFlow.value = currentFavs
        }

        scope.launch {
            delay(1500)
            if (!UserSession.isLoggedIn) return@launch
            try {
                loadLibrary(MusicApiService(), forceRefresh = true)
                if (isMyFav) {
                    loadFavoriteSongs(MusicApiService(), forceRefresh = true)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun onAlbumFavoriteToggled(
        album: Album,
        isFavorited: Boolean,
    ) {
        val currentData = _libraryFlow.value
        val currentAlbums = currentData.favoriteAlbums.toMutableList()
        if (isFavorited) {
            if (currentAlbums.none { it.mid == album.mid }) {
                currentAlbums.add(0, album)
            }
        } else {
            currentAlbums.removeAll { it.mid == album.mid }
        }
        val updatedData = currentData.copy(favoriteAlbums = currentAlbums)
        _libraryFlow.value = updatedData
        saveToDisk(updatedData)

        scope.launch {
            if (!UserSession.isLoggedIn) return@launch
            try {
                if (isFavorited) {
                    MusicApiService().addAlbumToFavorite(album.mid)
                } else {
                    MusicApiService().removeAlbumFromFavorite(album.mid)
                }
                delay(1500)
                loadLibrary(MusicApiService(), forceRefresh = true)
            } catch (_: Exception) {
            }
        }
    }

    private fun startPeriodicRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob =
            scope.launch {
                while (true) {
                    AppLifecycleManager.awaitForeground()
                    val elapsed = System.currentTimeMillis() - favSongsCache.fetchTimestamp
                    val remaining = FAVORITE_SONGS_CACHE_TTL_MS - elapsed
                    if (remaining > 0) {
                        delay(remaining)
                        continue
                    }
                    if (UserSession.isLoggedIn) {
                        try {
                            probeAndSyncFavoritesFirstPage(MusicApiService())
                            loadLibrary(MusicApiService(), forceRefresh = false)
                        } catch (_: Exception) {
                        }
                    }
                    delay(FAVORITE_SONGS_CACHE_TTL_MS)
                }
            }
    }

    suspend fun loadLibrary(
        apiService: MusicApiService,
        forceRefresh: Boolean = false,
    ): UserLibraryData {
        if (!UserSession.isLoggedIn) {
            val emptyData = UserLibraryData()
            _libraryFlow.value = emptyData
            return emptyData
        }

        val currentUin = UserSession.profile.uin
        val currentData = _libraryFlow.value

        if (!forceRefresh && hasValidCache(currentUin)) {
            return currentData
        }

        return withContext(Dispatchers.IO) {
            _isLoadingFlow.value = true
            try {
                val favRes = apiService.getFavoriteSongsDetail(page = 1, pageSize = 1)
                val lists = apiService.getPlaylists(excludeMyFavorite = true)
                val favAlbums =
                    try {
                        apiService.getFavoriteAlbums()
                    } catch (_: Throwable) {
                        emptyList()
                    }

                val newData =
                    UserLibraryData(
                        playlists = lists,
                        favoriteCount = favRes.total,
                        favoriteAlbums = favAlbums,
                        fetchTimestamp = System.currentTimeMillis(),
                        accountUin = currentUin,
                    )
                _libraryFlow.value = newData
                saveToDisk(newData)
                newData
            } catch (e: Exception) {
                _libraryFlow.value
            } finally {
                _isLoadingFlow.value = false
            }
        }
    }

    private fun getPlaylistCacheFile(dirId: Long, tid: Long): File? {
        val dir = playlistCacheDir ?: return null
        return File(dir, "${dirId}_${tid}.json")
    }

    fun getCachedPlaylistSongs(dirId: Long, tid: Long): List<Song>? {
        try {
            val file = getPlaylistCacheFile(dirId, tid) ?: return null
            if (file.exists() && file.length() > 0) {
                val cache = json.decodeFromString<PlaylistSongsCache>(file.readText())
                val currentUin = if (UserSession.isLoggedIn) UserSession.profile.uin else ""
                if (cache.accountUin == currentUin) {
                    return cache.songs
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun savePlaylistSongsCache(dirId: Long, tid: Long, songs: List<Song>, totalCount: Int = songs.size) {
        scope.launch {
            try {
                val file = getPlaylistCacheFile(dirId, tid) ?: return@launch
                val currentUin = if (UserSession.isLoggedIn) UserSession.profile.uin else ""
                val cache =
                    PlaylistSongsCache(
                        songs = songs,
                        totalCount = totalCount,
                        fetchTimestamp = System.currentTimeMillis(),
                        accountUin = currentUin,
                    )
                file.writeText(json.encodeToString(PlaylistSongsCache.serializer(), cache))
            } catch (_: Exception) {
            }
        }
    }

    suspend fun probeAndSyncPlaylistFirstPage(
        apiService: MusicApiService,
        dirId: Long,
        tid: Long,
        isFav: Boolean,
        targetTotalCount: Int = 0,
    ): List<Song> {
        return withContext(Dispatchers.IO) {
            try {
                val localSongs = getCachedPlaylistSongs(dirId, tid) ?: emptyList()
                val remoteSongs =
                    apiService.getPlaylistSongs(
                        dirId = dirId,
                        tid = tid,
                        isFav = isFav,
                        page = 1,
                        pageSize = 30,
                    )

                if (localSongs.isEmpty() && remoteSongs.isNotEmpty()) {
                    val all = fetchAllPlaylistSongs(apiService, dirId, tid, isFav)
                    savePlaylistSongsCache(dirId, tid, all, all.size)
                    return@withContext all
                }

                if (remoteSongs.isEmpty() && localSongs.isNotEmpty()) {
                    savePlaylistSongsCache(dirId, tid, emptyList(), 0)
                    return@withContext emptyList()
                }

                val localFirstMid = localSongs.firstOrNull()?.songMid
                val remoteFirstMid = remoteSongs.firstOrNull()?.songMid

                if (localFirstMid == remoteFirstMid) {
                    val checkCount = minOf(remoteSongs.size, localSongs.size)
                    val isPrefixIdentical = (0 until checkCount).all { i ->
                        remoteSongs[i].songMid == localSongs[i].songMid
                    }
                    if (isPrefixIdentical && (targetTotalCount <= 0 || targetTotalCount == localSongs.size)) {
                        return@withContext localSongs
                    } else {
                        val all = fetchAllPlaylistSongs(apiService, dirId, tid, isFav)
                        savePlaylistSongsCache(dirId, tid, all, all.size)
                        return@withContext all
                    }
                }

                val localHeadIndexInRemote = remoteSongs.indexOfFirst { it.songMid == localFirstMid }
                if (localHeadIndexInRemote > 0) {
                    val matchLength = minOf(remoteSongs.size - localHeadIndexInRemote, localSongs.size)
                    val isSubsequenceMatch = (0 until matchLength).all { i ->
                        remoteSongs[localHeadIndexInRemote + i].songMid == localSongs[i].songMid
                    }
                    if (isSubsequenceMatch) {
                        val newSongs = remoteSongs.take(localHeadIndexInRemote)
                        val merged = newSongs + localSongs
                        savePlaylistSongsCache(dirId, tid, merged, merged.size)
                        return@withContext merged
                    }
                }

                val all = fetchAllPlaylistSongs(apiService, dirId, tid, isFav)
                savePlaylistSongsCache(dirId, tid, all, all.size)
                all
            } catch (_: Exception) {
                getCachedPlaylistSongs(dirId, tid) ?: emptyList()
            }
        }
    }

    private suspend fun fetchAllPlaylistSongs(
        apiService: MusicApiService,
        dirId: Long,
        tid: Long,
        isFav: Boolean,
    ): List<Song> {
        val all = mutableListOf<Song>()
        var page = 1
        var hasMore = true
        while (hasMore) {
            val songs = apiService.getPlaylistSongs(dirId, tid, isFav, page = page, pageSize = 100)
            if (songs.isEmpty()) break
            all.addAll(songs)
            if (songs.size < 100) {
                hasMore = false
            } else {
                page++
            }
        }
        return all
    }
}
