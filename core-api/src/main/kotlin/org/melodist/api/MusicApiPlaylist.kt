package org.melodist.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.melodist.model.FavoriteSongsResult
import org.melodist.model.Playlist
import org.melodist.model.Song

/**
 * 歌单生命周期与资产管理扩展
 */

suspend fun MusicApiService.getFavoriteSongsDetail(
    page: Int = 1,
    pageSize: Int = 50,
): FavoriteSongsResult =
    withContext(Dispatchers.IO) {
        ensureMusicKeySafe()

        var uin = UserSession.profile.uin.ifBlank { "0" }
        var authst = UserSession.profile.musicKey

        fun buildFavPayload(u: String, a: String) =
            """
            {
              "comm": { "uin": "$u", "format": "json", "ct": 19, "cv": 1, "authst": "$a" },
              "req_fav": {
                "module": "music.musicasset.PlaylistDetailRead",
                "method": "GetUniformSongDetailInfo",
                "param": { "uin": "$u", "dirid": 201, "bPaged": true, "offset": ${(page - 1) * pageSize}, "size": $pageSize }
              }
            }
            """.trimIndent()

        try {
            var respJson = postGateway(buildFavPayload(uin, authst))
            var root = Json.parseToJsonElement(respJson).jsonObject

            val rootCode = root["code"]?.jsonPrimitive?.intOrNull ?: 0
            val favCode = root["req_fav"]?.jsonObject?.get("code")?.jsonPrimitive?.intOrNull ?: 0

            if (rootCode == 2000 || favCode == 2000) {
                ApiLogger.i("MusicApiPlaylist", "getFavoriteSongsDetail: token expired (code 2000), refreshing...")
                if (LoginApiService().forceRefreshMusicKey()) {
                    uin = UserSession.profile.uin.ifBlank { "0" }
                    authst = UserSession.profile.musicKey
                    respJson = postGateway(buildFavPayload(uin, authst))
                    root = Json.parseToJsonElement(respJson).jsonObject
                }
            }

            val dataObj =
                root["req_fav"]?.jsonObject?.get("data")?.jsonObject ?: return@withContext FavoriteSongsResult(emptyList(), 0, false)

            val total =
                dataObj["total"]?.jsonPrimitive?.intOrNull
                    ?: dataObj["total_song_num"]?.jsonPrimitive?.intOrNull ?: 0
            val hasMore =
                dataObj["hasmore"]?.jsonPrimitive?.intOrNull == 1 ||
                    dataObj["hasmore"]?.jsonPrimitive?.booleanOrNull == true

            val songArray = dataObj["list"]?.jsonArray ?: return@withContext FavoriteSongsResult(emptyList(), total, hasMore)
            val songs = songArray.mapNotNull { MusicApiService.parseSongFromElement(it) }

            val resolvedHasMore = hasMore || (total > 0 && (page - 1) * pageSize + songs.size < total)
            FavoriteSongsResult(songs, total, resolvedHasMore)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiPlaylist", "getFavoriteSongsDetail failed", e)
            FavoriteSongsResult(emptyList(), 0, false)
        }
    }

/**
 * 获取收藏歌曲列表（复用 getFavoriteSongsDetail，消除重复网络请求与 JSON 解析）
 */
suspend fun MusicApiService.getFavoriteSongs(
    page: Int = 1,
    pageSize: Int = 50,
): List<Song> = getFavoriteSongsDetail(page, pageSize).songs

/**
 * 获取当前登录用户的自建歌单与收藏歌单列表。
 *
 * @param excludeMyFavorite 是否在自建歌单中过滤系统默认的“我喜欢”歌单（dirId 为 201）
 * @return 用户歌单列表；未登录或请求失败时返回空列表
 */
