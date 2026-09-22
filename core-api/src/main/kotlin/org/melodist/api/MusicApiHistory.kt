package org.melodist.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.melodist.model.Song

/**
 * 最近播放数据类型枚举（映射 QQ 音乐底层 RPC 类型码）
 */
enum class RecentHistoryType(
    val typeCode: Int,
) {
    Song(2),
    Album(3),
    Playlist(4),
    Radio(5),
    Video(6),
}

data class RecentHistoryResult<T>(
    val items: List<T>,
    val updateTime: Long,
    val total: Int = items.size,
)

data class RecentSongItem(
    val song: Song,
    val lastTime: Long,
    val listenCnt: Int,
)

data class RecentAlbumItem(
    val albumId: Long,
    val albumMid: String,
    val albumName: String,
    val singerName: String,
    val coverUrl: String,
    val lastTime: Long,
    val listenCnt: Int = 1,
    val songCount: Int = 0,
)

data class RecentPlaylistItem(
    val tid: Long,
    val title: String,
    val coverUrl: String,
    val songCount: Int,
    val creatorNick: String,
    val lastTime: Long,
    val listenCnt: Int,
)

data class RecentReportItem(
    val id: String,
    val type: Int,
    val lastTime: Long = System.currentTimeMillis() / 1000,
    val listenCnt: Int = 1,
)

data class RecentDeleteItem(
    val id: String,
    val type: Int,
)

/**
 * 构建获取最近播放的 RPC 请求 Payload
 */
fun buildGetRecentHistoryPayload(
    type: Int,
    updateTime: Long = 0L,
    uin: String = "",
    authst: String = "",
): String {
    val loginType = if (authst.startsWith("W_X")) 1 else 2
    return """
        {
          "comm": {
            "ct": 11,
            "cv": 14090008,
            "v": 14090008,
            "tmeAppID": "qqmusic",
            "tmeLoginType": $loginType,
            "format": "json",
            "qq": "$uin",
            "authst": "$authst"
          },
          "req_recent": {
            "module": "music.musicasset.PlayRecentlyRead",
            "method": "GetPlayRecentlyInfo",
            "param": {
              "type": $type,
              "updateTime": $updateTime
            }
          }
        }
        """.trimIndent()
}

/**
 * 构建上报最近播放的 RPC 请求 Payload
 */
fun buildReportRecentHistoryPayload(
    items: List<RecentReportItem>,
    uin: String = "",
    authst: String = "",
): String {
    val loginType = if (authst.startsWith("W_X")) 1 else 2
    val dataJsonArray =
        buildJsonArray {
            for (item in items) {
                addJsonObject {
                    put("id", item.id)
                    put("type", item.type)
                    put("lastTime", item.lastTime)
                    put("listenCnt", item.listenCnt)
                    put("auxillaryID", "")
                    putJsonObject("auxillaryDict") {}
                }
            }
        }
    return """
        {
          "comm": {
            "ct": 11,
            "cv": 14090008,
            "v": 14090008,
            "tmeAppID": "qqmusic",
            "tmeLoginType": $loginType,
            "format": "json",
            "qq": "$uin",
            "authst": "$authst"
          },
          "report_recent": {
            "module": "music.musicasset.PlayRecentlyWrite",
            "method": "ReportPlayRecentlyInfo",
            "param": {
              "data": $dataJsonArray
            }
          }
        }
        """.trimIndent()
}

/**
 * 构建批量删除最近播放的 RPC 请求 Payload
 */
