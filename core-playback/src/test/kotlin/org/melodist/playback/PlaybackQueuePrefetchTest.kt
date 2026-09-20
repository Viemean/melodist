package org.melodist.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.melodist.api.MusicApiService
import org.melodist.model.Song

class PlaybackQueuePrefetchTest {

    private fun createDummySong(mid: String, name: String = mid): Song =
        Song(
            songId = mid.hashCode().toLong(),
            songMid = mid,
            name = name,
            singer = "Test Singer",
            album = "Test Album",
        )

    class FakePaginationSource(
        override var hasMore: Boolean = true,
        private val nextBatch: List<Song> = emptyList(),
    ) : QueuePaginationSource {
        override var isLoadingMore: Boolean = false
        var loadMoreCallCount = 0

        override suspend fun loadMore(): List<Song> {
            loadMoreCallCount++
            isLoadingMore = true
            try {
                return nextBatch
            } finally {
                isLoadingMore = false
            }
        }
    }

    @Test
    fun `prefetch is triggered when current index approaches queue end`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val queueManager = PlaybackQueueManager(
            scope = scope,
            apiService = MusicApiService(),
            onStateChanged = {},
            onPlaySongRequest = { _, _, _ -> },
            onStopPlaybackRequest = {},
        )

        val initialSongs = listOf(
            createDummySong("mid_1"),
            createDummySong("mid_2"),
            createDummySong("mid_3"),
            createDummySong("mid_4"),
            createDummySong("mid_5"),
        )
        val appendedBatch = listOf(
            createDummySong("mid_6"),
            createDummySong("mid_7"),
        )
        val fakeSource = FakePaginationSource(hasMore = true, nextBatch = appendedBatch)

        queueManager.setPlaylist(
            songs = initialSongs,
            startIndex = 0,
            paginationSource = fakeSource,
        )

        // 索引为 0 时距离尾部较远，不触发 prefetch
        assertEquals(0, fakeSource.loadMoreCallCount)

        // 切换至倒数第 3 首 (index = 2, size = 5, 2 >= 5 - 3)
        queueManager.setCurrentIndex(2)

        // 等待异步预拉取协程执行完成
        for (i in 1..20) {
            if (fakeSource.loadMoreCallCount > 0) break
            kotlinx.coroutines.delay(50)
        }

        // 验证自动触发了 loadMore 并追加了曲目
        assertEquals(1, fakeSource.loadMoreCallCount)
        assertEquals(7, queueManager.playlist.value.size)
        assertTrue(queueManager.playlist.value.any { it.songMid == "mid_6" })
        assertTrue(queueManager.playlist.value.any { it.songMid == "mid_7" })
    }

    @Test
    fun `prefetch is not triggered when hasMore is false`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val queueManager = PlaybackQueueManager(
            scope = scope,
            apiService = MusicApiService(),
            onStateChanged = {},
            onPlaySongRequest = { _, _, _ -> },
            onStopPlaybackRequest = {},
        )

        val initialSongs = listOf(
            createDummySong("mid_1"),
            createDummySong("mid_2"),
            createDummySong("mid_3"),
        )
        val fakeSource = FakePaginationSource(hasMore = false, nextBatch = emptyList())

        queueManager.setPlaylist(
            songs = initialSongs,
            startIndex = 2,
            paginationSource = fakeSource,
        )

        assertEquals(0, fakeSource.loadMoreCallCount)
        assertEquals(3, queueManager.playlist.value.size)
    }

    @Test
    fun `playback snapshot correctly reflects current state`() {
        val dummySong = createDummySong("test_mid", "Test Song")
        val snapshot = PlaybackSnapshot(
            currentSong = dummySong,
            isPlaying = true,
            currentPositionMs = 50_000L,
            durationMs = 200_000L,
            queueSize = 10,
            currentIndex = 2,
        )

        assertEquals(0.25f, snapshot.progress, 0.001f)
        assertTrue(snapshot.hasNext)
        assertTrue(snapshot.hasPrevious)
    }
}