suspend fun MusicApiService.getPlaylists(excludeMyFavorite: Boolean = true): List<Playlist> =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) return@withContext emptyList()
        ensureMusicKeySafe()

        var uin = UserSession.profile.uin.ifBlank { "0" }
        fun buildPlaylistsPayload(u: String) =
            """
            {
              "comm": { "uin": "$u", "format": "json", "ct": 19, "cv": 1, "authst": "" },
              "self_playlists": { "module": "music.musicasset.PlaylistBaseRead", "method": "GetPlaylistByUin", "param": { "uin": "$u" } },
              "fav_playlists": { "module": "music.musicasset.PlaylistFavRead", "method": "GetPlaylistFavInfo", "param": { "uin": "$u" } }
            }
            """.trimIndent()

        try {
            var respJson = postGateway(buildPlaylistsPayload(uin))
            var root = Json.parseToJsonElement(respJson).jsonObject

            val rootCode = root["code"]?.jsonPrimitive?.intOrNull ?: 0
            val selfCode = root["self_playlists"]?.jsonObject?.get("code")?.jsonPrimitive?.intOrNull ?: 0
            val favCode = root["fav_playlists"]?.jsonObject?.get("code")?.jsonPrimitive?.intOrNull ?: 0

            if (rootCode == 2000 || selfCode == 2000 || favCode == 2000) {
                ApiLogger.i("MusicApiPlaylist", "getPlaylists: token expired (code 2000), refreshing...")
                if (LoginApiService().forceRefreshMusicKey()) {
                    uin = UserSession.profile.uin.ifBlank { "0" }
                    respJson = postGateway(buildPlaylistsPayload(uin))
                    root = Json.parseToJsonElement(respJson).jsonObject
                }
            }

            val result = mutableListOf<Playlist>()

            // 1. 自建歌单
            val selfList =
                root["self_playlists"]
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonObject
                    ?.get("v_playlist")
                    ?.jsonArray
            if (selfList != null) {
                for (elem in selfList) {
                    val obj = elem.jsonObject
                    val dirId =
                        obj["dirid"]?.jsonPrimitive?.longOrNull
                            ?: obj["dirId"]?.jsonPrimitive?.longOrNull ?: continue
                    val name =
                        obj["title"]?.jsonPrimitive?.contentOrNull
                            ?: obj["name"]?.jsonPrimitive?.contentOrNull
                            ?: obj["dirName"]?.jsonPrimitive?.contentOrNull ?: "未命名歌单"
                    val songCount =
                        obj["songnum"]?.jsonPrimitive?.intOrNull
                            ?: obj["songNum"]?.jsonPrimitive?.intOrNull ?: 0
                    val pic =
                        obj["picUrl"]?.jsonPrimitive?.contentOrNull
                            ?: obj["logo"]?.jsonPrimitive?.contentOrNull
                            ?: obj["pic"]?.jsonPrimitive?.contentOrNull.orEmpty()

                    val item =
                        Playlist(
                            dirId = dirId,
                            name = name,
                            songCount = songCount,
                            tid = 0L,
                            isFav = false,
                            picUrl = pic,
                        )
                    if (excludeMyFavorite && item.isMyFavorite) {
                        continue
                    }
                    result.add(item)
                }
            }

            // 2. 外部收藏歌单
            val favList =
                root["fav_playlists"]
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonObject
                    ?.get("v_list")
                    ?.jsonArray
            if (favList != null) {
                for (elem in favList) {
                    val obj = elem.jsonObject
                    val tid =
                        obj["tid"]?.jsonPrimitive?.longOrNull
                            ?: obj["id"]?.jsonPrimitive?.longOrNull ?: 0L
                    val dirId =
                        obj["dirid"]?.jsonPrimitive?.longOrNull
                            ?: obj["dirId"]?.jsonPrimitive?.longOrNull ?: tid
                    val name =
                        obj["title"]?.jsonPrimitive?.contentOrNull
                            ?: obj["name"]?.jsonPrimitive?.contentOrNull ?: "收藏歌单"
                    val songCount =
                        obj["songnum"]?.jsonPrimitive?.intOrNull
                            ?: obj["songNum"]?.jsonPrimitive?.intOrNull ?: 0
                    val pic =
                        obj["logo"]?.jsonPrimitive?.contentOrNull
                            ?: obj["picUrl"]?.jsonPrimitive?.contentOrNull
                            ?: obj["pic"]?.jsonPrimitive?.contentOrNull.orEmpty()

                    val item =
                        Playlist(
                            dirId = dirId,
                            name = name,
                            songCount = songCount,
                            tid = tid,
                            isFav = true,
                            picUrl = pic,
                        )
                    if (excludeMyFavorite && item.isMyFavorite) {
                        continue
                    }
                    result.add(item)
                }
            }

            result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiPlaylist", "getPlaylists failed", e)
            emptyList()
        }
    }

