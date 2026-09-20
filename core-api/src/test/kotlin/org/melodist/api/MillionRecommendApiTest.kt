package org.melodist.api

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MillionRecommendApiTest {
    @Test
    fun `recommend feed dynamically extracts million collect card without hardcoded metadata`() {
        val testCardId = 8899001122L
        val testCoverUrl = "https://y.gtimg.cn/music/photo_new/T002R300x300M000DynamicCover.jpg"
        val testTitle = "百万收藏"

        val feedRespJson =
            """
            {
              "feed": {
                "code": 0,
                "data": {
                  "v_shelf": [
                    {
                      "id": 301,
                      "title_template": "今日为你推荐",
                      "v_niche": [
                        {
                          "v_card": [
                            {
                              "id": "$testCardId",
                              "type": 500,
                              "title": "$testTitle",
                              "cover": "$testCoverUrl"
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
              }
            }
            """.trimIndent()

        val root = Json.parseToJsonElement(feedRespJson).jsonObject
        val shelves =
            root["feed"]
                ?.jsonObject
                ?.get("data")
                ?.jsonObject
                ?.get("v_shelf")
                ?.jsonArray
        assertNotNull(shelves)

        var foundDisstid = 0L
        var foundCover = ""
        var foundTitle = ""

        shelfLoop@ for (shelf in shelves!!) {
            val niches = shelf.jsonObject["v_niche"]?.jsonArray ?: continue
            for (niche in niches) {
                val cards = niche.jsonObject["v_card"]?.jsonArray ?: continue
                for (card in cards) {
                    val title =
                        card.jsonObject["title"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            .orEmpty()
                    if (title.contains("百万") || title == "百万收藏") {
                        foundDisstid = card.jsonObject["id"]?.jsonPrimitive?.longOrNull ?: 0L
                        foundCover =
                            card.jsonObject["cover"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                .orEmpty()
                        foundTitle = title
                        if (foundDisstid > 0L) break@shelfLoop
                    }
                }
            }
        }

        assertEquals(testCardId, foundDisstid)
        assertEquals(testTitle, foundTitle)
        assertEquals(testCoverUrl, foundCover)
    }

    @Test
    fun `uniform_get_Dissinfo dynamically parses arbitrary song list from gateway`() {
        val songCount = 50
        val generatedSongs =
            (1..songCount).joinToString(",") { i ->
                """
                {
                  "id": $i,
                  "mid": "song_mid_$i",
                  "name": "Daily_Track_$i",
                  "interval": 180,
                  "singer": [{ "id": $i, "mid": "singer_mid_$i", "name": "Singer_$i" }],
                  "album": { "id": $i, "mid": "album_mid_$i", "name": "Album_$i" }
                }
                """.trimIndent()
            }

        val dissRespJson =
            """
            {
              "req_diss": {
                "code": 0,
                "data": {
                  "dirinfo": {
                    "id": 8899001122,
                    "title": "百万收藏",
                    "desc": "官方每日更新说明",
                    "picurl": "https://y.gtimg.cn/music/photo_new/dynamic_cover.jpg",
                    "songnum": $songCount
                  },
                  "total_song_num": $songCount,
                  "songlist": [ $generatedSongs ]
                }
              }
            }
            """.trimIndent()

        val root = Json.parseToJsonElement(dissRespJson).jsonObject
        val data = root["req_diss"]?.jsonObject?.get("data")?.jsonObject
        assertNotNull(data)

        val dirinfo = data!!["dirinfo"]?.jsonObject
        val desc =
            dirinfo
                ?.get("desc")
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
        val picUrl =
            dirinfo
                ?.get("picurl")
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
        val title =
            dirinfo
                ?.get("title")
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()

        val songArray = data["songlist"]?.jsonArray
        assertNotNull(songArray)

        val songs = songArray!!.mapNotNull { MusicApiService.parseSongFromElement(it) }
        val result =
            MillionRecommendResult(
                title = title,
                description = desc,
                coverUrl = picUrl,
                songs = songs,
            )

        assertEquals("百万收藏", result.title)
        assertEquals("官方每日更新说明", result.description)
        assertEquals(songCount, result.songs.size)
        assertEquals("Daily_Track_1", result.songs.first().name)
        assertEquals("Daily_Track_$songCount", result.songs.last().name)
    }

    @Test
    fun `millionRecommendResult default values are initialized safely`() {
        val emptyResult = MillionRecommendResult()
        assertEquals("百万收藏", emptyResult.title)
        assertTrue(emptyResult.description.isEmpty())
        assertTrue(emptyResult.coverUrl.isEmpty())
        assertTrue(emptyResult.songs.isEmpty())
    }
}
