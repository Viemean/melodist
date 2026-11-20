package org.melodist.data

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.model.Song

class RecentPlaybackManagerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `RecentSongEntry roundtrip serialization preserves song and timestamp`() {
        val song = Song(songMid = "mid_recent_1", name = "Test Song", singer = "Singer A")
        val entry = RecentSongEntry(song = song, lastTime = 1700000123L)

        val encoded = json.encodeToString(RecentSongEntry.serializer(), entry)
        val decoded = json.decodeFromString(RecentSongEntry.serializer(), encoded)

        assertEquals(entry, decoded)
    }

    @Test
    fun `legacy Song list JSON deserialization fallback functions correctly`() {
        val legacyJson = """
            [
                {"songMid": "mid_legacy_1", "name": "Legacy 1", "singer": "Artist 1"},
                {"songMid": "mid_legacy_2", "name": "Legacy 2", "singer": "Artist 2"}
            ]
        """.trimIndent()

        val legacySongs = json.decodeFromString<List<Song>>(legacyJson)
        assertEquals(2, legacySongs.size)
        assertEquals("mid_legacy_1", legacySongs[0].songMid)
        assertEquals("mid_legacy_2", legacySongs[1].songMid)

        val now = 1700000000L
        val convertedEntries =
            legacySongs.mapIndexed { idx, item ->
                RecentSongEntry(song = item, lastTime = now - (idx + 1) * 3600)
            }
        assertEquals(2, convertedEntries.size)
        assertTrue(convertedEntries[0].lastTime > convertedEntries[1].lastTime)
    }

    @Test
    fun `recordSong and removeSong updates flow and deduplicates entries`() {
        val songA = Song(songMid = "test_mid_a", name = "Song Alpha", singer = "Artist A")
        val songB = Song(songMid = "test_mid_b", name = "Song Beta", singer = "Artist B")

        RecentPlaybackManager.recordSong(songA)
        RecentPlaybackManager.recordSong(songB)

        val currentSongs = RecentPlaybackManager.recentSongsFlow.value
        assertEquals("test_mid_b", currentSongs[0].songMid)
        assertEquals("test_mid_a", currentSongs[1].songMid)

        // 重复记录已存在歌曲，应置顶去重
        RecentPlaybackManager.recordSong(songA)
        val updatedSongs = RecentPlaybackManager.recentSongsFlow.value
        assertEquals("test_mid_a", updatedSongs[0].songMid)
        assertEquals("test_mid_b", updatedSongs[1].songMid)

        // 移除歌曲
        RecentPlaybackManager.removeSong("test_mid_a")
        val afterRemove = RecentPlaybackManager.recentSongsFlow.value
        assertEquals(1, afterRemove.count { it.songMid == "test_mid_b" })
        assertEquals(0, afterRemove.count { it.songMid == "test_mid_a" })

        // 清理测试数据
        RecentPlaybackManager.removeSong("test_mid_b")
    }

    @Test
    fun `recordSong with blank mid falls back to title and singer deduplication`() {
        val songNoMid1 = Song(songMid = "", name = "Offline Track", singer = "Local Artist")
        val songNoMid2 = Song(songMid = "", name = "Offline Track", singer = "Local Artist")

        RecentPlaybackManager.recordSong(songNoMid1)
        RecentPlaybackManager.recordSong(songNoMid2)

        val songs = RecentPlaybackManager.recentSongsFlow.value
        val matches = songs.filter { it.name == "Offline Track" && it.singer == "Local Artist" }
        assertEquals(1, matches.size)

        // 清理
        RecentPlaybackManager.removeSong("")
    }

    @Test
    fun `recordSong ignores empty song entries`() {
        val beforeCount = RecentPlaybackManager.recentSongsFlow.value.size
        RecentPlaybackManager.recordSong(Song(songMid = "", name = "", singer = ""))
        val afterCount = RecentPlaybackManager.recentSongsFlow.value.size
        assertEquals(beforeCount, afterCount)
    }

    @Test
    fun `multi-source deduplication prefers non-online and sorts descending by time`() {
        val localEntry =
            RecentSongEntry(
                song = Song(songMid = "local_001", name = "Local Song"),
                lastTime = 2000L,
            )
        val cloudEntrySameMid =
            RecentSongEntry(
                song = Song(songMid = "local_001", name = "Cloud Duplicate"),
                lastTime = 1000L,
            )
        val cloudEntryNew =
            RecentSongEntry(
                song = Song(songMid = "cloud_002", name = "Cloud Song"),
                lastTime = 3000L,
            )

        val combined = listOf(localEntry) + listOf(cloudEntrySameMid, cloudEntryNew)
        val merged = mutableListOf<RecentSongEntry>()
        val seenMids = mutableSetOf<String>()

        for (entry in combined) {
            val mid = entry.song.songMid
            if (mid.isNotBlank() && seenMids.add(mid)) {
                merged.add(entry)
            }
        }

        val sorted = merged.sortedByDescending { it.lastTime }

        assertEquals(2, sorted.size)
        assertEquals("cloud_002", sorted[0].song.songMid)
        assertEquals("local_001", sorted[1].song.songMid)
        assertTrue(sorted[1].song.isLocal)
    }

    @Test
    fun `MAX_RECENT_ITEMS boundary truncation limits list size to 500`() {
        assertEquals(500, RecentPlaybackManager.MAX_RECENT_ITEMS)

        val entries =
            (1..550).map { i ->
                RecentSongEntry(
                    song = Song(songMid = "cap_test_$i", name = "Song $i"),
                    lastTime = i.toLong(),
                )
            }

        val truncated = entries.sortedByDescending { it.lastTime }.take(RecentPlaybackManager.MAX_RECENT_ITEMS)
        assertEquals(500, truncated.size)
        assertEquals("cap_test_550", truncated.first().song.songMid)
        assertEquals("cap_test_51", truncated.last().song.songMid)
    }
}