/**
 * 分页获取指定歌单内的歌曲列表。
 *
 * @param playlist 目标歌单模型
 * @param page 分页页码，从 1 起始
 * @param pageSize 每页拉取歌曲数量，默认 50
 * @return 歌单内的歌曲列表；请求失败或越界时返回空列表
 */
suspend fun MusicApiService.getPlaylistSongs(
    playlist: Playlist,
    page: Int = 1,
    pageSize: Int = 50,
): List<Song> = getPlaylistSongs(playlist.dirId, playlist.tid, playlist.isFav, page, pageSize)

/**
 * 分页获取指定目录或外部歌单 ID 下的歌曲列表。
 *
 * @param dirId 歌单本地/自建目录 ID
 * @param tid 收藏歌单的外部 DissId，为 0 时回退使用 `dirId`
 * @param isFav 是否为第三方收藏歌单
 * @param page 分页页码，从 1 起始
 * @param pageSize 每页拉取歌曲数量，默认 50
 * @return 歌单内的歌曲列表；请求失败时返回空列表
 */
suspend fun MusicApiService.getPlaylistSongs(
    dirId: Long,
    tid: Long = 0L,
    isFav: Boolean = false,
    page: Int = 1,
    pageSize: Int = 50,
): List<Song> =
    withContext(Dispatchers.IO) {
        ensureMusicKeySafe()

        val uin = UserSession.profile.uin.ifBlank { "0" }
        val authst = UserSession.profile.musicKey

        if ((!isFav || dirId == 201L) && dirId > 0L) {
            // 自建歌单或默认我喜欢
            val payload =
                """
                {
                  "comm": { "uin": "$uin", "format": "json", "ct": 19, "cv": 1, "authst": "" },
                  "req_songs": {
                    "module": "music.musicasset.PlaylistDetailRead",
                    "method": "GetUniformSongDetailInfo",
                    "param": { "uin": "$uin", "dirid": $dirId, "bPaged": true, "offset": ${(page - 1) * pageSize}, "size": $pageSize }
                  }
                }
                """.trimIndent()

            try {
                val respJson = postGateway(payload)
                val root = Json.parseToJsonElement(respJson).jsonObject
                val list =
                    root["req_songs"]
                        ?.jsonObject
                        ?.get("data")
                        ?.jsonObject
                        ?.get("list")
                        ?.jsonArray ?: return@withContext emptyList()
                list.mapNotNull { MusicApiService.parseSongFromElement(it) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ApiLogger.w("MusicApiPlaylist", "getPlaylistSongs (dirid) failed: dirId=$dirId, tid=$tid", e)
                emptyList()
            }
        } else {
            val dissId = if (tid > 0L) tid else dirId
            val payload =
                """
                {
                  "comm": { "uin": "$uin", "format": "json", "ct": 20, "cv": 1770, "platform": "wk_v17", "authst": "$authst" },
                  "req_diss": {
                    "module": "music.srfDissInfo.aiDissInfo",
                    "method": "uniform_get_Dissinfo",
                    "param": { "disstid": $dissId, "userinfo": 1, "tag": 1, "song_begin": ${(page - 1) * pageSize}, "song_num": $pageSize }
                  }
                }
                """.trimIndent()

            try {
                val respJson = postGateway(payload)
                val root = Json.parseToJsonElement(respJson).jsonObject
                val songList =
                    root["req_diss"]
                        ?.jsonObject
                        ?.get("data")
                        ?.jsonObject
                        ?.get("songlist")
                        ?.jsonArray ?: return@withContext emptyList()
                songList.mapNotNull { MusicApiService.parseSongFromElement(it) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ApiLogger.w("MusicApiPlaylist", "getPlaylistSongs (uniform) failed: dirId=$dirId, tid=$tid", e)
                emptyList()
            }
        }
    }

/**
 * 创建新的自建歌单。
 *
 * @param name 歌单标题名称
 * @return 包含操作结果、新建歌单 dirId 及提示信息的 [Triple]；未登录或创建失败时返回对应的错误原因
 */
suspend fun MusicApiService.createPlaylist(name: String): Triple<Boolean, Long, String> = createPlaylistInternal(name, canRetryWithRenew = true)

private suspend fun MusicApiService.createPlaylistInternal(
    name: String,
    canRetryWithRenew: Boolean,
): Triple<Boolean, Long, String> =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn || name.isBlank()) return@withContext Triple(false, 0L, "未登录或歌单名为空")
        ensureMusicKeySafe()
        val escaped = Json.encodeToString(name.trim())
        val comm = MusicApiService.buildAppCommJson()
        val payload =
            """
            {
              "comm": $comm,
              "createNewPlayList": {
                "module": "music.musicasset.PlaylistBaseWrite",
                "method": "AddPlaylist",
                "param": {
                  "dirName": $escaped,
                  "dirShow": 1,
                  "dirDesc": "",
                  "dirPicUrl": "",
                  "taglist": ""
                }
              }
            }
            """.trimIndent()

        try {
            val respJson = postAppGateway(payload, customCookieHeader = "")
            val root = Json.parseToJsonElement(respJson).jsonObject
            val rootCode = root["code"]?.jsonPrimitive?.intOrNull ?: 0
            val obj = root["createNewPlayList"]?.jsonObject ?: return@withContext Triple(false, 0L, "响应为空")
            val code = obj["code"]?.jsonPrimitive?.intOrNull ?: rootCode
            if (code == 0) {
                val dataObj = obj["data"]?.jsonObject
                val resObj = dataObj?.get("result")?.jsonObject
                val dirId =
                    resObj?.get("dirId")?.jsonPrimitive?.longOrNull
                        ?: resObj?.get("tid")?.jsonPrimitive?.longOrNull
                        ?: dataObj?.get("dirId")?.jsonPrimitive?.longOrNull
                        ?: 0L
                Triple(true, dirId, "创建成功")
            } else if ((code == 1000 || code == 10000 || code == 80105) && canRetryWithRenew) {
                ApiLogger.i("MusicApiPlaylist", "createPlaylist returned auth error $code, refreshing music key")
                ensureMusicKeySafe(forceRefresh = true)
                createPlaylistInternal(name, canRetryWithRenew = false)
            } else {
                val msg =
                    obj["data"]
                        ?.jsonObject
                        ?.get("msg")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                Triple(false, 0L, if (msg.isNotBlank()) msg else "创建失败(code=$code)")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiPlaylist", "createPlaylist failed: name=$name", e)
            Triple(false, 0L, e.message ?: "创建异常")
        }
    }

/**
 * 删除指定的自建歌单或取消收藏第三方歌单。
 *
 * @param playlist 待删除/取消收藏的歌单模型
 * @return 操作成功返回 `true`；未登录、属于“我喜欢”默认歌单或调用失败返回 `false`
 */
suspend fun MusicApiService.deletePlaylist(playlist: Playlist): Boolean = deletePlaylistInternal(playlist, canRetryWithRenew = true)

private suspend fun MusicApiService.deletePlaylistInternal(
    playlist: Playlist,
    canRetryWithRenew: Boolean,
): Boolean =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn || playlist.isMyFavorite) return@withContext false
        ensureMusicKeySafe()
        val comm = MusicApiService.buildAppCommJson()
        val payload =
            if (playlist.isCreated) {
                """
                {
                  "comm": $comm,
                  "deletePlayList": {
                    "module": "music.musicasset.PlaylistBaseWrite",
                    "method": "DelPlaylist",
                    "param": { "dirId": ${playlist.dirId} }
                  }
                }
                """.trimIndent()
            } else {
                val dissId = if (playlist.tid > 0L) playlist.tid else playlist.dirId
                """
                {
                  "comm": $comm,
                  "deleteFavPlayList": {
                    "module": "music.musicasset.PlaylistFavWrite",
                    "method": "CancelFavPlaylist",
                    "param": { "dirId": $dissId }
                  }
                }
                """.trimIndent()
            }

        try {
            val respJson = postAppGateway(payload, customCookieHeader = "")
            val root = Json.parseToJsonElement(respJson).jsonObject
            val rootCode = root["code"]?.jsonPrimitive?.intOrNull ?: 0
            val key = if (playlist.isCreated) "deletePlayList" else "deleteFavPlayList"
            val obj = root[key]?.jsonObject
            val code = obj?.get("code")?.jsonPrimitive?.intOrNull ?: rootCode
            if (code == 0) {
                true
            } else if ((code == 1000 || code == 10000 || code == 80105) && canRetryWithRenew) {
                ApiLogger.i("MusicApiPlaylist", "deletePlaylist returned auth error $code, refreshing music key")
                ensureMusicKeySafe(forceRefresh = true)
                deletePlaylistInternal(playlist, canRetryWithRenew = false)
            } else {
                false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiPlaylist", "deletePlaylist failed", e)
            false
        }
    }

