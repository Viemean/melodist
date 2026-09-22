package org.melodist.api

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
        try {
            LoginApiService().ensureMusicKey()
        } catch (_: Exception) {
        }

        val uin = UserSession.profile.uin.ifBlank { "0" }
        val authst = UserSession.profile.musicKey
        val payload =
            """
            {
              "comm": { "uin": "$uin", "format": "json", "ct": 19, "cv": 1, "authst": "$authst" },
              "req_fav": {
                "module": "music.musicasset.PlaylistDetailRead",
                "method": "GetUniformSongDetailInfo",
                "param": { "uin": "$uin", "dirid": 201, "bPaged": true, "offset": ${(page - 1) * pageSize}, "size": $pageSize }
              }
            }
            """.trimIndent()

        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
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

suspend fun MusicApiService.getPlaylists(excludeMyFavorite: Boolean = true): List<Playlist> =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) return@withContext emptyList()
        try {
            LoginApiService().ensureMusicKey()
        } catch (_: Exception) {
        }

        val uin = UserSession.profile.uin.ifBlank { "0" }
        val payload =
            """
            {
              "comm": { "uin": "$uin", "format": "json", "ct": 19, "cv": 1, "authst": "" },
              "self_playlists": { "module": "music.musicasset.PlaylistBaseRead", "method": "GetPlaylistByUin", "param": { "uin": "$uin" } },
              "fav_playlists": { "module": "music.musicasset.PlaylistFavRead", "method": "GetPlaylistFavInfo", "param": { "uin": "$uin" } }
            }
            """.trimIndent()

        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
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
            emptyList()
        }
    }

suspend fun MusicApiService.getPlaylistSongs(
    playlist: Playlist,
    page: Int = 1,
    pageSize: Int = 50,
): List<Song> = getPlaylistSongs(playlist.dirId, playlist.tid, playlist.isFav, page, pageSize)

suspend fun MusicApiService.getPlaylistSongs(
    dirId: Long,
    tid: Long = 0L,
    isFav: Boolean = false,
    page: Int = 1,
    pageSize: Int = 50,
): List<Song> =
    withContext(Dispatchers.IO) {
        try {
            LoginApiService().ensureMusicKey()
        } catch (_: Exception) {
        }

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
                emptyList()
            }
        } else {
            // 收藏外部歌单 (uniform_get_Dissinfo)
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
                emptyList()
            }
        }
    }

