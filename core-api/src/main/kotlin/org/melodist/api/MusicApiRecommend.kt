package org.melodist.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.melodist.model.RecommendShelf
import org.melodist.model.Song

/**
 * 智能推荐与电台雷达扩展
 */

@Serializable
data class DailyRecommendResult(
    val description: String = "",
    val songs: List<Song> = emptyList(),
)

suspend fun MusicApiService.getDailyRecommendDetail(): DailyRecommendResult =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) return@withContext DailyRecommendResult()
        ensureMusicKeySafe()

        val uin = UserSession.profile.uin.ifBlank { "0" }
        val authst = UserSession.profile.musicKey

        try {
            // 阶段一：通过推荐 Feed 获取今日“每日30首”歌单专属 ID (disstid)
            val feedPayload =
                """
                {
                  "comm": { "uin": "$uin", "format": "json", "ct": 20, "cv": 1770, "platform": "wk_v17", "authst": "$authst" },
                  "feed": {
                    "module": "music.recommend.RecommendFeed",
                    "method": "get_recommend_feed",
                    "param": { "direction": 0, "page": 1, "s_num": 6, "v_cache": [], "v_uniq": [] }
                  }
                }
                """.trimIndent()

            var dailyDisstid = 0L
            val feedJson = postGateway(feedPayload)
            val feedRoot = Json.parseToJsonElement(feedJson).jsonObject
            val feedObj = (feedRoot["feed"] ?: feedRoot["req_0"])?.jsonObject
            val shelves =
                feedObj
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
                            val cardObj = card.jsonObject
                            val title =
                                cardObj["title"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    .orEmpty()
                            if (title.contains("30首") || title.contains("每日30") || title == "每日30首") {
                                val miscellany = cardObj["miscellany"]?.jsonObject
                                dailyDisstid =
                                    cardObj["id"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0L }
                                        ?: miscellany?.get("dirid")?.jsonPrimitive?.longOrNull
                                        ?: 202L
                                if (dailyDisstid > 0L) break@shelfLoop
                            }
                        }
                    }
                }
            }

            if (dailyDisstid <= 0L) dailyDisstid = 202L

            // 阶段二：通过 uniform_get_Dissinfo 拉取专属推荐歌单全部歌曲与官方描述
            val dissPayload =
                """
                {
                  "comm": { "uin": "$uin", "format": "json", "ct": 20, "cv": 1770, "platform": "wk_v17", "authst": "$authst" },
                  "req_diss": {
                    "module": "music.srfDissInfo.aiDissInfo",
                    "method": "uniform_get_Dissinfo",
                    "param": { "disstid": $dailyDisstid, "userinfo": 1, "tag": 1, "song_begin": 0, "song_num": 30 }
                  }
                }
                """.trimIndent()

            val dissJson = postGateway(dissPayload)
            val dissRoot = Json.parseToJsonElement(dissJson).jsonObject
            val reqDissData = dissRoot["req_diss"]?.jsonObject?.get("data")?.jsonObject
            val dirinfo = reqDissData?.get("dirinfo")?.jsonObject
            val description =
                dirinfo
                    ?.get("desc")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()

            val songArray =
                reqDissData
                    ?.get("songlist")
                    ?.jsonArray ?: return@withContext DailyRecommendResult(description = description)

            val songs = songArray.mapNotNull { MusicApiService.parseSongFromElement(it) }
            DailyRecommendResult(description = description, songs = songs)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiRecommend", "getDailyRecommendDetail failed", e)
            DailyRecommendResult()
        }
    }

suspend fun MusicApiService.getDailyRecommendSongs(): List<Song> = getDailyRecommendDetail().songs

@Serializable
data class MillionRecommendResult(
    val disstid: Long = 211111L,
    val title: String = "百万收藏",
    val description: String = "",
    val coverUrl: String = "",
    val totalSongNum: Int = 0,
    val songs: List<Song> = emptyList(),
)

