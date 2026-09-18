package org.melodist.playback

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import java.io.File

data class RestoredPlaybackState(
    val preferredTier: AudioQualityTier? = null,
    val loopMode: PlaybackLoopMode = PlaybackLoopMode.ListRepeat,
    val isRadioMode: Boolean = false,
    val favoriteSongMids: Set<String> = emptySet(),
    val playlist: List<Song> = emptyList(),
    val shuffledIndices: String? = null,
    val shuffledPointer: Int = 0,
    val currentIndex: Int = -1,
    val currentSong: Song? = null,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
)

object PlaybackStateStorage {
    private const val PREFS_NAME = "melodist_playback_prefs"
    private val jsonHelper = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun getPrefs(context: Context?): SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun sanitizeSongCover(song: Song): Song {
        val url = song.coverUrl
        if (url.startsWith("file://")) {
            val path = url.removePrefix("file://")
            val file = File(path)
            if (!file.exists() || file.length() == 0L) {
                return song.copy(coverUrl = "")
            }
        }
        return song
    }

    fun savePlaybackState(
        context: Context?,
        currentSong: Song?,
        playlist: List<Song>,
        favoriteSongMids: Set<String>,
        currentIndex: Int,
        currentPositionMs: Long,
        durationMs: Long,
        preferredTier: AudioQualityTier,
        loopMode: PlaybackLoopMode,
        shuffledIndices: String,
        shuffledPointer: Int,
        isRadioMode: Boolean,
    ) {
        val prefs = getPrefs(context) ?: return
        try {
            val safePlaylist =
                if (playlist.size > 50 && currentIndex in playlist.indices) {
                    val start = (currentIndex - 20).coerceAtLeast(0)
                    val end = (currentIndex + 30).coerceAtMost(playlist.size)
                    playlist.subList(start, end)
                } else if (playlist.size > 50) {
                    playlist.take(50)
                } else {
                    playlist
                }
            val safeCurrentIndex =
                if (playlist.size > 50 && currentIndex in playlist.indices) {
                    currentIndex - (currentIndex - 20).coerceAtLeast(0)
                } else {
                    currentIndex
                }

            prefs.edit().apply {
                if (currentSong != null) {
                    putString("current_song", jsonHelper.encodeToString(currentSong))
                }
                if (safePlaylist.isNotEmpty()) {
                    putString("playback_queue", jsonHelper.encodeToString(safePlaylist))
                } else {
                    remove("playback_queue")
                }
                putInt("current_index", safeCurrentIndex)
                putLong("current_position_ms", currentPositionMs)
                putLong("duration_ms", durationMs)
                putString("preferred_tier", preferredTier.name)
                putString("loop_mode", loopMode.name)
                putString("shuffled_indices", shuffledIndices)
                putInt("shuffled_pointer", shuffledPointer)
                putString("favorite_song_mids", jsonHelper.encodeToString(favoriteSongMids))
                putBoolean("is_radio_mode", isRadioMode)
                apply()
            }
        } catch (e: Exception) {
            Log.w("MelodistPlayback", "Failed to save playback state", e)
        }
    }

    fun savePlaybackProgress(context: Context?, posMs: Long) {
        val prefs = getPrefs(context) ?: return
        try {
            prefs.edit().putLong("current_position_ms", posMs).apply()
        } catch (_: Exception) {
        }
    }

    fun restorePlaybackState(context: Context?): RestoredPlaybackState {
        val prefs = getPrefs(context) ?: return RestoredPlaybackState()
        try {
            val settingsTier = org.melodist.data.AppSettingsManager.settings.value.preferredQualityTier

            var loopMode = PlaybackLoopMode.ListRepeat
            val modeName = prefs.getString("loop_mode", null)
            if (modeName != null) {
                try {
                    loopMode = PlaybackLoopMode.valueOf(modeName)
                } catch (_: Exception) {
                }
            }

            val isRadioMode = prefs.getBoolean("is_radio_mode", false)

            var favSet = emptySet<String>()
            val favJson = prefs.getString("favorite_song_mids", null)
            if (!favJson.isNullOrBlank()) {
                try {
                    favSet = jsonHelper.decodeFromString<Set<String>>(favJson)
                } catch (_: Exception) {
                }
            }

            var queue = emptyList<Song>()
            val queueJson = prefs.getString("playback_queue", null)
            if (!queueJson.isNullOrBlank()) {
                try {
                    queue = jsonHelper.decodeFromString<List<Song>>(queueJson).map { sanitizeSongCover(it) }
                } catch (_: Exception) {
                }
            }

            val shufStr = prefs.getString("shuffled_indices", null)
            val shufPointer = prefs.getInt("shuffled_pointer", 0)
            val currentIndex = prefs.getInt("current_index", -1)

            var currentSong: Song? = null
            val songJson = prefs.getString("current_song", null)
            if (!songJson.isNullOrBlank()) {
                try {
                    val rawSong = jsonHelper.decodeFromString<Song>(songJson)
                    currentSong = sanitizeSongCover(rawSong)
                } catch (_: Exception) {
                }
            }

            val currentPositionMs = prefs.getLong("current_position_ms", 0L)
            val durationMs = prefs.getLong("duration_ms", 0L)

            return RestoredPlaybackState(
                preferredTier = settingsTier,
                loopMode = loopMode,
                isRadioMode = isRadioMode,
                favoriteSongMids = favSet,
                playlist = queue,
                shuffledIndices = shufStr,
                shuffledPointer = shufPointer,
                currentIndex = currentIndex,
                currentSong = currentSong,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
            )
        } catch (e: Exception) {
            Log.w("MelodistPlayback", "Failed to restore playback state", e)
            return RestoredPlaybackState()
        }
    }
}
