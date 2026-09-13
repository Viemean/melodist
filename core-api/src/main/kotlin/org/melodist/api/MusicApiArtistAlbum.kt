package org.melodist.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.melodist.model.Album
import org.melodist.model.ArtistDetail
import org.melodist.model.Song

/**
 * 歌手与专辑资产管理扩展
 */

suspend fun MusicApiService.getFavoriteAlbums(): List<Album> =
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
              "fav_albums": {
                "module": "music.musicasset.AlbumFavRead",
                "method": "GetAlbumFavInfo",
                "param": { "uin": "$uin" }
              }
            }
            """.trimIndent()

        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val listArr =
                root["fav_albums"]
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonObject
                    ?.get("v_list")
                    ?.jsonArray ?: return@withContext emptyList()

            val albums = mutableListOf<Album>()
            for (item in listArr) {
                val obj = item.jsonObject
                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: 0L
                val mid = obj["mid"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val songCount = obj["songnum"]?.jsonPrimitive?.intOrNull ?: 0
                val logo = obj["logo"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val pubTime = obj["pubtime"]?.jsonPrimitive?.longOrNull ?: 0L

                val singerArr = obj["v_singer"]?.jsonArray
                val artists =
                    singerArr
                        ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                        ?.joinToString(" / ") ?: "群星"

                if (mid.isNotBlank() && name.isNotBlank()) {
                    val cover = MusicApiService.getAlbumCoverUrl(mid).ifBlank { logo }
                    albums.add(Album(id, mid, name, artists, songCount, cover, pubTime))
                }
            }
            albums
        } catch (e: Exception) {
            emptyList()
        }
    }

suspend fun MusicApiService.getAlbumSongs(albumMid: String): List<Song> =
    withContext(Dispatchers.IO) {
        if (albumMid.isBlank()) return@withContext emptyList()
        val uin = UserSession.profile.uin.ifBlank { "0" }
        val payload =
            """
            {
              "comm": { "uin": "$uin", "format": "json", "ct": 19, "cv": 1, "authst": "" },
              "album_songs": {
                "module": "music.musichallAlbum.AlbumSongList",
                "method": "GetAlbumSongList",
                "param": { "albumMid": "$albumMid", "begin": 0, "num": -1, "order": 2 }
              }
            }
            """.trimIndent()

        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val songList =
                root["album_songs"]
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonObject
                    ?.get("songList")
                    ?.jsonArray ?: return@withContext emptyList()

            songList.mapNotNull {
                val sInfo = it.jsonObject["songInfo"] ?: it
                MusicApiService.parseSongFromElement(sInfo)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

suspend fun MusicApiService.getAlbumDetail(albumMid: String): org.melodist.model.AlbumDetail? =
    withContext(Dispatchers.IO) {
        if (albumMid.isBlank()) return@withContext null
        val uin = UserSession.profile.uin.ifBlank { "0" }
        val payload =
            """
            {
              "comm": { "uin": "$uin", "format": "json", "ct": 19, "cv": 1, "authst": "" },
              "album_songs": {
                "module": "music.musichallAlbum.AlbumSongList",
                "method": "GetAlbumSongList",
                "param": { "albumMid": "$albumMid", "begin": 0, "num": -1, "order": 2 }
              }
            }
            """.trimIndent()

        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val dataObj = root["album_songs"]?.jsonObject?.get("data")?.jsonObject ?: return@withContext null
            val songList =
                dataObj["songList"]?.jsonArray?.mapNotNull {
                    val sInfo = it.jsonObject["songInfo"] ?: it
                    MusicApiService.parseSongFromElement(sInfo)
                } ?: emptyList()

            val firstSong = songList.firstOrNull()
            val name = firstSong?.album.orEmpty().ifBlank { "专辑曲目" }
            val artist = firstSong?.singer.orEmpty()

            org.melodist.model.AlbumDetail(
                mid = albumMid,
                name = name,
                artist = artist,
                publishDate = "",
                company = "",
                description = "共收录 ${songList.size} 首单曲",
                songs = songList,
            )
        } catch (e: Exception) {
            null
        }
    }

suspend fun MusicApiService.getArtistDetail(singerMid: String): ArtistDetail? =
    withContext(Dispatchers.IO) {
        if (singerMid.isBlank()) return@withContext null
        val payload =
            """
            {
              "comm": { "ct": 24, "cv": 0 },
              "singer_detail": {
                "module": "music.web_singer_info_svr",
                "method": "get_singer_detail_info",
                "param": { "singermid": "$singerMid", "sort": 5, "sin": 0, "num": 30 }
              }
            }
            """.trimIndent()

        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val dataObj = root["singer_detail"]?.jsonObject?.get("data")?.jsonObject ?: return@withContext null

            val sInfo = dataObj["singer_info"]?.jsonObject
            val sName =
                sInfo
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
            val sId = sInfo?.get("id")?.jsonPrimitive?.longOrNull ?: 0L
            val brief = dataObj["singer_brief"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val slArray = dataObj["songlist"]?.jsonArray
            val songs = slArray?.mapNotNull { MusicApiService.parseSongFromElement(it) } ?: emptyList()

            ArtistDetail(singerMid, sId, sName, brief, songs)
        } catch (e: Exception) {
            null
        }
    }

suspend fun MusicApiService.getSingerSongList(
    singerMid: String,
    page: Int = 1,
    pageSize: Int = 30,
    isHotOrder: Boolean = true,
): Pair<List<Song>, Int> =
    withContext(Dispatchers.IO) {
        if (singerMid.isBlank()) return@withContext Pair(emptyList(), 0)
        val sort = if (isHotOrder) 5 else 2
        val begin = (page - 1) * pageSize
        val payload =
            """
            {
              "comm": { "ct": 24, "cv": 0 },
              "singer_detail": {
                "module": "music.web_singer_info_svr",
                "method": "get_singer_detail_info",
                "param": { "singermid": "$singerMid", "sort": $sort, "sin": $begin, "num": $pageSize }
              }
            }
            """.trimIndent()

        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val dataObj =
                root["singer_detail"]?.jsonObject?.get("data")?.jsonObject
                    ?: return@withContext Pair(emptyList(), 0)

            val total = dataObj["total_song"]?.jsonPrimitive?.intOrNull ?: 0
            val slArray = dataObj["songlist"]?.jsonArray ?: return@withContext Pair(emptyList(), total)
            val songs = slArray.mapNotNull { MusicApiService.parseSongFromElement(it) }

            Pair(songs, total)
        } catch (e: Exception) {
            Pair(emptyList(), 0)
        }
    }

suspend fun MusicApiService.addAlbumToFavorite(albumMid: String): Boolean =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn || albumMid.isBlank()) return@withContext false
        val uin = UserSession.profile.uin.ifBlank { "0" }
        val payload =
            """
            {
              "comm": { "uin": "$uin", "format": "json", "ct": 19, "cv": 1, "authst": "" },
              "fav_album": {
                "module": "music.musicasset.AlbumFavWrite",
                "method": "FavAlbum",
                "param": { "uin": "$uin", "v_albumMid": ["$albumMid"] }
              }
            }
            """.trimIndent()

        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            root["fav_album"]
                ?.jsonObject
                ?.get("code")
                ?.jsonPrimitive
                ?.intOrNull == 0
        } catch (e: Exception) {
            false
        }
    }

suspend fun MusicApiService.removeAlbumFromFavorite(albumMid: String): Boolean =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn || albumMid.isBlank()) return@withContext false
        val uin = UserSession.profile.uin.ifBlank { "0" }
        val payload =
            """
            {
              "comm": { "uin": "$uin", "format": "json", "ct": 19, "cv": 1, "authst": "" },
              "cancel_album": {
                "module": "music.musicasset.AlbumFavWrite",
                "method": "CancelFavAlbum",
                "param": { "uin": "$uin", "v_albumMid": ["$albumMid"] }
              }
            }
            """.trimIndent()

        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            root["cancel_album"]
                ?.jsonObject
                ?.get("code")
                ?.jsonPrimitive
                ?.intOrNull == 0
        } catch (e: Exception) {
            false
        }
    }

suspend fun MusicApiService.getSingerAlbumList(
    singerMid: String,
    page: Int = 1,
    pageSize: Int = 30,
): Pair<List<Album>, Int> =
    withContext(Dispatchers.IO) {
        if (singerMid.isBlank()) return@withContext Pair(emptyList(), 0)
        val begin = (page - 1) * pageSize
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_singer_album.fcg?singermid=$singerMid&order=time&begin=$begin&num=$pageSize&songstatus=1&format=json"
        try {
            val json = getUrl(url)
            val root = Json.parseToJsonElement(json).jsonObject
            val dataObj = root["data"]?.jsonObject ?: return@withContext Pair(emptyList(), 0)
            val total = dataObj["total"]?.jsonPrimitive?.intOrNull ?: 0
            val listArr = dataObj["list"]?.jsonArray ?: return@withContext Pair(emptyList(), total)
            val albums =
                listArr.mapNotNull { item ->
                    val obj = item.jsonObject
                    val mid = obj["albumMID"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val name = obj["albumName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val singer = obj["singerName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val pubTime = obj["pubTime"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val songCount =
                        obj["latest_song"]
                            ?.jsonObject
                            ?.get("song_count")
                            ?.jsonPrimitive
                            ?.intOrNull ?: 0
                    val id = obj["albumID"]?.jsonPrimitive?.longOrNull ?: 0L
                    if (mid.isNotBlank() && name.isNotBlank()) {
                        val artistDisplay =
                            if (singer.isBlank()) {
                                pubTime
                            } else if (pubTime.isBlank()) {
                                singer
                            } else {
                                "$singer ($pubTime)"
                            }
                        Album(id, mid, name, artistDisplay, songCount)
                    } else {
                        null
                    }
                }
            Pair(albums, total)
        } catch (e: Exception) {
            Pair(emptyList(), 0)
        }
    }

suspend fun MusicApiService.toggleSingerFollow(
    singerMid: String,
    isFollow: Boolean,
): Boolean =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn || singerMid.isBlank()) return@withContext false
        val uin = UserSession.profile.uin.ifBlank { "0" }
        val method = if (isFollow) "FavSinger" else "CancelFavSinger"
        val subKey = if (isFollow) "fav_singer" else "cancel_singer"
        val payload =
            """
            {
              "comm": { "uin": "$uin", "format": "json", "ct": 19, "cv": 1, "authst": "" },
              "$subKey": {
                "module": "music.musicasset.SingerFavWrite",
                "method": "$method",
                "param": { "uin": "$uin", "singermid": ["$singerMid"] }
              }
            }
            """.trimIndent()
        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            root[subKey]
                ?.jsonObject
                ?.get("code")
                ?.jsonPrimitive
                ?.intOrNull == 0
        } catch (_: Exception) {
            false
        }
    }