suspend fun MusicApiService.getMillionRecommendDetail(): MillionRecommendResult =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) return@withContext MillionRecommendResult()
        ensureMusicKeySafe()

        val uin = UserSession.profile.uin.ifBlank { "0" }
        val authst = UserSession.profile.musicKey

        try {
            // 阶段一：通过推荐 Feed 获取今日“百万收藏”专属 ID (disstid) 与封面
            val feedPayload =
                """
                {
                  "comm": { "uin": "$uin", "format": "json", "ct": 20, "cv": 1770, "platform": "wk_v17", "authst": "$authst" },
                  "feed": {
                    "module": "music.recommend.RecommendFeed",
                    "method": "get_recommend_feed",
                    "param": { "direction": 0, "page": 1, "s_num": 6, "v_cache": [], "v_uniq": [] }
                  }
                }
                """.trimIndent()

            var millionDisstid = 0L
            var cardCoverUrl = ""
            var cardTitle = "百万收藏"
            val feedJson = postGateway(feedPayload)
            val feedRoot = Json.parseToJsonElement(feedJson).jsonObject
            val feedObj = (feedRoot["feed"] ?: feedRoot["req_0"])?.jsonObject
            val shelves =
                feedObj
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
                            val cardObj = card.jsonObject
                            val title =
                                cardObj["title"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    .orEmpty()
                            if (title.contains("百万") || title == "百万收藏") {
                                millionDisstid = cardObj["id"]?.jsonPrimitive?.longOrNull ?: 211111L
                                cardCoverUrl = cardObj["cover"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                if (title.isNotBlank()) cardTitle = title
                                break@shelfLoop
                            }
                        }
                    }
                }
            }

            if (millionDisstid <= 0L) {
                millionDisstid = 211111L
            }

            // 阶段二：通过 uniform_get_Dissinfo 拉取专属推荐歌单全部歌曲与官方描述
            val dissPayload =
                """
                {
                  "comm": { "uin": "$uin", "format": "json", "ct": 20, "cv": 1770, "platform": "wk_v17", "authst": "$authst" },
                  "req_diss": {
                    "module": "music.srfDissInfo.aiDissInfo",
                    "method": "uniform_get_Dissinfo",
                    "param": { "disstid": $millionDisstid, "userinfo": 1, "tag": 1, "song_begin": 0, "song_num": 50 }
                  }
                }
                """.trimIndent()

            val dissJson = postGateway(dissPayload)
            val dissRoot = Json.parseToJsonElement(dissJson).jsonObject
            val reqDissData = dissRoot["req_diss"]?.jsonObject?.get("data")?.jsonObject
            val dirinfo = reqDissData?.get("dirinfo")?.jsonObject
            val description =
                dirinfo
                    ?.get("desc")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
                    .ifBlank { "每一首歌曲都超过百万收藏 · 每日更新" }
            val coverUrl =
                dirinfo
                    ?.get("picurl")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: cardCoverUrl

            val totalSongNum =
                reqDissData
                    ?.get("total_song_num")
                    ?.jsonPrimitive
                    ?.intOrNull
                    ?: dirinfo?.get("songnum")?.jsonPrimitive?.intOrNull
                    ?: 50

            val songArray = reqDissData?.get("songlist")?.jsonArray
            val songs = songArray?.mapNotNull { MusicApiService.parseSongFromElement(it) }.orEmpty()

            MillionRecommendResult(
                disstid = millionDisstid,
                title = cardTitle,
                description = description,
                coverUrl = coverUrl,
                totalSongNum = if (songs.isNotEmpty()) songs.size else totalSongNum,
                songs = songs,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiRecommend", "getMillionRecommendDetail failed", e)
            MillionRecommendResult()
        }
    }

suspend fun MusicApiService.getMillionRecommendSongs(): List<Song> = getMillionRecommendDetail().songs

suspend fun MusicApiService.getGuessRecommendSongs(count: Int = 25): List<Song> =
    withContext(Dispatchers.IO) {
        if (UserSession.isLoggedIn) {
            ensureMusicKeySafe()
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
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiRecommend", "getGuessRecommendSongs failed", e)
            emptyList()
        }
    }