/**
 * 收藏指定的第三方公开歌单。
 *
 * @param playlist 待收藏的歌单模型
 * @return 收藏成功返回 `true`；未登录或操作失败返回 `false`
 */
suspend fun MusicApiService.collectPlaylist(playlist: Playlist): Boolean = collectPlaylist(dissId = if (playlist.tid > 0L) playlist.tid else playlist.dirId)

/**
 * 收藏指定 DissId 的第三方公开歌单。
 *
 * @param dissId 目标歌单 DissId
 * @return 收藏成功返回 `true`；未登录或操作失败返回 `false`
 */
suspend fun MusicApiService.collectPlaylist(dissId: Long): Boolean = collectPlaylistInternal(dissId, canRetryWithRenew = true)

private suspend fun MusicApiService.collectPlaylistInternal(
    dissId: Long,
    canRetryWithRenew: Boolean,
): Boolean =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn || dissId <= 0L) return@withContext false
        ensureMusicKeySafe()
        val comm = MusicApiService.buildAppCommJson()
        val payload =
            """
            {
              "comm": $comm,
              "addFavPlayList": {
                "module": "music.musicasset.PlaylistFavWrite",
                "method": "FavPlaylist",
                "param": { "dirId": $dissId }
              }
            }
            """.trimIndent()

        try {
            val respJson = postAppGateway(payload, customCookieHeader = "")
            val root = Json.parseToJsonElement(respJson).jsonObject
            val rootCode = root["code"]?.jsonPrimitive?.intOrNull ?: 0
            val obj = root["addFavPlayList"]?.jsonObject
            val code = obj?.get("code")?.jsonPrimitive?.intOrNull ?: rootCode
            if (code == 0) {
                true
            } else if ((code == 1000 || code == 10000 || code == 80105) && canRetryWithRenew) {
                ApiLogger.i("MusicApiPlaylist", "collectPlaylist returned auth error $code, refreshing music key")
                ensureMusicKeySafe(forceRefresh = true)
                collectPlaylistInternal(dissId, canRetryWithRenew = false)
            } else {
                false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiPlaylist", "collectPlaylist failed: dissId=$dissId", e)
            false
        }
    }

