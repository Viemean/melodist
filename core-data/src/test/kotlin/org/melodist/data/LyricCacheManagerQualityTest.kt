package org.melodist.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.model.LyricLine
import org.melodist.model.WordSpan

class LyricCacheManagerQualityTest {
    @Test
    fun testWordSyncedLyricsPrioritizedOverLineLevelLyrics() {
        val lineLevelLyrics =
            listOf(
                LyricLine(timestampMs = 1000L, text = "Hello World", transText = "你好世界"),
                LyricLine(timestampMs = 5000L, text = "Goodbye World", transText = "再见世界"),
            )

        val wordSyncedLyrics =
            listOf(
                LyricLine(
                    timestampMs = 1000L,
                    text = "Hello World",
                    transText = "你好世界",
                    words =
                        listOf(
                            WordSpan(word = "Hello", offsetMs = 0L, durationMs = 400L),
                            WordSpan(word = "World", offsetMs = 400L, durationMs = 500L),
                        ),
                ),
                LyricLine(
                    timestampMs = 5000L,
                    text = "Goodbye World",
                    transText = "再见世界",
                    words =
                        listOf(
                            WordSpan(word = "Goodbye", offsetMs = 0L, durationMs = 500L),
                            WordSpan(word = "World", offsetMs = 500L, durationMs = 500L),
                        ),
                ),
            )

        assertTrue(LyricCacheManager.isBetterQuality(candidate = wordSyncedLyrics, current = lineLevelLyrics))
        assertFalse(LyricCacheManager.isBetterQuality(candidate = lineLevelLyrics, current = wordSyncedLyrics))
    }

    @Test
    fun testWordSyncedCountDifferenceEvaluation() {
        val partiallySynced =
            listOf(
                LyricLine(
                    timestampMs = 1000L,
                    text = "Line 1",
                    words = listOf(WordSpan(word = "Line", offsetMs = 0L, durationMs = 200L)),
                ),
                LyricLine(timestampMs = 3000L, text = "Line 2"),
            )

        val fullySynced =
            listOf(
                LyricLine(
                    timestampMs = 1000L,
                    text = "Line 1",
                    words = listOf(WordSpan(word = "Line", offsetMs = 0L, durationMs = 200L)),
                ),
                LyricLine(
                    timestampMs = 3000L,
                    text = "Line 2",
                    words = listOf(WordSpan(word = "Line", offsetMs = 0L, durationMs = 200L)),
                ),
            )

        assertTrue(LyricCacheManager.isBetterQuality(candidate = fullySynced, current = partiallySynced))
        assertFalse(LyricCacheManager.isBetterQuality(candidate = partiallySynced, current = fullySynced))
    }
}
