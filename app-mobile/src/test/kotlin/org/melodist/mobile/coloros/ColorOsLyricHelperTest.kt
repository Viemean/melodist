package org.melodist.mobile.coloros

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.melodist.model.LyricLine
import org.melodist.model.Song

class ColorOsLyricHelperTest {
    @Test
    fun `test formatLyricsToLrc with empty lyrics returns empty string`() {
        val result = ColorOsLyricHelper.formatLyricsToLrc(emptyList())
        assertEquals("", result)
    }

    @Test
    fun `test formatLyricsToLrc formats timestamps and strips internal newlines`() {
        val lyrics =
            listOf(
                LyricLine(timestampMs = 0L, text = "Line 1"),
                LyricLine(timestampMs = 4200L, text = "Line 2\nwith newline"),
                LyricLine(timestampMs = 65430L, text = "Line 3"),
            )

        val lrc = ColorOsLyricHelper.formatLyricsToLrc(lyrics)
        val lines = lrc.trimEnd().split('\n')

        assertEquals(3, lines.size)
        assertEquals("[00:00.00]Line 1", lines[0])
        assertEquals("[00:04.20]Line 2 with newline", lines[1])
        assertEquals("[01:05.43]Line 3", lines[2])
    }

    @Test
    fun `test buildLyricInfoJson creates valid json payload`() {
        val song =
            Song(
                songId = 1001L,
                songMid = "dummy_mid_1001",
                name = "Test Song",
                singer = "Test Singer",
                album = "Test Album",
            )
        val lrcText = "[00:00.00]Line 1\n"
        val jsonStr = ColorOsLyricHelper.buildLyricInfoJson(song, lrcText)

        val json = Json.parseToJsonElement(jsonStr).jsonObject
        assertEquals("Test Song", json["songName"]?.jsonPrimitive?.content)
        assertEquals("Test Singer", json["artist"]?.jsonPrimitive?.content)
        assertEquals("dummy_mid_1001", json["songId"]?.jsonPrimitive?.content)
        assertEquals(lrcText, json["lyric"]?.jsonPrimitive?.content)
    }
}
