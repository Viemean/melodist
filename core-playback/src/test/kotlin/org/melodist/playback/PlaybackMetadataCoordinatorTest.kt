package org.melodist.playback

import androidx.media3.common.MediaMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.model.Song

class PlaybackMetadataCoordinatorTest {
    @Test
    fun testCleanTrackNumber() {
        assertEquals("BLUE (蓝色)", PlaybackMetadataCoordinator.cleanTrackNumber("02. BLUE (蓝色)"))
        assertEquals("Crystalfall", PlaybackMetadataCoordinator.cleanTrackNumber("01. Crystalfall"))
        assertEquals("Song Title", PlaybackMetadataCoordinator.cleanTrackNumber("12 - Song Title"))
        assertEquals("Song Title", PlaybackMetadataCoordinator.cleanTrackNumber("01 Song Title"))
        assertEquals("Track One", PlaybackMetadataCoordinator.cleanTrackNumber("Track 01 - Track One"))
        assertEquals("Simple Song", PlaybackMetadataCoordinator.cleanTrackNumber("Simple Song"))
        // 边界校验：保留合法带数字歌名
        assertEquals("21 Guns", PlaybackMetadataCoordinator.cleanTrackNumber("21 Guns"))
        assertEquals("7 Rings", PlaybackMetadataCoordinator.cleanTrackNumber("7 Rings"))
        assertEquals("1989", PlaybackMetadataCoordinator.cleanTrackNumber("1989"))
    }

    @Test
    fun testNeedsEnrichment() {
        val localSongUnknown =
            Song(
                songMid = "local_123",
                name = "02. BLUE (蓝色)",
                singer = "未知歌手",
                localFilePath = "/storage/emulated/0/Music/02. BLUE (蓝色).flac",
            )
        assertTrue(PlaybackMetadataCoordinator.needsEnrichment(localSongUnknown))

        val webDavSongUnknown =
            Song(
                songMid = "webdav_server_123",
                name = "BLUE (蓝色)",
                singer = "WebDAV 音频",
            )
        assertTrue(PlaybackMetadataCoordinator.needsEnrichment(webDavSongUnknown))

        val completeSong =
            Song(
                songMid = "local_456",
                name = "BLUE (蓝色)",
                singer = "BIGBANG",
                album = "ALIVE",
                coverUrl = "file:///data/user/0/covers/local_456.webp",
                localFilePath = "/storage/emulated/0/Music/02. BLUE (蓝色).flac",
            )
        assertFalse(PlaybackMetadataCoordinator.needsEnrichment(completeSong))

        val onlineSong =
            Song(
                songMid = "0039MnYb0qxYAc",
                name = "晴天",
                singer = "周杰伦",
                album = "叶惠美",
            )
        assertFalse(PlaybackMetadataCoordinator.needsEnrichment(onlineSong))
    }

    @Test
    fun testEnrichFromExoMetadata() {
        val rawSong =
            Song(
                songMid = "local_123",
                name = "02. BLUE (蓝色)",
                singer = "未知歌手",
                album = "本地音频",
                localFilePath = "/storage/emulated/0/Music/02. BLUE (蓝色).flac",
            )

        val exoMetadata =
            MediaMetadata
                .Builder()
                .setTitle("BLUE (蓝色)")
                .setArtist("BIGBANG")
                .setAlbumTitle("ALIVE")
                .build()

        val enriched = PlaybackMetadataCoordinator.enrichFromExoMetadata(rawSong, exoMetadata)
        assertNotNull(enriched)
        assertEquals("BLUE (蓝色)", enriched!!.name)
        assertEquals("BIGBANG", enriched.singer)
        assertEquals("ALIVE", enriched.album)

        // 若 ExoPlayer 没有解析到有效新信息，返回 null
        val emptyExoMetadata = MediaMetadata.Builder().build()
        val noChange = PlaybackMetadataCoordinator.enrichFromExoMetadata(rawSong.copy(name = "BLUE (蓝色)"), emptyExoMetadata)
        assertNull(noChange)
    }

    @Test
    fun testEnrichSongMetadataForWebDav() {
        val webDavSong =
            Song(
                songMid = "webdav_server1_loser",
                name = "01. LOSER",
                singer = "WebDAV 音频",
                album = "WebDAV 云盘",
                mediaMid = "/dav/Music/01. LOSER.flac",
            )

        val enriched =
            PlaybackMetadataCoordinator.enrichSongMetadata(
                currentSong = webDavSong,
                title = "LOSER",
                artist = "BIGBANG",
                album = "MADE",
            )

        assertNotNull(enriched)
        assertEquals("LOSER", enriched!!.name)
        assertEquals("BIGBANG", enriched.singer)
        assertEquals("MADE", enriched.album)
    }
}
