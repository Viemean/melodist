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
    private const val TAG = "PlaybackStateStorage"
    private const val PREFS_NAME = "melodist_playback_prefs"
    private const val QUEUE_FILE_NAME = "playback_queue_v2.json"
    private val jsonHelper =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun getPrefs(context: Context?): SharedPreferences? = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    private fun getQueueFile(context: Context): File = File(context.filesDir, QUEUE_FILE_NAME)

    @Synchronized
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
        if (context == null) return
        val prefs = getPrefs(context) ?: return
        try {
            val queueFile = getQueueFile(context)
            if (playlist.isNotEmpty()) {
                val tempFile = File(context.filesDir, "$QUEUE_FILE_NAME.tmp")
                tempFile.writeText(jsonHelper.encodeToString(playlist))
                if (!tempFile.renameTo(queueFile)) {
                    tempFile.copyTo(queueFile, overwrite = true)
                    tempFile.delete()
                }
            } else {
                if (queueFile.exists()) {
                    queueFile.delete()
                }
            }

            prefs.edit().apply {
                if (currentSong != null) {
                    putString("current_song", jsonHelper.encodeToString(currentSong))
                }
                remove("playback_queue")
                putInt("current_index", currentIndex)
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

    fun savePlaybackProgress(
        context: Context?,
        posMs: Long,
    ) {
        val prefs = getPrefs(context) ?: return
        try {
            prefs.edit().putLong("current_position_ms", posMs).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save playback progress", e)
        }
    }

    @Synchronized
    fun restorePlaybackState(context: Context?): RestoredPlaybackState {
        if (context == null) return RestoredPlaybackState()
        val prefs = getPrefs(context) ?: return RestoredPlaybackState()
        try {
            val settingsTier = org.melodist.data.AppSettingsManager.settings.value.preferredQualityTier

            var loopMode = PlaybackLoopMode.ListRepeat
            val modeName = prefs.getString("loop_mode", null)
            if (modeName != null) {
                try {
                    loopMode = PlaybackLoopMode.valueOf(modeName)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse saved loop mode: $modeName", e)
                }
            }

            val isRadioMode = prefs.getBoolean("is_radio_mode", false)

            var favSet = emptySet<String>()
            val favJson = prefs.getString("favorite_song_mids", null)
            if (!favJson.isNullOrBlank()) {
                try {
                    favSet = jsonHelper.decodeFromString<Set<String>>(favJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode favorite song mids from prefs", e)
                }
            }

            var queue = emptyList<Song>()
            val queueFile = getQueueFile(context)
            if (queueFile.exists() && queueFile.length() > 0L) {
                try {
                    val queueJson = queueFile.readText()
                    if (queueJson.isNotBlank()) {
                        queue = jsonHelper.decodeFromString<List<Song>>(queueJson).map { sanitizeSongCover(it) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode playback queue from file", e)
                }
            } else {
                val queueJson = prefs.getString("playback_queue", null)
                if (!queueJson.isNullOrBlank()) {
                    try {
                        queue = jsonHelper.decodeFromString<List<Song>>(queueJson).map { sanitizeSongCover(it) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode playback queue from prefs", e)
                    }
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
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode current song from prefs", e)
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
