package org.melodist.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.*

class LyricParserTest {
    @Test
    fun `parseLrc parses timestamps and preserves sequential text correctly`() {
        val lrc =
            """
            [00:01.23]第一句歌词
            [00:05.50]第二句歌词
            [00:10.00]第三句歌词
            """.trimIndent()

        val lines = LyricParser.parseLrc(lrc)
        assertEquals(3, lines.size)
        assertEquals(1230L, lines[0].first)
        assertEquals("第一句歌词", lines[0].second)
        assertEquals(5500L, lines[1].first)
        assertEquals("第二句歌词", lines[1].second)
        assertEquals(10000L, lines[2].first)
        assertEquals("第三句歌词", lines[2].second)
    }

    @Test
    fun `parseQrcLine parses word spans correctly for karaoke`() {
        val qrcLine = "[01:05.20](0,300)旋(300,200)律(500,400)家"
        val parsed = LyricParser.parseQrcLine(qrcLine)

        assertNotNull(parsed)
        assertEquals(65200L, parsed!!.timestampMs)
        assertEquals("旋律家", parsed.text)
        assertEquals(3, parsed.words.size)

        assertEquals("旋", parsed.words[0].word)
        assertEquals(0L, parsed.words[0].offsetMs)
        assertEquals(300L, parsed.words[0].durationMs)

        assertEquals("律", parsed.words[1].word)
        assertEquals(300L, parsed.words[1].offsetMs)
        assertEquals(200L, parsed.words[1].durationMs)

        assertEquals("家", parsed.words[2].word)
        assertEquals(500L, parsed.words[2].offsetMs)
        assertEquals(400L, parsed.words[2].durationMs)
    }

    @Test
    fun `parseMergedLyrics matches translation correctly`() {
        val orig =
            """
            [00:01.00]Hello world
            [00:04.00]Music is life
            """.trimIndent()
        val trans =
            """
            [00:01.05]你好世界
            [00:04.00]音乐就是生命
            """.trimIndent()

        val merged = LyricParser.parseMergedLyrics(orig, trans)
        assertEquals(2, merged.size)
        assertEquals("Hello world", merged[0].text)
        assertEquals("你好世界", merged[0].transText)
        assertEquals("Music is life", merged[1].text)
        assertEquals("音乐就是生命", merged[1].transText)
    }

    @Test
    fun `parseMergedLyrics matches translations within 800ms tolerance`() {
        val orig =
            """
            [00:45.62]触れられる距離
            [00:47.43]じゃ気付けないから
            """.trimIndent()
        val trans =
            """
            [00:45.98]在触手可及的距离
            [00:47.86]竟也难以察觉
            """.trimIndent()

        val merged = LyricParser.parseMergedLyrics(orig, trans)
        assertEquals(2, merged.size)
        assertEquals("在触手可及的距离", merged[0].transText)
        assertEquals("竟也难以察觉", merged[1].transText)
    }

    @Test
    fun `parseMergedLyrics does not misalign translation to title meta line`() {
        val orig =
            """
            [00:00.44]Proof － mell
            [00:00.99]词: MELL
            [00:01.05]曲: 高瀬一矢
            [00:01.21]もし僕の夢が君の心
            [00:08.27]傷つけてたら
            """.trimIndent()
        val trans =
            """
            [00:00.44]//
            [00:00.99]//
            [00:01.05]//
            [00:01.21]若是我的梦想让你的心
            [00:08.27]受伤了的话
            """.trimIndent()

        val merged = LyricParser.parseMergedLyrics(orig, trans)
        assertEquals(5, merged.size)
        assertEquals("Proof － mell", merged[0].text)
        assertEquals("", merged[0].transText)

        assertEquals("もし僕の夢が君の心", merged[3].text)
        assertEquals("若是我的梦想让你的心", merged[3].transText)

        assertEquals("傷つけてたら", merged[4].text)
        assertEquals("受伤了的话", merged[4].transText)
    }

    @Test
    fun `getLyrics fetches and parses lyrics for 002of4nN1BV5Et`() {
        val lyrics = kotlinx.coroutines.runBlocking { MusicApiService().getLyrics("002of4nN1BV5Et", 246589314L) }
        assertTrue(lyrics.isNotEmpty(), "Lyrics should not be empty")
        assertTrue(lyrics.any { it.text.contains("蝉の声が聞こえ") })
    }
}

