package org.melodist.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.model.AudioQualityTier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class PlaybackLifecycleOptimizationTest {
    @Test
    fun `tracker continuously polls with high frequency to feed system services`() {
        val testScope = CoroutineScope(Dispatchers.Default)
        val updateCount = AtomicInteger(0)
        val currentPosition = AtomicLong(1000L)

        val tracker =
            PlaybackProgressTracker(
                scope = testScope,
                getPlayer = { null },
                isRemoteActive = { true },
                getEstimatedRemotePositionMs = { currentPosition.addAndGet(50L) },
                getCurrentSong = { null },
                getCurrentTier = { AudioQualityTier.Standard },
                isCurrentTrackFromCache = { false },
                getDurationMs = { 200_000L },
                isSongFavorite = { false },
                onPositionUpdated = { updateCount.incrementAndGet() },
                onDurationUpdated = {},
                onBufferedPositionUpdated = {},
                onFileCacheFractionUpdated = {},
                onTrackFromCacheConfirmed = {},
                onSavePlaybackProgressRequest = {},
                onTriggerPrefetchNextSongRequest = {},
            )

        try {
            tracker.start()
            Thread.sleep(180)
            assertTrue(
                updateCount.get() >= 2,
                "Tracker should continuously poll to feed system services (actual: ${updateCount.get()})",
            )
        } finally {
            tracker.stop()
            testScope.cancel()
        }
    }
}