fun buildDeleteRecentHistoryBatchPayload(
    items: List<RecentDeleteItem>,
    uin: String = "",
    authst: String = "",
): String {
    val loginType = if (authst.startsWith("W_X")) 1 else 2
    val dataJsonArray =
        buildJsonArray {
            for (item in items) {
                addJsonObject {
                    put("id", item.id)
                    put("type", item.type)
                }
            }
        }
    return """
        {
          "comm": {
            "ct": 11,
            "cv": 14090008,
            "v": 14090008,
            "tmeAppID": "qqmusic",
            "tmeLoginType": $loginType,
            "format": "json",
            "qq": "$uin",
            "authst": "$authst"
          },
          "del_recent": {
            "module": "music.musicasset.PlayRecentlyWrite",
            "method": "DeletePlayRecentlyInfo",
            "param": {
              "data": $dataJsonArray
            }
          }
        }
        """.trimIndent()
}

/**
 * 构建单个删除最近播放的 RPC 请求 Payload
 */
fun buildDeleteRecentHistoryPayload(
    id: String,
    type: Int,
    uin: String = "",
    authst: String = "",
): String =
    buildDeleteRecentHistoryBatchPayload(
        items = listOf(RecentDeleteItem(id = id, type = type)),
        uin = uin,
        authst = authst,
    )

private fun extractDataContainer(jsonStr: String): Pair<JsonObject, JsonObject>? {
    val root =
        try {
            Json.parseToJsonElement(jsonStr).jsonObject
        } catch (_: Exception) {
            return null
        }
    val reqRecent = root["req_recent"]?.jsonObject ?: root
    val outerData = reqRecent["data"]?.jsonObject ?: return null
    val innerData = outerData["data"]?.jsonObject ?: outerData
    return Pair(outerData, innerData)
}

/**
 * 解析单曲历史回包
 */
fun parseRecentSongsResponse(jsonStr: String): RecentHistoryResult<RecentSongItem> {
    val (outerData, innerData) = extractDataContainer(jsonStr) ?: return RecentHistoryResult(emptyList(), 0L)

    val updateTime =
        innerData["updateTime"]?.jsonPrimitive?.longOrNull
            ?: outerData["updateTime"]?.jsonPrimitive?.longOrNull
            ?: 0L
    val songList =
        innerData["songList"]?.jsonArray
            ?: outerData["songList"]?.jsonArray
            ?: return RecentHistoryResult(emptyList(), updateTime)

    val items =
        songList.mapNotNull { itemElem ->
            val itemObj = itemElem.jsonObject
            val trackElem = itemObj["track"] ?: itemObj["song_info"] ?: itemObj
            val song = MusicApiService.parseSongFromElement(trackElem) ?: return@mapNotNull null
            val lastTime = itemObj["lastTime"]?.jsonPrimitive?.longOrNull ?: 0L
            val listenCnt = itemObj["listenCnt"]?.jsonPrimitive?.intOrNull ?: 1
            RecentSongItem(song = song, lastTime = lastTime, listenCnt = listenCnt)
        }
    return RecentHistoryResult(items = items, updateTime = updateTime)
}

/**
 * 解析专辑历史回包
 */
