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

/**
 * 获取当前登录用户的个性化“每日推荐”详情（包含推荐描述文案与 30 首推荐歌曲）。
 *
 * @return 每日推荐详情模型 [DailyRecommendResult]；未登录或请求失败时返回空结果
 */
suspend fun MusicApiService.getDailyRecommendDetail(): DailyRecommendResult =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) return@withContext DailyRecommendResult()
        ensureMusicKeySafe()

        val uin = UserSession.profile.uin.ifBlank { "0" }
        val authst = UserSession.profile.musicKey

        try {
            // 阶段一：通过推荐 Feed 获取“每日30首”歌单 ID (disstid)
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

            // 阶段二：通过 uniform_get_Dissinfo 拉取推荐歌单全部歌曲与描述
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

/**
 * 获取当前登录用户的个性化“每日推荐”歌曲列表。
 *
 * @return 30 首推荐歌曲列表；未登录或失败返回空列表
 */
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

/**
 * 获取“百万收藏”推荐歌单详情（含封面、标题、描述与歌曲列表）。
 *
 * @return 百万收藏歌单详情模型 [MillionRecommendResult]；未登录或请求失败时返回空结果
 */
suspend fun MusicApiService.getMillionRecommendDetail(): MillionRecommendResult =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) return@withContext MillionRecommendResult()
        ensureMusicKeySafe()

        val uin = UserSession.profile.uin.ifBlank { "0" }
        val authst = UserSession.profile.musicKey

        try {
            // 阶段一：通过推荐 Feed 获取“百万收藏”歌单 ID (disstid) 与封面
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

            // 阶段二：通过 uniform_get_Dissinfo 拉取推荐歌单全部歌曲与描述
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

/**
 * 获取“百万收藏”推荐歌单的歌曲列表。
 *
 * @return 推荐歌曲列表；未登录或失败返回空列表
 */
suspend fun MusicApiService.getMillionRecommendSongs(): List<Song> = getMillionRecommendDetail().songs

/**
 * 获取基于用户画像与收听偏好的“猜你喜欢”个性化推荐歌曲。
 *
 * @param count 期望拉取的歌曲数量，默认 25 首
 * @return 推荐歌曲列表；请求失败时返回已成功收集的部分结果或空列表
 */
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

/**
 * 分页获取指定排行榜（如飙升榜、热歌榜、新歌榜）的歌曲列表。
 *
 * @param topId 榜单 ID（62: 飙升榜, 26: 热歌榜, 27: 新歌榜）
 * @param page 分页页码，从 1 起始
 * @param pageSize 每页拉取歌曲数量，默认 50
 * @return 榜单歌曲列表；请求失败或解析异常时返回空列表
 */
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

/**
 * 根据歌曲数字 ID 列表批量拉取结构化的 [Song] 元数据。
 *
 * @param songIds 歌曲数字 ID 集合列表
 * @return 解析成功的歌曲对象列表；列表为空或接口异常时返回空列表
 */
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

/**
 * 分页拉取推荐信息流货架列表（包含歌单推荐、个性化分类与场景化卡片）。
 *
 * @param direction 刷新滑动方向（0: 初始/下拉刷新, 1: 向上加载更多）
 * @param page 分页页码，从 1 起始
 * @param sNum 期望拉取的货架条目数量，默认 6
 * @return 推荐流货架模型 [RecommendShelf] 列表；未登录或请求失败返回空列表
 */
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

                // 仅保留“听「xxxx」的也在听 / 喜欢”货架，过滤非相关货架
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

private val similarSongsMemoryCache = java.util.concurrent.ConcurrentHashMap<Long, Pair<Long, List<Song>>>()
private const val SIMILAR_SONGS_CACHE_TTL_MS = 3 * 60 * 1000L

/**
 * 基于指定单曲获取相似歌曲推荐（带 3 分钟内存缓存）。
 *
 * @param songId 歌曲数字 ID
 * @param songMid 歌曲 MID（可选，当 songId <= 0 时用于回退解析数字 ID）
 * @return 相似推荐歌曲列表
 */
suspend fun MusicApiService.getSimilarSongs(
    songId: Long,
    songMid: String = "",
): List<Song> =
    withContext(Dispatchers.IO) {
        var targetSongId = songId
        if (targetSongId <= 0L && songMid.isNotBlank()) {
            try {
                val detailPayload =
                    """
                    {
                      "comm": { "format": "json", "ct": 20, "cv": 18030008 },
                      "songinfo": {
                        "module": "music.pf_song_detail_svr",
                        "method": "get_song_detail_yqq",
                        "param": { "song_mid": "$songMid" }
                      }
                    }
                    """.trimIndent()
                val detailResp = postGateway(detailPayload)
                val detailRoot = Json.parseToJsonElement(detailResp).jsonObject
                val trackObj =
                    detailRoot["songinfo"]
                        ?.jsonObject
                        ?.get("data")
                        ?.jsonObject
                        ?.get("track_info")
                        ?.jsonObject
                targetSongId = trackObj?.get("id")?.jsonPrimitive?.longOrNull ?: 0L
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ApiLogger.w("MusicApiRecommend", "resolve songId from songMid failed: $songMid", e)
            }
        }

        if (targetSongId <= 0L) return@withContext emptyList()

        val cached = similarSongsMemoryCache[targetSongId]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.first) < SIMILAR_SONGS_CACHE_TTL_MS && cached.second.isNotEmpty()) {
            return@withContext cached.second
        }

        if (UserSession.isLoggedIn) {
            ensureMusicKeySafe()
        }

        val uin = UserSession.profile.uin.ifBlank { "0" }
        val authst = UserSession.profile.musicKey

        try {
            val payload =
                """
                {
                  "comm": { "uin": "$uin", "format": "json", "ct": 20, "cv": 18030008, "authst": "$authst" },
                  "similar": {
                    "module": "music.recommend.TrackRelationServer",
                    "method": "GetSimilarSongs",
                    "param": { "songid": $targetSongId }
                  }
                }
                """.trimIndent()

            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val dataObj = root["similar"]?.jsonObject?.get("data")?.jsonObject
            val vecSongs =
                dataObj?.get("vecSong")?.jsonArray
                    ?: dataObj?.get("vecSongList")?.jsonArray
                    ?: dataObj?.get("tracks")?.jsonArray
                    ?: return@withContext emptyList()

            val parsedSongs =
                vecSongs.mapNotNull { item ->
                    MusicApiService.parseSongFromElement(item)
                }
            if (parsedSongs.isNotEmpty()) {
                similarSongsMemoryCache[targetSongId] = Pair(System.currentTimeMillis(), parsedSongs)
            }
            parsedSongs
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiRecommend", "getSimilarSongs failed: songId=$songId", e)
            emptyList()
        }
    }
