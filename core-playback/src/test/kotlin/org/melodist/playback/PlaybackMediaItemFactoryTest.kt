package org.melodist.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song

class PlaybackMediaItemFactoryTest {
    @Test
    fun `test buildMediaMetadata generates correct metadata with remote device annotation`() {
        val song =
            Song(
                songId = 101L,
                songMid = "mid_101",
                name = "Test Title",
                singer = "Test Artist",
                album = "Test Album",
                coverUrl = "https://example.com/cover.jpg",
            )

        val localMeta = PlaybackMediaItemFactory.buildMediaMetadata(song)
        assertEquals("Test Title", localMeta.title?.toString())
        assertEquals("Test Artist", localMeta.artist?.toString())
        assertEquals("Test Album", localMeta.albumTitle?.toString())

        val remoteMeta = PlaybackMediaItemFactory.buildMediaMetadata(song, remoteDeviceName = "Living Room TV")
        assertTrue(remoteMeta.albumTitle?.toString()?.contains("Living Room TV") == true)
    }

    @Test
    fun `test buildMediaItem creates item with custom cache key and metadata`() {
        val song =
            Song(
                songId = 202L,
                songMid = "mid_202",
                name = "Cache Song",
                singer = "Singer",
                album = "Album",
            )
        val item = PlaybackMediaItemFactory.buildMediaItem(null, song, AudioQualityTier.HiRes)

        assertNotNull(item)
        assertEquals("mid_202", item.mediaId)
        assertEquals("Cache Song", item.mediaMetadata.title?.toString())
    }
}