fun parseRecentAlbumsResponse(jsonStr: String): RecentHistoryResult<RecentAlbumItem> {
    val (outerData, innerData) = extractDataContainer(jsonStr) ?: return RecentHistoryResult(emptyList(), 0L)

    val updateTime =
        innerData["updateTime"]?.jsonPrimitive?.longOrNull
            ?: outerData["updateTime"]?.jsonPrimitive?.longOrNull
            ?: 0L
    val albumList =
        innerData["albumList"]?.jsonArray
            ?: innerData["allItems"]?.jsonObject?.get("albumList")?.jsonArray
            ?: outerData["albumList"]?.jsonArray
            ?: outerData["allItems"]?.jsonObject?.get("albumList")?.jsonArray
            ?: return RecentHistoryResult(emptyList(), updateTime)

    val items =
        albumList.mapNotNull { itemElem ->
            val obj = itemElem.jsonObject
            val albumInfo = obj["albumInfo"]?.jsonObject ?: obj
            val albumId =
                albumInfo["id"]?.jsonPrimitive?.longOrNull
                    ?: albumInfo["albumID"]?.jsonPrimitive?.longOrNull
                    ?: albumInfo["album_id"]?.jsonPrimitive?.longOrNull
                    ?: obj["id"]?.jsonPrimitive?.longOrNull ?: 0L
            val albumMid =
                albumInfo["mid"]?.jsonPrimitive?.contentOrNull
                    ?: albumInfo["albumMid"]?.jsonPrimitive?.contentOrNull
                    ?: albumInfo["album_mid"]?.jsonPrimitive?.contentOrNull
                    ?: obj["mid"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val albumName =
                albumInfo["name"]?.jsonPrimitive?.contentOrNull
                    ?: albumInfo["albumName"]?.jsonPrimitive?.contentOrNull
                    ?: albumInfo["album_name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val singerObj = albumInfo["singer"]?.jsonObject ?: obj["singer"]?.jsonObject
            val singerList = albumInfo["singerList"]?.jsonArray ?: obj["singerList"]?.jsonArray ?: obj["v_singer"]?.jsonArray
            val singerName =
                albumInfo["singerName"]?.jsonPrimitive?.contentOrNull
                    ?: obj["singerName"]?.jsonPrimitive?.contentOrNull
                    ?: singerObj?.get("name")?.jsonPrimitive?.contentOrNull
                    ?: singerList?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }?.joinToString(" / ")
                    ?: albumInfo["singer_name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["singer_name"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val songCount =
                albumInfo["num"]?.jsonPrimitive?.intOrNull
                    ?: obj["num"]?.jsonPrimitive?.intOrNull
                    ?: albumInfo["songnum"]?.jsonPrimitive?.intOrNull
                    ?: albumInfo["song_count"]?.jsonPrimitive?.intOrNull ?: 0

            val rawPic =
                albumInfo["albumPic"]?.jsonPrimitive?.contentOrNull
                    ?: albumInfo["pic"]?.jsonPrimitive?.contentOrNull
                    ?: obj["albumPicUrl"]?.jsonPrimitive?.contentOrNull
                    ?: obj["pic"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val coverUrl =
                if (rawPic.isNotBlank()) {
                    rawPic
                } else if (albumMid.isNotBlank()) {
                    MusicApiService.getAlbumCoverUrl(albumMid)
                } else {
                    ""
                }

            val lastTime = obj["lastTime"]?.jsonPrimitive?.longOrNull ?: 0L
            val listenCnt = obj["listenCnt"]?.jsonPrimitive?.intOrNull ?: 1

            if (albumMid.isBlank() && albumId == 0L) {
                null
            } else {
                RecentAlbumItem(
                    albumId = albumId,
                    albumMid = albumMid,
                    albumName = albumName,
                    singerName = singerName,
                    coverUrl = coverUrl,
                    lastTime = lastTime,
                    listenCnt = listenCnt,
                    songCount = songCount,
                )
            }
        }
    return RecentHistoryResult(items = items, updateTime = updateTime)
}

/**
 * 解析歌单历史回包（对应服务端 geDanList）
 */
fun parseRecentPlaylistsResponse(jsonStr: String): RecentHistoryResult<RecentPlaylistItem> {
    val (outerData, innerData) = extractDataContainer(jsonStr) ?: return RecentHistoryResult(emptyList(), 0L)

    val updateTime =
        innerData["updateTime"]?.jsonPrimitive?.longOrNull
            ?: outerData["updateTime"]?.jsonPrimitive?.longOrNull
            ?: 0L
    val folderList =
        innerData["geDanList"]?.jsonArray
            ?: innerData["folderList"]?.jsonArray
            ?: innerData["allItems"]?.jsonObject?.get("geDanList")?.jsonArray
            ?: outerData["geDanList"]?.jsonArray
            ?: outerData["folderList"]?.jsonArray
            ?: outerData["allItems"]?.jsonObject?.get("geDanList")?.jsonArray
            ?: return RecentHistoryResult(emptyList(), updateTime)

    val items =
        folderList.mapNotNull { itemElem ->
            val obj = itemElem.jsonObject
            val folderInfo = obj["folderInfo"]?.jsonObject ?: obj
            val tid =
                folderInfo["id"]?.jsonPrimitive?.longOrNull
                    ?: folderInfo["tid"]?.jsonPrimitive?.longOrNull
                    ?: folderInfo["dissid"]?.jsonPrimitive?.longOrNull
                    ?: obj["id"]?.jsonPrimitive?.longOrNull ?: 0L
            val title =
                folderInfo["name"]?.jsonPrimitive?.contentOrNull
                    ?: folderInfo["title"]?.jsonPrimitive?.contentOrNull
                    ?: folderInfo["dissname"]?.jsonPrimitive?.contentOrNull
                    ?: obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val coverUrl =
                folderInfo["pic"]?.jsonPrimitive?.contentOrNull
                    ?: folderInfo["albumPicUrl"]?.jsonPrimitive?.contentOrNull
                    ?: folderInfo["cover_url"]?.jsonPrimitive?.contentOrNull
                    ?: folderInfo["logo"]?.jsonPrimitive?.contentOrNull
                    ?: obj["pic"]?.jsonPrimitive?.contentOrNull
                    ?: obj["albumPicUrl"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val songCount =
                folderInfo["num"]?.jsonPrimitive?.intOrNull
                    ?: obj["num"]?.jsonPrimitive?.intOrNull
                    ?: folderInfo["song_count"]?.jsonPrimitive?.intOrNull
                    ?: folderInfo["total_song_num"]?.jsonPrimitive?.intOrNull ?: 0
            val creatorNick =
                folderInfo["creater"]?.jsonPrimitive?.contentOrNull
                    ?: obj["creater"]?.jsonPrimitive?.contentOrNull
                    ?: folderInfo["creator_nick"]?.jsonPrimitive?.contentOrNull
                    ?: folderInfo["nickname"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val lastTime = obj["lastTime"]?.jsonPrimitive?.longOrNull ?: 0L
            val listenCnt = obj["listenCnt"]?.jsonPrimitive?.intOrNull ?: 1

            val isAlgorithmic =
                title.contains("30首") ||
                    title.contains("每日30") ||
                    title.contains("红心雷达") ||
                    title.contains("猜你喜欢")

            if (isAlgorithmic || (tid == 0L && title.isBlank())) {
                null
            } else {
                RecentPlaylistItem(
                    tid = tid,
                    title = title,
                    coverUrl = coverUrl,
                    songCount = songCount,
                    creatorNick = creatorNick,
                    lastTime = lastTime,
                    listenCnt = listenCnt,
                )
            }
        }
    return RecentHistoryResult(items = items, updateTime = updateTime)
}

// =================== MusicApiService 扩展方法 ===================

private fun getEffectiveUin(): String =
    UserSession.profile.uin.ifBlank {
        UserSession.profile.cookies["uin"]?.trimStart('o')
            ?: UserSession.profile.cookies["qqmusic_uin"]?.trimStart('o')
            ?: UserSession.profile.cookies["musicid"]
            ?: ""
    }

private fun getEffectiveAuthst(): String =
    UserSession.profile.musicKey.ifBlank {
        UserSession.profile.cookies["qm_keyst"]
            ?: UserSession.profile.cookies["qqmusic_key"]
            ?: UserSession.profile.cookies["p_skey"]
            ?: UserSession.profile.cookies["skey"]
            ?: ""
    }

/**
 * 获取最近播放单曲列表
 */
suspend fun MusicApiService.getRecentSongs(updateTime: Long = 0L): RecentHistoryResult<RecentSongItem> =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) return@withContext RecentHistoryResult(emptyList(), 0L)
        ensureMusicKeySafe()
        val uin = getEffectiveUin()
        val authst = getEffectiveAuthst()
        val payload =
            buildGetRecentHistoryPayload(
                type = RecentHistoryType.Song.typeCode,
                updateTime = updateTime,
                uin = uin,
                authst = authst,
            )
        try {
            val resp = postGateway(payload)
            parseRecentSongsResponse(resp)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiHistory", "getRecentSongs failed", e)
            RecentHistoryResult(emptyList(), updateTime)
        }
    }

/**
 * 获取最近播放专辑列表
 */
suspend fun MusicApiService.getRecentAlbums(updateTime: Long = 0L): RecentHistoryResult<RecentAlbumItem> =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) return@withContext RecentHistoryResult(emptyList(), 0L)
        ensureMusicKeySafe()
        val uin = getEffectiveUin()
        val authst = getEffectiveAuthst()
        val payload =
            buildGetRecentHistoryPayload(
                type = RecentHistoryType.Album.typeCode,
                updateTime = updateTime,
                uin = uin,
                authst = authst,
            )
        try {
            val resp = postGateway(payload)
            parseRecentAlbumsResponse(resp)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiHistory", "getRecentAlbums failed", e)
            RecentHistoryResult(emptyList(), updateTime)
        }
    }

/**
 * 获取最近播放歌单列表
 */
suspend fun MusicApiService.getRecentPlaylists(updateTime: Long = 0L): RecentHistoryResult<RecentPlaylistItem> =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) return@withContext RecentHistoryResult(emptyList(), 0L)
        ensureMusicKeySafe()
        val uin = getEffectiveUin()
        val authst = getEffectiveAuthst()
        val payload =
            buildGetRecentHistoryPayload(
                type = RecentHistoryType.Playlist.typeCode,
                updateTime = updateTime,
                uin = uin,
                authst = authst,
            )
        try {
            val resp = postGateway(payload)
            parseRecentPlaylistsResponse(resp)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiHistory", "getRecentPlaylists failed", e)
            RecentHistoryResult(emptyList(), updateTime)
        }
    }

