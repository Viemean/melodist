package org.melodist.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MusicEntitiesTest {
    @Test
    fun `playlist model distinguishes favorite created and collected playlists`() {
        val favPlaylist = Playlist(dirId = 201L, name = "我喜欢", songCount = 128)
        assertTrue(favPlaylist.isMyFavorite)
        assertTrue(favPlaylist.isCreated)

        val userCreated = Playlist(dirId = 502L, name = "车载精选", songCount = 42, isFav = false)
        assertFalse(userCreated.isMyFavorite)
        assertTrue(userCreated.isCreated)

        val collected = Playlist(dirId = 9999L, name = "周杰伦全集", songCount = 200, tid = 8888L, isFav = true)
        assertFalse(collected.isMyFavorite)
        assertFalse(collected.isCreated)
    }

    @Test
    fun `playlist model computes private visibility and title aliases`() {
        val publicPlaylist = Playlist(dirId = 100L, name = "公开歌单", songCount = 10, dirShow = 1)
        assertFalse(publicPlaylist.isPrivate)
        assertEquals("公开歌单", publicPlaylist.title)
        assertEquals(10, publicPlaylist.songNum)

        val privatePlaylist = Playlist(dirId = 101L, name = "私密歌单", songCount = 5, dirShow = 2)
        assertTrue(privatePlaylist.isPrivate)
    }

    @Test
    fun `favoriteSongsResult verifies pagination hasMore logic`() {
        val resultHasMore =
            FavoriteSongsResult(
                songs = emptyList(),
                total = 100,
                hasMore = true,
            )
        assertTrue(resultHasMore.hasMore)
        assertEquals(100, resultHasMore.total)

        val resultCompleted =
            FavoriteSongsResult(
                songs = emptyList(),
                total = 30,
                hasMore = false,
            )
        assertFalse(resultCompleted.hasMore)
        assertEquals(30, resultCompleted.total)
    }

    @Test
    fun `album model aliases title to name correctly`() {
        val album =
            Album(
                id = 12345L,
                mid = "000abcde",
                title = "范特西",
                artist = "周杰伦",
                songCount = 10,
            )
        assertEquals("范特西", album.name)
        assertEquals("范特西", album.title)
    }
}
