package org.melodist.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.api.MusicApiService
import org.melodist.model.Song

class PlaybackQueueNextPlayTest {
    private fun createDummySong(
        mid: String,
        name: String = mid,
    ): Song =
        Song(
            songId = mid.hashCode().toLong(),
            songMid = mid,
            name = name,
            singer = "Test Singer",
            album = "Test Album",
        )

    @Test
    fun `test insertNextPlay in Shuffle mode ensures next track is the inserted one`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        var requestedSong: Song? = null
        val queueManager =
            PlaybackQueueManager(
                scope = scope,
                apiService = MusicApiService(),
                onStateChanged = {},
                onPlaySongRequest = { song, _, _ -> requestedSong = song },
                onStopPlaybackRequest = {},
            )

        val initialSongs = (0 until 10).map { createDummySong("mid_$it") }
        queueManager.setPlaylist(initialSongs, startIndex = 0)
        queueManager.setLoopMode(PlaybackLoopMode.Shuffle)

        val targetSong = initialSongs[7]
        queueManager.insertNextPlay(targetSong)

        assertTrue(queueManager.hasPendingNextPlay)
        val peeked = queueManager.getNextSong(isRemoteActive = false, remoteNextSong = null)
        assertEquals("mid_7", peeked?.songMid)

        queueManager.playNext()
        assertEquals("mid_7", requestedSong?.songMid)
        assertFalse(queueManager.hasPendingNextPlay)
    }

    @Test
    fun `test insertNextPlay in SingleRepeat mode guides getNextSong and playNext`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        var requestedSong: Song? = null
        val queueManager =
            PlaybackQueueManager(
                scope = scope,
                apiService = MusicApiService(),
                onStateChanged = {},
                onPlaySongRequest = { song, _, _ -> requestedSong = song },
                onStopPlaybackRequest = {},
            )

        val initialSongs = listOf(createDummySong("mid_0"), createDummySong("mid_1"))
        queueManager.setPlaylist(initialSongs, startIndex = 0)
        queueManager.setLoopMode(PlaybackLoopMode.SingleRepeat)

        val newSong = createDummySong("mid_insert")
        queueManager.insertNextPlay(newSong)

        assertTrue(queueManager.hasPendingNextPlay)
        val peeked = queueManager.getNextSong(isRemoteActive = false, remoteNextSong = null)
        assertEquals("mid_insert", peeked?.songMid)

        queueManager.playNext()
        assertEquals("mid_insert", requestedSong?.songMid)
        assertFalse(queueManager.hasPendingNextPlay)
    }

    @Test
    fun `test insertNextPlay with item before current index keeps playing track stable`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        var requestedSong: Song? = null
        val queueManager =
            PlaybackQueueManager(
                scope = scope,
                apiService = MusicApiService(),
                onStateChanged = {},
                onPlaySongRequest = { song, _, _ -> requestedSong = song },
                onStopPlaybackRequest = {},
            )

        val songs =
            listOf(
                createDummySong("mid_0"),
                createDummySong("mid_1"),
                createDummySong("mid_2"),
                createDummySong("mid_3"),
            )
        // 当前播放 mid_2 (下标 2)
        queueManager.setPlaylist(songs, startIndex = 2)

        // 把排在前面的 mid_0 设为下一首播放
        queueManager.insertNextPlay(songs[0])

        // mid_0 被挪到当前歌曲之后，原本的 mid_2 下标应自动向左调整为 1
        assertEquals(1, queueManager.currentIndex.value)
        assertEquals("mid_2", queueManager.playlist.value[queueManager.currentIndex.value].songMid)

        // 紧接着的下一首必然是 mid_0 (位于下标 2)
        val peeked = queueManager.getNextSong(isRemoteActive = false, remoteNextSong = null)
        assertEquals("mid_0", peeked?.songMid)

        queueManager.playNext()
        assertEquals("mid_0", requestedSong?.songMid)
        assertEquals(2, queueManager.currentIndex.value)
    }
}
