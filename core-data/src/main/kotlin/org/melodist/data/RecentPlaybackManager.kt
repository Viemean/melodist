package org.melodist.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.melodist.model.Song
import java.io.File

object RecentPlaybackManager {
    const val MAX_RECENT_ITEMS = 500
    private const val FILE_NAME = "recent_playback_history.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _recentSongsFlow = MutableStateFlow<List<Song>>(emptyList())
    val recentSongsFlow: StateFlow<List<Song>> = _recentSongsFlow.asStateFlow()

    private var historyFile: File? = null

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

    fun removeSong(songMid: String) {
        if (songMid.isBlank()) return
        val current = _recentSongsFlow.value.filter { it.songMid != songMid }
        _recentSongsFlow.value = current
        saveToDisk()
    }

    fun removeSongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        val removedMids = songs.map { it.songMid }.toSet()
        val current = _recentSongsFlow.value.filter { !removedMids.contains(it.songMid) }
        _recentSongsFlow.value = current
        saveToDisk()
    }

    fun clear() {
        _recentSongsFlow.value = emptyList()
        scope.launch {
            try {
                historyFile?.delete()
            } catch (_: Exception) {
            }
        }
    }
}
