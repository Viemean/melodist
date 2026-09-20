package org.melodist.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SongCoverResolutionTest {
    @Test
    fun testThumbnailCoverUrlUses800x800() {
        val song1200 =
            Song(
                coverUrl = "https://y.qq.com/music/photo_new/T002R1200x1200M000003yPnkT3h4fO8.jpg?max_age=2592000",
            )
        assertEquals(
            "https://y.qq.com/music/photo_new/T002R800x800M000003yPnkT3h4fO8.jpg?max_age=2592000",
            song1200.thumbnailCoverUrl,
        )

        val song800 =
            Song(
                coverUrl = "https://y.gtimg.cn/music/photo_new/T002R800x800M000003yPnkT3h4fO8.jpg?max_age=2592000",
            )
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T002R800x800M000003yPnkT3h4fO8.jpg?max_age=2592000",
            song800.thumbnailCoverUrl,
        )

        val song300 =
            Song(
                coverUrl = "https://y.qq.com/music/photo_new/T002R300x300M000003yPnkT3h4fO8.jpg?max_age=2592000",
            )
        assertEquals(
            "https://y.qq.com/music/photo_new/T002R800x800M000003yPnkT3h4fO8.jpg?max_age=2592000",
            song300.thumbnailCoverUrl,
        )
    }

    @Test
    fun testPlayerCoverCandidatesPrioritizeRawWith1200And800Fallback() {
        val song =
            Song(
                coverUrl = "https://y.qq.com/music/photo_new/T002R800x800M000003yPnkT3h4fO8.jpg?max_age=2592000",
            )
        val candidates = song.playerCoverCandidates
        assertEquals(4, candidates.size)
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
        assertEquals(
            "https://y.qq.com/music/photo_new/T002R500x500M000003yPnkT3h4fO8.jpg?max_age=2592000",
            candidates[3],
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
    }

    @Test
    fun testPlaylistThumbnailPicUrlUses800x800() {
        val playlist =
            Playlist(
                dirId = 1L,
                name = "Test Playlist",
                songCount = 10,
                picUrl = "https://y.qq.com/music/photo_new/T002R1200x1200M000003yPnkT3h4fO8.jpg",
            )
        assertEquals(
            "https://y.qq.com/music/photo_new/T002R800x800M000003yPnkT3h4fO8.jpg",
            playlist.thumbnailPicUrl,
        )
        assertEquals(
            listOf(
                "https://y.qq.com/music/photo_new/T002R800x800M000003yPnkT3h4fO8.jpg",
                "https://y.qq.com/music/photo_new/T002R500x500M000003yPnkT3h4fO8.jpg",
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
        val rawUrl = "https://y.qq.com/music/photo_new/T002M000003yPnkT3h4fO8.jpg"
        val url1200 = "https://y.qq.com/music/photo_new/T002R1200x1200M000003yPnkT3h4fO8.jpg"
        val url800 = "https://y.qq.com/music/photo_new/T002R800x800M000003yPnkT3h4fO8.jpg"

        // 1. WiFi 环境（非蜂窝）：首选原图
        val wifiCandidates = song.resolvePlayerCoverCandidates(isCellular = false, hasRawCache = false)
        assertEquals(rawUrl, wifiCandidates.first())

        // 2. 蜂窝移动网络 + 无原图缓存：最大加载 1200，降级 800
        val cellularNoCache = song.resolvePlayerCoverCandidates(isCellular = true, hasRawCache = false)
        assertFalse(cellularNoCache.contains(rawUrl))
        assertEquals(url1200, cellularNoCache.first())
        assertEquals(url800, cellularNoCache[1])

        // 3. 蜂窝移动网络 + 已有原图缓存：直接使用原图
        val cellularWithCache = song.resolvePlayerCoverCandidates(isCellular = true, hasRawCache = true)
        assertEquals(rawUrl, cellularWithCache.first())
    }
}
