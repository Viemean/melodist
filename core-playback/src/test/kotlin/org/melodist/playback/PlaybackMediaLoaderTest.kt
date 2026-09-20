package org.melodist.playback

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.melodist.api.MusicApiService
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import java.io.File

class PlaybackMediaLoaderTest {

    private val fakeApiService = MusicApiService()

    @Test
    fun `buildMediaItem configures uri, id and cache key`() {
        val song = Song(
            songId = 101L,
            songMid = "test_mid_1",
            name = "Test Track",
            singer = "Artist",
        )
        val testUri = Uri.parse("https://example.com/audio.flac")
        val item = PlaybackMediaLoader.buildMediaItem(testUri, song, AudioQualityTier.HiRes)

        assertEquals("test_mid_1", item.mediaId)
        if (testUri != null) {
            assertEquals(testUri, item.localConfiguration?.uri)
            assertEquals(MelodistCacheManager.getCacheKey(song.songMid, AudioQualityTier.HiRes), item.localConfiguration?.customCacheKey)
        }
    }

    @Test
    fun `resolveTarget returns LocalFile when audio file exists`(@TempDir tempDir: File) = runBlocking {
        val audioFile = File(tempDir, "sample.flac").apply { writeBytes(ByteArray(2048)) }
        val song = Song(
            songId = 1L,
            songMid = "local_track_1",
            name = "Local Song",
            singer = "Local Artist",
            localFilePath = audioFile.absolutePath,
            currentTier = AudioQualityTier.HiRes,
        )

        val result = PlaybackMediaLoader.resolveTarget(
            context = null,
            song = song,
            targetTier = AudioQualityTier.HiRes,
            apiService = fakeApiService,
            isSongFavorite = { false },
        )

        assertTrue(result is PlaybackTargetResult.LocalFile)
        val local = result as PlaybackTargetResult.LocalFile
        assertEquals(audioFile.absolutePath, local.localFile.absolutePath)
        assertEquals(AudioQualityTier.HiRes, local.actualTier)
    }

    @Test
    fun `resolveTarget returns Failure for missing local audio without proxy`() = runBlocking {
        val song = Song(
            songId = 2L,
            songMid = "local_missing",
            name = "Missing Song",
            singer = "Local Artist",
            localFilePath = "/non/existent/path.flac",
        )

        val result = PlaybackMediaLoader.resolveTarget(
            context = null,
            song = song,
            targetTier = AudioQualityTier.Standard,
            apiService = fakeApiService,
            isSongFavorite = { false },
        )

        assertTrue(result is PlaybackTargetResult.Failure)
        val failure = result as PlaybackTargetResult.Failure
        assertEquals(false, failure.allowRetry)
    }
}
