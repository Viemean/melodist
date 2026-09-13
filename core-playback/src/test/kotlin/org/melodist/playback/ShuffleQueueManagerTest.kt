package org.melodist.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.model.Song

class ShuffleQueueManagerTest {
    private fun createDummySongs(count: Int): List<Song> =
        (0 until count).map { i ->
            Song(
                songId = i.toLong(),
                songMid = "mid_$i",
                name = "Song $i",
                singer = "Singer ${i % 3}", // 3 位歌手轮替
                album = "Album $i",
                albumMid = "amid_$i",
                durationSeconds = 200,
                coverUrl = "http://example.com/cover_$i.jpg",
            )
        }

    @Test
    fun `test shuffle queue preserves all elements without duplicates`() {
        val manager = ShuffleQueueManager()
        val songs = createDummySongs(20)
        manager.reset(totalCount = 20, currentOriginalIndex = 5, songs = songs)

        val queue = manager.shuffledIndices
        assertEquals(20, queue.size)
        // 验证首项为当前播放歌曲
        assertEquals(5, queue[0])
        // 验证列表无重复且包含所有项
        assertEquals((0 until 20).toSet(), queue.toSet())
    }

    @Test
    fun `test next advances strictly through shuffled queue and cycles`() {
        val manager = ShuffleQueueManager()
        val songs = createDummySongs(5)
        manager.reset(totalCount = 5, currentOriginalIndex = 2, songs = songs)

        val expectedFirst = manager.shuffledIndices[0]
        assertEquals(2, expectedFirst)

        val playedIndices = mutableListOf<Int>()
        playedIndices.add(manager.currentOriginalIndex!!)

        for (i in 1 until 5) {
            val nextIdx = manager.next(songs)
            playedIndices.add(nextIdx)
        }

        // 播满 5 首后，所有歌曲均被且仅被播放一次
        assertEquals(5, playedIndices.size)
        assertEquals((0 until 5).toSet(), playedIndices.toSet())

        // 再次 next 触发重新洗牌周期
        val cycleNext = manager.next(songs)
        assertTrue(cycleNext in 0 until 5)
    }

    @Test
    fun `test previous backtracks accurately to previously played song`() {
        val manager = ShuffleQueueManager()
        val songs = createDummySongs(10)
        manager.reset(totalCount = 10, currentOriginalIndex = 0, songs = songs)

        val song1 = manager.currentOriginalIndex!!
        val song2 = manager.next(songs)
        manager.next(songs)

        // 按上一首：精准退回 song2
        assertEquals(song2, manager.previous())
        // 再按上一首：精准退回 song1
        assertEquals(song1, manager.previous())
    }

    @Test
    fun `test syncTo updates pointer when user clicks a song manually`() {
        val manager = ShuffleQueueManager()
        val songs = createDummySongs(10)
        manager.reset(totalCount = 10, currentOriginalIndex = 0, songs = songs)

        val targetIndex = 7
        manager.syncTo(targetIndex, totalCount = 10, songs = songs)

        assertEquals(targetIndex, manager.currentOriginalIndex)

        // 接下来切下一首，应继续沿着洗牌队列前进，而不是重置
        val nextAfterSync = manager.next(songs)
        assertNotEquals(targetIndex, nextAfterSync)
    }
}
