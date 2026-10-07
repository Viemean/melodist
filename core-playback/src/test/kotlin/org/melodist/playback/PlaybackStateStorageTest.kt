package org.melodist.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import java.io.File
import java.nio.file.Files

class PlaybackStateStorageTest {
    private class MemoryEditor(
        private val map: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        override fun putString(
            key: String,
            value: String?,
        ): SharedPreferences.Editor {
            map[key] = value
            return this
        }

        override fun putStringSet(
            key: String,
            values: Set<String>?,
        ): SharedPreferences.Editor = this

        override fun putInt(
            key: String,
            value: Int,
        ): SharedPreferences.Editor {
            map[key] = value
            return this
        }

        override fun putLong(
            key: String,
            value: Long,
        ): SharedPreferences.Editor {
            map[key] = value
            return this
        }

        override fun putFloat(
            key: String,
            value: Float,
        ): SharedPreferences.Editor = this

        override fun putBoolean(
            key: String,
            value: Boolean,
        ): SharedPreferences.Editor {
            map[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            map.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            map.clear()
            return this
        }

        override fun commit(): Boolean = true

        override fun apply() {}
    }

    private class MemorySharedPreferences(
        private val map: MutableMap<String, Any?>,
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = map

        override fun getString(
            key: String,
            defValue: String?,
        ): String? = (map[key] as? String) ?: defValue

        override fun getStringSet(
            key: String,
            defValues: Set<String>?,
        ): Set<String>? = defValues

        override fun getInt(
            key: String,
            defValue: Int,
        ): Int = (map[key] as? Int) ?: defValue

        override fun getLong(
            key: String,
            defValue: Long,
        ): Long = (map[key] as? Long) ?: defValue

        override fun getFloat(
            key: String,
            defValue: Float,
        ): Float = defValue

        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ): Boolean = (map[key] as? Boolean) ?: defValue

        override fun contains(key: String): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = MemoryEditor(map)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class FakeContext(
        private val baseDir: File,
        private val prefs: SharedPreferences,
    ) : ContextWrapper(null) {
        override fun getFilesDir(): File = baseDir

        override fun getSharedPreferences(
            name: String?,
            mode: Int,
        ): SharedPreferences = prefs

        override fun getApplicationContext(): Context = this
    }

    @Test
    fun `save and restore 325 songs preserves full playlist and state`() {
        val tempDir = Files.createTempDirectory("playback_test").toFile()
        val prefsMap = mutableMapOf<String, Any?>()
        val mockContext = FakeContext(tempDir, MemorySharedPreferences(prefsMap))

        val largePlaylist =
            (1..325).map { i ->
                Song(
                    songId = i.toLong(),
                    songMid = "mid_$i",
                    name = "Song $i",
                    singer = "Artist $i",
                    album = "Album $i",
                )
            }

        PlaybackStateStorage.savePlaybackState(
            context = mockContext,
            currentSong = largePlaylist[0],
            playlist = largePlaylist,
            favoriteSongMids = setOf("mid_1", "mid_2"),
            currentIndex = 0,
            currentPositionMs = 45000L,
            durationMs = 240000L,
            preferredTier = AudioQualityTier.Standard,
            loopMode = PlaybackLoopMode.ListRepeat,
            shuffledIndices = "0,1,2",
            shuffledPointer = 0,
            isRadioMode = false,
        )

        val restored = PlaybackStateStorage.restorePlaybackState(mockContext)
        assertEquals(325, restored.playlist.size, "Playlist should retain all 325 songs without truncation")
        assertEquals(largePlaylist[0].songMid, restored.playlist[0].songMid)
        assertEquals(largePlaylist[324].songMid, restored.playlist[324].songMid)
        assertEquals(0, restored.currentIndex)
        assertEquals(45000L, restored.currentPositionMs)
        assertEquals(240000L, restored.durationMs)
        assertNotNull(restored.currentSong)
        assertEquals("mid_1", restored.currentSong?.songMid)
    }

    @Test
    fun `fallback to legacy prefs if queue file does not exist`() {
        val tempDir = Files.createTempDirectory("playback_fallback_test").toFile()
        val prefsMap = mutableMapOf<String, Any?>()
        val mockContext = FakeContext(tempDir, MemorySharedPreferences(prefsMap))

        // Write legacy json to prefsMap
        prefsMap["playback_queue"] = """[{"songId":10,"songMid":"legacy_mid","name":"Legacy Song","singer":"Legacy Artist","album":"Legacy Album"}]"""
        prefsMap["current_index"] = 0

        val restored = PlaybackStateStorage.restorePlaybackState(mockContext)
        assertEquals(1, restored.playlist.size)
        assertEquals("legacy_mid", restored.playlist[0].songMid)
    }
}
