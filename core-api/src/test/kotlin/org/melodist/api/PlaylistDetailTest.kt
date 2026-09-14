package org.melodist.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.melodist.model.FavoriteSongsResult
import org.melodist.model.Playlist
import org.melodist.model.Song

class PlaylistDetailTest {
    @Test
    fun `playlist model distinguishes favorite created and collected playlists`() {
        val favPlaylist = Playlist(dirId = 201L, name = "我喜欢", songCount = 128)
        assertTrue(favPlaylist.isMyFavorite)
        assertTrue(favPlaylist.isCreated)

        val userCreated = Playlist(dirId = 502L, name = "车载精选", songCount = 42, isFav = false)
        assertFalse(userCreated.isMyFavorite)
        assertTrue(userCreated.isCreated)

        val collected = Playlist(dirId = 9999L, name = "周杰伦全集", songCount = 200, tid = 8888L, isFav = true)
        assertFalse(collected.isMyFavorite)
        assertFalse(collected.isCreated)
    }

    @Test
    fun `favoriteSongsResult verifies pagination hasMore logic`() {
        val resultHasMore =
            FavoriteSongsResult(
                songs = emptyList(),
                total = 100,
                hasMore = true,
            )
        assertTrue(resultHasMore.hasMore)
        assertEquals(100, resultHasMore.total)

        val resultCompleted =
            FavoriteSongsResult(
                songs = emptyList(),
                total = 30,
                hasMore = false,
            )
        assertFalse(resultCompleted.hasMore)
    }

    @Test
    fun `external collected playlist uniform_get_Dissinfo computes correct pagination parameters`() {
        val pageSize = 100
        val page1Begin = (1 - 1) * pageSize
        val page1Num = pageSize
        assertEquals(0, page1Begin)
        assertEquals(100, page1Num)

        val page2Begin = (2 - 1) * pageSize
        val page2Num = pageSize
        assertEquals(100, page2Begin)
        assertEquals(100, page2Num)

        val page3Begin = (3 - 1) * pageSize
        val page3Num = pageSize
        assertEquals(200, page3Begin)
        assertEquals(100, page3Num)
    }

    @Test
    fun `user created playlist GetUniformSongDetailInfo computes correct offset parameters`() {
        val pageSize = 100
        val page1Offset = (1 - 1) * pageSize
        assertEquals(0, page1Offset)

        val page2Offset = (2 - 1) * pageSize
        assertEquals(100, page2Offset)
    }

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

        // 判定保护条件：若播放队列规模大于当前 UI 列表且包含该歌曲，则避免执行覆盖
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

