package org.melodist.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.melodist.model.Song

/**
 * 智能推荐与电台雷达扩展
 */

suspend fun MusicApiService.getDailyRecommendSongs(): List<Song> =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) return@withContext emptyList()
        try {
            LoginApiService().ensureMusicKey()
        } catch (_: Exception) {
        }

        val uin = UserSession.profile.uin.ifBlank { "0" }
        val authst = UserSession.profile.musicKey

        try {
            // 阶段一：通过推荐 Feed 获取今日“每日30首”歌单专属 ID (disstid)
            val feedPayload =
                """
                {
                  "comm": { "uin": "$uin", "format": "json", "ct": 19, "cv": 1, "authst": "$authst" },
                  "feed": {
                    "module": "music.recommend.RecommendFeed",
                    "method": "get_recommend_feed",
                    "param": { "direction": 0, "page": 1, "s_num": 0, "v_cache": [] }
                  }
                }
                """.trimIndent()

            var dailyDisstid = 0L
            val feedJson = postGateway(feedPayload)
            val feedRoot = Json.parseToJsonElement(feedJson).jsonObject
            val shelves =
                feedRoot["feed"]
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonObject
                    ?.get("v_shelf")
                    ?.jsonArray

            if (shelves != null) {
                shelfLoop@ for (shelf in shelves) {
                    val niches = shelf.jsonObject["v_niche"]?.jsonArray ?: continue
                    for (niche in niches) {
                        val cards = niche.jsonObject["v_card"]?.jsonArray ?: continue
                        for (card in cards) {
                            val title =
                                card.jsonObject["title"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    .orEmpty()
                            if (title.contains("30首") || title.contains("每日30") || title == "每日30首") {
                                dailyDisstid = card.jsonObject["id"]?.jsonPrimitive?.longOrNull ?: 0L
                                if (dailyDisstid > 0L) break@shelfLoop
                            }
                        }
                    }
                }
            }

            if (dailyDisstid <= 0L) return@withContext emptyList()

            // 阶段二：通过 uniform_get_Dissinfo 拉取专属推荐歌单全部歌曲
            val dissPayload =
                """
                {
                  "comm": { "uin": "$uin", "format": "json", "ct": 19, "cv": 1, "authst": "$authst" },
                  "req_diss": {
                    "module": "music.srfDissInfo.aiDissInfo",
                    "method": "uniform_get_Dissinfo",
                    "param": { "disstid": $dailyDisstid, "userinfo": 1, "tag": 1 }
                  }
                }
                """.trimIndent()

            val dissJson = postGateway(dissPayload)
            val dissRoot = Json.parseToJsonElement(dissJson).jsonObject
            val songArray =
                dissRoot["req_diss"]
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonObject
                    ?.get("songlist")
                    ?.jsonArray ?: return@withContext emptyList()

            songArray.mapNotNull { MusicApiService.parseSongFromElement(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

suspend fun MusicApiService.getGuessRecommendSongs(count: Int = 25): List<Song> =
    withContext(Dispatchers.IO) {
        if (UserSession.isLoggedIn) {
            try {
                LoginApiService().ensureMusicKey()
            } catch (_: Exception) {
            }
        }

        val uin = UserSession.profile.uin.ifBlank { "0" }
        val authst = UserSession.profile.musicKey
        val result = mutableListOf<Song>()
        val seenMids = mutableSetOf<String>()

        try {
            val maxBatches = (count / 15 + 1).coerceIn(1, 4)
            for (batch in 0 until maxBatches) {
                if (result.size >= count) break
                val payload =
                    """
                    {
                      "comm": { "uin": "$uin", "format": "json", "ct": 19, "cv": 1, "authst": "$authst" },
                      "guess": {
                        "module": "music.radioProxy.MbTrackRadioSvr",
                        "method": "get_radio_track",
                        "param": { "id": 99, "num": 15, "from": 0, "scene": 0, "song_ids": [] }
                      }
                    }
                    """.trimIndent()

                val respJson = postGateway(payload)
                val root = Json.parseToJsonElement(respJson).jsonObject
                val tracks =
                    root["guess"]
                        ?.jsonObject
                        ?.get("data")
                        ?.jsonObject
                        ?.get("tracks")
                        ?.jsonArray ?: break

                var addedInBatch = 0
                for (item in tracks) {
                    val song = MusicApiService.parseSongFromElement(item)
                    if (song != null && song.songMid.isNotBlank() && seenMids.add(song.songMid)) {
                        result.add(song)
                        addedInBatch++
                    }
                }
                if (addedInBatch == 0 && batch >= 2) break
            }
            result
        } catch (_: Exception) {
            result
        }
    }

suspend fun MusicApiService.getTopList(
    topId: Int = 62, // 62: 飙升榜, 26: 热歌榜, 27: 新歌榜
    page: Int = 1,
    pageSize: Int = 50,
): List<Song> =
    withContext(Dispatchers.IO) {
        val payload =
            """
            {
              "comm": { "ct": 24, "cv": 0 },
              "toplist": {
                "module": "musicToplist.ToplistInfoServer",
                "method": "GetDetail",
                "param": { "topId": $topId, "offset": ${(page - 1) * pageSize}, "num": $pageSize, "period": "" }
              }
            }
            """.trimIndent()

        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val songList =
                root["toplist"]
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonObject
                    ?.get("songInfoList")
                    ?.jsonArray ?: return@withContext emptyList()

            songList.mapNotNull { MusicApiService.parseSongFromElement(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
