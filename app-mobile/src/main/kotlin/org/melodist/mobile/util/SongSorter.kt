package org.melodist.mobile.util

import org.melodist.model.Song
import org.melodist.model.SongSortOrder
import java.text.Collator
import java.util.Locale

object SongSorter {
    private val collator: Collator by lazy {
        Collator.getInstance(Locale.CHINA).apply {
            strength = Collator.PRIMARY
        }
    }

    /**
     * 根据指定的排序模式对歌曲列表进行稳定排序。
     */
    fun sort(
        songs: List<Song>,
        order: SongSortOrder,
    ): List<Song> =
        when (order) {
            SongSortOrder.DEFAULT -> songs
            SongSortOrder.NAME ->
                songs.sortedWith { a, b ->
                    val cmp = collator.compare(a.name.trim(), b.name.trim())
                    if (cmp != 0) cmp else collator.compare(a.singer.trim(), b.singer.trim())
                }
            SongSortOrder.ARTIST ->
                songs.sortedWith { a, b ->
                    val cmp = collator.compare(a.singer.trim(), b.singer.trim())
                    if (cmp != 0) cmp else collator.compare(a.name.trim(), b.name.trim())
                }
            SongSortOrder.DATE_ADDED ->
                songs.sortedWith { a, b ->
                    // 最新添加在最前（时间戳倒序），时间相同时以歌名字典序保持稳定
                    val timeCmp = b.dateAdded.compareTo(a.dateAdded)
                    if (timeCmp != 0) timeCmp else collator.compare(a.name.trim(), b.name.trim())
                }
        }

    /**
     * 获取歌曲在当前排序模式下的展示索引标识（用于快速滚动条指示气泡）。
     */
    fun getInitial(
        song: Song,
        order: SongSortOrder,
    ): String {
        val target =
            when (order) {
                SongSortOrder.ARTIST -> song.singer.trim()
                else -> song.name.trim()
            }
        if (target.isEmpty()) return "#"
        val firstChar = target.first()
        return when {
            firstChar.isLetter() && firstChar.code in 0..127 -> firstChar.uppercaseChar().toString()
            firstChar.isDigit() -> "#"
            else -> firstChar.toString()
        }
    }
}