/**
 * 根据歌曲 MID 解析对应的数字标识 ID。
 *
 * @param songMid 歌曲 MID 字符串
 * @return 对应的歌曲数字 ID；解析失败或参数为空返回 `0L`
 */
suspend fun MusicApiService.resolveSongId(songMid: String): Long =
    withContext(Dispatchers.IO) {
        if (songMid.isBlank()) return@withContext 0L
        ensureMusicKeySafe()
        val uin = UserSession.profile.uin.ifBlank { "0" }
        val authst = UserSession.profile.musicKey
        val payload =
            """
            {
              "comm": { "uin": "$uin", "format": "json", "ct": 19, "cv": 1, "authst": "$authst", "tmeAppID": "qqmusic" },
              "songinfo": {
                "module": "music.pf_song_detail_svr",
                "method": "get_song_detail_yqq",
                "param": { "song_mid": "$songMid" }
              }
            }
            """.trimIndent()

        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val trackInfo =
                root["songinfo"]
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonObject
                    ?.get("track_info")
                    ?.jsonObject
            trackInfo?.get("id")?.jsonPrimitive?.longOrNull ?: 0L
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiPlaylist", "resolveSongId failed: songMid=$songMid", e)
            0L
        }
    }

enum class AddSongResult {
    Success,
    AlreadyExists,
    Failed,
}

