package org.melodist.playback

import org.melodist.model.Song

/**
 * 播放队列外部追加数据分页源契约。
 * 当播放队列源自支持分页的界面（如搜索结果、歌手曲目、歌单详情）时，
 * 播放队列界面（如 PlayerQueueBottomSheet）滑到底部可触发此数据源拉取后续页，
 * 并将新增曲目无缝追加到当前播放队列与源界面。
 */
interface QueuePaginationSource {
    val hasMore: Boolean
    val isLoadingMore: Boolean

    suspend fun loadMore(): List<Song>
}
