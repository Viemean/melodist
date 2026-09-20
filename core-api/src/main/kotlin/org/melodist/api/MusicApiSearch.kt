package org.melodist.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.melodist.model.Album
import org.melodist.model.Playlist
import org.melodist.model.Song

/**
 * 搜索领域扩展：单曲、专辑、歌单检索
 */

/**
 * 搜索单曲 (search_type: 0)
 */
suspend fun MusicApiService.search(
    query: String,
    page: Int = 1,
    pageSize: Int = 30,
): List<Song> =
    withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val escapedQuery = Json.encodeToString(query)
        val payload =
            """
            {
              "music.search.SearchCgiService": {
                "module": "music.search.SearchCgiService",
                "method": "DoSearchForQQMusicDesktop",
                "param": {
                  "query": $escapedQuery,
                  "page_num": $page,
                  "num_per_page": $pageSize,
                  "search_type": 0
                }
              }
            }
            """.trimIndent()

        try {
            val respJson = postAg1Gateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val svc = root["music.search.SearchCgiService"]?.jsonObject
            val data = svc?.get("data")?.jsonObject
            val body = data?.get("body")?.jsonObject
            val songObj = body?.get("song")?.jsonObject
            val songList = songObj?.get("list")?.jsonArray ?: return@withContext emptyList()

            songList.mapNotNull { MusicApiService.parseSongFromElement(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

/**
 * 搜索专辑 (search_type: 2)
 */
suspend fun MusicApiService.searchAlbums(
    query: String,
    page: Int = 1,
    pageSize: Int = 30,
): List<Album> =
    withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val escapedQuery = Json.encodeToString(query)
        val payload =
            """
            {
              "music.search.SearchCgiService": {
                "module": "music.search.SearchCgiService",
                "method": "DoSearchForQQMusicDesktop",
                "param": {
                  "query": $escapedQuery,
                  "page_num": $page,
                  "num_per_page": $pageSize,
                  "search_type": 2
                }
              }
            }
            """.trimIndent()

        try {
            val respJson = postAg1Gateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val svc = root["music.search.SearchCgiService"]?.jsonObject
            val data = svc?.get("data")?.jsonObject
            val body = data?.get("body")?.jsonObject
            val albumObj = body?.get("album")?.jsonObject
            val albumList = albumObj?.get("list")?.jsonArray ?: return@withContext emptyList()

            albumList.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    val id = obj["albumID"]?.jsonPrimitive?.longOrNull ?: 0L
                    val mid = obj["albumMID"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (mid.isBlank()) return@mapNotNull null
                    val title = obj["albumName"]?.jsonPrimitive?.contentOrNull ?: ""
                    val artist =
                        obj["singerName"]?.jsonPrimitive?.contentOrNull
                            ?: obj["singer_list"]
                                ?.jsonArray
                                ?.firstOrNull()
                                ?.jsonObject
                                ?.get("name")
                                ?.jsonPrimitive
                                ?.contentOrNull
                            ?: ""
                    val pic = obj["albumPic"]?.jsonPrimitive?.contentOrNull ?: ""
                    val songCount = obj["song_count"]?.jsonPrimitive?.intOrNull ?: 0
                    Album(
                        id = id,
                        mid = mid,
                        title = title,
                        artist = artist,
                        songCount = songCount,
                        coverUrl = pic,
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

/**
 * 搜索歌单 (search_type: 3)
 */
suspend fun MusicApiService.searchPlaylists(
    query: String,
    page: Int = 1,
    pageSize: Int = 30,
): List<Playlist> =
    withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val escapedQuery = Json.encodeToString(query)
        val payload =
            """
            {
              "music.search.SearchCgiService": {
                "module": "music.search.SearchCgiService",
                "method": "DoSearchForQQMusicDesktop",
                "param": {
                  "query": $escapedQuery,
                  "page_num": $page,
                  "num_per_page": $pageSize,
                  "search_type": 3
                }
              }
            }
            """.trimIndent()

        try {
            val respJson = postAg1Gateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val svc = root["music.search.SearchCgiService"]?.jsonObject
            val data = svc?.get("data")?.jsonObject
            val body = data?.get("body")?.jsonObject
            val songlistObj = body?.get("songlist")?.jsonObject
            val songlistArray = songlistObj?.get("list")?.jsonArray ?: return@withContext emptyList()

            songlistArray.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    val dissid = obj["dissid"]?.jsonPrimitive?.contentOrNull ?: ""
                    val dissIdLong = dissid.toLongOrNull() ?: 0L
                    if (dissIdLong <= 0L && dissid.isBlank()) return@mapNotNull null
                    val name = obj["dissname"]?.jsonPrimitive?.contentOrNull ?: ""
                    val pic = obj["imgurl"]?.jsonPrimitive?.contentOrNull ?: ""
                    val songCount = obj["song_count"]?.jsonPrimitive?.intOrNull ?: 0
                    Playlist(
                        dirId = dissIdLong,
                        name = name,
                        songCount = songCount,
                        tid = dissIdLong,
                        isFav = true,
                        picUrl = pic,
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
