package org.melodist.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.melodist.model.ListenTogetherHistoryRecord
import org.melodist.model.ListenTogetherRoomInfo
import org.melodist.model.ListenTogetherSyncState
import org.melodist.model.Song
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * 创建一起听房间并返回房间信息及外部落地页链接。
 *
 * @param song 初始播放歌曲
 * @param playlist 当前待播放列表
 * @return 房间信息实体，创建失败时返回 null
 */
suspend fun MusicApiService.createListenTogetherRoom(
    song: Song,
    playlist: List<Song> = emptyList(),
    offsetMs: Long = 0L,
    playStatus: Int = 2,
): ListenTogetherRoomInfo? =
    withContext(Dispatchers.IO) {
        val commJson = MusicApiService.buildAppCommJson()
        val songId = song.songId
        val songTitle = song.name

        val songsToSync =
            if (playlist.isNotEmpty()) {
                playlist.take(1000).ifEmpty { listOf(song) }
            } else {
                listOf(song)
            }
        val currIdx = songsToSync.indexOfFirst { it.songId == songId }.coerceAtLeast(0)

        val payload =
            buildJsonObject {
                put("comm", Json.parseToJsonElement(commJson))
                put(
                    "req_create",
                    buildJsonObject {
                        put("module", "music.lightUGCRoom.LightUGCRoomSvr")
                        put("method", "LightUGCRoomCreate")
                        put(
                            "param",
                            buildJsonObject {
                                put("fromTag", 0)
                                put("playStatus", playStatus)
                                put("playMode", 0)
                                put("songOffset", offsetMs)
                                put("seekTime", offsetMs)
                                put("sourceType", 0)
                                put("avatarValid", 0)
                                put("musicWorldValid", 0)
                                put("isAITogether", 0)
                                put("isMatchStranger", 0)
                                put(
                                    "songPlaying",
                                    buildJsonObject {
                                        put("songID", songId)
                                        put("songType", 0)
                                        put("songIndex", currIdx)
                                        put("title", songTitle)
                                    },
                                )
                                put(
                                    "songInfos",
                                    buildJsonArray {
                                        songsToSync.forEachIndexed { idx, s ->
                                            add(
                                                buildJsonObject {
                                                    put("songID", s.songId)
                                                    put("songType", 0)
                                                    put("songIndex", idx)
                                                    put("title", s.name)
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    },
                )
            }.toString()

        try {
            val responseStr = postAppGateway(payload)
            val root = Json.parseToJsonElement(responseStr).jsonObject
            val reqCreate = root["req_create"]?.jsonObject ?: return@withContext null
            val code = reqCreate["code"]?.jsonPrimitive?.intOrNull ?: -1
            if (code != 0) {
                ApiLogger.w("MusicApiListenTogether", "LightUGCRoomCreate failed code=$code")
                return@withContext null
            }

            val dataObj = reqCreate["data"]?.jsonObject ?: return@withContext null
            val entryInfo = dataObj["entryInfo"]?.jsonObject ?: return@withContext null
            val roomInfo = entryInfo["roomInfo"]?.jsonObject ?: return@withContext null
            val showId = roomInfo["showID"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
            val creatorUin = roomInfo["createUin"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val shareUrl =
                "https://i.y.qq.com/n2/m/share/listen_together/index.html?showid=$showId&songid=$songId"

            ListenTogetherRoomInfo(
                showId = showId,
                creatorUin = creatorUin,
                shareUrl = shareUrl,
                currentSongId = songId,
                currentSongTitle = songTitle,
                usersCount = 1,
                isValid = true,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiListenTogether", "createListenTogetherRoom failed", e)
            null
        }
    }

/**
 * 加入已存在的一起听房间。
 *
 * @param showId 房间唯一标识 (例如 LUGC...)
 * @param fromEncUin 邀请者加密 UIN
 * @return 是否加入成功
 */
suspend fun MusicApiService.joinListenTogetherRoom(
    showId: String,
    fromEncUin: String = "",
): Boolean =
    withContext(Dispatchers.IO) {
        if (showId.isBlank()) return@withContext false
        val commJson = MusicApiService.buildAppCommJson()

        val payload =
            buildJsonObject {
                put("comm", Json.parseToJsonElement(commJson))
                put(
                    "req_join",
                    buildJsonObject {
                        put("module", "music.lightUGCRoom.LightUGCRoomSvr")
                        put("method", "LightUGCRoomEntry")
                        put(
                            "param",
                            buildJsonObject {
                                put("showID", showId)
                                put("fromEncryptedUin", fromEncUin)
                                put("entryType", 0)
                                put("fromTag", 0)
                                put("avatarValid", 0)
                                put("musicWorldValid", 0)
                                put("songInfos", buildJsonArray {})
                            },
                        )
                    },
                )
            }.toString()

        try {
            val responseStr = postAppGateway(payload)
            val root = Json.parseToJsonElement(responseStr).jsonObject
            val reqJoin = root["req_join"]?.jsonObject ?: return@withContext false
            val code = reqJoin["code"]?.jsonPrimitive?.intOrNull ?: -1
            code == 0
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiListenTogether", "joinListenTogetherRoom failed", e)
            false
        }
    }

/**
 * 发送一起听心跳并拉取当前房间的实时播放与成员状态。
 *
 * @param showId 房间唯一标识
 * @return 实时同步状态，房间已关闭时返回 isRoomValid = false
 */
suspend fun MusicApiService.heartbeatListenTogetherRoom(showId: String): ListenTogetherSyncState? =
    withContext(Dispatchers.IO) {
        if (showId.isBlank()) return@withContext null
        val commJson = MusicApiService.buildAppCommJson()

        val payload =
            """
            {
              "comm": $commJson,
              "req_hb": {
                "module": "music.lightUGCRoom.LightUGCRoomSvr",
                "method": "LightUGCRoomHeartBeat",
                "param": { "showID": "$showId" }
              }
            }
            """.trimIndent()

        try {
            val responseStr = postAppGateway(payload)
            val root = Json.parseToJsonElement(responseStr).jsonObject
            val reqHb = root["req_hb"]?.jsonObject ?: return@withContext null
            val code = reqHb["code"]?.jsonPrimitive?.intOrNull ?: -1
            if (code == 11000) {
                return@withContext ListenTogetherSyncState(
                    showId = showId,
                    isRoomValid = false,
                )
            }
            if (code != 0) return@withContext null

            val dataObj = reqHb["data"]?.jsonObject ?: return@withContext null
            val songInfo = dataObj["songInfo"]?.jsonObject
            val playingSong = songInfo?.get("playingSongInfo")?.jsonObject
            val currentSongId = playingSong?.get("songID")?.jsonPrimitive?.longOrNull ?: 0L
            val songTitle = playingSong?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
            val playStatus = songInfo?.get("playStatus")?.jsonPrimitive?.intOrNull ?: 0
            val songOffsetMs = songInfo?.get("songOffset")?.jsonPrimitive?.longOrNull ?: 0L
            val songPlayVersion = songInfo?.get("songPlayVersion")?.jsonPrimitive?.longOrNull ?: 0L
            val mediaVersion = songInfo?.get("mediaVersion")?.jsonPrimitive?.longOrNull
                ?: dataObj["mediaVersion"]?.jsonPrimitive?.longOrNull
                ?: 0L
            val usersCount = dataObj["users"]?.jsonArray?.size ?: 1

            ListenTogetherSyncState(
                showId = showId,
                currentSongId = currentSongId,
                songTitle = songTitle,
                playStatus = playStatus,
                songOffsetMs = songOffsetMs,
                songPlayVersion = songPlayVersion,
                mediaVersion = mediaVersion,
                usersCount = usersCount,
                isRoomValid = true,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiListenTogether", "heartbeatListenTogetherRoom failed", e)
            null
        }
    }

/**
 * 退出指定的一起听房间。
 *
 * @param showId 房间唯一标识
 * @return 是否成功请求退出
 */
suspend fun MusicApiService.quitListenTogetherRoom(showId: String): Boolean =
    withContext(Dispatchers.IO) {
        if (showId.isBlank()) return@withContext false
        val commJson = MusicApiService.buildAppCommJson()
        val currentUin = UserSession.profile.uin

        val payload =
            """
            {
              "comm": $commJson,
              "req_quit": {
                "module": "music.lightUGCRoom.LightUGCRoomSvr",
                "method": "LightUGCRoomQuit",
                "param": {
                  "showID": "$showId",
                  "uinList": ["$currentUin"],
                  "from": "player"
                }
              }
            }
            """.trimIndent()

        try {
            val responseStr = postAppGateway(payload)
            val root = Json.parseToJsonElement(responseStr).jsonObject
            val reqQuit = root["req_quit"]?.jsonObject ?: return@withContext false
            val code = reqQuit["code"]?.jsonPrimitive?.intOrNull ?: -1
            code == 0
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiListenTogether", "quitListenTogetherRoom failed", e)
            false
        }
    }

/**
 * 通过歌曲数字 ID 检索并构建完整的 Song 实体。
 *
 * @param songId 歌曲数字 ID
 * @return 解析得到的完整 Song 实体，失败返回 null
 */
suspend fun MusicApiService.getSongBySongId(songId: Long): Song? =
    withContext(Dispatchers.IO) {
        if (songId <= 0L) return@withContext null
        val list = getSongsBySongIds(listOf(songId))
        list.firstOrNull()
    }

/**
 * 批量通过歌曲数字 ID 集合检索并构建完整的 Song 实体列表。
 *
 * @param songIds 歌曲数字 ID 集合
 * @return 解析得到的完整 Song 列表
 */
suspend fun MusicApiService.getSongsBySongIds(songIds: List<Long>): List<Song> =
    withContext(Dispatchers.IO) {
        val validIds = songIds.filter { it > 0L }
        if (validIds.isEmpty()) return@withContext emptyList()
        val distinctIds = validIds.distinct().take(1000)
        val chunks = distinctIds.chunked(100)
        val commJson = MusicApiService.buildAppCommJson()

        val allSongs =
            chunks.map { chunkIds ->
                async {
                    val payload =
                        buildJsonObject {
                            put("comm", Json.parseToJsonElement(commJson))
                            put(
                                "req_track",
                                buildJsonObject {
                                    put("module", "music.trackInfo.UniformRuleCtrl")
                                    put("method", "CgiGetTrackInfo")
                                    put(
                                        "param",
                                        buildJsonObject {
                                            put(
                                                "ids",
                                                buildJsonArray {
                                                    chunkIds.forEach { add(it) }
                                                },
                                            )
                                            put(
                                                "types",
                                                buildJsonArray {
                                                    chunkIds.forEach { add(0) }
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }.toString()

                    try {
                        val responseStr = postAppGateway(payload)
                        val root = Json.parseToJsonElement(responseStr).jsonObject
                        val reqTrack = root["req_track"]?.jsonObject ?: return@async emptyList()
                        val dataObj = reqTrack["data"]?.jsonObject ?: return@async emptyList()
                        val tracks = dataObj["tracks"]?.jsonArray ?: return@async emptyList()
                        tracks.mapNotNull { MusicApiService.parseSongFromElement(it) }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        ApiLogger.w("MusicApiListenTogether", "getSongsBySongIds chunk failed", e)
                        emptyList()
                    }
                }
            }.awaitAll().flatten()

        val songMap = allSongs.associateBy { it.songId }
        validIds.mapNotNull { songMap[it] }
    }

/**
 * 初次入房拉取当前房间媒体信息与完整曲目 ID 列表 (cmd=0)。
 *
 * @param showId 房间唯一标识
 * @return 房间内的歌曲数字 ID 队列
 */
suspend fun MusicApiService.getListenTogetherSongList(showId: String): List<Long> =
    withContext(Dispatchers.IO) {
        if (showId.isBlank()) return@withContext emptyList()
        val commJson = MusicApiService.buildAppCommJson()

        val payload =
            buildJsonObject {
                put("comm", Json.parseToJsonElement(commJson))
                put(
                    "req_oper",
                    buildJsonObject {
                        put("module", "music.lightUGCRoom.LightUGCRoomSongSvr")
                        put("method", "LightUGCRoomSongOper")
                        put(
                            "param",
                            buildJsonObject {
                                put("showID", showId)
                                put("cmd", 0)
                            },
                        )
                    },
                )
            }.toString()

        try {
            val responseStr = postAppGateway(payload)
            val root = Json.parseToJsonElement(responseStr).jsonObject
            val reqOper = root["req_oper"]?.jsonObject ?: return@withContext emptyList()
            val dataObj = reqOper["data"]?.jsonObject ?: return@withContext emptyList()
            val songList = dataObj["songList"]?.jsonArray ?: return@withContext emptyList()
            songList.mapNotNull { it.jsonObject["songID"]?.jsonPrimitive?.longOrNull }.filter { it > 0L }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiListenTogether", "getListenTogetherSongList failed", e)
            emptyList()
        }
    }

/**
 * 向上报房间歌曲播放控制操作（播放、暂停、进度跳转、切歌）。
 *
 * @param showId 房间唯一标识
 * @param cmd 指令码：1 全量队列同步，2 恢复播放，3 暂停，4 切歌，7 进度跳转
 * @param songId 目标曲目 ID
 * @param songTitle 目标曲目名称
 * @param songIndex 目标曲目在播放列表中的索引
 * @param offsetMs 当前播放进度偏移（毫秒）
 * @param playlist 同步队列曲目列表（仅在 cmd = 1 时有效）
 * @return 操作是否成功被服务端接收
 */
suspend fun MusicApiService.sendListenTogetherSongOper(
    showId: String,
    cmd: Int,
    songId: Long = 0L,
    songTitle: String = "",
    songIndex: Int = 0,
    offsetMs: Long = 0L,
    playlist: List<Song> = emptyList(),
): Boolean =
    withContext(Dispatchers.IO) {
        if (showId.isBlank()) return@withContext false
        val commJson = MusicApiService.buildAppCommJson()

        val payload =
            buildJsonObject {
                put("comm", Json.parseToJsonElement(commJson))
                put(
                    "req_oper",
                    buildJsonObject {
                        put("module", "music.lightUGCRoom.LightUGCRoomSongSvr")
                        put("method", "LightUGCRoomSongOper")
                        put(
                            "param",
                            buildJsonObject {
                                put("showID", showId)
                                put("cmd", cmd)
                                put("sourceType", 0)
                                put("playlistType", 0)
                                when (cmd) {
                                    2 -> {
                                        put("seekTime", offsetMs)
                                        put("songOffset", offsetMs)
                                        if (songId > 0L) {
                                            put(
                                                "songPlaying",
                                                buildJsonObject {
                                                    put("songID", songId)
                                                    put("songType", 0)
                                                    put("songIndex", songIndex)
                                                    if (songTitle.isNotBlank()) put("title", songTitle)
                                                },
                                            )
                                        }
                                    }
                                    3 -> {
                                        put("seekTime", offsetMs)
                                        put("songOffset", offsetMs)
                                        if (songId > 0L) {
                                            put(
                                                "songPlaying",
                                                buildJsonObject {
                                                    put("songID", songId)
                                                    put("songType", 0)
                                                    put("songIndex", songIndex)
                                                    if (songTitle.isNotBlank()) put("title", songTitle)
                                                },
                                            )
                                        }
                                    }
                                    7 -> {
                                        put("seekTime", offsetMs)
                                        put("songOffset", offsetMs)
                                        if (songId > 0L) {
                                            put(
                                                "songPlaying",
                                                buildJsonObject {
                                                    put("songID", songId)
                                                    put("songType", 0)
                                                    put("songIndex", songIndex)
                                                    if (songTitle.isNotBlank()) put("title", songTitle)
                                                },
                                            )
                                        }
                                    }
                                    4 -> {
                                        put("songOffset", 0)
                                        put(
                                            "songPlaying",
                                            buildJsonObject {
                                                put("songID", songId)
                                                put("songType", 0)
                                                put("songIndex", songIndex)
                                                if (songTitle.isNotBlank()) put("title", songTitle)
                                            },
                                        )
                                    }
                                    1 -> {
                                        put("seekTime", offsetMs)
                                        put("songOffset", offsetMs)
                                        if (songId > 0L) {
                                            put(
                                                "songPlaying",
                                                buildJsonObject {
                                                    put("songID", songId)
                                                    put("songType", 0)
                                                    put("songIndex", songIndex)
                                                    if (songTitle.isNotBlank()) put("title", songTitle)
                                                },
                                            )
                                        }
                                        if (playlist.isNotEmpty()) {
                                            put(
                                                "songList",
                                                buildJsonArray {
                                                    playlist.take(1000).forEachIndexed { idx, s ->
                                                        add(
                                                            buildJsonObject {
                                                                put("songID", s.songId)
                                                                put("songType", 0)
                                                                put("songIndex", idx)
                                                                put("title", s.name)
                                                            },
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    },
                )
            }.toString()

        try {
            val responseStr = postAppGateway(payload)
            val root = Json.parseToJsonElement(responseStr).jsonObject
            val reqOper = root["req_oper"]?.jsonObject ?: return@withContext false
            val code = reqOper["code"]?.jsonPrimitive?.intOrNull ?: -1
            ApiLogger.i("MusicApiListenTogether", "sendListenTogetherSongOper cmd=$cmd code=$code")
            code == 0
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiListenTogether", "sendListenTogetherSongOper failed cmd=$cmd", e)
            false
        }
    }

/**
 * 查询指定房间的当前实时静态状态与成员列表。
 *
 * @param showId 房间唯一标识
 * @return 房间详情，房间不存在或已结束返回 null
 */
suspend fun MusicApiService.queryListenTogetherRoomState(showId: String): ListenTogetherRoomInfo? =
    withContext(Dispatchers.IO) {
        if (showId.isBlank()) return@withContext null
        val commJson = MusicApiService.buildAppCommJson()

        val payload =
            """
            {
              "comm": $commJson,
              "req_query": {
                "module": "music.lightUGCRoom.LightUGCRoomSvr",
                "method": "QueryLightUGCRoomState",
                "param": { "showID": "$showId" }
              }
            }
            """.trimIndent()

        try {
            val responseStr = postAppGateway(payload)
            val root = Json.parseToJsonElement(responseStr).jsonObject
            val reqQuery = root["req_query"]?.jsonObject ?: return@withContext null
            val code = reqQuery["code"]?.jsonPrimitive?.intOrNull ?: -1
            if (code != 0) return@withContext null

            val dataObj = reqQuery["data"]?.jsonObject ?: return@withContext null
            val roomInfo = dataObj["roomInfo"]?.jsonObject ?: return@withContext null
            val together = roomInfo["together"]?.jsonObject
            val isTogetherValid = together?.get("valid")?.jsonPrimitive?.contentOrNull == "true"
            val users = (dataObj["users"] as? JsonArray) ?: JsonArray(emptyList())

            val creatorUin = roomInfo["createUin"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val encUin = UserSession.profile.uin

            ListenTogetherRoomInfo(
                showId = showId,
                creatorUin = creatorUin,
                shareUrl = "https://i.y.qq.com/n2/m/share/listen_together/index.html?showid=$showId&songid=0&share_encrypt_uin=$encUin",
                usersCount = users.size,
                isValid = isTogetherValid,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiListenTogether", "queryListenTogetherRoomState failed", e)
            null
        }
    }

/**
 * 拉取当前账号历史一起听房间记录列表。
 *
 * @param reqNum 请求返回的最大记录数（默认 50 条）
 * @return 历史房间记录列表
 */
suspend fun MusicApiService.getListenTogetherHistory(reqNum: Int = 50): List<ListenTogetherHistoryRecord> =
    withContext(Dispatchers.IO) {
        val commJson = MusicApiService.buildAppCommJson()

        val payload =
            """
            {
              "comm": $commJson,
              "req_hist": {
                "module": "music.lightUGCRoom.LightUGCRoomDataRecordSvr",
                "method": "GetLightUGCRoomHistory",
                "param": { "reqNum": $reqNum }
              }
            }
            """.trimIndent()

        try {
            val responseStr = postAppGateway(payload)
            val root = Json.parseToJsonElement(responseStr).jsonObject
            val reqHist = root["req_hist"]?.jsonObject ?: return@withContext emptyList()
            val code = reqHist["code"]?.jsonPrimitive?.intOrNull ?: -1
            if (code != 0) return@withContext emptyList()

            val dataObj = reqHist["data"]?.jsonObject ?: return@withContext emptyList()
            val historyList = (dataObj["historyList"] as? JsonArray)
                ?: (dataObj["records"] as? JsonArray)
                ?: (dataObj["roomList"] as? JsonArray)
                ?: return@withContext emptyList()

            historyList.mapNotNull { itemElement ->
                try {
                    val itemObj = itemElement.jsonObject
                    val showId = itemObj["showID"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val createTime = itemObj["createTime"]?.jsonPrimitive?.longOrNull ?: 0L
                    val closeTime = itemObj["closeTime"]?.jsonPrimitive?.longOrNull ?: 0L
                    val userObj = itemObj["user"]?.jsonObject
                    val nick = userObj?.get("nick")?.jsonPrimitive?.contentOrNull.orEmpty()
                    val avatar = userObj?.get("avatar")?.jsonPrimitive?.contentOrNull.orEmpty()
                    val songObj = itemObj["songInfo"]?.jsonObject
                    val songName = songObj?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
                    val singer = songObj?.get("singer")?.jsonPrimitive?.contentOrNull.orEmpty()

                    ListenTogetherHistoryRecord(
                        showId = showId,
                        createTime = createTime,
                        closeTime = closeTime,
                        friendNick = nick,
                        friendAvatar = avatar,
                        lastSongName = songName,
                        lastSinger = singer,
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiListenTogether", "getListenTogetherHistory failed", e)
            emptyList()
        }
    }

/**
 * 从 URL 中解析一起听落地页参数。
 *
 * @param urlString 待解析的目标链接
 * @return Triple(showId, encUin, songId)
 */
private fun parseFromListenTogetherHttpUrl(urlString: String): Triple<String, String, Long> {
    val httpUrl = urlString.toHttpUrlOrNull() ?: return Triple("", "", 0L)
    val showId = httpUrl.queryParameter("showid").orEmpty()
    val encUin = httpUrl.queryParameter("share_encrypt_uin")?.ifBlank { null }
        ?: httpUrl.queryParameter("hosteuin").orEmpty()
    val songId = httpUrl.queryParameter("songid")?.toLongOrNull() ?: 0L
    return Triple(showId, encUin, songId)
}

/**
 * 解析并还原一起听分享文本，支持识别落地页 URL、原始房间号以及短链接重定向。
 *
 * @param rawText 用户输入的文本
 * @return Triple(showId, encUin, songId)
 */
suspend fun MusicApiService.resolveListenTogetherShareTarget(
    rawText: String,
): Triple<String, String, Long> = withContext(Dispatchers.IO) {
    if (rawText.isBlank()) return@withContext Triple("", "", 0L)

    val cleanText = rawText.trim()

    // 1. 尝试从文本中提取 URL
    val urlRegex = Regex("https?://[a-zA-Z0-9\\-._~:/?#\\[\\]@!$&'*+,;=%]+")
    var matchedUrl = urlRegex.find(cleanText)?.value
    if (matchedUrl == null) {
        val domainRegex = Regex("(?:[a-zA-Z0-9-]+\\.)*y\\.qq\\.com/[a-zA-Z0-9\\-._~:/?#\\[\\]@!$&'*+,;=%]+")
        val domainMatch = domainRegex.find(cleanText)?.value
        if (domainMatch != null) {
            matchedUrl = "https://$domainMatch"
        }
    }

    if (matchedUrl != null) {
        val trimmedUrl = matchedUrl.trimEnd(',', '.', ';', ')', '，', '。')
        val initialParsed = parseFromListenTogetherHttpUrl(trimmedUrl)
        if (initialParsed.first.isNotBlank()) {
            return@withContext initialParsed
        }

        // 若直接解析未获得 showId，发起网络探测获取 302 重定向落地页
        try {
            val redirectClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            val request = Request.Builder()
                .url(trimmedUrl)
                .header("User-Agent", MusicApiService.WEB_USER_AGENT)
                .build()

            redirectClient.newCall(request).execute().use { response ->
                val location = response.header("Location")
                if (!location.isNullOrBlank()) {
                    val resolvedHttpUrl = response.request.url.resolve(location)?.toString() ?: location
                    val resolved = parseFromListenTogetherHttpUrl(resolvedHttpUrl)
                    if (resolved.first.isNotBlank()) {
                        return@withContext resolved
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiListenTogether", "resolve short url redirect failed", e)
        }
    }

    // 2. 兜底正则匹配房间号 (LUGC 开头)
    val lugcMatch = Regex("LUGC[0-9a-zA-Z]+").find(cleanText)
    if (lugcMatch != null) {
        return@withContext Triple(lugcMatch.value, "", 0L)
    }

    Triple("", "", 0L)
}