suspend fun MusicApiService.createPlaylist(name: String): Triple<Boolean, Long, String> =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn || name.isBlank()) return@withContext Triple(false, 0L, "未登录或歌单名为空")
        try {
            LoginApiService().ensureMusicKey()
        } catch (_: Exception) {
        }
        val escaped = Json.encodeToString(name.trim())
        val payload =
            """
            {
              "comm": { "ct": 24, "cv": 0 },
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
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val obj = root["createNewPlayList"]?.jsonObject ?: return@withContext Triple(false, 0L, "响应为空")
            val code = obj["code"]?.jsonPrimitive?.intOrNull ?: -1
            if (code == 0) {
                val dataObj = obj["data"]?.jsonObject
                val resObj = dataObj?.get("result")?.jsonObject
                val dirId =
                    resObj?.get("dirId")?.jsonPrimitive?.longOrNull
                        ?: resObj?.get("tid")?.jsonPrimitive?.longOrNull
                        ?: dataObj?.get("dirId")?.jsonPrimitive?.longOrNull
                        ?: 0L
                Triple(true, dirId, "创建成功")
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
            Triple(false, 0L, e.message ?: "创建异常")
        }
    }

suspend fun MusicApiService.deletePlaylist(playlist: Playlist): Boolean =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn || playlist.isMyFavorite) return@withContext false
        try {
            LoginApiService().ensureMusicKey()
        } catch (_: Exception) {
        }
        val payload =
            if (playlist.isCreated) {
                """
                {
                  "comm": { "ct": 24, "cv": 0 },
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
                  "comm": { "ct": 24, "cv": 0 },
                  "deleteFavPlayList": {
                    "module": "music.musicasset.PlaylistFavWrite",
                    "method": "CancelFavPlaylist",
                    "param": { "dirId": $dissId }
                  }
                }
                """.trimIndent()
            }

        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val key = if (playlist.isCreated) "deletePlayList" else "deleteFavPlayList"
            root[key]
                ?.jsonObject
                ?.get("code")
                ?.jsonPrimitive
                ?.intOrNull == 0
        } catch (e: Exception) {
            false
        }
    }

suspend fun MusicApiService.resolveSongId(songMid: String): Long =
    withContext(Dispatchers.IO) {
        if (songMid.isBlank()) return@withContext 0L
        try {
            LoginApiService().ensureMusicKey()
        } catch (_: Exception) {
        }
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
        } catch (_: Exception) {
            0L
        }
    }

enum class AddSongResult {
    Success,
    AlreadyExists,
    Failed,
}

suspend fun MusicApiService.addSongToPlaylist(
    dirId: Long,
    songId: Long,
    songMid: String = "",
): AddSongResult =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn || dirId <= 0L) return@withContext AddSongResult.Failed
        val actualId = if (songId <= 0L && songMid.isNotBlank()) resolveSongId(songMid) else songId
        if (actualId <= 0L) return@withContext AddSongResult.Failed

        try {
            LoginApiService().ensureMusicKey()
        } catch (_: Exception) {
        }

        val payload =
            """
            {
              "comm": { "ct": 24, "cv": 0 },
              "addSongsToPlayList": {
                "module": "music.musicasset.PlaylistDetailWrite",
                "method": "AddSonglist",
                "param": { "dirId": $dirId, "v_songInfo": [{ "songId": $actualId, "songType": 0 }] }
              }
            }
            """.trimIndent()

        try {
            val respJson = postAg1Gateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val addObj = root["addSongsToPlayList"]?.jsonObject ?: return@withContext AddSongResult.Failed
            val code =
                addObj["code"]?.jsonPrimitive?.intOrNull
                    ?: addObj["subcode"]?.jsonPrimitive?.intOrNull ?: -1
            if (code == 0) {
                val data = addObj["data"]?.jsonObject
                val succNum = data?.get("succ_song_num")?.jsonPrimitive?.intOrNull ?: 1
                val failNum = data?.get("fail_song_num")?.jsonPrimitive?.intOrNull ?: 0
                when {
                    succNum > 0 -> AddSongResult.Success
                    failNum > 0 -> AddSongResult.AlreadyExists
                    else -> AddSongResult.Success
                }
            } else {
                AddSongResult.Failed
            }
        } catch (_: Exception) {
            AddSongResult.Failed
        }
    }

suspend fun MusicApiService.addSongsToPlaylist(
    dirId: Long,
    songs: List<Song>,
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

        try {
            LoginApiService().ensureMusicKey()
        } catch (_: Exception) {
        }

        val songInfoJson = resolvedSongInfos.joinToString(separator = ",") { """{ "songId": $it, "songType": 0 }""" }
        val payload =
            """
            {
              "comm": { "ct": 24, "cv": 0 },
              "addSongsToPlayList": {
                "module": "music.musicasset.PlaylistDetailWrite",
                "method": "AddSonglist",
                "param": { "dirId": $dirId, "v_songInfo": [$songInfoJson] }
              }
            }
            """.trimIndent()

        try {
            val respJson = postAg1Gateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val addObj = root["addSongsToPlayList"]?.jsonObject ?: return@withContext AddSongResult.Failed
            val code =
                addObj["code"]?.jsonPrimitive?.intOrNull
                    ?: addObj["subcode"]?.jsonPrimitive?.intOrNull ?: -1
            if (code == 0) {
                val data = addObj["data"]?.jsonObject
                val succNum = data?.get("succ_song_num")?.jsonPrimitive?.intOrNull ?: 1
                val failNum = data?.get("fail_song_num")?.jsonPrimitive?.intOrNull ?: 0
                when {
                    succNum > 0 -> AddSongResult.Success
                    failNum > 0 -> AddSongResult.AlreadyExists
                    else -> AddSongResult.Success
                }
            } else {
                AddSongResult.Failed
            }
        } catch (_: Exception) {
            AddSongResult.Failed
        }
    }

suspend fun MusicApiService.deleteSongsFromPlaylist(
    dirId: Long,
    songs: List<Song>,
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

        try {
            LoginApiService().ensureMusicKey()
        } catch (_: Exception) {
        }

        val songInfoJson = resolvedSongInfos.joinToString(separator = ",") { """{ "songId": $it, "songType": 0 }""" }
        val payload =
            """
            {
              "comm": { "ct": 24, "cv": 0 },
              "delSongsFromPlayList": {
                "module": "music.musicasset.PlaylistDetailWrite",
                "method": "DelSonglist",
                "param": { "dirId": $dirId, "v_songInfo": [$songInfoJson] }
              }
            }
            """.trimIndent()

        try {
            val respJson = postAg1Gateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val delObj = root["delSongsFromPlayList"]?.jsonObject ?: return@withContext false
            val code =
                delObj["code"]?.jsonPrimitive?.intOrNull
                    ?: delObj["subcode"]?.jsonPrimitive?.intOrNull ?: -1
            code == 0
        } catch (_: Exception) {
            false
        }
    }

suspend fun MusicApiService.deleteSongFromPlaylist(
    dirId: Long,
    songId: Long,
    songMid: String = "",
): Boolean =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn || dirId <= 0L) return@withContext false
        val actualId = if (songId <= 0L && songMid.isNotBlank()) resolveSongId(songMid) else songId
        if (actualId <= 0L) return@withContext false

        try {
            LoginApiService().ensureMusicKey()
        } catch (_: Exception) {
        }

        val payload =
            """
            {
              "comm": { "ct": 24, "cv": 0 },
              "delSongsFromPlayList": {
                "module": "music.musicasset.PlaylistDetailWrite",
                "method": "DelSonglist",
                "param": { "dirId": $dirId, "v_songInfo": [{ "songId": $actualId, "songType": 0 }] }
              }
            }
            """.trimIndent()

        try {
            val respJson = postAg1Gateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val delObj = root["delSongsFromPlayList"]?.jsonObject ?: return@withContext false
            val code =
                delObj["code"]?.jsonPrimitive?.intOrNull
                    ?: delObj["subcode"]?.jsonPrimitive?.intOrNull ?: -1
            code == 0
        } catch (_: Exception) {
            false
        }
    }

suspend fun MusicApiService.addSongToFavorite(
    songId: Long,
    songMid: String,
): Boolean = addSongToPlaylist(dirId = 201L, songId = songId, songMid = songMid) == AddSongResult.Success

suspend fun MusicApiService.deleteSongFromFavorite(
    songId: Long,
    songMid: String,
): Boolean = deleteSongFromPlaylist(dirId = 201L, songId = songId, songMid = songMid)