/**
 * 向指定自建歌单添加单首歌曲。
 *
 * @param dirId 目标歌单目录 ID
 * @param songId 歌曲数字 ID，若为 0 将根据 `songMid` 自动解析
 * @param songMid 歌曲 MID 字符串
 * @return 结果枚举 [AddSongResult]（成功、已存在或失败）
 */
suspend fun MusicApiService.addSongToPlaylist(
    dirId: Long,
    songId: Long,
    songMid: String = "",
): AddSongResult = addSongToPlaylistInternal(dirId, songId, songMid, canRetryWithRenew = true)

private suspend fun MusicApiService.addSongToPlaylistInternal(
    dirId: Long,
    songId: Long,
    songMid: String,
    canRetryWithRenew: Boolean,
): AddSongResult =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn || dirId <= 0L) return@withContext AddSongResult.Failed
        val actualId = if (songId <= 0L && songMid.isNotBlank()) resolveSongId(songMid) else songId
        if (actualId <= 0L) return@withContext AddSongResult.Failed

        ensureMusicKeySafe()

        val comm = MusicApiService.buildAppCommJson()
        val payload =
            """
            {
              "comm": $comm,
              "addSongsToPlayList": {
                "module": "music.musicasset.PlaylistDetailWrite",
                "method": "AddSonglist",
                "param": { "dirId": $dirId, "v_songInfo": [{ "songId": $actualId, "songType": 0 }] }
              }
            }
            """.trimIndent()

        try {
            val respJson = postAppGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val rootCode = root["code"]?.jsonPrimitive?.intOrNull ?: 0
            val addObj = root["addSongsToPlayList"]?.jsonObject
            val code =
                addObj?.get("code")?.jsonPrimitive?.intOrNull
                    ?: addObj?.get("subcode")?.jsonPrimitive?.intOrNull
                    ?: rootCode
            if (code == 0 && addObj != null) {
                val data = addObj["data"]?.jsonObject
                val succNum = data?.get("succ_song_num")?.jsonPrimitive?.intOrNull ?: 1
                val failNum = data?.get("fail_song_num")?.jsonPrimitive?.intOrNull ?: 0
                when {
                    succNum > 0 -> AddSongResult.Success
                    failNum > 0 -> AddSongResult.AlreadyExists
                    else -> AddSongResult.Success
                }
            } else if ((code == 1000 || code == 10000 || code == 80105) && canRetryWithRenew) {
                ApiLogger.i("MusicApiPlaylist", "addSongToPlaylist returned auth error $code, refreshing music key")
                ensureMusicKeySafe(forceRefresh = true)
                addSongToPlaylistInternal(dirId, songId, songMid, canRetryWithRenew = false)
            } else {
                AddSongResult.Failed
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiPlaylist", "addSongToPlaylist failed: dirId=$dirId", e)
            AddSongResult.Failed
        }
    }

/**
 * 向指定自建歌单批量添加多首歌曲。
 *
 * @param dirId 目标歌单目录 ID
 * @param songs 待添加的歌曲列表
 * @return 结果枚举 [AddSongResult]（成功、已存在或失败）
 */
suspend fun MusicApiService.addSongsToPlaylist(
    dirId: Long,
    songs: List<Song>,
): AddSongResult = addSongsToPlaylistInternal(dirId, songs, canRetryWithRenew = true)

