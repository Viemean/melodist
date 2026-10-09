package org.melodist.api

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

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

    @Test
    fun `isPlaceholderLyrics detects instrumental and placeholder correctly`() {
        val instrumentalLrc = LyricParser.parseMergedLyrics("[00:00.00]此歌曲为没有填词的纯音乐，请您欣赏", null)
        assertTrue(LyricParser.isPlaceholderLyrics(instrumentalLrc))

        val noLyricLrc = LyricParser.parseMergedLyrics("[00:00.00]暂无歌词", null)
        assertTrue(LyricParser.isPlaceholderLyrics(noLyricLrc))

        val normalLrc = LyricParser.parseMergedLyrics("[00:01.00]Hello world\n[00:05.00]Second line", null)
        assertFalse(LyricParser.isPlaceholderLyrics(normalLrc))
    }

    @Test
    fun `parseMergedLyrics merges interleaved single lrc bilingual lyrics correctly`() {
        val singleLrc =
            """
            [by:嘉一大王]
            [00:00.00]作词 : 深野香/水口哲也/玉井健二
            [00:01.00]作曲 : 玉井健二/飛内将大
            [00:07.68]Stop listen rewind
            [00:07.68]别再听倒带了
            [00:10.49]Come a little closer
            [00:10.49]靠近我一些
            [00:14.26]Listen to the silence beating hard
            [00:14.26]仔细听那无声的跳动
            [00:17.56]Where ever you go
            [00:17.56]随处紧跟着你
            """.trimIndent()

        val parsed = LyricParser.parseMergedLyrics(singleLrc, null)
        assertEquals(6, parsed.size)
        // Meta lines
        assertEquals("作词 : 深野香/水口哲也/玉井健二", parsed[0].text)
        assertEquals("", parsed[0].transText)
        assertEquals("作曲 : 玉井健二/飛内将大", parsed[1].text)
        assertEquals("", parsed[1].transText)

        // Bilingual lines
        assertEquals(7680L, parsed[2].timestampMs)
        assertEquals("Stop listen rewind", parsed[2].text)
        assertEquals("别再听倒带了", parsed[2].transText)

        assertEquals(10490L, parsed[3].timestampMs)
        assertEquals("Come a little closer", parsed[3].text)
        assertEquals("靠近我一些", parsed[3].transText)

        assertEquals(14260L, parsed[4].timestampMs)
        assertEquals("Listen to the silence beating hard", parsed[4].text)
        assertEquals("仔细听那无声的跳动", parsed[4].transText)

        assertEquals(17560L, parsed[5].timestampMs)
        assertEquals("Where ever you go", parsed[5].text)
        assertEquals("随处紧跟着你", parsed[5].transText)
    }

    @Test
    fun `parseMergedLyrics preserves single language lyrics correctly without merging`() {
        val monoLrc =
            """
            [00:01.00]First line
            [00:05.00]Second line
            [00:10.00]Third line
            """.trimIndent()

        val parsed = LyricParser.parseMergedLyrics(monoLrc, null)
        assertEquals(3, parsed.size)
        assertEquals("First line", parsed[0].text)
        assertEquals("", parsed[0].transText)
        assertEquals("Second line", parsed[1].text)
        assertEquals("", parsed[1].transText)
        assertEquals("Third line", parsed[2].text)
        assertEquals("", parsed[2].transText)
    }

    @Test
    fun `isMetaInfoLine recognizes multilingual metadata roles correctly`() {
        // 中文/全角/半角/带空格
        assertTrue(LyricParser.isMetaInfoLine(0L, "作词 : 深野香"))
        assertTrue(LyricParser.isMetaInfoLine(0L, "作曲：玉井健二"))
        assertTrue(LyricParser.isMetaInfoLine(0L, "制作人: 张三"))
        assertTrue(LyricParser.isMetaInfoLine(0L, "编曲: 李四"))

        // 日文/繁体
        assertTrue(LyricParser.isMetaInfoLine(0L, "作詞 : 梶浦由記"))
        assertTrue(LyricParser.isMetaInfoLine(0L, "編曲 : Tim Vegas"))

        // 韩文
        assertTrue(LyricParser.isMetaInfoLine(0L, "작사 : G-DRAGON"))
        assertTrue(LyricParser.isMetaInfoLine(0L, "작곡 : TEDDY"))

        // 英文
        assertTrue(LyricParser.isMetaInfoLine(0L, "Lyrics by: Jamie Scott"))
        assertTrue(LyricParser.isMetaInfoLine(0L, "Composed by: Jamie Scott"))
        assertTrue(LyricParser.isMetaInfoLine(0L, "Arranged by : John Doe"))
        assertTrue(LyricParser.isMetaInfoLine(0L, "Mixing Engineer: Will Chen"))

        // 版权声明
        assertTrue(LyricParser.isMetaInfoLine(0L, "TME享有本翻译作品的著作权"))

        // 普通正文歌词不应被误判
        assertFalse(LyricParser.isMetaInfoLine(10000L, "Stop listen rewind"))
        assertFalse(LyricParser.isMetaInfoLine(10000L, "别再听倒带了"))
        assertFalse(LyricParser.isMetaInfoLine(10000L, "这是一句普通的歌词"))
        assertFalse(LyricParser.isMetaInfoLine(2000L, "— 我依然在这里"))
    }
}
