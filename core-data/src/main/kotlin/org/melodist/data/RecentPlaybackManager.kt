package org.melodist.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.melodist.api.MusicApiService
import org.melodist.api.RecentAlbumItem
import org.melodist.api.RecentDeleteItem
import org.melodist.api.RecentHistoryType
import org.melodist.api.RecentPlaylistItem
import org.melodist.api.UserSession
import org.melodist.api.deleteRecentHistory
import org.melodist.api.deleteRecentHistoryBatch
import org.melodist.api.getRecentAlbums
import org.melodist.api.getRecentPlaylists
import org.melodist.api.getRecentSongs
import org.melodist.model.Song
import java.io.File

object RecentPlaybackManager {
    const val MAX_RECENT_ITEMS = 500
    private const val FILE_NAME = "recent_playback_history.json"
    private const val SYNC_THROTTLE_MS = 30_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val apiService by lazy { MusicApiService() }

    private val _recentSongsFlow = MutableStateFlow<List<Song>>(emptyList())
    val recentSongsFlow: StateFlow<List<Song>> = _recentSongsFlow.asStateFlow()

    private val _recentAlbumsFlow = MutableStateFlow<List<RecentAlbumItem>>(emptyList())
    val recentAlbumsFlow: StateFlow<List<RecentAlbumItem>> = _recentAlbumsFlow.asStateFlow()

    private val _recentPlaylistsFlow = MutableStateFlow<List<RecentPlaylistItem>>(emptyList())
    val recentPlaylistsFlow: StateFlow<List<RecentPlaylistItem>> = _recentPlaylistsFlow.asStateFlow()

    private val _isSyncingFlow = MutableStateFlow(false)
    val isSyncingFlow: StateFlow<Boolean> = _isSyncingFlow.asStateFlow()

    private var historyFile: File? = null
    private var lastSyncTimeMs: Long = 0L

    fun init(context: Context) {
        val appContext = context.applicationContext
        historyFile = File(appContext.filesDir, FILE_NAME)
        loadFromDisk()
    }

    private fun loadFromDisk() {
        scope.launch {
            val file = historyFile ?: return@launch
            if (!file.exists()) return@launch
            try {
                val content = file.readText()
                if (content.isNotBlank()) {
                    val list = json.decodeFromString<List<Song>>(content)
                    _recentSongsFlow.value = list.take(MAX_RECENT_ITEMS)
                }
            } catch (_: Exception) {
            }
            if (UserSession.isLoggedIn) {
                syncFromCloud()
            }
        }
    }

    private fun saveToDisk() {
        scope.launch {
            val file = historyFile ?: return@launch
            try {
                val list = _recentSongsFlow.value
                val content = json.encodeToString(list)
                file.writeText(content)
            } catch (_: Exception) {
            }
        }
    }

    fun recordSong(song: Song) {
        if (song.songMid.isBlank() && song.name.isBlank()) return

        val current = _recentSongsFlow.value.toMutableList()
        current.removeAll { it.songMid.isNotBlank() && it.songMid == song.songMid }
        if (song.songMid.isBlank()) {
            current.removeAll { it.name == song.name && it.singer == song.singer }
        }

        current.add(0, song)
        val trimmed = if (current.size > MAX_RECENT_ITEMS) current.take(MAX_RECENT_ITEMS) else current
        _recentSongsFlow.value = trimmed
        saveToDisk()
    }

