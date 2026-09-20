package org.melodist.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SongCoverResolutionTest {
    @Test
    fun testThumbnailCoverUrlUses500x500() {
        val song1200 =
            Song(
                coverUrl = "https://y.qq.com/music/photo_new/T002R1200x1200M000003yPnkT3h4fO8.jpg?max_age=2592000",
            )
        assertEquals(
            "https://y.qq.com/music/photo_new/T002R500x500M000003yPnkT3h4fO8.jpg?max_age=2592000",
            song1200.thumbnailCoverUrl,
        )

        val song800 =
            Song(
                coverUrl = "https://y.gtimg.cn/music/photo_new/T002R800x800M000003yPnkT3h4fO8.jpg?max_age=2592000",
            )
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T002R500x500M000003yPnkT3h4fO8.jpg?max_age=2592000",
            song800.thumbnailCoverUrl,
        )

        val song300 =
            Song(
                coverUrl = "https://y.qq.com/music/photo_new/T002R300x300M000003yPnkT3h4fO8.jpg?max_age=2592000",
            )
        assertEquals(
            "https://y.qq.com/music/photo_new/T002R500x500M000003yPnkT3h4fO8.jpg?max_age=2592000",
            song300.thumbnailCoverUrl,
        )
    }

    @Test
    fun testPlayerCoverCandidatesPrioritize1200With800Fallback() {
        val song =
            Song(
                coverUrl = "https://y.qq.com/music/photo_new/T002R800x800M000003yPnkT3h4fO8.jpg?max_age=2592000",
            )
        val candidates = song.playerCoverCandidates
        assertEquals(3, candidates.size)
        assertEquals(
            "https://y.qq.com/music/photo_new/T002R1200x1200M000003yPnkT3h4fO8.jpg?max_age=2592000",
            candidates[0],
        )
        assertEquals(
            "https://y.qq.com/music/photo_new/T002R800x800M000003yPnkT3h4fO8.jpg?max_age=2592000",
            candidates[1],
        )
        assertEquals(
            "https://y.qq.com/music/photo_new/T002R500x500M000003yPnkT3h4fO8.jpg?max_age=2592000",
            candidates[2],
        )
    }

    @Test
    fun testRawCoverCandidatesPrioritizeM000WithFallbacks() {
        val song =
            Song(
                coverUrl = "https://y.qq.com/music/photo_new/T002R1200x1200M000003yPnkT3h4fO8.jpg?max_age=2592000",
            )
        val candidates = song.rawCoverCandidates
        assertEquals(3, candidates.size)
        assertEquals(
            "https://y.qq.com/music/photo_new/T002M000003yPnkT3h4fO8.jpg?max_age=2592000",
            candidates[0],
        )
        assertEquals(
            "https://y.qq.com/music/photo_new/T002R1200x1200M000003yPnkT3h4fO8.jpg?max_age=2592000",
            candidates[1],
        )
        assertEquals(
            "https://y.qq.com/music/photo_new/T002R800x800M000003yPnkT3h4fO8.jpg?max_age=2592000",
            candidates[2],
        )
    }

    @Test
    fun testLocalAndBlankCoversArePreserved() {
        val blankSong = Song(coverUrl = "")
        assertEquals("", blankSong.thumbnailCoverUrl)
        assertEquals(emptyList<String>(), blankSong.playerCoverCandidates)

        val localSong = Song(coverUrl = "/storage/emulated/0/Music/cover.jpg")
        assertEquals("/storage/emulated/0/Music/cover.jpg", localSong.thumbnailCoverUrl)
        assertEquals(listOf("/storage/emulated/0/Music/cover.jpg"), localSong.playerCoverCandidates)

        val localWithRaw =
            Song(
                coverUrl = "file:///cache/covers/cover_123.webp",
                rawCoverUrl = "file:///cache/covers/cover_raw_123.jpg",
            )
        assertEquals("file:///cache/covers/cover_123.webp", localWithRaw.thumbnailCoverUrl)
        assertEquals(
            listOf("file:///cache/covers/cover_raw_123.jpg", "file:///cache/covers/cover_123.webp"),
            localWithRaw.playerCoverCandidates,
        )
    }

    @Test
    fun testPlaylistThumbnailPicUrlUses500x500() {
        val playlist =
            Playlist(
                dirId = 1L,
                name = "Test Playlist",
                songCount = 10,
                picUrl = "https://y.qq.com/music/photo_new/T002R1200x1200M000003yPnkT3h4fO8.jpg",
            )
        assertEquals(
            "https://y.qq.com/music/photo_new/T002R500x500M000003yPnkT3h4fO8.jpg",
            playlist.thumbnailPicUrl,
        )
        assertEquals(
            listOf(
                "https://y.qq.com/music/photo_new/T002R500x500M000003yPnkT3h4fO8.jpg",
                "https://y.qq.com/music/photo_new/T002R800x800M000003yPnkT3h4fO8.jpg",
            ),
            playlist.thumbnailCandidates,
        )
    }

    @Test
    fun testResolvePlayerCoverCandidatesOnCellularAndWifi() {
        val song =
            Song(
                songMid = "003yPnkT3h4fO8",
                coverUrl = "https://y.qq.com/music/photo_new/T002R800x800M000003yPnkT3h4fO8.jpg",
            )
        val url1200 = "https://y.qq.com/music/photo_new/T002R1200x1200M000003yPnkT3h4fO8.jpg"
        val url800 = "https://y.qq.com/music/photo_new/T002R800x800M000003yPnkT3h4fO8.jpg"
        val url500 = "https://y.qq.com/music/photo_new/T002R500x500M000003yPnkT3h4fO8.jpg"

        // 1. WiFi 环境（非蜂窝）：首选 1200
        val wifiCandidates = song.resolvePlayerCoverCandidates(isCellular = false, hasRawCache = false)
        assertEquals(url1200, wifiCandidates.first())

        // 2. 蜂窝移动网络：不加载 1200，降级 800
        val cellularCandidates = song.resolvePlayerCoverCandidates(isCellular = true, hasRawCache = false)
        assertFalse(cellularCandidates.contains(url1200))
        assertEquals(url800, cellularCandidates.first())
        assertEquals(url500, cellularCandidates[1])
    }
}
