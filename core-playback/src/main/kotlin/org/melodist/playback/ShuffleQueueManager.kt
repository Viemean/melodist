package org.melodist.playback

import org.melodist.model.Song
import kotlin.random.Random

/**
 * 主流播放器洗牌算法管理器 (Fisher-Yates 伪随机置换队列 + 历史回溯 + 歌手平滑去聚集)
 */
class ShuffleQueueManager(
    private val random: Random = Random.Default,
) {
    private val _shuffledIndices = mutableListOf<Int>()
    val shuffledIndices: List<Int> get() = _shuffledIndices

    private var _pointer: Int = 0
    val pointer: Int get() = _pointer

    val currentOriginalIndex: Int?
        get() = _shuffledIndices.getOrNull(_pointer)

    /**
     * 以当前播放歌曲为首项，生成一个完整的洗牌队列
     */
    fun reset(
        totalCount: Int,
        currentOriginalIndex: Int = 0,
        songs: List<Song> = emptyList(),
    ) {
        _shuffledIndices.clear()
        if (totalCount <= 0) {
            _pointer = 0
            return
        }

        val clampedIndex = currentOriginalIndex.coerceIn(0, totalCount - 1)
        val remaining = (0 until totalCount).filter { it != clampedIndex }.toMutableList()

        // Fisher-Yates 洗牌算法
        for (i in remaining.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val temp = remaining[i]
            remaining[i] = remaining[j]
            remaining[j] = temp
        }

        // Spotify 平滑优化：若存在歌手信息，分散相邻同一歌手
        if (songs.size == totalCount) {
            smoothArtistClustering(remaining, songs)
        }

        _shuffledIndices.add(clampedIndex)
        _shuffledIndices.addAll(remaining)
        _pointer = 0
    }

    /**
     * 切换至下一首曲目
     */
    fun next(songs: List<Song> = emptyList()): Int {
        if (_shuffledIndices.isEmpty()) return -1
        if (_shuffledIndices.size == 1) return _shuffledIndices[0]

        _pointer++
        if (_pointer >= _shuffledIndices.size) {
            val lastPlayed = _shuffledIndices.last()
            val totalCount = _shuffledIndices.size
            reset(totalCount, lastPlayed, songs)

            // 重新洗牌后，首项是刚刚听完的最后一首，顺延至下一首避免连播
            _pointer = 1
        }
        return _shuffledIndices[_pointer]
    }

    /**
     * 切上一首：精确回溯刚才播放过的歌曲
     */
    fun previous(): Int {
        if (_shuffledIndices.isEmpty()) return -1
        if (_shuffledIndices.size == 1) return _shuffledIndices[0]

        _pointer--
        if (_pointer < 0) {
            _pointer = _shuffledIndices.size - 1
        }
        return _shuffledIndices[_pointer]
    }

    /**
     * 预览下一首索引，不推进指针
     */
    fun peekNext(): Int? {
        if (_shuffledIndices.isEmpty()) return null
        if (_shuffledIndices.size == 1) return _shuffledIndices[0]
        val nextPointer = (_pointer + 1) % _shuffledIndices.size
        return _shuffledIndices.getOrNull(nextPointer)
    }

    /**
     * 预览上一首索引，不推进指针
     */
    fun peekPrevious(): Int? {
        if (_shuffledIndices.isEmpty()) return null
        if (_shuffledIndices.size == 1) return _shuffledIndices[0]
        val prevPointer = if (_pointer - 1 < 0) _shuffledIndices.size - 1 else _pointer - 1
        return _shuffledIndices.getOrNull(prevPointer)
    }

    /**
     * 用户手动在列表中点播某首歌曲时，同步洗牌队列指针
     */
    fun syncTo(
        originalIndex: Int,
        totalCount: Int,
        songs: List<Song> = emptyList(),
    ) {
        if (_shuffledIndices.size != totalCount) {
            reset(totalCount, originalIndex, songs)
            return
        }
        val existingIndex = _shuffledIndices.indexOf(originalIndex)
        if (existingIndex != -1) {
            _pointer = existingIndex
        } else {
            reset(totalCount, originalIndex, songs)
        }
    }

    /**
     * 将指定原始索引的歌曲提升至当前指针后一位，确保下一首必然播放该曲目
     */
    fun promoteToNext(originalIndex: Int) {
        if (_shuffledIndices.size <= 1) return
        val existingIndex = _shuffledIndices.indexOf(originalIndex)
        if (existingIndex == -1 || existingIndex == _pointer) return

        _shuffledIndices.removeAt(existingIndex)
        if (existingIndex < _pointer) {
            _pointer--
        }
        val nextPos = (_pointer + 1).coerceIn(0, _shuffledIndices.size)
        _shuffledIndices.add(nextPos, originalIndex)
    }

    /**
     * 序列化状态
     */
    fun serialize(): String = _shuffledIndices.joinToString(",")

    /**
     * 反序列化状态
     */
    fun restore(
        serialized: String?,
        restoredPointer: Int,
        totalCount: Int,
    ) {
        if (serialized.isNullOrBlank()) return
        try {
            val list = serialized.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (list.size == totalCount && list.toSet().size == totalCount) {
                _shuffledIndices.clear()
                _shuffledIndices.addAll(list)
                _pointer = restoredPointer.coerceIn(0, _shuffledIndices.size - 1)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 平滑同一歌手聚集：在不破坏随机分布的前提下，尽量避免相邻歌曲为同一歌手
     */
    private fun smoothArtistClustering(
        indices: MutableList<Int>,
        songs: List<Song>,
    ) {
        if (indices.size <= 2) return
        for (i in 0 until indices.size - 1) {
            val currSinger =
                songs
                    .getOrNull(indices[i])
                    ?.singer
                    ?.trim()
                    .orEmpty()
            val nextSinger =
                songs
                    .getOrNull(indices[i + 1])
                    ?.singer
                    ?.trim()
                    .orEmpty()

            if (currSinger.isNotEmpty() && currSinger == nextSinger) {
                for (j in (i + 2) until indices.size) {
                    val candidateSinger =
                        songs
                            .getOrNull(indices[j])
                            ?.singer
                            ?.trim()
                            .orEmpty()
                    if (candidateSinger.isNotEmpty() && candidateSinger != currSinger) {
                        val temp = indices[i + 1]
                        indices[i + 1] = indices[j]
                        indices[j] = temp
                        break
                    }
                }
            }
        }
    }
}