    fun syncFromCloud(force: Boolean = false, type: RecentHistoryType? = null) {
        if (!UserSession.isLoggedIn) return
        val now = System.currentTimeMillis()
        if (!force && (now - lastSyncTimeMs) < SYNC_THROTTLE_MS) return

        scope.launch {
            if (_isSyncingFlow.value && !force) return@launch
            _isSyncingFlow.value = true
            try {
                lastSyncTimeMs = now
                val shouldSyncSongs = type == null || type == RecentHistoryType.Song
                val shouldSyncAlbums = type == null || type == RecentHistoryType.Album
                val shouldSyncPlaylists = type == null || type == RecentHistoryType.Playlist

                if (shouldSyncSongs) {
                    val songsResult = apiService.getRecentSongs()
                    val cloudSongs = songsResult.items.map { it.song }
                    Log.i("MelodistRecent", "getRecentSongs returned ${cloudSongs.size} songs")
                    if (cloudSongs.isNotEmpty()) {
                        val currentList = _recentSongsFlow.value
                        val nonOnlineSongs = currentList.filter {
                            it.isLocal || it.isWebDav || it.songMid.startsWith("local_") || it.songMid.startsWith("webdav_")
                        }

                        val merged = mutableListOf<Song>()
                        val seenMids = mutableSetOf<String>()

                        for (song in nonOnlineSongs) {
                            if (song.songMid.isNotBlank() && seenMids.add(song.songMid)) {
                                merged.add(song)
                            }
                        }
                        for (song in cloudSongs) {
                            if (song.songMid.isNotBlank() && seenMids.add(song.songMid)) {
                                merged.add(song)
                            }
                        }
                        val finalSongs = merged.take(MAX_RECENT_ITEMS)
                        _recentSongsFlow.value = finalSongs
                        saveToDisk()
                    }
                }

                if (shouldSyncAlbums) {
                    val albumsResult = apiService.getRecentAlbums()
                    Log.i("MelodistRecent", "getRecentAlbums returned ${albumsResult.items.size} albums")
                    _recentAlbumsFlow.value = albumsResult.items
                }

                if (shouldSyncPlaylists) {
                    val playlistsResult = apiService.getRecentPlaylists()
                    Log.i("MelodistRecent", "getRecentPlaylists returned ${playlistsResult.items.size} playlists")
                    _recentPlaylistsFlow.value = playlistsResult.items
                }
            } catch (e: Exception) {
                Log.e("MelodistRecent", "syncFromCloud error", e)
            } finally {
                _isSyncingFlow.value = false
            }
        }
    }

    fun removeSong(songMid: String) {
        if (songMid.isBlank()) return
        val current = _recentSongsFlow.value
        val target = current.find { it.songMid == songMid }
        _recentSongsFlow.value = current.filter { it.songMid != songMid }
        saveToDisk()

        if (target != null && !target.isLocal && !target.isWebDav && !songMid.startsWith("local_") && !songMid.startsWith("webdav_") && UserSession.isLoggedIn) {
            scope.launch {
                try {
                    val id = if (target.songId > 0L) target.songId.toString() else target.songMid
                    apiService.deleteRecentHistory(id, RecentHistoryType.Song)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun removeSongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        val removedMids = songs.map { it.songMid }.toSet()
        val current = _recentSongsFlow.value.filter { !removedMids.contains(it.songMid) }
        _recentSongsFlow.value = current
        saveToDisk()

        if (UserSession.isLoggedIn) {
            val deleteItems = songs
                .filter { !it.isLocal && !it.isWebDav && !it.songMid.startsWith("local_") && !it.songMid.startsWith("webdav_") }
                .mapNotNull { song ->
                    val id = if (song.songId > 0L) song.songId.toString() else song.songMid
                    if (id.isNotBlank()) RecentDeleteItem(id = id, type = RecentHistoryType.Song.typeCode) else null
                }
            if (deleteItems.isNotEmpty()) {
                scope.launch {
                    try {
                        apiService.deleteRecentHistoryBatch(deleteItems)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun removeAlbum(item: RecentAlbumItem) {
        _recentAlbumsFlow.value = _recentAlbumsFlow.value.filter { it.albumMid != item.albumMid }
        if (UserSession.isLoggedIn) {
            scope.launch {
                try {
                    val id = if (item.albumId > 0L) item.albumId.toString() else item.albumMid
                    apiService.deleteRecentHistory(id, RecentHistoryType.Album)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun removePlaylist(item: RecentPlaylistItem) {
        _recentPlaylistsFlow.value = _recentPlaylistsFlow.value.filter { it.tid != item.tid }
        if (UserSession.isLoggedIn) {
            scope.launch {
                try {
                    apiService.deleteRecentHistory(item.tid.toString(), RecentHistoryType.Playlist)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun clear() {
        val current = _recentSongsFlow.value
        _recentSongsFlow.value = emptyList()
        scope.launch {
            try {
                historyFile?.delete()
            } catch (_: Exception) {
            }
            if (UserSession.isLoggedIn) {
                val deleteItems = current
                    .filter { !it.isLocal && !it.isWebDav && !it.songMid.startsWith("local_") && !it.songMid.startsWith("webdav_") }
                    .mapNotNull { song ->
                        val id = if (song.songId > 0L) song.songId.toString() else song.songMid
                        if (id.isNotBlank()) RecentDeleteItem(id = id, type = RecentHistoryType.Song.typeCode) else null
                    }
                if (deleteItems.isNotEmpty()) {
                    try {
                        apiService.deleteRecentHistoryBatch(deleteItems)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}
