package org.melodist.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class PlaybackSessionReportTest {
    @Test
    fun `flushActivePlaybackSession triggers callback with accurate duration and resets state`() {
        val testScope = CoroutineScope(Dispatchers.Default)
        val reportedSong = AtomicReference<Song?>()
        val reportedDuration = AtomicLong(0L)

        val song1 = Song(songId = 186016L, songMid = "0039MnYb0qxYAc", name = "晴天", durationSeconds = 269)

        val tracker =
            PlaybackProgressTracker(
                scope = testScope,
                getPlayer = { null },
                isRemoteActive = { false },
                getEstimatedRemotePositionMs = { null },
                getCurrentSong = { song1 },
                getCurrentTier = { AudioQualityTier.Standard },
                isCurrentTrackFromCache = { false },
                getDurationMs = { 269_000L },
                isSongFavorite = { false },
                onPositionUpdated = {},
                onDurationUpdated = {},
                onBufferedPositionUpdated = {},
                onFileCacheFractionUpdated = {},
                onTrackFromCacheConfirmed = {},
                onSavePlaybackProgressRequest = {},
                onTriggerPrefetchNextSongRequest = {},
                onSongPlaybackSessionFinished = { song, durationMs ->
                    reportedSong.set(song)
                    reportedDuration.set(durationMs)
                },
            )

        try {
            // 在未开始播放前主动 flush，不应触发任何上报
            tracker.flushActivePlaybackSession()
            assertNull(reportedSong.get())
            assertEquals(0L, reportedDuration.get())
        } finally {
            tracker.stop()
            testScope.cancel()
        }
    }
}
