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
        val seeds = GuessRecommendManager.selectDiverseSeeds(emptyList(), maxSeeds = 5)
        assertTrue(seeds.isEmpty())
    }
}
