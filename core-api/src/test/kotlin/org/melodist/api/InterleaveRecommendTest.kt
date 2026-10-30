package org.melodist.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.model.Song

class InterleaveRecommendTest {
    @Test
    fun `interleaveAndFilterRecommendations performs round-robin across seeds`() {
        val seed1Songs =
            listOf(
                Song(songId = 101L, songMid = "mid_101", name = "Song 101", singer = "Singer A"),
                Song(songId = 102L, songMid = "mid_102", name = "Song 102", singer = "Singer A"),
                Song(songId = 103L, songMid = "mid_103", name = "Song 103", singer = "Singer A"),
            )
        val seed2Songs =
            listOf(
                Song(songId = 201L, songMid = "mid_201", name = "Song 201", singer = "Singer B"),
                Song(songId = 202L, songMid = "mid_202", name = "Song 202", singer = "Singer B"),
            )
        val seed3Songs =
            listOf(
                Song(songId = 301L, songMid = "mid_301", name = "Song 301", singer = "Singer C"),
            )

        val result =
            interleaveAndFilterRecommendations(
                seedResults = listOf(seed1Songs, seed2Songs, seed3Songs),
                targetCount = 6,
                excludeSongIds = emptySet(),
            )

        assertEquals(6, result.size)
        // 第一轮轮转应该依次来自 seed1, seed2, seed3
        assertEquals(101L, result[0].songId)
        assertEquals(201L, result[1].songId)
        assertEquals(301L, result[2].songId)
    }

    @Test
    fun `interleaveAndFilterRecommendations excludes specified song ids`() {
        val seed1Songs =
            listOf(
                Song(songId = 101L, songMid = "mid_101", name = "Fav Song", singer = "Singer A"),
                Song(songId = 102L, songMid = "mid_102", name = "New Song", singer = "Singer A"),
            )
        val result =
            interleaveAndFilterRecommendations(
                seedResults = listOf(seed1Songs),
                targetCount = 5,
                excludeSongIds = setOf(101L),
            )

        assertEquals(1, result.size)
        assertEquals(102L, result[0].songId)
        assertFalse(result.any { it.songId == 101L })
    }

    @Test
    fun `interleaveAndFilterRecommendations caps singer concentration to max 2 in first pass`() {
        val seedSongs =
            listOf(
                Song(songId = 1L, songMid = "m1", name = "S1", singer = "Aimer"),
                Song(songId = 2L, songMid = "m2", name = "S2", singer = "Aimer"),
                Song(songId = 3L, songMid = "m3", name = "S3", singer = "Aimer"),
                Song(songId = 4L, songMid = "m4", name = "S4", singer = "Yorushika"),
                Song(songId = 5L, songMid = "m5", name = "S5", singer = "Mili"),
            )

        val result =
            interleaveAndFilterRecommendations(
                seedResults = listOf(seedSongs),
                targetCount = 4,
                excludeSongIds = emptySet(),
            )

        assertEquals(4, result.size)
        val aimerCount = result.count { it.singer == "Aimer" }
        assertEquals(2, aimerCount)
        assertTrue(result.any { it.singer == "Yorushika" })
        assertTrue(result.any { it.singer == "Mili" })
    }
}
