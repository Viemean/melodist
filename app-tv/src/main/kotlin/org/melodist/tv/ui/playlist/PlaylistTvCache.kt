package org.melodist.tv.ui.playlist

import org.melodist.model.Song

/**
 * TV 端歌单详情内存缓存，用于跨页面与播放态返回时保留曲目列表与最后焦点状态。
 */
object PlaylistScreenCache {
    var lastCacheKey: String = ""
    var songs: List<Song> = emptyList()
    var totalCount: Int = 0
    var hasMore: Boolean = false
    var lockedCoverUrl: String = ""
    var lockedAlbumMid: String = ""
    var lastPlayedIndex: Int = 0
    var lastFocusedIndex: Int = -1

    fun matches(key: String): Boolean = lastCacheKey == key && songs.isNotEmpty()

    fun save(
        key: String,
        songs: List<Song>,
        total: Int,
        more: Boolean,
        cover: String,
        albumMid: String,
    ) {
        if (this.lastCacheKey != key) {
            this.lastFocusedIndex = -1
        }
        this.lastCacheKey = key
        this.songs = songs
        this.totalCount = total
        this.hasMore = more
        this.lockedCoverUrl = cover
        this.lockedAlbumMid = albumMid
    }

    fun onSongFavoriteChanged(
        song: Song,
        isFav: Boolean,
    ) {
        if (lastCacheKey != "favorites_0_0_") return
        val current = songs.toMutableList()
        current.removeAll { it.songMid == song.songMid || (song.songId > 0 && it.songId == song.songId) }
        if (isFav) {
            current.add(0, song)
            totalCount++
            if (song.coverUrl.isNotBlank()) {
                lockedCoverUrl = song.coverUrl
            }
        } else {
            totalCount = (totalCount - 1).coerceAtLeast(0)
        }
        songs = current
    }
}