private suspend fun MusicApiService.addSongsToPlaylistInternal(
    dirId: Long,
    songs: List<Song>,
    canRetryWithRenew: Boolean,
): AddSongResult =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn || dirId <= 0L || songs.isEmpty()) return@withContext AddSongResult.Failed
        val resolvedSongInfos =
            songs.mapNotNull { s ->
                val id =
                    if (s.songId > 0L) {
                        s.songId
                    } else if (s.songMid.isNotBlank()) {
                        resolveSongId(s.songMid)
                    } else {
                        0L
                    }
                if (id > 0L) id else null
            }
        if (resolvedSongInfos.isEmpty()) return@withContext AddSongResult.Failed

        ensureMusicKeySafe()

        val comm = MusicApiService.buildAppCommJson()
        val songInfoJson = resolvedSongInfos.joinToString(separator = ",") { """{ "songId": $it, "songType": 0 }""" }
        val payload =
            """
            {
              "comm": $comm,
              "addSongsToPlayList": {
                "module": "music.musicasset.PlaylistDetailWrite",
                "method": "AddSonglist",
                "param": { "dirId": $dirId, "v_songInfo": [$songInfoJson] }
              }
            }
            """.trimIndent()

        try {
            val respJson = postAppGateway(payload, customCookieHeader = "")
            val root = Json.parseToJsonElement(respJson).jsonObject
            val rootCode = root["code"]?.jsonPrimitive?.intOrNull ?: 0
            val addObj = root["addSongsToPlayList"]?.jsonObject
            val code =
                addObj?.get("code")?.jsonPrimitive?.intOrNull
                    ?: addObj?.get("subcode")?.jsonPrimitive?.intOrNull
                    ?: rootCode
            if (code == 0 && addObj != null) {
                val data = addObj["data"]?.jsonObject
                val succNum = data?.get("succ_song_num")?.jsonPrimitive?.intOrNull ?: 1
                val failNum = data?.get("fail_song_num")?.jsonPrimitive?.intOrNull ?: 0
                when {
                    succNum > 0 -> AddSongResult.Success
                    failNum > 0 -> AddSongResult.AlreadyExists
                    else -> AddSongResult.Success
                }
            } else if ((code == 1000 || code == 10000 || code == 80105) && canRetryWithRenew) {
                ApiLogger.i("MusicApiPlaylist", "addSongsToPlaylist returned auth error $code, refreshing music key")
                ensureMusicKeySafe(forceRefresh = true)
                addSongsToPlaylistInternal(dirId, songs, canRetryWithRenew = false)
            } else {
                AddSongResult.Failed
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiPlaylist", "addSongsToPlaylist failed: dirId=$dirId", e)
            AddSongResult.Failed
        }
    }

/**
 * 从指定自建歌单中批量删除歌曲。
 *
 * @param dirId 目标歌单目录 ID
 * @param songs 待删除的歌曲列表
 * @return 操作成功返回 `true`；未登录或操作失败返回 `false`
 */
suspend fun MusicApiService.deleteSongsFromPlaylist(
    dirId: Long,
    songs: List<Song>,
): Boolean = deleteSongsFromPlaylistInternal(dirId, songs, canRetryWithRenew = true)

private suspend fun MusicApiService.deleteSongsFromPlaylistInternal(
    dirId: Long,
    songs: List<Song>,
    canRetryWithRenew: Boolean,
): Boolean =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn || dirId <= 0L || songs.isEmpty()) return@withContext false
        val resolvedSongInfos =
            songs.mapNotNull { s ->
                val id =
                    if (s.songId > 0L) {
                        s.songId
                    } else if (s.songMid.isNotBlank()) {
                        resolveSongId(s.songMid)
                    } else {
                        0L
                    }
                if (id > 0L) id else null
            }
        if (resolvedSongInfos.isEmpty()) return@withContext false

        ensureMusicKeySafe()

        val comm = MusicApiService.buildAppCommJson()
        val songInfoJson = resolvedSongInfos.joinToString(separator = ",") { """{ "songId": $it, "songType": 0 }""" }
        val payload =
            """
            {
              "comm": $comm,
              "delSongsFromPlayList": {
                "module": "music.musicasset.PlaylistDetailWrite",
                "method": "DelSonglist",
                "param": { "dirId": $dirId, "v_songInfo": [$songInfoJson] }
              }
            }
            """.trimIndent()

        try {
            val respJson = postAppGateway(payload, customCookieHeader = "")
            val root = Json.parseToJsonElement(respJson).jsonObject
            val rootCode = root["code"]?.jsonPrimitive?.intOrNull ?: 0
            val delObj = root["delSongsFromPlayList"]?.jsonObject
            val code =
                delObj?.get("code")?.jsonPrimitive?.intOrNull
                    ?: delObj?.get("subcode")?.jsonPrimitive?.intOrNull
                    ?: rootCode
            if (code == 0 && delObj != null) {
                true
            } else if ((code == 1000 || code == 10000 || code == 80105) && canRetryWithRenew) {
                ApiLogger.i("MusicApiPlaylist", "deleteSongsFromPlaylist returned auth error $code, refreshing music key")
                ensureMusicKeySafe(forceRefresh = true)
                deleteSongsFromPlaylistInternal(dirId, songs, canRetryWithRenew = false)
            } else {
                false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiPlaylist", "deleteSongsFromPlaylist failed: dirId=$dirId", e)
            false
        }
    }

