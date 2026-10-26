package org.melodist.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.api.MusicApiService
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song

class AudioQualityCoordinatorTest {
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fakeApiService = MusicApiService()
    private val coordinator = AudioQualityCoordinator(testScope, fakeApiService)

    @Test
    fun `resetForSong resets available tiers for local song`() {
        val localSong =
            Song(
                songId = 1L,
                songMid = "local_1",
                name = "Local Track",
                singer = "Local Artist",
                localFilePath = "local/fake.flac",
                currentTier = AudioQualityTier.HiRes,
            )

        coordinator.resetForSong(localSong)

        assertEquals(setOf(AudioQualityTier.HiRes), coordinator.availableTiers.value)
        assertEquals(1, coordinator.probedQualityOptions.value.size)
        assertEquals(
            AudioQualityTier.HiRes,
            coordinator.probedQualityOptions.value
                .first()
                .tier,
        )
        assertEquals("local_1", coordinator.probedSongMid)
    }

    @Test
    fun `resetForSong clears available tiers for online song`() {
        val onlineSong =
            Song(
                songId = 2L,
                songMid = "online_1",
                name = "Online Track",
                singer = "Online Artist",
            )

        coordinator.updateAvailableTiers(setOf(AudioQualityTier.SQ, AudioQualityTier.HQ))
        coordinator.resetForSong(onlineSong)

        assertTrue(coordinator.availableTiers.value.isEmpty())
        assertTrue(coordinator.probedQualityOptions.value.isEmpty())
        assertEquals(null, coordinator.probedSongMid)
    }

    @Test
    fun `getFallbackTier follows degradation ladder`() {
        assertEquals(AudioQualityTier.Dolby, coordinator.getFallbackTier(AudioQualityTier.Master))
        assertEquals(AudioQualityTier.DtsX, coordinator.getFallbackTier(AudioQualityTier.Dolby))
        assertEquals(AudioQualityTier.Atmos, coordinator.getFallbackTier(AudioQualityTier.DtsX))
        assertEquals(AudioQualityTier.Premium, coordinator.getFallbackTier(AudioQualityTier.Atmos))
        assertEquals(AudioQualityTier.HiRes, coordinator.getFallbackTier(AudioQualityTier.Premium))
        assertEquals(AudioQualityTier.SQ, coordinator.getFallbackTier(AudioQualityTier.HiRes))
        assertEquals(AudioQualityTier.HQ, coordinator.getFallbackTier(AudioQualityTier.SQ))
        assertEquals(AudioQualityTier.Standard, coordinator.getFallbackTier(AudioQualityTier.HQ))
        assertEquals(null, coordinator.getFallbackTier(AudioQualityTier.Standard))
    }

    @Test
    fun `when flac decoder missing, clampCodecSupportedTier clamps to HQ`() {
        AudioCodecDetector.flacSupportedOverride = false
        try {
            val onlineSong = Song(songId = 3L, songMid = "online_3", name = "Online Song", singer = "Artist")
            assertEquals(AudioQualityTier.HQ, PlaybackSourceResolver.clampCodecSupportedTier(AudioQualityTier.SQ, onlineSong))
            assertEquals(AudioQualityTier.HQ, PlaybackSourceResolver.clampCodecSupportedTier(AudioQualityTier.HiRes, onlineSong))
            assertEquals(AudioQualityTier.HQ, PlaybackSourceResolver.clampCodecSupportedTier(AudioQualityTier.Master, onlineSong))
            assertEquals(AudioQualityTier.Standard, PlaybackSourceResolver.clampCodecSupportedTier(AudioQualityTier.Standard, onlineSong))
        } finally {
            AudioCodecDetector.flacSupportedOverride = null
        }
    }

    @Test
    fun `when flac decoder missing, local flac song resetForSong marks tier unavailable`() {
        AudioCodecDetector.flacSupportedOverride = false
        try {
            val localSong =
                Song(
                    songId = 4L,
                    songMid = "local_4",
                    name = "Local FLAC",
                    singer = "Artist",
                    localFilePath = "local/song.flac",
                    currentTier = AudioQualityTier.SQ,
                )
            coordinator.resetForSong(localSong)
            assertTrue(coordinator.availableTiers.value.isEmpty())
            assertEquals(
                false,
                coordinator.probedQualityOptions.value
                    .first()
                    .isAvailable,
            )
        } finally {
            AudioCodecDetector.flacSupportedOverride = null
        }
    }
}