/**
 * 上报最近播放记录（单曲、专辑或歌单）
 */
suspend fun MusicApiService.reportRecentHistory(
    id: String,
    type: RecentHistoryType,
    lastTime: Long = System.currentTimeMillis() / 1000,
    listenCnt: Int = 1,
): Boolean =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) return@withContext false
        val uin = getEffectiveUin()
        val authst = getEffectiveAuthst()
        val payload =
            buildReportRecentHistoryPayload(
                items = listOf(RecentReportItem(id = id, type = type.typeCode, lastTime = lastTime, listenCnt = listenCnt)),
                uin = uin,
                authst = authst,
            )
        try {
            val resp = postGateway(payload)
            val root = Json.parseToJsonElement(resp).jsonObject
            val code =
                root["report_recent"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.intOrNull
                    ?: root["code"]?.jsonPrimitive?.intOrNull ?: -1
            code == 0
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiHistory", "reportRecentHistory failed: id=$id, type=$type", e)
            false
        }
    }

/**
 * 批量删除最近播放记录
 */
suspend fun MusicApiService.deleteRecentHistoryBatch(items: List<RecentDeleteItem>): Boolean =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn || items.isEmpty()) return@withContext false
        ensureMusicKeySafe()
        val uin = getEffectiveUin()
        val authst = getEffectiveAuthst()
        val payload =
            buildDeleteRecentHistoryBatchPayload(
                items = items,
                uin = uin,
                authst = authst,
            )
        try {
            val resp = postGateway(payload)
            val root = Json.parseToJsonElement(resp).jsonObject
            val code =
                root["del_recent"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.intOrNull
                    ?: root["code"]?.jsonPrimitive?.intOrNull ?: -1
            code == 0
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiHistory", "deleteRecentHistoryBatch failed: itemsCount=${items.size}", e)
            false
        }
    }

/**
 * 单个删除最近播放记录
 */
suspend fun MusicApiService.deleteRecentHistory(
    id: String,
    type: RecentHistoryType,
): Boolean = deleteRecentHistoryBatch(listOf(RecentDeleteItem(id = id, type = type.typeCode)))
