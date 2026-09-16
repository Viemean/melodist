package org.melodist.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.addSongToFavorite
import org.melodist.api.deleteSongFromFavorite
import org.melodist.api.getFavoriteSongsDetail
import org.melodist.data.UserLibraryCacheManager
import org.melodist.model.Song

class PlaybackFavoriteController(
    private val scope: CoroutineScope,
    private val apiService: MusicApiService,
    private val onStateChanged: () -> Unit,
) {
    private val _favoriteSongMids = MutableStateFlow<Set<String>>(emptySet())
    val favoriteSongMids: StateFlow<Set<String>> = _favoriteSongMids.asStateFlow()

    private val _songFavoriteToggledEvent = MutableSharedFlow<Pair<Song, Boolean>>(extraBufferCapacity = 16)
    val songFavoriteToggledEvent: SharedFlow<Pair<Song, Boolean>> = _songFavoriteToggledEvent.asSharedFlow()

    @Volatile
    private var isSyncingFavorites = false

    fun restoreFavorites(mids: Set<String>) {
        _favoriteSongMids.value = mids
    }

    fun setFavoriteSongMids(mids: Set<String>) {
        if (mids.isEmpty()) return
        _favoriteSongMids.value = _favoriteSongMids.value + mids
        onStateChanged()
    }

    fun addFavoriteSongMids(mids: Collection<String>) {
        if (mids.isEmpty()) return
        val validMids = mids.filter { it.isNotBlank() }
        if (validMids.isEmpty()) return
        _favoriteSongMids.value = _favoriteSongMids.value + validMids
        onStateChanged()
    }

    fun syncFavoriteSongsAsync(forceRefresh: Boolean = false) {
        if (!UserSession.isLoggedIn) return
        if (isSyncingFavorites) return
        isSyncingFavorites = true

        scope.launch(Dispatchers.IO) {
            try {
                var page = 1
                var hasMore = true
                val allFavMids = mutableSetOf<String>()
                while (hasMore && UserSession.isLoggedIn && page <= 50) {
                    val result = apiService.getFavoriteSongsDetail(page = page, pageSize = 100)
                    if (result.songs.isNotEmpty()) {
                        val mids = result.songs.mapNotNull { it.songMid.takeIf { m -> m.isNotBlank() } }
                        allFavMids.addAll(mids)
                        _favoriteSongMids.value = _favoriteSongMids.value + mids
                        hasMore = result.hasMore && (allFavMids.size < result.total)
                        page++
                    } else {
                        hasMore = false
                    }
                }
                if (allFavMids.isNotEmpty()) {
                    if (forceRefresh) {
                        _favoriteSongMids.value = allFavMids
                    } else {
                        _favoriteSongMids.value = _favoriteSongMids.value + allFavMids
                    }
                    onStateChanged()
                }
            } catch (e: Exception) {
                Log.w("MelodistPlayback", "Failed to sync favorite songs: $e")
            } finally {
                isSyncingFavorites = false
            }
        }
    }

    fun isSongFavoriteSupported(song: Song?): Boolean {
        if (song == null) return false
        if (song.songMid.startsWith("webdav_") || !song.localFilePath.isNullOrBlank()) {
            return false
        }
        return true
    }

    fun isSongFavorite(songMid: String?): Boolean {
        if (songMid == null || songMid.startsWith("webdav_")) return false
        return _favoriteSongMids.value.contains(songMid)
    }

    fun setSongFavoriteState(songMid: String, isFav: Boolean, currentSong: Song?) {
        if (songMid.isBlank()) return
        val currentSet = _favoriteSongMids.value
        val updated = if (isFav) currentSet + songMid else currentSet - songMid
        if (updated != currentSet) {
            _favoriteSongMids.value = updated
            onStateChanged()
            if (currentSong?.songMid == songMid) {
                _songFavoriteToggledEvent.tryEmit(currentSong to isFav)
            }
        }
    }

    fun toggleSongFavorite(song: Song, appContext: Context?) {
        if (!isSongFavoriteSupported(song)) {
            if (appContext != null) {
                val msg = if (song.songMid.startsWith("webdav_")) {
                    "WebDAV 音乐不支持收藏"
                } else {
                    "本地音乐不支持收藏"
                }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        val mid = song.songMid
        val id = song.songId
        val isFav = _favoriteSongMids.value.contains(mid)
        val willBeFav = !isFav
        val updated =
            if (isFav) {
                _favoriteSongMids.value - mid
            } else {
                _favoriteSongMids.value + mid
            }
        _favoriteSongMids.value = updated
        onStateChanged()
        _songFavoriteToggledEvent.tryEmit(song to willBeFav)

        scope.launch(Dispatchers.IO) {
            try {
                if (isFav) {
                    apiService.deleteSongFromFavorite(id, mid)
                } else {
                    apiService.addSongToFavorite(id, mid)
                }
                UserLibraryCacheManager.onFavoriteToggled(song, willBeFav)
            } catch (e: Exception) {
                Log.w("MelodistPlayback", "Failed to sync favorite to cloud: $e")
            }
        }
    }
}
