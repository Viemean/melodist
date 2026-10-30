package org.melodist.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.model.Song

class GuessRecommendManagerTest {
    @Test
    fun selectDiverseSeeds_prefersDifferentSingers() {
        val candidates =
            listOf(
                Song(songId = 1L, name = "Song 1", singer = "Aimer"),
                Song(songId = 2L, name = "Song 2", singer = "Aimer"),
                Song(songId = 3L, name = "Song 3", singer = "Yorushika"),
                Song(songId = 4L, name = "Song 4", singer = "Yorushika"),
                Song(songId = 5L, name = "Song 5", singer = "Mili"),
                Song(songId = 6L, name = "Song 6", singer = "Vivienne"),
                Song(songId = 7L, name = "Song 7", singer = "fripSide"),
            )

        val seeds = GuessRecommendManager.selectDiverseSeeds(candidates, maxSeeds = 5)
        assertEquals(5, seeds.size)

        val uniqueSingers = seeds.map { it.singer }.toSet()
        // 5 个不同歌手各抽 1 首
        assertEquals(5, uniqueSingers.size)
    }

    @Test
    fun selectDiverseSeeds_fallsBackWhenSingersFewerThanMaxSeeds() {
        val candidates =
            listOf(
                Song(songId = 1L, name = "Song 1", singer = "Aimer"),
                Song(songId = 2L, name = "Song 2", singer = "Aimer"),
                Song(songId = 3L, name = "Song 3", singer = "Aimer"),
            )

        val seeds = GuessRecommendManager.selectDiverseSeeds(candidates, maxSeeds = 5)
        assertEquals(3, seeds.size)
    }

    @Test
    fun selectDiverseSeeds_handlesEmptyCandidates() {
        val seeds = GuessRecommendManager.selectDiverseSeeds(emptyList(), maxSeeds = 6)
        assertTrue(seeds.isEmpty())
    }

    @Test
    fun selectDiverseSeeds_respectsFourToTwoRatio() {
        val favSongs =
            listOf(
                Song(songId = 1L, name = "Fav 1", singer = "Aimer"),
                Song(songId = 2L, name = "Fav 2", singer = "Yorushika"),
                Song(songId = 3L, name = "Fav 3", singer = "Mili"),
                Song(songId = 4L, name = "Fav 4", singer = "Vivienne"),
                Song(songId = 5L, name = "Fav 5", singer = "fripSide"),
                Song(songId = 6L, name = "Fav 6", singer = "Kenshi Yonezu"),
            )
        val recentSongs =
            listOf(
                Song(songId = 101L, name = "Recent 1", singer = "Yoasobi"),
                Song(songId = 102L, name = "Recent 2", singer = "King Gnu"),
                Song(songId = 103L, name = "Recent 3", singer = "ZUTOMAYO"),
            )

        val seeds =
            GuessRecommendManager.selectDiverseSeeds(
                favSongs = favSongs,
                recentSongs = recentSongs,
                maxSeeds = 6,
                favQuota = 4,
            )
        assertEquals(6, seeds.size)

        val favIds = favSongs.map { it.songId }.toSet()
        val recentIds = recentSongs.map { it.songId }.toSet()

        val fromFav = seeds.filter { favIds.contains(it.songId) }
        val fromRecent = seeds.filter { recentIds.contains(it.songId) }

        // 4 首来自红心，2 首来自最近播放
        assertEquals(4, fromFav.size)
        assertEquals(2, fromRecent.size)

        // 6 首种子必须来自 6 位不同歌手
        val uniqueSingers = seeds.map { it.singer }.toSet()
        assertEquals(6, uniqueSingers.size)
    }

    @Test
    fun selectDiverseSeeds_restrictsFavoritesToTop50Singers() {
        // 构建 60 位不同歌手的红心歌曲
        val favSongs =
            (1..60).map { i ->
                Song(songId = i.toLong(), name = "Song $i", singer = "Singer_$i")
            }

        val seeds =
            GuessRecommendManager.selectDiverseSeeds(
                favSongs = favSongs,
                recentSongs = emptyList(),
                maxSeeds = 6,
                favQuota = 4,
            )
        assertEquals(6, seeds.size)

        val top50SingerNames = (1..50).map { "Singer_$it" }.toSet()
        // 所有选中的种子歌手都必须在前 50 位歌手之内
        for (seed in seeds) {
            assertTrue(top50SingerNames.contains(seed.singer), "Seed singer ${seed.singer} must be in top 50")
        }
    }
}
