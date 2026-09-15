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
    private var isObservingUser = false
    private var periodicRefreshJob: Job? = null
    private var favSongsCache = FavoriteSongsCache()

    fun init(context: Context) {
        val appContext = context.applicationContext
        cacheFile = File(appContext.cacheDir, CACHE_FILE_NAME)
        favSongsCacheFile = File(appContext.cacheDir, FAV_SONGS_CACHE_FILE_NAME)
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

        // Background full refresh after server sync
        scope.launch {
            delay(1500) // wait for API call to complete
            if (!UserSession.isLoggedIn) return@launch
            try {
                loadFavoriteSongs(MusicApiService(), forceRefresh = true)
                val favCount = _favoriteSongsFlow.value.size
                val currentUin = UserSession.profile.uin
                val updated =
                    _libraryFlow.value.copy(
                        favoriteCount = favCount,
                        accountUin = currentUin,
                    )
                _libraryFlow.value = updated
                saveToDisk(updated)
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
                            loadFavoriteSongs(MusicApiService(), forceRefresh = true)
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
}
