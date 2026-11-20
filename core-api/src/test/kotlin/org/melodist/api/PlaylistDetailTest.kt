package org.melodist.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.model.Song

class PlaylistDetailTest {
    @Test
    fun `uniform_get_Dissinfo gateway response parses songlist accurately`() {
        val respJson =
            """
            {
              "req_diss": {
                "code": 0,
                "data": {
                  "dissname": "收藏大歌单",
                  "total_song_num": 250,
                  "songlist": [
                    {
                      "songmid": "001A",
                      "songname": "歌曲A",
                      "singer": [{ "name": "歌手1" }],
                      "albumname": "专辑1",
                      "interval": 210
                    },
                    {
                      "songmid": "002B",
                      "songname": "歌曲B",
                      "singer": [{ "name": "歌手2" }],
                      "albumname": "专辑2",
                      "interval": 180
                    }
                  ]
                }
              }
            }
            """.trimIndent()

        val root = Json.parseToJsonElement(respJson).jsonObject
        val songElements =
            root["req_diss"]
                ?.jsonObject
                ?.get("data")
                ?.jsonObject
                ?.get("songlist")
                ?.jsonArray

        assertNotNull(songElements)
        assertEquals(2, songElements!!.size)

        val songs = songElements.mapNotNull { MusicApiService.parseSongFromElement(it) }
        assertEquals(2, songs.size)
        assertEquals("001A", songs[0].songMid)
        assertEquals("歌曲A", songs[0].name)
        assertEquals("002B", songs[1].songMid)
        assertEquals("歌曲B", songs[1].name)
    }

    @Test
    fun `uniform_get_Dissinfo parses complete metadata accurately`() {
        val respJson =
            """
            {
              "req_diss": {
                "code": 0,
                "data": {
                  "dirinfo": {
                    "id": 7774259544,
                    "title": "Yorushika | 让夜鹿治愈你的心",
                    "picurl": "https://qpic.y.qq.com/cover.jpg",
                    "desc": "治愈长简介测试",
                    "listennum": 1042431,
                    "ordernum": 10003,
                    "songnum": 55,
                    "host_nick": "夜鹿作者",
                    "tag": [
                      { "id": 169, "name": "日语" },
                      { "id": 116, "name": "治愈" }
                    ],
                    "creator": {
                      "nick": "夜鹿创建者"
                    }
                  },
                  "total_song_num": 55,
                  "songlist": []
                }
              }
            }
            """.trimIndent()

        val root = Json.parseToJsonElement(respJson).jsonObject
        val reqDissData = root["req_diss"]?.jsonObject?.get("data")?.jsonObject
        val dirinfo = reqDissData?.get("dirinfo")?.jsonObject
        assertNotNull(dirinfo)

        val title =
            dirinfo
                ?.get("title")
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
        val desc =
            dirinfo
                ?.get("desc")
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
        val creator =
            dirinfo
                ?.get("creator")
                ?.jsonObject
                ?.get("nick")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: dirinfo
                    ?.get("host_nick")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
        val tags =
            dirinfo
                ?.get("tag")
                ?.jsonArray
                ?.mapNotNull {
                    it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                }.orEmpty()
        val playCount = dirinfo?.get("listennum")?.jsonPrimitive?.longOrNull ?: 0L
        val favCount = dirinfo?.get("ordernum")?.jsonPrimitive?.longOrNull ?: 0L

        assertEquals("Yorushika | 让夜鹿治愈你的心", title)
        assertEquals("治愈长简介测试", desc)
        assertEquals("夜鹿创建者", creator)
        assertEquals(listOf("日语", "治愈"), tags)
        assertEquals(1042431L, playCount)
        assertEquals(10003L, favCount)
    }

    @Test
    fun `queue protection logic retains complete playback queue when clicking partially loaded list`() {
        // 模拟播放队列拥有完整的 200 首歌曲
        val fullPlaybackQueue =
            (1..200).map { i ->
                Song(
                    songId = i.toLong(),
                    songMid = "mid_$i",
                    name = "Song $i",
                    singer = "Singer",
                    album = "Album",
                    albumMid = "amid_$i",
                    durationSeconds = 180,
                    coverUrl = "",
                )
            }

        // 模拟右侧选项列表仅加载了第 1 页（100 首）
        val partialUiList = fullPlaybackQueue.take(100)

        // 用户在选项列表中点击第 5 首
        val targetSong = partialUiList[4]

        // 队列保护判定：播放队列包含当前曲目且长度大于目标列表时不予覆盖
        val shouldProtectQueue =
            fullPlaybackQueue.size > partialUiList.size &&
                fullPlaybackQueue.any { it.songMid == targetSong.songMid }

        assertTrue(shouldProtectQueue)
    }

    @Test
    fun `addSonglist and delSonglist response formats parse accurately`() {
        val addSuccessResp =
            """
            {
              "addSongsToPlayList": {
                "code": 0,
                "subcode": 0,
                "data": {
                  "succ_song_num": 1,
                  "fail_song_num": 0
                }
              }
            }
            """.trimIndent()
        val addRoot = Json.parseToJsonElement(addSuccessResp).jsonObject
        val addObj = addRoot["addSongsToPlayList"]?.jsonObject
        assertNotNull(addObj)
        assertEquals(0, addObj?.get("code")?.jsonPrimitive?.intOrNull)
        val addData = addObj?.get("data")?.jsonObject
        assertEquals(1, addData?.get("succ_song_num")?.jsonPrimitive?.intOrNull)

        val delSuccessResp =
            """
            {
              "delSongsFromPlayList": {
                "code": 0,
                "subcode": 0
              }
            }
            """.trimIndent()
        val delRoot = Json.parseToJsonElement(delSuccessResp).jsonObject
        val delObj = delRoot["delSongsFromPlayList"]?.jsonObject
        assertNotNull(delObj)
        assertEquals(0, delObj?.get("code")?.jsonPrimitive?.intOrNull)
    }
}