/**
 * 从指定自建歌单中删除单首歌曲。
 *
 * @param dirId 目标歌单目录 ID
 * @param songId 歌曲数字 ID，若为 0 将根据 `songMid` 自动解析
 * @param songMid 歌曲 MID 字符串
 * @return 操作成功返回 `true`；未登录或操作失败返回 `false`
 */
suspend fun MusicApiService.deleteSongFromPlaylist(
    dirId: Long,
    songId: Long,
    songMid: String = "",
): Boolean = deleteSongFromPlaylistInternal(dirId, songId, songMid, canRetryWithRenew = true)

private suspend fun MusicApiService.deleteSongFromPlaylistInternal(
    dirId: Long,
    songId: Long,
    songMid: String,
    canRetryWithRenew: Boolean,
): Boolean =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn || dirId <= 0L) return@withContext false
        val actualId = if (songId <= 0L && songMid.isNotBlank()) resolveSongId(songMid) else songId
        if (actualId <= 0L) return@withContext false

        ensureMusicKeySafe()

        val comm = MusicApiService.buildAppCommJson()
        val payload =
            """
            {
              "comm": $comm,
              "delSongsFromPlayList": {
                "module": "music.musicasset.PlaylistDetailWrite",
                "method": "DelSonglist",
                "param": { "dirId": $dirId, "v_songInfo": [{ "songId": $actualId, "songType": 0 }] }
              }
            }
            """.trimIndent()

        try {
            val respJson = postAppGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val rootCode = root["code"]?.jsonPrimitive?.intOrNull ?: 0
            val delObj = root["delSongsFromPlayList"]?.jsonObject
            val code =
                delObj?.get("code")?.jsonPrimitive?.intOrNull
                    ?: delObj?.get("subcode")?.jsonPrimitive?.intOrNull
                    ?: rootCode
            if (code == 0 && delObj != null) {
                true
            } else if ((code == 1000 || code == 10000 || code == 80105) && canRetryWithRenew) {
                ApiLogger.i("MusicApiPlaylist", "deleteSongFromPlaylist returned auth error $code, refreshing music key")
                ensureMusicKeySafe(forceRefresh = true)
                deleteSongFromPlaylistInternal(dirId, songId, songMid, canRetryWithRenew = false)
            } else {
                false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiPlaylist", "deleteSongFromPlaylist failed: dirId=$dirId, songId=$actualId", e)
            false
        }
    }

/**
 * 将指定歌曲添加至当前用户的“我喜欢”默认歌单。
 *
 * @param songId 歌曲数字 ID
 * @param songMid 歌曲 MID 字符串
 * @return 收藏成功返回 `true`；失败或未登录返回 `false`
 */
suspend fun MusicApiService.addSongToFavorite(
    songId: Long,
    songMid: String,
): Boolean = addSongToPlaylist(dirId = 201L, songId = songId, songMid = songMid) == AddSongResult.Success

/**
 * 将指定歌曲从当前用户的“我喜欢”默认歌单中取消收藏。
 *
 * @param songId 歌曲数字 ID
 * @param songMid 歌曲 MID 字符串
 * @return 取消成功返回 `true`；失败或未登录返回 `false`
 */
suspend fun MusicApiService.deleteSongFromFavorite(
    songId: Long,
    songMid: String,
): Boolean = deleteSongFromPlaylist(dirId = 201L, songId = songId, songMid = songMid)
