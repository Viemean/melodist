package org.melodist.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.api.MusicApiService
import org.melodist.model.PlaybackSourceContext
import org.melodist.model.Song

class PlaybackSourceContextTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val apiService = MusicApiService()

    @Test
    fun testPlaybackSourceContextDataModels() {
        val albumCtx = PlaybackSourceContext.Album(albumMid = "002abc", albumId = 12345L)
        assertEquals("002abc", albumCtx.albumMid)
        assertEquals(12345L, albumCtx.albumId)

        val playlistCtx = PlaybackSourceContext.Playlist(id = "987654")
        assertEquals("987654", playlistCtx.id)
    }

    @Test
    fun testQueueManagerSourceContextPreservationAndClear() {
        var requestedSong: Song? = null
        val queueManager =
            PlaybackQueueManager(
                scope = scope,
                apiService = apiService,
                onStateChanged = {},
                onPlaySongRequest = { song, _, _ -> requestedSong = song },
                onStopPlaybackRequest = {},
            )

        assertNull(queueManager.sourceContext.value)

        val song1 = Song(songId = 1L, songMid = "mid_1", name = "Track 1")
        val song2 = Song(songId = 2L, songMid = "mid_2", name = "Track 2")
        val albumContext = PlaybackSourceContext.Album(albumMid = "album_mid_test", albumId = 999L)

        queueManager.setPlaylist(
            songs = listOf(song1, song2),
            startIndex = 0,
            sourceContext = albumContext,
        )

        assertEquals(albumContext, queueManager.sourceContext.value)
        assertEquals("mid_1", requestedSong?.songMid)

        // Switch to playlist context
        val playlistContext = PlaybackSourceContext.Playlist(id = "playlist_123")
        queueManager.setPlaylist(
            songs = listOf(song2),
            startIndex = 0,
            sourceContext = playlistContext,
        )

        assertEquals(playlistContext, queueManager.sourceContext.value)

        // Clear playlist should reset sourceContext to null
        queueManager.clearPlaylist()
        assertNull(queueManager.sourceContext.value)
        assertTrue(queueManager.playlist.value.isEmpty())
    }
}
