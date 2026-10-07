package org.melodist.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SimilarRecommendApiTest {
    @Test
    fun `GetSimilarSongs response with vecSong properly parsed to Song entities`() {
        val jsonStr =
            """
            {
              "code": 0,
              "similar": {
                "code": 0,
                "data": {
                  "retcode": 0,
                  "vecSong": [
                    {
                      "track": {
                        "id": 108993031,
                        "mid": "002smtI04TvmR2",
                        "name": "ON FIRE",
                        "title": "ON FIRE",
                        "subtitle": "原曲：エクステンドアッシュ",
                        "singer": [
                          {
                            "id": 945044,
                            "mid": "003xLqdz3IbB42",
                            "name": "Vivienne"
                          }
                        ],
                        "album": {
                          "id": 1661400,
                          "mid": "001xpyvQ3bS4mw",
                          "name": "DANCE with WOLVES"
                        },
                        "interval": 311,
                        "pay": {
                          "pay_play": 0
                        }
                      }
                    },
                    {
                      "track": {
                        "id": 201624337,
                        "mid": "001ozpU21CA5Sl",
                        "name": "Listen Up",
                        "title": "Listen Up",
                        "singer": [
                          {
                            "id": 950866,
                            "mid": "002kQm1o3JJjwJ",
                            "name": "3L"
                          }
                        ],
                        "album": {
                          "id": 1977117,
                          "mid": "001dk4xu1dY2It",
                          "name": "天降り立ちて神と見ゆ"
                        },
                        "interval": 342,
                        "pay": {
                          "pay_play": 1
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()

        val root = Json.parseToJsonElement(jsonStr).jsonObject
        val dataObj = root["similar"]?.jsonObject?.get("data")?.jsonObject
        val vecSongs = dataObj?.get("vecSong")?.jsonArray
        assertNotNull(vecSongs)
        assertEquals(2, vecSongs?.size)

        val songs = vecSongs!!.mapNotNull { MusicApiService.parseSongFromElement(it) }
        assertEquals(2, songs.size)

        val first = songs[0]
        assertEquals(108993031L, first.songId)
        assertEquals("002smtI04TvmR2", first.songMid)
        assertEquals("ON FIRE", first.name)
        assertEquals("Vivienne", first.singer)
        assertEquals("DANCE with WOLVES", first.album)
        assertEquals(311, first.durationSeconds)
        assertFalse(first.isVip)

        val second = songs[1]
        assertEquals(201624337L, second.songId)
        assertEquals("001ozpU21CA5Sl", second.songMid)
        assertEquals("Listen Up", second.name)
        assertEquals("3L", second.singer)
        assertTrue(second.isVip)
    }
}
