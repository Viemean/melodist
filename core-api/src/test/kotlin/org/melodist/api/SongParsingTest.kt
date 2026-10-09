package org.melodist.api

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.melodist.model.AudioQualityTier

class SongParsingTest {
    @Test
    fun `parseSongFromElement with nested track structure correctly extracts fields`() {
        val json =
            """
            {
              "track": {
                "id": 1001,
                "mid": "0039MnYb0qxYhV",
                "title": "七里香",
                "interval": 299,
                "singer": [
                  { "name": "周杰伦" },
                  { "name": "方文山" }
                ],
                "album": {
                  "name": "七里香",
                  "mid": "00333Ukj2Backend"
                }
              }
            }
            """.trimIndent()

        val element = Json.parseToJsonElement(json)
        val song = MusicApiService.parseSongFromElement(element)

        assertNotNull(song)
        assertEquals(1001L, song!!.songId)
        assertEquals("0039MnYb0qxYhV", song.songMid)
        assertEquals("七里香", song.name)
        assertEquals("周杰伦 / 方文山", song.singer)
        assertEquals("七里香", song.album)
        assertEquals(299, song.durationSeconds)
        assertEquals(AudioQualityTier.SQ, song.currentTier)
        assertTrue(song.coverUrl.contains("00333Ukj2Backend"))
    }

    @Test
    fun `parseSongFromElement with flat structure and alias fields parses successfully`() {
        val json =
            """
            {
              "songid": 2002,
              "songmid": "003aAP403wPVIe",
              "songname": "晴天",
              "interval": 269,
              "singer": [
                { "singer_name": "周杰伦" }
              ],
              "albumname": "叶惠美",
              "albummid": "000YehuiMei"
            }
            """.trimIndent()

        val element = Json.parseToJsonElement(json)
        val song = MusicApiService.parseSongFromElement(element)

        assertNotNull(song)
        assertEquals(2002L, song!!.songId)
        assertEquals("003aAP403wPVIe", song.songMid)
        assertEquals("晴天", song.name)
        assertEquals("周杰伦", song.singer)
        assertEquals("叶惠美", song.album)
        assertEquals(269, song.durationSeconds)
    }

    @Test
    fun `parseSongFromElement returns null when mid is missing`() {
        val json =
            """
            {
              "id": 3003,
              "title": "缺失mid曲目"
            }
            """.trimIndent()

        val element = Json.parseToJsonElement(json)
        val song = MusicApiService.parseSongFromElement(element)

        assertNull(song)
    }

    @Test
    fun `parseSongFromElement with single song and vs array extracts visualMid and generates cover`() {
        val json =
            """
            {
              "track": {
                "id": 4004,
                "mid": "002KmAc20f9e73",
                "title": "君が生きてなくてよかった (Single Version)",
                "interval": 210,
                "singer": [
                  { "name": "ヰ世界情緒" }
                ],
                "album": {
                  "name": "",
                  "mid": ""
                },
                "vs": [
                  "",
                  "000hg1311IryBB"
                ]
              }
            }
            """.trimIndent()

        val element = Json.parseToJsonElement(json)
        val song = MusicApiService.parseSongFromElement(element)

        assertNotNull(song)
        assertEquals("002KmAc20f9e73", song!!.songMid)
        assertEquals("000hg1311IryBB", song.visualMid)
        assertTrue(song.coverUrl.contains("T062R1200x1200M000000hg1311IryBB.jpg"))
    }

    @Test
    fun `parseSongFromElement correctly extracts isVip based on pay_play`() {
        val vipJson =
            """
            {
              "mid": "0039MnYb0qxYhV",
              "title": "晴天",
              "pay": {
                "pay_play": 1,
                "pay_month": 1
              }
            }
            """.trimIndent()
        val vipSong = MusicApiService.parseSongFromElement(Json.parseToJsonElement(vipJson))
        assertNotNull(vipSong)
        assertTrue(vipSong!!.isVip)

        val freeJson =
            """
            {
              "mid": "004CtaWc0bTE0o",
              "title": "我曾经爱过你 I Loved You",
              "pay": {
                "pay_play": 0,
                "pay_month": 1,
                "time_free": 1
              }
            }
            """.trimIndent()
        val freeSong = MusicApiService.parseSongFromElement(Json.parseToJsonElement(freeJson))
        assertNotNull(freeSong)
        assertFalse(freeSong!!.isVip)
    }

    @Test
    fun `parseSongFromElement with empty album mid retains empty album attributes`() {
        val json =
            """
            {
              "id": 582346274,
              "mid": "002jChwo3BCtBh",
              "name": "バカみたいに",
              "singer": [
                { "name": "柿崎ユウタ" },
                { "name": "初音未来" }
              ],
              "album": {
                "id": 0,
                "mid": "",
                "name": "",
                "pmid": "0030lak94GN5Ad_0"
              }
            }
            """.trimIndent()

        val song = MusicApiService.parseSongFromElement(Json.parseToJsonElement(json))
        assertNotNull(song)
        assertEquals("002jChwo3BCtBh", song!!.songMid)
        assertEquals("", song.albumMid)
        assertEquals("", song.album)
        assertEquals("", song.coverUrl)
    }
}