suspend fun MusicApiService.getTrackInfoBatch(songIds: List<Long>): List<Song> =
    withContext(Dispatchers.IO) {
        if (songIds.isEmpty()) return@withContext emptyList()
        val uin = UserSession.profile.uin.ifBlank { "0" }
        val idsJson = songIds.joinToString(",", "[", "]")
        val typesJson = songIds.joinToString(",", "[", "]") { "200" }

        val payload =
            """
            {
              "comm": { "ct": 20, "cv": 1770, "uin": "$uin", "format": "json", "platform": "wk_v17" },
              "req_0": {
                "module": "music.trackInfo.UniformRuleCtrl",
                "method": "CgiGetTrackInfo",
                "param": {
                  "ids": $idsJson,
                  "types": $typesJson,
                  "source": "AiNoFree"
                }
              }
            }
            """.trimIndent()

        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val tracks =
                root["req_0"]
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonObject
                    ?.get("tracks")
                    ?.jsonArray ?: return@withContext emptyList()

            tracks.mapNotNull { MusicApiService.parseSongFromElement(it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiRecommend", "getTrackInfoBatch failed", e)
            emptyList()
        }
    }

suspend fun MusicApiService.getRecommendFeed(
    direction: Int = 0,
    page: Int = 1,
    sNum: Int = 6,
): List<RecommendShelf> =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) return@withContext emptyList()
        ensureMusicKeySafe()

        val uin = UserSession.profile.uin.ifBlank { "0" }
        val authst = UserSession.profile.musicKey

        val payload =
            """
            {
              "comm": { "uin": "$uin", "format": "json", "ct": 20, "cv": 1770, "platform": "wk_v17", "authst": "$authst" },
              "feed": {
                "module": "music.recommend.RecommendFeed",
                "method": "get_recommend_feed",
                "param": { "direction": $direction, "page": $page, "v_cache": [], "v_uniq": [], "s_num": $sNum }
              }
            }
            """.trimIndent()

        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val shelvesArray =
                root["feed"]
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonObject
                    ?.get("v_shelf")
                    ?.jsonArray ?: return@withContext emptyList()

            val shelves = mutableListOf<RecommendShelf>()
            for (shelfElem in shelvesArray) {
                val shelfObj = shelfElem.jsonObject
                val rawTemplate = shelfObj["title_template"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val titleContent = shelfObj["title_content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val group = shelfObj["group"]?.jsonPrimitive?.intOrNull ?: 0
                val style = shelfObj["style"]?.jsonPrimitive?.intOrNull ?: 0

                val moreObj = shelfObj["more"]?.jsonObject
                val moreTitle =
                    moreObj
                        ?.get("title")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                val moreId =
                    moreObj
                        ?.get("id")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()

                var title = shelfObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (rawTemplate.isNotBlank()) {
                    title =
                        if (titleContent.isNotBlank()) {
                            rawTemplate.replace("{String}", titleContent)
                        } else {
                            rawTemplate
                        }
                }
                title = title.replace(Regex("[💗❤️💖💕💓💘🤍🖤🤎💜💙💚💛🧡♥]"), "").trim()
                if (title.isBlank()) {
                    continue
                }

                // 仅保留基于特定种子衍生的“听「xxxx」的也在听 / 喜欢”专属货架，过滤掉“今日为你推荐”等非「xxxx」货架
                val isTargetShelf =
                    titleContent.isNotBlank() &&
                        (rawTemplate.contains("听") || title.contains("听「")) &&
                        (rawTemplate.contains("也在听") || rawTemplate.contains("喜欢") || title.contains("也在听") || title.contains("喜欢"))
                if (!isTargetShelf) {
                    continue
                }

                val niches = shelfObj["v_niche"]?.jsonArray ?: continue
                val cardSongsFallback = mutableListOf<Song>()
                val songIds = mutableListOf<Long>()

                for (niche in niches) {
                    val cards = niche.jsonObject["v_card"]?.jsonArray ?: continue
                    for (cardElem in cards) {
                        val cardObj = cardElem.jsonObject
                        val id = cardObj["id"]?.jsonPrimitive?.longOrNull ?: 0L
                        val cardTitle = cardObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val cardSubtitle = cardObj["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val cardCover = cardObj["cover"]?.jsonPrimitive?.contentOrNull.orEmpty()

                        if (id > 0L) {
                            songIds.add(id)
                            cardSongsFallback.add(
                                Song(
                                    songId = id,
                                    name = cardTitle,
                                    singer = cardSubtitle,
                                    coverUrl = cardCover,
                                ),
                            )
                        }
                    }
                }

                if (songIds.isEmpty()) continue

                // 批量获取高精度 Track 元数据
                val detailedSongs =
                    try {
                        getTrackInfoBatch(songIds)
                    } catch (_: Exception) {
                        emptyList()
                    }

                val finalSongs =
                    if (detailedSongs.isNotEmpty()) {
                        // 按原始 ID 顺序重排并补充缺失项
                        val detailedMap = detailedSongs.associateBy { it.songId }
                        songIds.mapNotNull { id ->
                            detailedMap[id] ?: cardSongsFallback.firstOrNull { it.songId == id }
                        }
                    } else {
                        cardSongsFallback
                    }

                if (finalSongs.isNotEmpty()) {
                    shelves.add(
                        RecommendShelf(
                            title = title,
                            rawTemplate = rawTemplate,
                            titleContent = titleContent,
                            group = group,
                            style = style,
                            moreTitle = moreTitle,
                            moreId = moreId,
                            songs = finalSongs,
                        ),
                    )
                }
            }
            shelves
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiRecommend", "getRecommendFeed failed: direction=$direction, page=$page", e)
            emptyList()
        }
    }
