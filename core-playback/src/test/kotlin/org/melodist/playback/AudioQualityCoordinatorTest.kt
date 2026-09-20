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
        val localSong = Song(
            songId = 1L,
            songMid = "local_1",
            name = "Local Track",
            singer = "Local Artist",
            localFilePath = "/tmp/fake.flac",
            currentTier = AudioQualityTier.HiRes,
        )

        coordinator.resetForSong(localSong)

        assertEquals(setOf(AudioQualityTier.HiRes), coordinator.availableTiers.value)
        assertEquals(1, coordinator.probedQualityOptions.value.size)
        assertEquals(AudioQualityTier.HiRes, coordinator.probedQualityOptions.value.first().tier)
        assertEquals("local_1", coordinator.probedSongMid)
    }

    @Test
    fun `resetForSong clears available tiers for online song`() {
        val onlineSong = Song(
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
        assertEquals(AudioQualityTier.HiRes, coordinator.getFallbackTier(AudioQualityTier.Master))
        assertEquals(AudioQualityTier.SQ, coordinator.getFallbackTier(AudioQualityTier.HiRes))
        assertEquals(AudioQualityTier.HQ, coordinator.getFallbackTier(AudioQualityTier.SQ))
        assertEquals(AudioQualityTier.Standard, coordinator.getFallbackTier(AudioQualityTier.HQ))
        assertEquals(null, coordinator.getFallbackTier(AudioQualityTier.Standard))
    }
}
