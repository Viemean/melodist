package org.melodist.mobile.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.getAlbumSongs
import org.melodist.api.getDailyRecommendSongs
import org.melodist.api.getGuessRecommendSongs
import org.melodist.api.getMillionRecommendSongs
import org.melodist.api.getPlaylistSongs
import org.melodist.api.getRecentAlbums
import org.melodist.api.getRecentPlaylists
import org.melodist.api.getRecentSongs
import org.melodist.api.getSingerSongList
import org.melodist.api.getSongBySongId
import org.melodist.api.resolveSongId
import org.melodist.api.search
import org.melodist.api.searchAlbums
import org.melodist.api.searchArtists
import org.melodist.core.connect.mcp.McpPlaybackHandler
import org.melodist.data.LocalMusicManager
import org.melodist.data.RecentPlaybackManager
import org.melodist.data.RecentSongEntry
import org.melodist.data.UserLibraryCacheManager
import org.melodist.model.Song
import org.melodist.playback.PlaybackLoopMode
import org.melodist.playback.PlaybackManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class McpPlaybackHandlerImpl : McpPlaybackHandler {
    override suspend fun play(
        target: String,
        id: String,
    ): Result<String> {
        return when (target.lowercase()) {
            "song" -> {
                val existing =
                    PlaybackManager.playlist.value.find { it.songMid == id || it.songId.toString() == id }
                        ?: UserLibraryCacheManager.favoriteSongsFlow.value.find { it.songMid == id || it.songId.toString() == id }
                        ?: LocalMusicManager.getScannedSongs().find { it.songMid == id || it.songId.toString() == id }

                val songToPlay =
                    existing ?: run {
                        val numId = id.toLongOrNull() ?: MusicApiService().resolveSongId(id)
                        if (numId > 0L) {
                            MusicApiService().getSongBySongId(numId)
                        } else {
                            val searchResults = MusicApiService().search(id, page = 1, pageSize = 5)
                            searchResults.find { it.songMid == id } ?: searchResults.firstOrNull()
                        }
                    }

                if (songToPlay != null) {
                    withContext(Dispatchers.Main) {
                        PlaybackManager.playSong(songToPlay)
                    }
                    Result.success("Now playing: ${songToPlay.name} - ${songToPlay.singer}")
                } else {
                    Result.failure(IllegalArgumentException("Song not found for id: $id"))
                }
            }

            "playlist" -> {
                val tid =
                    id.toLongOrNull()
                        ?: return Result.failure(IllegalArgumentException("Invalid numeric playlist id: $id"))

                val api = MusicApiService()
                val allSongs = mutableListOf<Song>()
                var page = 1
                var hasMore = true
                while (hasMore && page <= 10) {
                    val chunk = api.getPlaylistSongs(dirId = 0L, tid = tid, page = page, pageSize = 100)
                    if (chunk.isEmpty()) {
                        hasMore = false
                    } else {
                        allSongs.addAll(chunk)
                        hasMore = chunk.size >= 100
                        page++
                    }
                }
                if (allSongs.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        PlaybackManager.setPlaylist(allSongs, startIndex = 0)
                    }
                    Result.success("Loaded and playing playlist $id with ${allSongs.size} songs")
                } else {
                    Result.failure(IllegalArgumentException("No songs returned for playlist $id"))
                }
            }

            "album" -> {
                val api = MusicApiService()
                val (albumMid, albumTitle) =
                    if (id.startsWith("00") && id.length == 14) {
                        id to "Album $id"
                    } else {
                        val albums = api.searchAlbums(id, page = 1, pageSize = 1)
                        if (albums.isNotEmpty()) {
                            albums[0].mid to albums[0].title
                        } else {
                            id to "Album $id"
                        }
                    }
                val songs = api.getAlbumSongs(albumMid)
                if (songs.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        PlaybackManager.setPlaylist(songs, startIndex = 0)
                    }
                    Result.success("Loaded and playing album $albumTitle with ${songs.size} songs")
                } else {
                    Result.failure(IllegalArgumentException("No songs found for album: $id"))
                }
            }

            "singer", "artist" -> {
                val api = MusicApiService()
                val (singerMid, singerName) =
                    if (id.startsWith("00") && id.length == 14) {
                        id to "Artist $id"
                    } else {
                        val artists = api.searchArtists(id, page = 1, pageSize = 1)
                        if (artists.isNotEmpty()) {
                            artists[0].mid to artists[0].name
                        } else {
                            id to "Artist $id"
                        }
                    }
                val (songs, _) = api.getSingerSongList(singerMid, page = 1, pageSize = 50)
                if (songs.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        PlaybackManager.setPlaylist(songs, startIndex = 0)
                    }
                    Result.success("Loaded and playing artist $singerName with ${songs.size} songs")
                } else {
                    Result.failure(IllegalArgumentException("No songs found for artist: $id"))
                }
            }

            "daily", "guess", "million", "favorites", "favorite", "fav", "recommend" -> {
                if (!UserSession.isLoggedIn) {
                    return Result.failure(IllegalStateException("User is not logged in to Melodist"))
                }
                val actualCategory = if (target.lowercase() == "recommend") id.lowercase() else target.lowercase()
                val normalizedCategory =
                    when (actualCategory) {
                        "favorite", "fav", "like" -> "favorites"
                        else -> actualCategory
                    }
                val api = MusicApiService()
                val (songs, label) =
                    when (normalizedCategory) {
                        "daily" -> api.getDailyRecommendSongs() to "每日推荐"
                        "guess" -> api.getGuessRecommendSongs() to "猜你喜欢"
                        "million" -> api.getMillionRecommendSongs() to "百万收藏"
                        "favorites" -> UserLibraryCacheManager.loadFavoriteSongs(api, forceRefresh = false) to "我的喜欢"
                        else -> emptyList<Song>() to actualCategory
                    }
                if (songs.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        PlaybackManager.setPlaylist(songs, startIndex = 0)
                    }
                    Result.success("Loaded and playing $label with ${songs.size} songs")
                } else {
                    Result.failure(IllegalArgumentException("No songs returned for $label"))
                }
            }

            "history", "recent" -> {
                val entries = RecentPlaybackManager.getRecentSongEntries()
                val songs =
                    if (entries.isNotEmpty()) {
                        entries.map { it.song }
                    } else if (UserSession.isLoggedIn) {
                        MusicApiService().getRecentSongs().items.map { it.song }
                    } else {
                        emptyList()
                    }
                if (songs.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        PlaybackManager.setPlaylist(songs, startIndex = 0)
                    }
                    Result.success("Loaded and playing ${songs.size} songs from recent playback history")
                } else {
                    Result.failure(IllegalArgumentException("No recent playback history found"))
                }
            }

            else -> {
                Result.failure(
                    IllegalArgumentException(
                        "Unsupported play target: $target. Expected 'song', 'playlist', 'daily', 'guess', 'million', 'favorites', or 'history'",
                    ),
                )
            }
        }
    }

    override suspend fun controlPlayback(
        action: String,
        mode: String?,
    ): Result<String> =
        withContext(Dispatchers.Main) {
            when (action.lowercase()) {
                "play" -> {
                    PlaybackManager.play()
                    Result.success("Playback started")
                }

                "pause" -> {
                    PlaybackManager.pause()
                    Result.success("Playback paused")
                }

                "next" -> {
                    PlaybackManager.playNext()
                    Result.success("Skipped to next song")
                }

                "previous" -> {
                    PlaybackManager.playPrevious()
                    Result.success("Skipped to previous song")
                }

                "loop_mode" -> {
                    val loopMode =
                        when (mode?.lowercase()) {
                            "single_repeat" -> PlaybackLoopMode.SingleRepeat
                            "shuffle" -> PlaybackLoopMode.Shuffle
                            "list_repeat" -> PlaybackLoopMode.ListRepeat
                            else -> null
                        }
                    if (loopMode != null) {
                        PlaybackManager.setLoopMode(loopMode)
                        Result.success("Loop mode set to ${loopMode.label}")
                    } else {
                        PlaybackManager.cycleLoopMode()
                        Result.success("Cycled loop mode to ${PlaybackManager.loopMode.value.label}")
                    }
                }

                else -> {
                    Result.failure(IllegalArgumentException("Unknown control action: $action"))
                }
            }
        }

    override suspend fun getPlaybackStatus(
        queueOffset: Int,
        queueLimit: Int,
    ): Result<JsonObject> =
        withContext(Dispatchers.Main) {
            try {
                val currentSong = PlaybackManager.currentSong.value
                val isPlaying = PlaybackManager.isPlaying.value
                val posMs = PlaybackManager.currentPositionMs.value
                val durMs = PlaybackManager.durationMs.value
                val loopMode = PlaybackManager.loopMode.value
                val queue = PlaybackManager.playlist.value

                val total = queue.size
                val safeOffset = queueOffset.coerceIn(0, total)
                val safeLimit = queueLimit.coerceIn(1, 100)
                val slicedQueue = queue.drop(safeOffset).take(safeLimit)

                val json =
                    buildJsonObject {
                        put("isPlaying", isPlaying)
                        put("positionMs", posMs)
                        put("durationMs", durMs)
                        put("loopMode", loopMode.name)
                        put("loopModeLabel", loopMode.label)
                        put(
                            "currentSong",
                            if (currentSong != null) {
                                buildJsonObject {
                                    put("songMid", currentSong.songMid)
                                    put("songId", currentSong.songId)
                                    put("title", currentSong.name)
                                    put("singer", currentSong.singer)
                                    put("album", currentSong.album)
                                    put("coverUrl", currentSong.coverUrl)
                                    put("tier", PlaybackManager.currentTier.value.name)
                                }
                            } else {
                                buildJsonObject {}
                            },
                        )
                        put("queueTotal", total)
                        put("queueOffset", safeOffset)
                        put("queueLimit", safeLimit)
                        put(
                            "queue",
                            buildJsonArray {
                                slicedQueue.forEachIndexed { idx, s ->
                                    add(
                                        buildJsonObject {
                                            put("index", safeOffset + idx)
                                            put("songMid", s.songMid)
                                            put("title", s.name)
                                            put("singer", s.singer)
                                        },
                                    )
                                }
                            },
                        )
                    }
                Result.success(json)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getPlaybackHistory(
        type: String,
        limit: Int,
    ): Result<JsonArray> =
        withContext(Dispatchers.IO) {
            try {
                val safeLimit = limit.coerceIn(1, 50)
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

                val array =
                    when (type.lowercase()) {
                        "song" -> {
                            val entries = RecentPlaybackManager.getRecentSongEntries()
                            val items =
                                if (entries.isEmpty() && UserSession.isLoggedIn) {
                                    MusicApiService().getRecentSongs().items.map {
                                        RecentSongEntry(song = it.song, lastTime = it.lastTime)
                                    }
                                } else {
                                    entries
                                }
                            buildJsonArray {
                                items.take(safeLimit).forEach { entry ->
                                    val s = entry.song
                                    val playedAtStr =
                                        if (entry.lastTime > 0L) {
                                            formatter.format(Instant.ofEpochSecond(entry.lastTime))
                                        } else {
                                            ""
                                        }
                                    add(
                                        buildJsonObject {
                                            put("songMid", s.songMid)
                                            put("songId", s.songId)
                                            put("title", s.name)
                                            put("singer", s.singer)
                                            put("album", s.album)
                                            put("durationSeconds", s.durationSeconds)
                                            put("coverUrl", s.coverUrl)
                                            put("lastTime", entry.lastTime)
                                            put("playedAt", playedAtStr)
                                        },
                                    )
                                }
                            }
                        }

                        "album" -> {
                            val albums =
                                RecentPlaybackManager.recentAlbumsFlow.value.ifEmpty {
                                    if (UserSession.isLoggedIn) MusicApiService().getRecentAlbums().items else emptyList()
                                }
                            buildJsonArray {
                                albums.take(safeLimit).forEach { alb ->
                                    val playedAtStr =
                                        if (alb.lastTime > 0L) {
                                            formatter.format(Instant.ofEpochSecond(alb.lastTime))
                                        } else {
                                            ""
                                        }
                                    add(
                                        buildJsonObject {
                                            put("albumId", alb.albumId)
                                            put("albumMid", alb.albumMid)
                                            put("albumName", alb.albumName)
                                            put("singerName", alb.singerName)
                                            put("coverUrl", alb.coverUrl)
                                            put("songCount", alb.songCount)
                                            put("lastTime", alb.lastTime)
                                            put("playedAt", playedAtStr)
                                        },
                                    )
                                }
                            }
                        }

                        "playlist" -> {
                            val playlists =
                                RecentPlaybackManager.recentPlaylistsFlow.value.ifEmpty {
                                    if (UserSession.isLoggedIn) MusicApiService().getRecentPlaylists().items else emptyList()
                                }
                            buildJsonArray {
                                playlists.take(safeLimit).forEach { pl ->
                                    val playedAtStr =
                                        if (pl.lastTime > 0L) {
                                            formatter.format(Instant.ofEpochSecond(pl.lastTime))
                                        } else {
                                            ""
                                        }
                                    add(
                                        buildJsonObject {
                                            put("tid", pl.tid)
                                            put("title", pl.title)
                                            put("coverUrl", pl.coverUrl)
                                            put("creatorNick", pl.creatorNick)
                                            put("songCount", pl.songCount)
                                            put("lastTime", pl.lastTime)
                                            put("playedAt", playedAtStr)
                                        },
                                    )
                                }
                            }
                        }

                        else -> {
                            return@withContext Result.failure(
                                IllegalArgumentException("Unsupported history type: $type. Expected 'song', 'album', or 'playlist'"),
                            )
                        }
                    }
                Result.success(array)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
