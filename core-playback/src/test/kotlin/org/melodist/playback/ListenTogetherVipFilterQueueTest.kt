package org.melodist.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.melodist.api.MusicApiService
import org.melodist.model.Song

class ListenTogetherVipFilterQueueTest {
    private fun createSong(
        mid: String,
        isVip: Boolean,
    ): Song =
        Song(
            songId = mid.hashCode().toLong(),
            songMid = mid,
            name = mid,
            singer = "Singer",
            album = "Album",
            isVip = isVip,
        )

    @Test
    fun `test playNext skips VIP songs when in room and not both VIP`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        var requestedSong: Song? = null
        var isFilterEnabled = true
        val queueManager =
            PlaybackQueueManager(
                scope = scope,
                apiService = MusicApiService(),
                onStateChanged = {},
                onPlaySongRequest = { song, _, _ -> requestedSong = song },
                onStopPlaybackRequest = {},
                isVipFilterEnabledProvider = { isFilterEnabled },
            )

        // 歌曲列表: 0(Non-VIP), 1(VIP), 2(VIP), 3(Non-VIP), 4(VIP)
        val songs =
            listOf(
                createSong("mid_0", isVip = false),
                createSong("mid_1", isVip = true),
                createSong("mid_2", isVip = true),
                createSong("mid_3", isVip = false),
                createSong("mid_4", isVip = true),
            )
        queueManager.setPlaylist(songs, startIndex = 0)
        queueManager.setLoopMode(PlaybackLoopMode.ListRepeat)

        // 从 0 切下一首，应该跳过 1 和 2，直接播放 3
        queueManager.playNext()
        assertEquals("mid_3", requestedSong?.songMid)

        // 从 3 切下一首，应该跳过 4，回环播放 0
        queueManager.playNext()
        assertEquals("mid_0", requestedSong?.songMid)
    }

    @Test
    fun `test playNext plays normal sequence when both are VIP or not in room`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        var requestedSong: Song? = null
        var isFilterEnabled = false
        val queueManager =
            PlaybackQueueManager(
                scope = scope,
                apiService = MusicApiService(),
                onStateChanged = {},
                onPlaySongRequest = { song, _, _ -> requestedSong = song },
                onStopPlaybackRequest = {},
                isVipFilterEnabledProvider = { isFilterEnabled },
            )

        val songs =
            listOf(
                createSong("mid_0", isVip = false),
                createSong("mid_1", isVip = true),
                createSong("mid_2", isVip = false),
            )
        queueManager.setPlaylist(songs, startIndex = 0)
        queueManager.setLoopMode(PlaybackLoopMode.ListRepeat)

        // 未开启过滤时，顺序切歌不跳过 VIP
        queueManager.playNext()
        assertEquals("mid_1", requestedSong?.songMid)
    }

    @Test
    fun `test playPrevious skips VIP songs backwards when filtering is active`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        var requestedSong: Song? = null
        var isFilterEnabled = true
        val queueManager =
            PlaybackQueueManager(
                scope = scope,
                apiService = MusicApiService(),
                onStateChanged = {},
                onPlaySongRequest = { song, _, _ -> requestedSong = song },
                onStopPlaybackRequest = {},
                isVipFilterEnabledProvider = { isFilterEnabled },
            )

        val songs =
            listOf(
                createSong("mid_0", isVip = false),
                createSong("mid_1", isVip = true),
                createSong("mid_2", isVip = false),
                createSong("mid_3", isVip = true),
            )
        queueManager.setPlaylist(songs, startIndex = 2)
        queueManager.setLoopMode(PlaybackLoopMode.ListRepeat)

        // 从 2 切上一首，应该跳过 1，直接定位到 0
        queueManager.playPrevious()
        assertEquals("mid_0", requestedSong?.songMid)
    }
}
