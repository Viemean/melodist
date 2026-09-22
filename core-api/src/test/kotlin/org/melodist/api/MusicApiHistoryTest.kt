package org.melodist.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MusicApiHistoryTest {
    @Test
    fun `recentHistoryType codes match protocol mapping`() {
        assertEquals(2, RecentHistoryType.Song.typeCode)
        assertEquals(3, RecentHistoryType.Album.typeCode)
        assertEquals(4, RecentHistoryType.Playlist.typeCode)
        assertEquals(5, RecentHistoryType.Radio.typeCode)
        assertEquals(6, RecentHistoryType.Video.typeCode)
    }

    @Test
    fun `buildGetRecentHistoryPayload produces expected module and method`() {
        val payload =
            buildGetRecentHistoryPayload(
                type = RecentHistoryType.Song.typeCode,
                updateTime = 1720000000L,
                uin = "10001",
                authst = "test_key",
            )
        val root = Json.parseToJsonElement(payload).jsonObject
        val comm = root["comm"]?.jsonObject
        assertNotNull(comm)
        assertEquals(11, comm?.get("ct")?.jsonPrimitive?.intOrNull)
        assertEquals("10001", comm?.get("qq")?.jsonPrimitive?.content)
        assertEquals("test_key", comm?.get("authst")?.jsonPrimitive?.content)

        val reqRecent = root["req_recent"]?.jsonObject
        assertNotNull(reqRecent)
        assertEquals("music.musicasset.PlayRecentlyRead", reqRecent?.get("module")?.jsonPrimitive?.content)
        assertEquals("GetPlayRecentlyInfo", reqRecent?.get("method")?.jsonPrimitive?.content)

        val param = reqRecent?.get("param")?.jsonObject
        assertNotNull(param)
        assertEquals(2, param?.get("type")?.jsonPrimitive?.intOrNull)
        assertEquals(1720000000L, param?.get("updateTime")?.jsonPrimitive?.longOrNull())
    }

    @Test
    fun `buildReportRecentHistoryPayload produces correct PlayRecentlyWrite structure`() {
        val item = RecentReportItem(id = "001abc", type = RecentHistoryType.Song.typeCode, lastTime = 1726000000L, listenCnt = 2)
        val payload =
            buildReportRecentHistoryPayload(
                items = listOf(item),
                uin = "10001",
                authst = "test_key",
            )
        val root = Json.parseToJsonElement(payload).jsonObject
        val report = root["report_recent"]?.jsonObject
        assertNotNull(report)
        assertEquals("music.musicasset.PlayRecentlyWrite", report?.get("module")?.jsonPrimitive?.content)
        assertEquals("ReportPlayRecentlyInfo", report?.get("method")?.jsonPrimitive?.content)

        val data = report?.get("param")?.jsonObject?.get("data")
        assertNotNull(data)
    }

    @Test
    fun `buildDeleteRecentHistoryBatchPayload produces valid data array`() {
        val items =
            listOf(
                RecentDeleteItem(id = "101", type = 2),
                RecentDeleteItem(id = "102", type = 2),
            )
        val payload =
            buildDeleteRecentHistoryBatchPayload(
                items = items,
                uin = "10001",
                authst = "test_key",
            )
        val root = Json.parseToJsonElement(payload).jsonObject
        val delRecent = root["del_recent"]?.jsonObject
        assertNotNull(delRecent)
        assertEquals("DeletePlayRecentlyInfo", delRecent?.get("method")?.jsonPrimitive?.content)

        val data =
            delRecent
                ?.get("param")
                ?.jsonObject
                ?.get("data")
                ?.jsonArray
        assertNotNull(data)
        assertEquals(2, data?.size)
        assertEquals(
            "101",
            data
                ?.get(0)
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "102",
            data
                ?.get(1)
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `parseRecentSongsResponse correctly parses songList and metadata`() {
        val mockJson =
            """
            {
              "code": 0,
              "req_recent": {
                "code": 0,
                "data": {
                  "type": 1,
                  "updateTime": 1726999999,
                  "songList": [
                    {
                      "lastTime": 1726999999,
                      "listenCnt": 3,
                      "track": {
                        "id": 123456,
                        "mid": "003m3test",
                        "name": "测试歌曲",
                        "singer": [
                          { "id": 101, "mid": "00singer", "name": "测试歌手" }
                        ],
                        "album": {
                          "id": 202,
                          "mid": "00album",
                          "name": "测试专辑"
                        },
                        "interval": 240
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()

        val result = parseRecentSongsResponse(mockJson)
        assertEquals(1726999999L, result.updateTime)
        assertEquals(1, result.items.size)

        val first = result.items.first()
        assertEquals(1726999999L, first.lastTime)
        assertEquals(3, first.listenCnt)
        assertEquals(123456L, first.song.songId)
        assertEquals("003m3test", first.song.songMid)
        assertEquals("测试歌曲", first.song.name)
        assertEquals("测试歌手", first.song.singer)
        assertEquals("测试专辑", first.song.album)
        assertEquals(240, first.song.durationSeconds)
    }

    @Test
    fun `parseRecentAlbumsResponse correctly parses albumList`() {
        val mockJson =
            """
            {
              "code": 0,
              "req_recent": {
                "code": 0,
                "data": {
                  "type": 3,
                  "updateTime": 1726998888,
                  "albumList": [
                    {
                      "lastTime": 1726998888,
                      "listenCnt": 5,
                      "albumInfo": {
                        "albumID": 98765,
                        "albumMid": "00albumMid",
                        "albumName": "经典专辑",
                        "albumPic": "https://img.example.com/cover.jpg",
                        "singer": {
                          "name": "周杰伦"
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()

        val result = parseRecentAlbumsResponse(mockJson)
        assertEquals(1726998888L, result.updateTime)
        assertEquals(1, result.items.size)

        val first = result.items.first()
        assertEquals(98765L, first.albumId)
        assertEquals("00albumMid", first.albumMid)
        assertEquals("经典专辑", first.albumName)
        assertEquals("周杰伦", first.singerName)
        assertEquals("https://img.example.com/cover.jpg", first.coverUrl)
        assertEquals(1726998888L, first.lastTime)
        assertEquals(5, first.listenCnt)
    }

    @Test
    fun `parseRecentPlaylistsResponse correctly parses geDanList`() {
        val mockJson =
            """
            {
              "code": 0,
              "req_recent": {
                "code": 0,
                "data": {
                  "type": 4,
                  "updateTime": 1726997777,
                  "geDanList": [
                    {
                      "lastTime": 1726997777,
                      "listenCnt": 2,
                      "folderInfo": {
                        "tid": 88888888,
                        "title": "年度热歌榜",
                        "pic": "https://img.example.com/folder.jpg",
                        "song_count": 100,
                        "creator_nick": "官方助手"
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()

        val result = parseRecentPlaylistsResponse(mockJson)
        assertEquals(1726997777L, result.updateTime)
        assertEquals(1, result.items.size)

        val first = result.items.first()
        assertEquals(88888888L, first.tid)
        assertEquals("年度热歌榜", first.title)
        assertEquals("https://img.example.com/folder.jpg", first.coverUrl)
        assertEquals(100, first.songCount)
        assertEquals("官方助手", first.creatorNick)
        assertEquals(1726997777L, first.lastTime)
        assertEquals(2, first.listenCnt)
    }

    @Test
    fun `parseRecentSongsResponse correctly parses nested data structure from real QQ Music gateway`() {
        val mockRealGatewayJson =
            """
            {
              "code": 0,
              "req_recent": {
                "code": 0,
                "data": {
                  "type": 2,
                  "data": {
                    "allItems": {
                      "albumList": [],
                      "geDanList": []
                    },
                    "updateTime": 1727000000,
                    "songList": [
                      {
                        "lastTime": 1727000000,
                        "listenCnt": 1,
                        "track": {
                          "id": 10908804,
                          "mid": "002V8y2Z1test",
                          "name": "真实歌曲",
                          "singer": [
                            { "id": 123, "mid": "00singer", "name": "真实歌手" }
                          ],
                          "album": {
                            "id": 456,
                            "mid": "00album",
                            "name": "真实专辑"
                          },
                          "interval": 210
                        }
                      }
                    ]
                  }
                }
              }
            }
            """.trimIndent()

        val result = parseRecentSongsResponse(mockRealGatewayJson)
        assertEquals(1727000000L, result.updateTime)
        assertEquals(1, result.items.size)
        val item = result.items.first()
        assertEquals(10908804L, item.song.songId)
        assertEquals("002V8y2Z1test", item.song.songMid)
        assertEquals("真实歌曲", item.song.name)
    }

    @Test
    fun `gateway rejects unauthenticated PlayRecentlyRead with auth code instead of unknown module`() =
        kotlinx.coroutines.runBlocking {
            val service = MusicApiService()
            val payload = buildGetRecentHistoryPayload(type = 1, updateTime = 0L, uin = "0", authst = "")
            val resp = service.postGateway(payload)
            val root = Json.parseToJsonElement(resp).jsonObject
            val reqRecent = root["req_recent"]?.jsonObject
            assertNotNull(reqRecent, "Gateway response must route req_recent")
            val code = reqRecent?.get("code")?.jsonPrimitive?.intOrNull
            assertNotNull(code)
            assertTrue(code == 1000 || code == 500003, "Code must indicate auth requirement ($code)")
        }

    private fun kotlinx.serialization.json.JsonPrimitive.longOrNull(): Long? = this.content.toLongOrNull()
}
