package org.melodist.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.data.AppLifecycleManager
import org.melodist.model.AudioQualityTier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class PlaybackLifecycleOptimizationTest {

    @Test
    fun `tracker polls frequently when in foreground`() {
        AppLifecycleManager.setForegroundForTesting(true)
        val testScope = CoroutineScope(Dispatchers.Default)
        val updateCount = AtomicInteger(0)
        val currentPosition = AtomicLong(1000L)

        val tracker = PlaybackProgressTracker(
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
                "Foreground tracker should poll multiple times in 180ms (actual: ${updateCount.get()})"
            )
        } finally {
            tracker.stop()
            testScope.cancel()
            AppLifecycleManager.setForegroundForTesting(false)
        }
    }

    @Test
    fun `tracker instantly resumes when switching from background to foreground`() {
        AppLifecycleManager.setForegroundForTesting(false)
        val testScope = CoroutineScope(Dispatchers.Default)
        val updateCount = AtomicInteger(0)
        val currentPosition = AtomicLong(5000L)

        val tracker = PlaybackProgressTracker(
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
            // 初始在后台执行第 1 次并进入 1000ms 挂起
            Thread.sleep(80)
            val initialCount = updateCount.get()
            assertEquals(1, initialCount, "Background tracker should only run initial loop before long sleep")

            // 在未到达 1000ms 时立即切换回前台
            val switchTime = System.currentTimeMillis()
            AppLifecycleManager.setForegroundForTesting(true)

            // 等待即刻唤醒响应（远低于 1000ms，只需约 80ms）
            Thread.sleep(100)
            val resumedCount = updateCount.get()
            val elapsedMs = System.currentTimeMillis() - switchTime

            assertTrue(
                resumedCount > initialCount,
                "Tracker should instantly wake up upon foreground restoration without waiting for 1000ms delay"
            )
            assertTrue(
                elapsedMs < 500L,
                "Instant resume should happen well within 500ms (elapsed: ${elapsedMs}ms)"
            )
        } finally {
            tracker.stop()
            testScope.cancel()
            AppLifecycleManager.setForegroundForTesting(false)
        }
    }

    @Test
    fun `tracker sleeps at low frequency when in continuous background`() {
        AppLifecycleManager.setForegroundForTesting(false)
        val testScope = CoroutineScope(Dispatchers.Default)
        val updateCount = AtomicInteger(0)
        val currentPosition = AtomicLong(5000L)

        val tracker = PlaybackProgressTracker(
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
            // 在持续后台 400ms 内，由于 1000ms 挂起，应该仅执行初始的 1 次轮询
            Thread.sleep(400)
            assertEquals(1, updateCount.get(), "In continuous background, tracker must remain suspended and not poll frequently")
        } finally {
            tracker.stop()
            testScope.cancel()
            AppLifecycleManager.setForegroundForTesting(false)
        }
    }
}
