package org.melodist.data

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.melodist.model.Album
import org.melodist.model.Playlist
import org.melodist.model.Song

class UserLibraryCacheManagerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `UserLibraryData serialization preserves properties and handles unknown keys`() {
        val original =
            UserLibraryData(
                playlists =
                    listOf(
                        Playlist(dirId = 1L, name = "My Playlist", songCount = 15, tid = 100L),
                    ),
                favoriteCount = 42,
                favoriteAlbums =
                    listOf(
                        Album(id = 1L, mid = "album_001", title = "Test Album", artist = "Test Singer", songCount = 10),
                    ),
                fetchTimestamp = 1700000000000L,
                accountUin = "10001",
            )

        val serialized = json.encodeToString(UserLibraryData.serializer(), original)
        val deserialized = json.decodeFromString(UserLibraryData.serializer(), serialized)

        assertEquals(original, deserialized)

        val withUnknownFields = """
            {
                "playlists": [],
                "favoriteCount": 10,
                "favoriteAlbums": [],
                "fetchTimestamp": 1000,
                "accountUin": "10002",
                "futureFieldString": "ignored",
                "futureFieldInt": 999
            }
        """.trimIndent()

        val parsedWithUnknown = json.decodeFromString(UserLibraryData.serializer(), withUnknownFields)
        assertEquals("10002", parsedWithUnknown.accountUin)
        assertEquals(10, parsedWithUnknown.favoriteCount)
    }

    @Test
    fun `FavoriteSongsCache and PlaylistSongsCache serialization roundtrip`() {
        val favSongsCache =
            FavoriteSongsCache(
                songs =
                    listOf(
                        Song(songMid = "song_001", name = "Fav Song 1"),
                        Song(songMid = "song_002", name = "Fav Song 2"),
                    ),
                fetchTimestamp = 1700000005000L,
                accountUin = "20002",
            )
        val favJson = json.encodeToString(FavoriteSongsCache.serializer(), favSongsCache)
        val parsedFav = json.decodeFromString(FavoriteSongsCache.serializer(), favJson)
        assertEquals(favSongsCache, parsedFav)

        val playlistSongsCache =
            PlaylistSongsCache(
                songs = listOf(Song(songMid = "song_003", name = "Playlist Song 1")),
                totalCount = 1,
                fetchTimestamp = 1700000100000L,
                accountUin = "20002",
            )
        val playlistJson = json.encodeToString(PlaylistSongsCache.serializer(), playlistSongsCache)
        val parsedPlaylist = json.decodeFromString(PlaylistSongsCache.serializer(), playlistJson)
        assertEquals(playlistSongsCache, parsedPlaylist)
    }

    @Test
    fun `hasValidCache returns false when cache is unpopulated or uin mismatches`() {
        assertFalse(UserLibraryCacheManager.hasValidCache(""))
        assertFalse(UserLibraryCacheManager.hasValidCache("nonexistent_uin_999"))
    }

    @Test
    fun `saveCachedPlaylistMetadata stores and retrieves playlist metadata`() {
        val playlist =
            Playlist(
                dirId = 101L,
                name = "Rock Classics",
                songCount = 50,
                tid = 202L,
                creator = "Curator",
            )

        UserLibraryCacheManager.saveCachedPlaylistMetadata(playlist)

        val retrievedExact = UserLibraryCacheManager.getCachedPlaylistMetadata(dirId = 101L, tid = 202L)
        assertNotNull(retrievedExact)
        assertEquals("Rock Classics", retrievedExact?.name)
        assertEquals(50, retrievedExact?.songCount)

        // 通过单独 tid 存储与命中
        val tidOnlyPlaylist =
            Playlist(
                dirId = 0L,
                name = "Tid Only Playlist",
                songCount = 20,
                tid = 303L,
            )
        UserLibraryCacheManager.saveCachedPlaylistMetadata(tidOnlyPlaylist)
        val retrievedByTid = UserLibraryCacheManager.getCachedPlaylistMetadata(dirId = 0L, tid = 303L)
        assertNotNull(retrievedByTid)
        assertEquals("Tid Only Playlist", retrievedByTid?.name)

        // 通过单独 dirId 存储与命中
        val dirIdOnlyPlaylist =
            Playlist(
                dirId = 404L,
                name = "DirId Only Playlist",
                songCount = 30,
                tid = 0L,
            )
        UserLibraryCacheManager.saveCachedPlaylistMetadata(dirIdOnlyPlaylist)
        val retrievedByDirId = UserLibraryCacheManager.getCachedPlaylistMetadata(dirId = 404L, tid = 0L)
        assertNotNull(retrievedByDirId)
        assertEquals("DirId Only Playlist", retrievedByDirId?.name)

        val retrievedNonExistent = UserLibraryCacheManager.getCachedPlaylistMetadata(dirId = 999999L, tid = 888888L)
        assertNull(retrievedNonExistent)
    }

    @Test
    fun `saveCachedPlaylistMetadata ignores playlist with zero dirId and tid`() {
        val emptyIdPlaylist =
            Playlist(
                dirId = 0L,
                name = "Invalid Playlist",
                songCount = 0,
                tid = 0L,
            )

        UserLibraryCacheManager.saveCachedPlaylistMetadata(emptyIdPlaylist)
        assertNull(UserLibraryCacheManager.getCachedPlaylistMetadata(0L, 0L))
    }

    @Test
    fun `isFavoriteSongsCacheValid returns false when uninitialized`() {
        assertFalse(UserLibraryCacheManager.isFavoriteSongsCacheValid())
    }
}
